// JNI glue between dev.keycorrect.core.llama.LlamaNative and llama.cpp (pinned v0.7.0, CPU only).
//
// Two jobs: score(), the log-probability of candidate words after a context, with the context's KV
// cache kept between calls; and generate(), a short greedy continuation.
//
// KV layout (one unified cache, kSeqMax sequences):
//   seq 0              the tokens of the last scored context (the reusable prefix cache)
//   seq 1..16          scratch, one per candidate while a batch of candidates is decoded
//   seq 17             scratch for generate(); it borrows the prefix of seq 0 and leaves it alone
//
// Privacy: nothing here logs text. Only llama.cpp's ERROR lines (model path, tensor names) get out.
// Threading: a session must be used by one thread at a time; the Kotlin wrapper documents this.
//
// Strings cross the JNI boundary as UTF-16 and are converted here, because JNI's "modified UTF-8"
// cannot carry emoji (supplementary characters) in either direction.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <unistd.h>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {

constexpr int kMaxCandidateSeqs = 16;                   // candidates decoded per batch
constexpr llama_seq_id kGenerateSeq = kMaxCandidateSeqs + 1;
constexpr uint32_t kSeqMax = kGenerateSeq + 1;
constexpr int kMaxCandidateTokens = 32;                 // longer candidates are truncated
constexpr float kNoScore = -1.0e4f;                     // a candidate with no tokens
constexpr int64_t kMmapAlignment = 32;

using Clock = std::chrono::steady_clock;

struct Model {
    llama_model *model = nullptr;
    FILE *file = nullptr;  // the fd-backed variant keeps its FILE open as long as the model lives
};

struct Session {
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_vocab = 0;
    std::vector<llama_token> cached;  // tokens of the context in seq 0
    llama_batch batch{};
    int batch_cap = 0;
    bool has_deadline = false;
    Clock::time_point deadline;

    bool expired() const { return has_deadline && Clock::now() >= deadline; }
};

// Set when a timeout aborted a decode: llama.cpp then logs a few ERROR lines that are expected.
std::atomic<bool> g_aborting{false};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level != GGML_LOG_LEVEL_ERROR || g_aborting.load()) return;
#ifdef __ANDROID__
    __android_log_write(ANDROID_LOG_ERROR, "kcllama", text);
#else
    std::fputs(text, stderr);
#endif
}

void ensure_backend() {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
    });
}

bool abort_callback(void *data) {
    const bool stop = static_cast<Session *>(data)->expired();
    if (stop) g_aborting.store(true);
    return stop;
}

// ---- UTF-16 <-> UTF-8 -------------------------------------------------------------------------

void append_utf8(std::string &out, uint32_t c) {
    if (c < 0x80) {
        out += static_cast<char>(c);
    } else if (c < 0x800) {
        out += static_cast<char>(0xC0 | (c >> 6));
        out += static_cast<char>(0x80 | (c & 0x3F));
    } else if (c < 0x10000) {
        out += static_cast<char>(0xE0 | (c >> 12));
        out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (c & 0x3F));
    } else {
        out += static_cast<char>(0xF0 | (c >> 18));
        out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
        out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (c & 0x3F));
    }
}

std::string to_utf8(JNIEnv *env, jstring js) {
    std::string out;
    if (js == nullptr) return out;
    const jsize n = env->GetStringLength(js);
    const jchar *chars = env->GetStringChars(js, nullptr);
    if (chars == nullptr) return out;
    out.reserve(static_cast<size_t>(n) * 3 / 2);
    for (jsize i = 0; i < n; i++) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;  // a lone surrogate
        }
        append_utf8(out, c);
    }
    env->ReleaseStringChars(js, chars);
    return out;
}

// Invalid bytes become U+FFFD; an incomplete sequence at the end (generation can stop mid-character)
// is dropped.
jstring from_utf8(JNIEnv *env, const std::string &s) {
    std::vector<jchar> out;
    out.reserve(s.size());
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        const auto b = static_cast<uint8_t>(s[i]);
        int len = b < 0x80 ? 1 : (b >> 5) == 0x6 ? 2 : (b >> 4) == 0xE ? 3 : (b >> 3) == 0x1E ? 4 : 0;
        if (len == 0) {
            out.push_back(0xFFFD);
            i++;
            continue;
        }
        if (i + len > n) break;
        uint32_t c = len == 1 ? b : b & (0xFF >> (len + 1));
        bool ok = true;
        for (int k = 1; k < len; k++) {
            const auto cb = static_cast<uint8_t>(s[i + k]);
            if ((cb & 0xC0) != 0x80) {
                ok = false;
                break;
            }
            c = (c << 6) | (cb & 0x3F);
        }
        if (!ok || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) {
            out.push_back(0xFFFD);
            i++;
            continue;
        }
        if (c >= 0x10000) {
            c -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (c >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (c & 0x3FF)));
        } else {
            out.push_back(static_cast<jchar>(c));
        }
        i += len;
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

// ---- Tokens -----------------------------------------------------------------------------------

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    std::vector<llama_token> tokens(text.size() + 8);
    int32_t n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                               static_cast<int32_t>(tokens.size()), add_special, false);
    if (n < 0 && n != INT32_MIN) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), add_special, false);
    }
    tokens.resize(n > 0 ? static_cast<size_t>(n) : 0);
    return tokens;
}

// The tokens of a context string: BOS first (an empty context is just BOS), at most n_ctx / 2 of
// them (BOS plus the most recent ones). Trailing spaces go: the candidates carry their own.
std::vector<llama_token> context_tokens(const Session &s, std::string text) {
    while (!text.empty() && text.back() == ' ') text.pop_back();
    std::vector<llama_token> tokens = tokenize(s.vocab, text, true);
    llama_token bos = llama_vocab_bos(s.vocab);
    if (bos < 0) bos = llama_vocab_eos(s.vocab);
    const bool has_bos = bos >= 0;
    if (has_bos && (tokens.empty() || tokens.front() != bos)) tokens.insert(tokens.begin(), bos);
    const size_t cap = static_cast<size_t>(s.n_ctx / 2);
    if (tokens.size() > cap) {
        const size_t keep = has_bos ? cap - 1 : cap;
        tokens.erase(tokens.begin() + (has_bos ? 1 : 0), tokens.end() - static_cast<std::ptrdiff_t>(keep));
    }
    return tokens;
}

size_t common_prefix(const std::vector<llama_token> &a, const std::vector<llama_token> &b) {
    const size_t n = std::min(a.size(), b.size());
    size_t i = 0;
    while (i < n && a[i] == b[i]) i++;
    return i;
}

// ---- Batch and decode -------------------------------------------------------------------------

void batch_clear(Session &s) { s.batch.n_tokens = 0; }

void batch_add(Session &s, llama_token token, llama_pos pos, llama_seq_id seq, bool logits) {
    const int i = s.batch.n_tokens++;
    s.batch.token[i] = token;
    s.batch.pos[i] = pos;
    s.batch.n_seq_id[i] = 1;
    s.batch.seq_id[i][0] = seq;
    s.batch.logits[i] = logits ? 1 : 0;
}

// 0 on success. Anything else (abort, no free slot, error) leaves the caller to clean up.
int decode(Session &s) { return llama_decode(s.ctx, s.batch); }

void reset_cache(Session &s) {
    llama_memory_clear(llama_get_memory(s.ctx), true);
    s.cached.clear();
}

void remove_scratch_seqs(Session &s) {
    llama_memory_t mem = llama_get_memory(s.ctx);
    for (llama_seq_id k = 1; k <= kGenerateSeq; k++) llama_memory_seq_rm(mem, k, -1, -1);
}

// Brings seq 0 to hold exactly `tokens` except for the part from `p` on, which is decoded now with
// logits for the last token only. p is the reusable prefix, at most tokens.size() - 1 so that the
// last token is always decoded (its logits predict the first candidate token).
// Returns false on abort or error; the cache is then reset.
bool decode_context(Session &s, const std::vector<llama_token> &tokens, size_t p) {
    llama_memory_t mem = llama_get_memory(s.ctx);
    if (p < s.cached.size()) {
        if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(p), -1)) {
            reset_cache(s);
            p = 0;
        } else {
            s.cached.resize(p);
        }
    }
    const size_t n = tokens.size();
    for (size_t from = p; from < n;) {
        const size_t count = std::min<size_t>(n - from, static_cast<size_t>(s.batch_cap));
        batch_clear(s);
        for (size_t i = 0; i < count; i++) {
            batch_add(s, tokens[from + i], static_cast<llama_pos>(from + i), 0, from + i + 1 == n);
        }
        if (decode(s) != 0) {
            reset_cache(s);
            return false;
        }
        from += count;
    }
    s.cached = tokens;
    return true;
}

// exp(x) for x <= 0 (relative error about 2e-7), written so the compiler can vectorise it: the
// vocabulary is ~49k entries per scored position, and libm's expf is scalar. x = n ln2 + r with
// |r| <= ln2 / 2, a degree-6 polynomial for exp(r), and 2^n built in the exponent bits.
inline float fast_exp_neg(float x) {
    x = std::max(x, -87.0f);
    const int n = static_cast<int>(x * 1.44269504f - 0.5f);  // rounds to nearest for x <= 0
    const auto fn = static_cast<float>(n);
    const float r = (x - fn * 0.693145752f) - fn * 1.42860682e-6f;
    float p = 1.0f / 720.0f;
    p = p * r + 1.0f / 120.0f;
    p = p * r + 1.0f / 24.0f;
    p = p * r + 1.0f / 6.0f;
    p = p * r + 0.5f;
    p = p * r + 1.0f;
    p = p * r + 1.0f;
    const uint32_t bits = static_cast<uint32_t>(n + 127) << 23;
    float scale;
    std::memcpy(&scale, &bits, sizeof(scale));
    return p * scale;
}

// log(sum(exp(x))) over one row of logits, stable; eight independent lanes so it vectorises.
float log_sum_exp(const float *x, int n) {
    constexpr int kLanes = 8;
    float lane_max[kLanes];
    for (int k = 0; k < kLanes; k++) lane_max[k] = x[0];
    int i = 0;
    for (; i + kLanes <= n; i += kLanes) {
        for (int k = 0; k < kLanes; k++) lane_max[k] = std::max(lane_max[k], x[i + k]);
    }
    float m = lane_max[0];
    for (int k = 1; k < kLanes; k++) m = std::max(m, lane_max[k]);
    for (int j = i; j < n; j++) m = std::max(m, x[j]);

    float acc[kLanes] = {};
    for (i = 0; i + kLanes <= n; i += kLanes) {
        for (int k = 0; k < kLanes; k++) acc[k] += fast_exp_neg(x[i + k] - m);
    }
    float sum = 0.0f;
    for (int k = 0; k < kLanes; k++) sum += acc[k];
    for (int j = i; j < n; j++) sum += fast_exp_neg(x[j] - m);
    return m + std::log(sum);
}

void arm_deadline(Session &s, jlong timeout_ms) {
    g_aborting.store(false);
    s.has_deadline = timeout_ms >= 0;
    if (s.has_deadline) s.deadline = Clock::now() + std::chrono::milliseconds(timeout_ms);
}

// ---- score ------------------------------------------------------------------------------------

bool score_impl(Session &s, const std::string &context, const std::vector<std::string> &candidates,
                std::vector<float> &scores) {
    const size_t n_cand = candidates.size();
    scores.assign(n_cand, 0.0f);
    if (s.expired()) return false;

    const std::vector<llama_token> ctx_tokens = context_tokens(s, context);
    if (ctx_tokens.empty()) return false;
    const size_t n = ctx_tokens.size();

    std::vector<std::vector<llama_token>> cand_tokens(n_cand);
    for (size_t i = 0; i < n_cand; i++) {
        cand_tokens[i] = tokenize(s.vocab, " " + candidates[i], false);
        if (cand_tokens[i].size() > static_cast<size_t>(kMaxCandidateTokens)) {
            cand_tokens[i].resize(kMaxCandidateTokens);
        }
    }

    const size_t p = std::min(common_prefix(s.cached, ctx_tokens), n - 1);
    if (!decode_context(s, ctx_tokens, p)) return false;

    // The first token of every candidate is predicted by the last context token.
    const float *logits = llama_get_logits_ith(s.ctx, -1);
    if (logits == nullptr) return false;
    const float first_lse = log_sum_exp(logits, s.n_vocab);
    for (size_t i = 0; i < n_cand; i++) {
        scores[i] = cand_tokens[i].empty() ? kNoScore : logits[cand_tokens[i][0]] - first_lse;
    }

    // The rest: candidates in batches, each in its own sequence copied from seq 0. Row b of the
    // output holds the prediction for token (j + 1) of candidate row_cand[b].
    llama_memory_t mem = llama_get_memory(s.ctx);
    const size_t budget = static_cast<size_t>(s.n_ctx) - n;  // free cells next to the context
    size_t next = 0;
    while (next < n_cand) {
        std::vector<size_t> row_cand;
        std::vector<llama_token> row_target;
        std::vector<size_t> group;
        size_t cells = 0;
        while (next < n_cand && group.size() < static_cast<size_t>(kMaxCandidateSeqs)) {
            const size_t extra = cand_tokens[next].empty() ? 0 : cand_tokens[next].size() - 1;
            if (!group.empty() && cells + extra > budget) break;
            if (extra > 0) {
                group.push_back(next);
                cells += extra;
            }
            next++;
        }
        if (group.empty()) continue;

        batch_clear(s);
        for (size_t g = 0; g < group.size(); g++) {
            const auto seq = static_cast<llama_seq_id>(g + 1);
            const std::vector<llama_token> &t = cand_tokens[group[g]];
            llama_memory_seq_cp(mem, 0, seq, -1, -1);
            for (size_t j = 0; j + 1 < t.size(); j++) {
                batch_add(s, t[j], static_cast<llama_pos>(n + j), seq, true);
                row_cand.push_back(group[g]);
                row_target.push_back(t[j + 1]);
            }
        }
        const int rc = decode(s);
        if (rc != 0) {
            remove_scratch_seqs(s);
            if (rc < -1) reset_cache(s);
            return false;
        }
        for (size_t b = 0; b < row_cand.size(); b++) {
            const float *row = llama_get_logits_ith(s.ctx, static_cast<int32_t>(b));
            if (row == nullptr) {
                remove_scratch_seqs(s);
                return false;
            }
            scores[row_cand[b]] += row[row_target[b]] - log_sum_exp(row, s.n_vocab);
        }
        remove_scratch_seqs(s);
    }
    return true;
}

// ---- generate ---------------------------------------------------------------------------------

bool ends_phrase(const std::string &piece) {
    return piece.find_first_of(".!?,") != std::string::npos;
}

bool generate_impl(Session &s, const std::string &prompt, int max_tokens, std::string &text) {
    if (s.expired()) return false;
    const std::vector<llama_token> tokens = context_tokens(s, prompt);
    if (tokens.empty()) return false;
    const size_t n = tokens.size();
    max_tokens = std::min(max_tokens, s.n_ctx / 2);

    llama_memory_t mem = llama_get_memory(s.ctx);
    size_t p = std::min(common_prefix(s.cached, tokens), n - 1);
    // The cells of seq 0 stay occupied, so the new tokens need room beside them. If there is none,
    // the score cache is sacrificed: the next score() decodes its context again.
    if (s.cached.size() + (n - p) + static_cast<size_t>(max_tokens) > static_cast<size_t>(s.n_ctx)) {
        reset_cache(s);
        p = 0;
    }

    llama_memory_seq_rm(mem, kGenerateSeq, -1, -1);
    if (p > 0) llama_memory_seq_cp(mem, 0, kGenerateSeq, 0, static_cast<llama_pos>(p));
    batch_clear(s);
    for (size_t i = p; i < n; i++) {
        batch_add(s, tokens[i], static_cast<llama_pos>(i), kGenerateSeq, i + 1 == n);
    }
    bool ok = decode(s) == 0;

    for (int step = 0; ok && step < max_tokens; step++) {
        const float *logits = llama_get_logits_ith(s.ctx, -1);
        if (logits == nullptr) {
            ok = false;
            break;
        }
        llama_token best = 0;
        for (int i = 1; i < s.n_vocab; i++) {
            if (logits[i] > logits[best]) best = i;
        }
        if (llama_vocab_is_eog(s.vocab, best)) break;

        char buf[128];
        const int len = llama_token_to_piece(s.vocab, best, buf, sizeof(buf), 0, false);
        if (len < 0) {
            ok = false;
            break;
        }
        const std::string piece(buf, static_cast<size_t>(len));
        const size_t nl = piece.find('\n');
        if (nl != std::string::npos) {
            text.append(piece, 0, nl);
            break;
        }
        text += piece;
        if (ends_phrase(piece) || step + 1 == max_tokens) break;

        batch_clear(s);
        batch_add(s, best, static_cast<llama_pos>(n + step), kGenerateSeq, true);
        ok = decode(s) == 0;
    }
    llama_memory_seq_rm(mem, kGenerateSeq, -1, -1);
    return ok;
}

// ---- Models -----------------------------------------------------------------------------------

llama_model_params model_params(bool use_mmap) {
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.load_mode = use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    return params;
}

jlong wrap_model(llama_model *model, FILE *file) {
    if (model == nullptr) {
        if (file != nullptr) std::fclose(file);
        return 0;
    }
    return reinterpret_cast<jlong>(new Model{model, file});
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_keycorrect_core_llama_LlamaNative_loadModelFile(
    JNIEnv *env, jobject, jstring path, jboolean use_mmap) {
    try {
        ensure_backend();
        const std::string p = to_utf8(env, path);
        return wrap_model(llama_model_load_from_file(p.c_str(), model_params(use_mmap == JNI_TRUE)), nullptr);
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_dev_keycorrect_core_llama_LlamaNative_loadModelFd(
    JNIEnv *, jobject, jint fd, jlong offset, jboolean use_mmap) {
    FILE *file = nullptr;
    try {
        ensure_backend();
        if (offset < 0) return 0;
        const int copy = dup(fd);  // the Java side keeps its fd; this FILE owns the copy
        if (copy < 0) return 0;
        file = fdopen(copy, "rb");
        if (file == nullptr) {
            close(copy);
            return 0;
        }
        if (fseeko(file, static_cast<off_t>(offset), SEEK_SET) != 0) {
            std::fclose(file);
            return 0;
        }
        const bool mmap = use_mmap == JNI_TRUE && offset % kMmapAlignment == 0;
        return wrap_model(llama_model_load_from_file_ptr(file, model_params(mmap)), file);
    } catch (...) {
        if (file != nullptr) std::fclose(file);
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_dev_keycorrect_core_llama_LlamaNative_newContext(
    JNIEnv *, jobject, jlong model_handle, jint n_ctx, jint n_threads) {
    try {
        auto *m = reinterpret_cast<Model *>(model_handle);
        if (m == nullptr || n_ctx <= 0 || n_threads <= 0) return 0;
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(n_ctx);
        cp.n_batch = cp.n_ubatch = static_cast<uint32_t>(n_ctx);
        cp.n_seq_max = kSeqMax;
        cp.kv_unified = true;  // all sequences share the n_ctx cells; candidates share the prefix
        cp.n_threads = cp.n_threads_batch = n_threads;
        cp.offload_kqv = false;
        cp.op_offload = false;
        cp.no_perf = true;
        auto *s = new Session();
        cp.abort_callback = abort_callback;
        cp.abort_callback_data = s;
        s->ctx = llama_init_from_model(m->model, cp);
        if (s->ctx == nullptr) {
            delete s;
            return 0;
        }
        s->vocab = llama_model_get_vocab(m->model);
        s->n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
        s->n_vocab = llama_vocab_n_tokens(s->vocab);
        s->batch_cap = s->n_ctx;
        s->batch = llama_batch_init(s->batch_cap, 0, 1);
        return reinterpret_cast<jlong>(s);
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL Java_dev_keycorrect_core_llama_LlamaNative_freeContext(JNIEnv *, jobject,
                                                                              jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    llama_batch_free(s->batch);
    llama_free(s->ctx);
    delete s;
}

JNIEXPORT void JNICALL Java_dev_keycorrect_core_llama_LlamaNative_freeModel(JNIEnv *, jobject,
                                                                            jlong handle) {
    auto *m = reinterpret_cast<Model *>(handle);
    if (m == nullptr) return;
    llama_model_free(m->model);
    if (m->file != nullptr) std::fclose(m->file);
    delete m;
}

JNIEXPORT jfloatArray JNICALL Java_dev_keycorrect_core_llama_LlamaNative_score(
    JNIEnv *env, jobject, jlong handle, jstring context, jobjectArray candidates, jlong timeout_ms) {
    try {
        auto *s = reinterpret_cast<Session *>(handle);
        if (s == nullptr || context == nullptr || candidates == nullptr) return nullptr;
        const jsize count = env->GetArrayLength(candidates);
        std::vector<std::string> cands(static_cast<size_t>(count));
        for (jsize i = 0; i < count; i++) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(candidates, i));
            cands[static_cast<size_t>(i)] = to_utf8(env, js);
            env->DeleteLocalRef(js);
        }
        const std::string ctx = to_utf8(env, context);
        arm_deadline(*s, timeout_ms);
        std::vector<float> scores;
        const bool ok = score_impl(*s, ctx, cands, scores);
        s->has_deadline = false;
        if (!ok) return nullptr;
        jfloatArray out = env->NewFloatArray(count);
        if (out != nullptr) env->SetFloatArrayRegion(out, 0, count, scores.data());
        return out;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL Java_dev_keycorrect_core_llama_LlamaNative_generate(
    JNIEnv *env, jobject, jlong handle, jstring prompt, jint max_tokens, jlong timeout_ms) {
    try {
        auto *s = reinterpret_cast<Session *>(handle);
        if (s == nullptr || prompt == nullptr || max_tokens <= 0) return nullptr;
        const std::string text_in = to_utf8(env, prompt);
        arm_deadline(*s, timeout_ms);
        std::string text;
        const bool ok = generate_impl(*s, text_in, max_tokens, text);
        s->has_deadline = false;
        return ok ? from_utf8(env, text) : nullptr;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL Java_dev_keycorrect_core_llama_LlamaNative_describe(JNIEnv *env, jobject,
                                                                              jlong handle) {
    auto *m = reinterpret_cast<Model *>(handle);
    if (m == nullptr) return env->NewStringUTF("");
    char desc[256] = {0};
    llama_model_desc(m->model, desc, sizeof(desc));
    char out[384];
    std::snprintf(out, sizeof(out), "%s | %.1fM params | vocab %d", desc,
                  static_cast<double>(llama_model_n_params(m->model)) / 1.0e6,
                  llama_vocab_n_tokens(llama_model_get_vocab(m->model)));
    return env->NewStringUTF(out);
}

}  // extern "C"
