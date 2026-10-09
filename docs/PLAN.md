# KeyCorrect — Research, Design & Implementation Plan

Status: **draft for review** (2026-10-09). Nothing is implemented yet. All decisions in §16 are made (D1–D10 confirmed by you; D11 placeholder name). Target devices: **Galaxy S23 Ultra** and **Galaxy Tab S9** (both Snapdragon 8 Gen 2, Android 16+).

---

## 1. Research summary

### 1.1 Open-source keyboards surveyed

| Project | License | UI tech | Status (Oct 2026) | What we take from it |
|---|---|---|---|---|
| **HeliBoard** | GPL-3.0-only (AOSP files Apache-2.0 AND GPL-3.0) | Views (keyboard), Compose (settings) | Very active (v4.1) | Field-type logic (`InputAttributes`), simple+JSON layout formats with selectors, zip backup, sensitive-clip handling. Gesture typing needs Google's **proprietary** `libjni_latinimegoogle` — not an option for us. |
| **FUTO Keyboard** | FUTO Source First 1.1 (**not open source**; no commercial modification/redistribution) | AOSP Views + Compose | Active (v0.1.30) | **Reference only, no code reuse.** Best-in-class ideas: GGUF transformer LM via llama.cpp blended with dictionary decoder ("LM strength" slider), idle-time LoRA finetuning on user typing, whisper.cpp voice with dynamic `audio_ctx`, neural layout-agnostic swipe decoder. |
| **FlorisBoard** | Apache-2.0 | Compose | Active, but prediction not shipped (0.5.x) | Cleanest pluggable NLP API (`SuggestionProvider` with `suggest / notifySuggestionAccepted / notifySuggestionReverted / removeSuggestion`), own Kotlin glide typing (statistical template matching), clipboard w/ `isSensitive` + separate expiry, inline autofill, Compose-in-IME plumbing, baseline profiles. Code is reusable with attribution under either license choice. |
| AOSP LatinIME | Apache-2.0 | Views | Maintenance only | Reference spatial model (`ProximityInfo`), binary dictionary + dictionaries (Apache-2.0), revert-autocorrect semantics. |
| AnySoftKeyboard | Apache-2.0 | Views (Java) | Active | Java gesture detector, add-on packs, auto-incognito on `NO_PERSONALIZED_LEARNING`. |
| Thumb-Key / Unexpected / Simple Keyboard | AGPL-3.0 / GPL-3.0 / Apache-2.0 | Compose / Views / Views | Active | Private in-app clipboard (Thumb-Key), `TYPE_NULL` editor fallback to key events for Termux (Unexpected), tiny footprint and space-swipe cursor (Simple). |

Reusable non-code assets: FUTO layout definitions (Apache-2.0), FUTO swipe dataset swipe.futo.org (MIT), FUTO Swipe model weights (FUTO Model Weights License 1.0 — commercial OK but requires a visible "powered by FUTO Swipe" notice; ExecuTorch `.pte`, ~2.6 MB encoder, layout-agnostic), AOSP dictionaries (Apache-2.0).

### 1.2 Design principles distilled
- **Latency is the product.** Key feedback within one frame; commit within a few ms; suggestions must never block typing. Anything slow (LLMs) is async and *advisory*.
- **Spatial model over hit boxes.** Treat each touch as a probability over keys (Gaussian per key, Ouyang et al. / Gboard FST paper) and combine with the language model (noisy channel). Learn per-user touch offsets (Henze et al. CHI'12: offset correction alone cut errors ~9%).
- **Always let the user keep what they typed.** The literal is always one tap away; autocorrect is undoable with one backspace.
- **Restrained haptics** using `HapticFeedbackConstants.KEYBOARD_TAP/PRESS/RELEASE`.
- **Suggestion stability.** Don't reshuffle the slot the user is about to tap.
- Primary keys may be smaller than 48dp because the spatial model compensates; secondary controls (toolbar, panels) keep ≥48dp targets.

### 1.3 Model landscape (defaults are chosen in §4.5 / §5.3)
- **STT:** sherpa-onnx (Apache-2.0, official AAR + Kotlin API) runs streaming Zipformer, Moonshine v2, Parakeet, Whisper. Moonshine v2 (MIT weights, English-only, streaming; Small 123M ≈ 7.8% avg WER) has the best accuracy-per-size. Parakeet TDT v3 (CC-BY-4.0, ~640 MB) is the accuracy king but heavy. whisper.cpp (MIT) gives 99 languages but is batch-only. Android's `SpeechRecognizer` lets the user pick any installed recognizer (Google, FUTO Voice Input…) — a free pluggability path.
- **LM:** llama.cpp (MIT) is the only runtime with easy logits/candidate scoring + KV-cache reuse — critical because we want to **rerank** dictionary candidates, not free-generate. SmolLM2-135M/360M and Qwen3-0.6B are Apache-2.0. Gemma 3/3n use Gemma Terms (avoid bundling; allow user-supplied). Gemini Nano / ML Kit GenAI: no logits, foreground-only, device allowlist → only viable as an optional "rewrite/proofread" action, not autocorrect.
- **Personalization:** user n-gram cache interpolated with LM (cheap, instant) ≫ logit bias ≫ kNN retrieval ≫ on-device LoRA (FUTO proves feasible for ~40–150M models while idle+charging).

---

## 2. Architecture overview

```
┌──────────────────────────── IME process (lean, always resident while keyboard shown) ────────────────────────────┐
│ KeyCorrectIme : InputMethodService (LifecycleOwner + SavedStateRegistryOwner for Compose)                          │
│   ├─ UI layer (Compose): KeyboardSurface (single-Canvas key renderer) · SuggestionStrip · Toolbar · Panels          │
│   │                      (emoji, clipboard, editing, voice, layout switcher) · inline autofill chips (AndroidView)   │
│   ├─ InputController: key events → composing state machine → InputConnection (batch edits)                          │
│   ├─ FieldProfile: EditorInfo → layout/variant + feature gates (learning, suggestions, clipboard preview, engines)   │
│   ├─ Decoder pipeline (dedicated thread): SpatialModel → CandidateGen (dictionary/user dict/shortcuts)              │
│   │     → ClassicScorer (n-gram + user cache) → FusionRanker ◄── async rerank from TextEngine (sequence-numbered)    │
│   ├─ LearningTracker: observes suggestions shown/taken, commits, reverts, edits → LearningStore                     │
│   ├─ ClipboardService · ShortcutService · EmojiService · GestureDecoder (swipe)                                     │
│   └─ EngineHost client (AIDL) ──────────────────────────────────────────────┐                                     │
└──────────────────────────────────────────────────────────────────────────────│────────────────────────────────────┘
                                                                               ▼
┌────────── :engine process (isolated, can be killed/restarted, unloads when idle) ──────────┐   ┌─ 3rd-party plugin APKs ─┐
│ Built-in engines: llama.cpp TextEngine (GGUF) · sherpa-onnx SttEngine · whisper.cpp (opt)   │   │ AIDL TextEngine/SttEngine│
│ Model manager (files in app-private storage; imported via SAF or companion download)        │   │ (user-approved, pinned   │
└─────────────────────────────────────────────────────────────────────────────────────────────┘   │  by signing cert)        │
                                                                                                    └──────────────────────────┘
Settings app (Activity, Compose M3 Expressive): layouts editor, engines, learning dashboard, clipboard, backup/restore.
No INTERNET permission anywhere in the keyboard [D3]. Network engines can only exist as separate, optional plugin APKs.
```

Why a separate `:engine` process: a 150–600 MB model in the IME process makes the keyboard the OOM-killer's favourite target ("keyboard disappeared"). Isolated, the engine can die without taking typing with it, and `onTrimMemory` can unload it. Binder overhead (~0.1–0.5 ms/call) is negligible against model latency. The *same* AIDL contract serves third-party plugins, so built-in and external engines are interchangeable.

### 2.1 Gradle modules
```
:app                      IME service, settings activity, DI wiring
:core:model               shared data types (Candidate, FieldProfile, LearningEvent…)
:core:engine-api          TextEngine / SttEngine interfaces + AIDL + Parcelables (also published as plugin SDK)
:core:input               composing state machine, InputConnection wrapper, cursor/selection ops
:core:layout              layout schema, JSON parser/serializer, geometry, built-in layouts
:core:decoder             spatial model, candidate generation, classic scorer, fusion ranker, autocorrect policy
:core:dictionary          mmap'd binary dictionary reader (+ writer used by tools)
:core:learning            tracker, outcome classifier, learning store, touch model
:core:data                encrypted DB (Room+SQLCipher), DataStore settings, backup/restore
:feature:keyboard-ui      renderer, strip, panels
:feature:clipboard | :feature:emoji | :feature:shortcuts | :feature:gesture | :feature:voice | :feature:settings
:engine:host              :engine process service, model manager
:engine:llamacpp          JNI + CMake (llama.cpp submodule)
:engine:sherpa            sherpa-onnx AAR wrapper
:engine:whisper           (optional) whisper.cpp JNI
:plugin-sample            example 3rd-party engine APK
:tools:dictbuild          JVM CLI: wordlists → binary dictionary
:tools:eval               JVM harness: simulated typing → accuracy/KSR/latency metrics
:benchmark                Macrobenchmark + baseline profile generation
```

Language: Kotlin throughout; C/C++ only via existing libraries (llama.cpp, sherpa-onnx, whisper.cpp). Move hot loops to native only if profiling demands.

---

## 3. Input pipeline & latency design

1. **Touch → key** (UI thread, <1 ms): renderer owns geometry; touch produces a `TouchPoint(x, y, t, pointerId)`. Visual press feedback immediately.
2. **Commit** (UI thread, <3 ms): composing text updated via `InputConnection.setComposingText` inside `beginBatchEdit/endBatchEdit`.
3. **Fast decode** (decoder thread, target <8 ms p95): spatial likelihoods for the touch sequence → candidates from dictionary trie (beam over key probabilities, edit-distance tolerance) + user dictionary + shortcuts → classic score (unigram/n-gram + user cache + correction memory) → FusionRanker → strip.
4. **Slow rerank** (engine process, async, deadline ~60–150 ms): top-K candidates (+ context) sent to the active TextEngine. Results carry the input sequence number; stale results are dropped. When results arrive, re-fuse and update the strip subject to the **stability rule** (don't change the top slot within 150 ms of the user's last touch near the strip).
5. **Autocorrect on space/punctuation**: uses the best ranking available *at that instant* (optionally wait ≤30 ms for a pending rerank — setting). Typing never waits on an engine.
6. **Next-word / phrase prediction** after commit: same pipeline with empty composing word; may return multi-word candidates (§3.2).

### 3.1 Context is a first-class input
Every decode request carries a `TypingContext`, not just the current word:
- **Left context**: the last *N* words before the cursor (classic n-gram uses up to 3; neural engines get the current sentence plus up to ~64 tokens of preceding text), sentence boundary / capitalization state, and whether we're at the start of a field.
- **Right context**: text after the cursor (via `getSurroundingText`), so mid-sentence edits are corrected to fit both sides ("I ___ to the store" picks *went*, not *want*).
- **Field context**: field class, app package bucket, hint text.
- The classic engine scores with a back-off n-gram (trigram → bigram → unigram) **plus** the user's own n-grams; correction memory and learned pairs are keyed by a **context bucket** (previous word + field class) so "there→their" can be learned *after "over"* without forcing it everywhere.
- Context is re-read after cursor jumps / external edits (`onUpdateSelection`), and trimmed at privacy boundaries (never crosses into a password field's text).

### 3.2 Phrase / sentence-segment completion
- Candidates may be **multi-word** (up to ~6 words / one clause). Sources: (a) the user's own frequent phrases mined from their n-gram store ("see you tomorrow", "let me know if"), (b) high-probability n-gram chains from the base LM, (c) the neural engine generating greedily until confidence drops below a threshold or a clause boundary is hit.
- Shown as a dedicated **phrase chip** in the strip (single-word suggestions stay in their slots so word-level typing isn't disrupted). Tap → insert the whole phrase; **swipe right on the spacebar / tap the chip's arrow → accept just the next word**, so partial acceptance is natural.
- Strict confidence gate (phrases are easy to get wrong and distracting): shown only when the model's cumulative probability clears a threshold the learning layer tunes from your accept rate.
- Never in password/URL/email/number fields; suppressed for remote/plugin engines in fields excluded by §4.3.

Threads: UI · decoder (single-thread dispatcher, cancellable) · IO (DB writes, batched) · engine IPC. No work while the keyboard is hidden; engine unloads after N minutes idle (default 5) or on `onTrimMemory(TRIM_MEMORY_BACKGROUND+)`.

---

## 4. Pluggable autocorrect / autocomplete engines

### 4.1 Capability tiers
Your requirement is "pass in text, get text out". That's the floor; engines can opt into richer capabilities and the pipeline uses whatever is available.

```kotlin
interface TextEngine {
    val info: EngineInfo            // id, name, version, languages, capabilities, privacy (local/remote)
    suspend fun load(ctx: EngineContext)
    suspend fun unload()
}
enum class Capability { GENERATE, SCORE, PREDICT, LEARN, HINTS }

// Tier 0 — any "text in → text out" model (LLM, seq2seq, HTTP endpoint)
interface GenerativeEngine : TextEngine { suspend fun generate(req: GenerateRequest): GenerateResult } // prompt, maxTokens, n, stop
// Tier 1 — can score strings: log P(candidate | context)   ← best fit for reranking
interface ScoringEngine : TextEngine { suspend fun score(context: String, candidates: List<String>): FloatArray }
// Tier 2 — full replacement: takes touches/composing + context, returns ranked candidates
interface PredictiveEngine : TextEngine { suspend fun predict(req: PredictRequest): List<Candidate> }
// Optional add-ons
interface LearningEngine : TextEngine { suspend fun onLearningEvents(batch: List<LearningEvent>) }  // engine-side adaptation
interface HintableEngine : TextEngine { fun setHints(h: UserHints) }  // user vocab, recent corrections, style
```

How each tier plugs in:
| Tier | Used for | How |
|---|---|---|
| GENERATE | next-word & phrase completion, "fix this word/sentence" | User-editable prompt templates (`{context}`, `{word}`, `{n}`), output parser (lines / JSON / first-token). Results enter fusion as candidates with a **rank feature** only. Too slow for per-keystroke autocorrect by design; used async. |
| SCORE | reranking dictionary candidates for autocorrect + completion | Candidates (≤16) scored against shared context with KV-cache reuse. Enters fusion as a real log-prob feature. |
| PREDICT | engines that do their own decoding (e.g. a neural keyboard decoder) | Replaces CandidateGen+ClassicScorer; personalization layer still wraps its output. |

Auto-upgrade: an HTTP endpoint that returns logprobs (OpenAI-compatible `logprobs`, llama-server `n_probs`) is wrapped as SCORE automatically.

### 4.2 Engine sources
1. **Built-in classic engine** (always present; the fast path; no model needed).
2. **Built-in llama.cpp engine** — user picks a bundled/downloaded GGUF or imports any GGUF via SAF.
3. **Built-in ONNX engine** (later) — for custom small models exported to ONNX.
4. **Plugin APK** — bound service with intent action `dev.keycorrect.TEXT_ENGINE`, discovered via `<queries>`. User must approve each plugin; we pin its signing certificate; settings show "external engine active".
5. **HTTP endpoint** (OpenAI-compatible / Ollama / llama-server) — *not in the keyboard* [D3]. Possible later as an optional separate "network bridge" plugin APK speaking our AIDL contract; the keyboard itself never opens a socket (on Android even `localhost` needs INTERNET).

### 4.3 Privacy gates (enforced by the pipeline, not by engines)
Engines never receive text from password fields, `IME_FLAG_NO_PERSONALIZED_LEARNING` fields, or when incognito is on — the classic engine handles those alone (and in password fields, no suggestions at all). Remote engines additionally get a per-field-class allowlist (default: off for email/URL/number fields).

### 4.4 Classic engine (default fast path)
- **Dictionary**: our own memory-mapped binary trie (DAWG) with unigram frequencies + a compact bigram/trigram table; built by `:tools:dictbuild` from AOSP wordlists (Apache-2.0) and other compatibly-licensed lists per language.
- **Spatial model**: per-key 2D Gaussian (mean = key center + learned user offset, covariance from key size), per-layout.
- **Candidate generation**: beam search over the trie weighted by spatial probabilities, allowing insert/delete/transpose with penalties (handles both proximity typos and real misspellings), plus completions of the current prefix.
- **Scoring**: noisy channel `log P(touches|word) + λ·log P_LM(word|context) + user-model terms` (§6).

### 4.5 Defaults
- **Always on**: classic engine.
- **Neural reranker (optional, SCORE tier)**: **SmolLM2-135M** (Apache-2.0, ~145 MB Q8 / ~90 MB Q4) via llama.cpp in the `:engine` process. Offer **SmolLM2-360M** / **Qwen3-0.6B** as "higher quality, slower" options. Auto-suggest enabling the reranker on devices with ≥6 GB RAM. An "LM strength" slider (à la FUTO) controls λ for the neural term.
- Later evaluation: a purpose-trained ~20–50M keyboard LM (FUTO showed ~42M works) could beat SmolLM2-135M on latency at similar quality; the eval harness (§14) decides.

---

## 5. Pluggable speech-to-text

### 5.1 Interface
```kotlin
interface SttEngine {
    val info: SttEngineInfo  // streaming?, sampleRate, languages, supportsBiasing, capturesOwnAudio, local/remote
    fun startSession(cfg: SttConfig): SttSession   // language, hints (user vocab), punctuation, profanity filter
}
interface SttSession {
    val results: Flow<SttResult>    // Partial(text, stablePrefixLen) | Final(text, alternatives) | Error
    fun acceptAudio(pcm16: ShortArray)  // not used when capturesOwnAudio
    fun finish(); fun cancel()
}
```
Audio is captured in the IME (visible window ⇒ "while-in-use" mic access is fine; no foreground service needed) at 16 kHz mono with VAD, then streamed to the engine (over AIDL via a pipe `ParcelFileDescriptor` for out-of-process engines). Batch engines receive the whole utterance at VAD end (or rolling chunks).

### 5.2 Engine sources
1. Built-in **sherpa-onnx** (streaming + offline models).
2. Built-in **whisper.cpp** (optional module; multilingual batch; FUTO-style dynamic `audio_ctx` for short clips).
3. **System recognizer adapter** — `SpeechRecognizer.createSpeechRecognizer(ctx, chosenComponent)` / `createOnDeviceSpeechRecognizer`: lets you use Google, FUTO Voice Input, or any `RecognitionService` you've installed. (`capturesOwnAudio = true`.)
4. **Plugin APK** via AIDL (`dev.keycorrect.STT_ENGINE`).
5. **HTTP** (OpenAI-compatible `/v1/audio/transcriptions`) — only via an optional separate bridge plugin APK [D3].

### 5.3 Defaults
**Constraint (from you): the default model must be small — ≤ ~50 MB of weights, ≤ ~150 MB RAM while dictating — and is loaded only while the mic is active, then unloaded.**

Default candidates (all English, all ≤ ~50 MB, benchmarked head-to-head on the S23 Ultra in Phase 6 for WER on your own voice samples, latency, and RAM):
| Candidate | License | Mode | Size (approx.) |
|---|---|---|---|
| **Moonshine v2 Streaming Tiny** (34M params, int8) | MIT | Streaming | ~35–45 MB (verify; community repacks are larger because they duplicate decoders) |
| **Moonshine Tiny (v1) quantized** via sherpa-onnx | MIT | Offline (fast enough for short utterances) | sherpa-onnx archive 29.9 MB |
| **Streaming Zipformer en-20M** (int8) via sherpa-onnx | Apache-2.0 | Streaming | 20M params → int8 tens of MB (verify extracted size) |
| **Whisper tiny.en** q5/q8 via whisper.cpp | MIT | Batch | ~31–42 MB |

Expected default: **Moonshine v2 Streaming Tiny** (streaming + best published accuracy at that size), falling back to Zipformer-20M if it misses RAM/latency targets.

Small models are made more accurate by the keyboard around them, not by size: contextual biasing with your vocabulary (hotwords / prompt), rescoring the n-best list with the text engine + your n-grams, and post-ASR replacement from learned STT pairs (§5.4).

Opt-in "higher accuracy" downloads (clearly labelled with size/RAM): Moonshine v2 Small (~120 MB), Parakeet TDT 110M int8 (~105 MB). Large models (Parakeet 0.6B, Whisper small+) are supported but not offered by default.

### 5.4 Dictation UX & learning
Partial results shown as composing text (stable prefix committed progressively), auto-punctuation/capitalization, spoken commands ("new line", "delete that", "select last word"), optional "clean up" pass through the GENERATE engine. Edits you make right after dictation are learned as **STT substitution pairs** ("cube control" → "kubectl") and fed back as **contextual biasing** (sherpa-onnx hotwords for transducers / Whisper initial prompt) plus post-ASR replacement. Dictated text feeds the text learning store at reduced weight (source-tagged).

---

## 6. Learning over time

### 6.1 Principle: personalization lives *outside* the engine
A **Personalization Layer** wraps whichever engine is active, so learning works with any engine — including a black-box "text in, text out" model — and survives switching engines:

```
final_score(c) = FusionRanker(features(c))
features: engine score (log-prob, or rank if GENERATE-only) · spatial log-likelihood · classic LM log-prob
          · user n-gram log-prob · correction-memory score · edit-distance class · source (dict/user/shortcut/engine)
          · is_literal · recency · chars remaining · field class
```
- **User n-gram cache** (unigram/bigram/trigram counts with time decay) — interpolated with the base LM. Instant effect.
- **Correction memory**: pairs `(typed → final, context-bucket)` with accept/revert counts.
- **Touch model**: per-key learned offset/covariance from words you accepted (personal key centers).
- **FusionRanker**: a tiny online logistic/pairwise model (≈20 weights, per engine id) trained on every suggestion outcome; regularized toward hand-tuned defaults so it can't drift wildly. This is how the system learns *how much to trust a given engine for you*.
- **Adaptive autocorrect threshold**: tuned toward a target revert rate (default 3%): more reverts → more conservative.

Engines that declare `LEARN` / `HINTS` additionally receive sanitized signals:
- HINTS: top personal vocabulary, recent correction pairs, preferred casing → prompt context for GENERATE engines, logit bias for llama.cpp.
- LEARN: aggregated events plus a short **encrypted buffer of recent non-sensitive sentences (on by default, toggle in settings; never from password/no-learning/incognito fields; 7-day retention; "clear now" button)** → the built-in llama.cpp engine can later do **idle+charging LoRA finetuning** (à la FUTO). Adapter files are per-base-model and included in backups.

### 6.2 Event model
For every word, the tracker records a `WordEpisode`: context (left/right context per §3.1), input (touches / gesture / voice / paste), every suggestion list shown with timestamps, the commit method (space, punctuation, tapped slot k, autocorrect, gesture, voice), the committed text, and **post-commit edits** observed within a tracking window (last ~5 words / ~2 minutes) via `onUpdateSelection` + `getSurroundingText`.

### 6.3 Outcome rules (the edge cases)
Weights are relative; "pos/neg" update the user model, correction memory, and produce FusionRanker training pairs.

| # | Situation | Interpretation | Learning |
|---|---|---|---|
| 1 | User taps suggestion *k* | Explicit accept | Strong pos for *k* in context. Pairwise "k ≻ j" for candidates ranked above *k* (weak neg for them). |
| 2 | Suggestion *S* was shown (any slot) and the user **typed S out fully** | **Implicit accept** | Pos for *S* (same as typing it), plus FusionRanker "S was right". Weight scaled by visibility: shown ≥300 ms and ≥2 chars before the end → full; appeared only on the last char → small (user likely didn't see it). Also logged as a "missed savings" metric. |
| 3 | User typed *W* ≠ all shown suggestions and committed it | User preferred *W* | Pos for *W* in context. Shown candidates get a **capped, visibility-weighted weak neg** (prevents "never accept suggestions" users from collapsing the model). Pairwise "W ≻ top". |
| 4 | *W* is not in any dictionary | Possible new word **or** uncorrected typo | Goes to *candidate words* with count. Becomes a completion only after ≥2 commits in ≥2 sessions (or explicit add). Becomes an **autocorrect target** only after ≥3 uses and never if it's edit-distance-1 from a common word that the user has also typed (likely typo). |
| 5 | Autocorrect *T→C* fired and the user kept going | Implicit accept of the correction | Pos for *C*, pos for pair *T→C*. Touch model updated toward *C*'s keys. |
| 6 | Autocorrect fired, user hit backspace immediately (restores *T*) | **Revert** | Strong neg for pair *T→C* (in context); pos for *T*. After 2 reverts of the same pair: *T* added to user dictionary, pair suppressed. Counts toward revert-rate for adaptive threshold. |
| 7 | Autocorrect fired, user later went back and edited *C* → *X* | Delayed correction | If *X = T*: same as #6. Else: neg *T→C*, pos *T→X* (the user meant *X*). |
| 8 | User taps literal ("keep what I typed") slot | Explicit rejection of correction | Strong pos *T*, suppress the top correction pair. |
| 9 | User tapped a suggestion then immediately deleted it | Mis-tap | Undo the accept from #1; weak neg. |
| 10 | User deletes a committed word and retypes another | Rewrite or typo-fix | If edit distance ≤ ~⅓ of length → missed correction: learn pair *old→new* (we'll fix it next time). Else → rewording: pos for new word only, no correction pair. |
| 11 | Long-press suggestion → "Never suggest" | Explicit block | Word/pair blocklisted (user can undo in settings). |
| 12 | Swipe decoded *G1*, user picks *G2* from strip / deletes & re-swipes | Gesture misdecode | Pos *G2* given gesture; gesture-specific pair memory; boosts *G2* prior for similar shapes. |
| 13 | User consistently types a casing variant mid-sentence (iPhone, McDonald's) | Casing preference | Learn canonical casing after 2 uses. |
| 14 | Suggestions shown while user is ignoring the strip for a long run | Low engagement | Don't accumulate negatives beyond the cap; reduce strip churn instead. |
| 15 | Text from paste, autofill, shortcut expansion, or our own predictions committed in bulk | Not user typing | Not learned as typing evidence (shortcut usage counts toward shortcut ranking only). |
| 16 | Voice-dictated text | Different error model | Learned at 0.3× weight, source-tagged; edits after dictation → STT pairs (§5.4). |
| 17 | Password / no-learning / incognito fields | Privacy | **Nothing recorded**, not even in memory beyond the composing word. |
| 18 | Phrase chip tapped (whole phrase inserted) | Explicit accept of every word | Pos for the phrase and each word-in-context; boosts phrase confidence gate. |
| 19 | Phrase partially accepted (next-word accept *k* times, then user types something else) | Accept prefix, reject rest | Pos for the first *k* words; neg for the (k+1)th predicted word *in that context*; the typed word gets pos (as #3). |
| 20 | Phrase inserted then trailing words deleted | Over-reach | Treat as #19 with *k* = words kept; raises phrase gate slightly. |
| 21 | Same correction is right in one context but wrong in another ("their"/"there") | Context-dependent | Pairs stored per context bucket (§3.1) with global back-off; a revert in one bucket does not suppress the pair elsewhere. |

Additional safeguards: time decay (default half-life ~90 days of *active use*), a per-event cap on rank movement (anti-poisoning / anti-overfit), per-language stores, optional per-field-class buckets (messaging vs email vs URL), and a "What KeyCorrect learned" screen to browse/forget words, pairs, and shortcuts, plus "forget last N minutes".

### 6.4 Storage
SQLite (Room) with SQLCipher [D9]: `words`, `ngrams`, `phrases`, `correction_pairs(context_bucket)`, `blocklist`, `touch_model`, `candidate_words`, `ranker_weights(engine_id)`, `episodes_ring` (last few hundred episodes, auto-pruned), `sentence_buffer` (D10, 7-day TTL). Writes batched on the IO thread at commit boundaries.

---

## 7. Swipe typing
- **Phase 5:** own **SHARK²-style decoder** in Kotlin: resample gesture to N points; prune vocabulary by start/end key neighbourhoods and path length; compute *location* and *shape* channel distances against on-the-fly word templates from the current layout geometry; combine with LM prior + user model via the same FusionRanker. Live preview of the top candidate during the swipe; decode on lift <30 ms target. Trail rendering with Expressive motion. Works on any custom layout because templates come from geometry.
- **Phase 10 (optional) neural encoder** [D7]: either FUTO Swipe weights (layout-agnostic, tiny; requires ExecuTorch runtime + attribution notice) or our own model trained on the MIT swipe.futo.org dataset, emitting per-timestep key probabilities constrained by our lexicon.

---

## 8. Field adaptation (`FieldProfile`)

| EditorInfo | Layout | Suggestions / autocorrect | Strip shows | Other |
|---|---|---|---|---|
| `TYPE_CLASS_TEXT` normal | Text | On | Words, emoji, shortcuts | — |
| `…VARIATION_URI` | Text + `/` `.` `.com` (long-press TLDs), no auto-space | Autocorrect off; completion from learned domains (opt-in) | Domains, clipboard URL | — |
| `…EMAIL_ADDRESS` | Text + `@` `.` | Autocorrect off | **Your saved emails**, learned domains | — |
| `…PERSON_NAME`, `…POSTAL_ADDRESS` | Text, auto-caps words | Correction lenient, names prioritized | **Your saved name/address** | — |
| `…PASSWORD`, `…WEB_PASSWORD`, `…VISIBLE_PASSWORD`, `NUMBER_VARIATION_PASSWORD` | Text + number row (or numpad) | **Off; no learning; no engines** | Inline autofill only | Key previews off, mic hidden, clipboard previews masked, optional `FLAG_SECURE` |
| `TYPE_CLASS_NUMBER` (+SIGNED/DECIMAL) | Numpad with `-` / `.` as flags allow | Off | Recent numbers? (off by default) | — |
| `TYPE_CLASS_PHONE` | Phone pad (`+ * # ,`) | Off | **Your saved phone number** | — |
| `TYPE_CLASS_DATETIME` (+DATE/TIME) | Numpad with `/ - :` | Off | Today's date/time | — |
| `TYPE_NULL` (terminals) | Text + ctrl/esc/tab/arrows row (scheduled for Phase 4, editing; until then plain Text) | Off | — | Send raw key events (Termux) |
| `TEXT_FLAG_NO_SUGGESTIONS` | — | Off | — | — |
| `IME_FLAG_NO_PERSONALIZED_LEARNING` | — | On (base model only) | — | Incognito indicator, no learning |
| `imeOptions` action | Enter key becomes Go/Search/Send/Next/Done icon | — | — | `IME_FLAG_NO_ENTER_ACTION` respected |
| Hint text / field name heuristics ("OTP", "code", "zip") | numpad for codes even in text fields (opt-in) | — | Recent OTP from clipboard (expires 3 min) | — |

"Personal details" (emails, phones, addresses, name) are stored encrypted and editable in settings. Per-app overrides allowed (e.g. force layout X in app Y).

---

## 9. Clipboard history
- Listener: `ClipboardManager.addPrimaryClipChangedListener` (only works while we are the default IME — Android 10+ restriction, which is fine).
- **Never stored**: clips with `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+), clips copied *from* a password field we were typing in, clips matching sensitive heuristics (card numbers w/ Luhn, IBAN, high-entropy tokens, `sk-…`/`ghp_…` key patterns, OTP-like codes → kept only 3 min with masked preview) — heuristics configurable.
- Keep last **N items (default 30)**; text TTL default 7 days; pinned items never expire; images capped (default 20 MB total).
- **Images/files**: copied into app-private storage *at capture time* (the source URI grant is temporary); pasted via `InputConnectionCompat.commitContent` through our non-exported `FileProvider` with per-URI grant, only if the editor's `contentMimeTypes` accept it; otherwise fall back to setting the primary clip.
- URL items get a "open/clean tracking params" action.
- UI: clipboard panel (grid, pin, delete, clear all), suggestion-strip chip for a fresh clip (≤60 s old; masked in password fields), "clear system clipboard after paste" option for sensitive items.
- Storage encrypted; history disabled entirely during direct boot and in incognito.

## 10. Text selection & editing
- **Spacebar**: horizontal drag moves cursor by character with acceleration; vertical drag moves lines (where `TYPE_NULL`/terminal → arrow key events); long-press-then-drag enters selection mode (anchor at start). Second finger while dragging extends selection.
- **Backspace**: swipe left selects whole words back (preview highlight), release deletes; swipe back to cancel.
- **Editing panel** (toolbar button): D-pad, word-jump, Home/End, select toggle (shift-anchored), select word / sentence / line / all, cut/copy/paste, undo/redo (own undo stack for our commits + `Ctrl+Z` key events fallback).
- **Selection strip**: when text is selected, the suggestion strip shows the selection with "expand to word/sentence", case toggle (Aa/AA/aa), and "send to engine" actions (fix spelling / rewrite via GENERATE engine).
- Uses `getSurroundingText` (API 31), `setSelection`, `performContextMenuAction`, `deleteSurroundingTextInCodePoints`; grapheme-aware cursor moves (emoji, ZWJ sequences).

## 11. Emoji
Data from Unicode `emoji-test.txt` + CLDR annotations (Unicode License v3) compiled into an asset at build time; filtered at runtime via `Paint.hasGlyph`; EmojiCompat for older fonts. Categories, recents/frequent, skin-tone & gender preferences (per-emoji memory), keyword search (CLDR, localized), emoji suggestions in the strip ("pizza" → 🍕), kaomoji/emoticon tab. GIFs/stickers are out of scope (network).

## 12. Shortcuts (text expansion)
`trigger → expansion` with variables `{date}`, `{time}`, `{clipboard}`, `{cursor}`; optional scope by field class/app; mode per shortcut: *suggest* (default, appears as top strip chip) or *auto-expand on space*. Import from Android `UserDictionary` shortcuts (readable by the default IME) and Gboard `dictionary.txt` format.

## 13. Layout, theming, customization
- **Layout schema (JSON, versioned)**: rows of keys with width weights, labels, codes, popup keys, per-key shape overrides, selectors (shift state, field variation, layout direction) inspired by HeliBoard/FlorisBoard; import of HeliBoard *simple* and FlorisBoard-JSON layouts.
- **Global geometry**: key height (% of screen, separate portrait/landscape), horizontal/vertical gap, corner radius, key **shape** (rounded rect, squircle, pill, circle, M3 Expressive `MaterialShapes`), label size/font, number row, bottom-row composition, split mode (tablet/foldable), one-handed (left/right), floating keyboard, symbol pages editable.
- **In-app layout editor** with live preview; drag keys, edit popups, export/share JSON.
- **Renderer** (an internal implementation detail, not handwriting): one Compose `Canvas` draws all keys (cached `Outline`s/paths, `TextMeasurer` cache) — avoids hundreds of composables per key and keeps recomposition at zero during typing. Key preview/popup bubbles drawn in a transparent area above the keyboard with `onComputeInsets` limiting the touchable region.
- **Theming**: Material 3 with `dynamicLight/DarkColorScheme` (wallpaper colors, Android 12+), follows system dark mode live; Expressive motion (spring specs, `MotionScheme.expressive()`), press animation morphs key shape (`androidx.graphics.shapes`). User themes: override palette/contrast/borders/background image. Settings app uses `MaterialExpressiveTheme` (material3 1.5 alpha currently — wrapped so we can swap to stable).

### 13.1 UI/UX customization (dedicated phase)
Everything below is a setting with live preview, grouped into **profiles** (e.g. phone portrait, phone landscape, tablet, tablet + book-cover keyboard) and exportable as part of backups.
- **Haptics**: on/off per event (key press, key release, long-press popup, space-drag cursor ticks, delete-repeat, swipe start/end, suggestion accept); **duration (ms)** and **strength** (amplitude when `Vibrator.hasAmplitudeControl()`, which the S23 Ultra/Tab S9 support) per event; style = one-shot vs composition primitives (`PRIMITIVE_CLICK/TICK/LOW_TICK`) vs system `KEYBOARD_TAP`; test button. Option to ignore system "touch vibration" setting.
- **Sound**: on/off, volume, sound set (system / soft click / typewriter / none), separate sounds for space/delete/enter.
- **Key geometry**: keyboard height (per profile), bottom padding (gesture-nav), side margins, horizontal/vertical gaps, **key shape** (rounded rect with radius slider, squircle, pill, circle, M3 Expressive shapes), border width, shadow/elevation, per-key-class overrides (letters, modifiers, space, enter, symbols), number row, extra keys (emoji, comma, language, clipboard).
- **Labels**: font family, size, weight, case (auto/upper/lower), hint labels for long-press characters (show/hide/position).
- **Press feedback**: key preview popup on/off/size/delay, press animation (shape morph, scale, ripple, none), animation speed.
- **Timing**: long-press delay, key-repeat rate/delay, double-tap-shift caps-lock window, popup dismissal, swipe sensitivity, spacebar cursor speed/acceleration, backspace-swipe sensitivity.
- **Colors / theme editor**: dynamic (wallpaper) vs custom palette, separate light/dark, per-key-class colors, contrast boost, background image/blur, theme import/export.
- **Toolbar & strip**: which buttons appear and in what order, number of suggestion slots, phrase chip on/off, strip auto-hide.
- **Modes**: one-handed (left/right, width), split (tablet, adjustable gap), floating (position/size remembered per app orientation), thumb-zone presets for the Tab S9.
- **Symbol / layout pages**: fully editable via the layout editor (§13).

### 13.2 Device notes (S23 Ultra, Tab S9)
- Both Snapdragon 8 Gen 2 with ≥8 GB RAM: neural reranker (SmolLM2-135M; 360M likely fine) can be **on by default** on these devices; budgets in §14 are still measured as if on a mid-range phone so the keyboard stays light.
- **Tab S9 + Book Cover Keyboard / hardware keyboard**: when a physical keyboard is attached, show only the suggestion/toolbar strip (`onEvaluateInputViewShown`), keep autocorrect/predictions/clipboard working for hardware typing, support Samsung DeX layouts.
- Tablet defaults: split layout option, larger key gaps, landscape height profile.
- One UI quirks to test: Samsung's own keyboard settings/"keyboard vibration" interplay, Edge panels, edge-to-edge insets with gesture navigation.

## 14. Performance budgets & verification
| Metric | Budget |
|---|---|
| Touch-down → visual feedback | ≤1 frame (16/8 ms) |
| Key → text in editor | ≤3 ms p95 on UI thread |
| Fast decode (suggestions) | ≤8 ms p95 mid-range |
| Neural rerank | ≤150 ms p95 (async; never blocks) |
| `onCreateInputView` cold | ≤150 ms; warm show ≤50 ms |
| IME process memory (no models) | ≤80 MB PSS |
| Idle CPU when hidden | 0 (no timers, no wakelocks) |
| APK size (no models) | ≤15 MB (models downloaded/imported) |

Tooling: Macrobenchmark + baseline profiles; microbenchmarks for decoder; `:tools:eval` simulated-typist harness (Gaussian touch noise around key centers + realistic typo model over a held-out corpus) reporting autocorrect accuracy, false-correction rate, top-3 accuracy, keystroke savings rate, and latency — run per engine, in CI-like scripts, to compare engines and tune defaults. Table-driven unit tests for every row of §6.3.

## 15. Security & privacy model
- **No `INTERNET` permission in the keyboard APK** [D3]. Models are acquired by file import (SAF) with SHA-256 verification against a built-in manifest of known models (settings shows the download link to open in a browser). Any network engine must be a separate plugin APK, user-approved like any plugin.
- No exported components except the IME service (guarded by `BIND_INPUT_METHOD`), the launcher settings activity, and plugin-facing nothing (we *bind out* to plugins; they never bind in). `FileProvider` not exported.
- `android:allowBackup="false"` + `dataExtractionRules` excluding everything (no Google cloud backup / device-transfer of typing data); user-controlled encrypted export instead (§15.1).
- Encryption at rest [D9]: learning DB, clipboard, personal details via SQLCipher with a Keystore-wrapped key. Settings in DataStore.
- **Direct boot**: keyboard is `directBootAware` so it works on the lock screen; before first unlock only device-protected settings (layout/theme) are available — learning, clipboard, engines, personal details are disabled. **Deferred to Phase 9 (hardening):** `android:directBootAware` is intentionally not set yet, because a direct-boot-aware IME can become the lock-screen keyboard and a bug there could lock the user out. It must be tested explicitly (reboot before first unlock, lock screen entry, PIN/password entry) before enabling.
- Password fields: no suggestions, no learning, no engines, no key preview popups, mic hidden, masked clipboard, optional `FLAG_SECURE` on the IME window; key views marked `accessibilityDataSensitive` (API 34) so non-tool accessibility services can't read keystrokes.
- No user text in logs (lint rule + release log stripping). No analytics. Crash reports manual & redacted.
- Plugins: explicit approval, signing-cert pinning, capability display, never used in sensitive fields, can be revoked; remote engines show a persistent indicator.

### 15.1 Backup / transfer
`.kcbackup` = zip: `manifest.json` (schema & app version, contents, checksums) + `settings.json` + `layouts/*.json` + `themes/*.json` + `shortcuts.json` + `user_dictionary.tsv` + `learning/` (words, n-grams, correction pairs, touch model, ranker weights as JSON-lines) + `personal.json` + optional `clipboard/` (pinned only) + optional `models/adapters/` (base models referenced by hash, not included). Encrypted with AES-256-GCM, key from passphrase via Argon2id (fallback PBKDF2). Saved/loaded via SAF; import offers **merge** (counts summed, newest wins on conflicts) or **replace**; schema migrations versioned. Also plain exports of dictionary/shortcuts (TSV, Gboard-compatible) for interop.

### 15.2 Autofill
`android:supportsInlineSuggestions="true"`; implement `onCreateInlineSuggestionsRequest` (presentation specs styled from our theme via `androidx.autofill` `InlineSuggestionUi`) and `onInlineSuggestionsResponse` → inflate `InlineContentView`s into the strip (scrollable, pinned password-manager icon at end). Works with any system autofill service (Bitwarden, 1Password, Google, Proton…). We never see credential values until the user taps one, and then only as committed text which is excluded from learning.

---

## 16. Decisions for you

| # | Decision | Options | My recommendation |
|---|---|---|---|
| **D1 ✅ from scratch** | Starting point | (a) from scratch in Kotlin/Compose, borrowing Apache-2.0 code from FlorisBoard/AOSP where useful · (b) fork HeliBoard · (c) fork FlorisBoard | **(a)**. Pluggable engines + learning layer are the core and must be designed in; HeliBoard's AOSP Java core is hard to restructure and its swipe needs a proprietary lib; FlorisBoard has no shipped prediction and is mid-rewrite (0.6/0.7), so a fork diverges immediately. |
| **D2 ✅ GPL-3.0-or-later** | License | GPL-3.0-or-later · Apache-2.0 · keep private | **GPL-3.0-or-later**: lets us also borrow from HeliBoard/Unexpected Keyboard, and keeps forks of a privacy keyboard open. Apache-2.0 if you want max permissiveness (then no HeliBoard code). |
| **D3 ✅ fully local** | Network | (a) keyboard has no INTERNET; companion APK for downloads/HTTP engines · (b) single APK with INTERNET (user-toggled) · (c) fully offline, models only via file import | **(a)**. Decided: keyboard fully local; network engines only ever as an optional separate plugin APK. |
| **D4 ✅ English only** | Languages at launch | English only · English + others (which?) | Affects dictionaries, STT and LM defaults. **English-first architecture with multilingual support built in**, other languages added as dictionary packs. |
| D5 ✅ | minSdk / target | — | **minSdk 31**, compileSdk/targetSdk 37 (Android 17) if the platform package is final, else 36. You run Android 16+. |
| D6 ✅ | Default STT | — | **Small model ≤ ~50 MB** (Moonshine v2 Streaming Tiny expected; Zipformer-20M / Moonshine v1 tiny / Whisper tiny.en benchmarked in Phase 6). Larger models opt-in only. |
| D7 ✅ | Swipe engine | own SHARK² decoder · FUTO Swipe weights (attribution, ExecuTorch) · train own neural model on MIT dataset | **Own SHARK² first**, neural later (Phase 10) with your choice then. |
| D8 ✅ | Key renderer (how keys are drawn internally — not handwriting) | single-Canvas in Compose · Android Views | **Single-Canvas in Compose**. Handwriting input: out of scope. |
| D9 ✅ | Encryption at rest | SQLCipher + Keystore · sandbox only | **SQLCipher** (small perf cost, defends rooted/forensic access). |
| D10 ✅ | Raw-text buffer for engine finetuning | — | **On by default with a toggle**, encrypted, 7-day retention, never from sensitive fields. |
| D11 | App identity | name & package id | "KeyCorrect", `dev.keycorrect` (placeholder — your call). |
| D12 ✅ | Build environment | — | You install JDK 21 + Android cmdline tools/SDK/NDK; I verify versions (see `docs/SETUP.md`). |

## 17. Phased roadmap

Each phase ends usable; exit criteria in *italics*.

| Phase | Scope | Exit criteria |
|---|---|---|
| **0 Foundations** | Toolchain, Gradle multi-module + version catalog, lint/detekt/ktfmt, IME skeleton with Compose host & lifecycle owners, settings activity shell, DI (manual or Koin—small), test infra | *Keyboard selectable in system settings and types letters into a test app.* |
| **1 Core keyboard** | Layout schema + JSON + built-ins (QWERTY, symbols ×2, numpad, phone, datetime), single-Canvas renderer with **virtual accessibility nodes per key** (TalkBack/explore-by-touch, also used for automated UI tests), multitouch, shift/caps-lock, long-press popups, key previews, haptics/sound, `FieldProfile` + IME actions, dynamic-color theme & dark mode, basic size settings, inline autofill | *Daily-drivable without predictions; all field types from §8 switch correctly.* |
| **2 Prediction core** | `engine-api`, dictbuild + mmap dictionary, spatial model, classic engine with left/right context (§3.1) and phrase completion from n-grams (§3.2), suggestion strip with literal slot, autocorrect + one-backspace revert, user dictionary, shortcuts, `:tools:eval` harness | *Eval harness baseline recorded; autocorrect p95 <8 ms on device.* |
| **3 Learning** | Tracker, episode model, outcome classifier (§6.3), encrypted learning store, user n-gram cache, correction memory, touch model, FusionRanker, adaptive threshold, privacy gates, "What I learned" UI | *All §6.3 rows covered by tests; simulated-personal-corpus eval shows improvement over baseline.* |
| **4 Editing, clipboard, emoji** | Spacebar/backspace gestures, editing panel, selection strip; `TYPE_NULL` terminal extra-keys row (ctrl/esc/tab/arrows, §8); clipboard history w/ sensitivity + images; emoji panel/search/suggestions | *Feature checklist + no password-field leaks (instrumented tests).* |
| **5 Swipe** | SHARK² decoder, trail, preview, gesture learning, eval for swipe | *Top-1 swipe accuracy target on eval set; decode <30 ms.* |
| **6 Voice** | `SttEngine`, audio capture/VAD, sherpa-onnx engine + model manager (SAF import), system-recognizer adapter, dictation UX, biasing & STT learning; benchmark the ≤50 MB candidates on S23 Ultra | *Dictation works offline; default chosen from device benchmarks.* |
| **7 Neural & plugins** | `:engine` process, llama.cpp JNI (SCORE + GENERATE), SmolLM2 defaults, LM-strength slider, neural phrase completion, prompt templates, plugin AIDL SDK + sample plugin (optional later: separate HTTP-bridge plugin APK) | *Reranker improves eval metrics within latency budget; sample plugin works end-to-end.* |
| **8 UI/UX customization** | Everything in §13.1: haptics (per-event duration/strength/style), sound, key shapes/geometry/labels, press feedback, timings, theme editor, toolbar/strip config, profiles (phone/tablet/landscape/hardware-keyboard), in-app layout & symbol-page editor, one-handed/split/floating, Expressive motion polish | *Every §13.1 setting live-previews and round-trips through backup.* |
| **9 Hardening & polish** | Backup/restore, security audit (manifest, logs, FLAG_SECURE, `accessibilityDataSensitive` on key views in password fields, direct boot: enabling `directBootAware` only after explicit lock-screen/first-unlock testing, §15), Tab S9 hardware-keyboard mode, baseline profiles, perf pass on S23 Ultra + Tab S9 | *All budgets in §14 met on your devices.* |
| 10 Optional | On-device LoRA finetuning, neural swipe, ONNX engine, more languages, Gemini-Nano rewrite action | — |

## 18. Risks & open questions
- **Dictionary/n-gram data licensing per language** — AOSP lists are fine for English; each additional language must be checked.
- **Model latency on mid-range devices** — all phone numbers in research are unverified; Phase 6/7 includes on-device benchmarks before locking defaults.
- **Compose in IME edge cases** (lifecycle ordering: `onCreateInputView` can precede `onCreate` work; must init lifecycle/saved-state early).
- **Material3 Expressive APIs** may still be experimental/alpha in 1.5 — isolated behind our own theme wrapper.
- **Android 17 local-network permission** may affect a future LAN HTTP bridge plugin (not the keyboard).
- Learning quality depends on good defaults for weights/thresholds — the eval harness is non-negotiable.
