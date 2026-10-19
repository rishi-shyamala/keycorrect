# KeyCorrect — Phase 7 plan: neural and plugin engines

Goal (PLAN §4, §17): an optional on-device language model that improves suggestions (the SCORE tier
reranks the classic engine's candidates; GENERATE gives phrase completion), an LM-strength slider,
and the plugin AIDL SDK with a sample plugin. Exit criteria: the reranker improves eval metrics
within the latency budget, and the sample plugin works end to end.

## Decisions (user, 2026-10-18)

| # | Question | Decision |
|---|---|---|
| P7-1 | Getting the model onto the phone (no INTERNET, D3) | **A neural-pack APK plus file import.** An optional `dev.keycorrect.neural` APK holds the default model and the llama.cpp runtime. It is signature-checked like the voice pack. Settings → Engines → "Import model" also accepts any GGUF the user picks (SAF). |
| P7-2 | Downloads | **Approved:** llama.cpp (MIT) source at a pinned release, built with the NDK; candidate models from their official repos (SmolLM2-135M and 360M, Qwen3-0.6B, and the small Gemma models for testing). There is also a research step for newer small models released since the project began. Downloads are git-ignored with checksums recorded, and only the chosen default ships (in the pack). |
| P7-3 | Default | **On when the pack is installed:** the reranker is on automatically and phrase completion is off. Both switches and the LM-strength slider are in Settings → Engines. |
| P7-4 | Delivery | **One PR.** |

## Design
- **Process:** the model runs in a separate `:engine` process (bound service), so a crash or
  out-of-memory there never takes the keyboard down. The keyboard talks to it over the same AIDL
  contract that plugins use.
- **SCORE:** up to 16 candidates are scored against the shared context, reusing the KV cache. The
  score enters fusion as a log-prob feature weighted by the LM-strength slider. It is asynchronous
  with a deadline: the classic result is shown at once and the reranked one replaces it if it
  arrives in time.
- **GENERATE:** phrase completion runs asynchronously, with prompt templates.
- **Privacy gates (PLAN §4.3):** no engine text in password, incognito or no-learning fields.
  Plugins need per-plugin approval with a pinned certificate.
- **Plugin SDK:** the AIDL `dev.keycorrect.TEXT_ENGINE` and a sample plugin APK.
- **Eval:** a reranker arm in `tools/eval`, giving accuracy, false corrections and latency for each
  candidate model. The default is chosen from the data.

## Model research (2026-10-18)

**Runtime:** llama.cpp **v0.7.0** (2026-10-15, MIT), pinned in `tools/data/fetch-neural.sh`. The script
downloads the source tarball and the candidate GGUFs into `data/raw/neural/` (git-ignored by
`/data/raw/`), pinned to Hugging Face commits, with SHA-256 in `tools/data/neural-sources.sha256`.

### Candidates

All GGUF sizes are the files actually fetched (or listed by the repo for the ones not fetched).
"Pack" means the size of the GGUF if it were the model in the neural-pack APK (budget ~250 MB total).

| Model | Params | Licence | GGUF source | File | Vocab | Fits pack? |
|---|---|---|---|---|---|---|
| **SmolLM2-135M** (base) | 135M | Apache-2.0 | ggml-org (Q4_K_M 101 MB also there) | Q8_0 **145 MB** | 49,152 | yes |
| **SmolLM2-360M** (base) | 362M | Apache-2.0 | QuantFactory conversion (HuggingFaceTB ships only the instruct Q8_0, 386 MB) | Q4_K_M **271 MB** (Q4_0 229 MB) | 49,152 | marginal |
| **Granite 4.0 350M** (instruct) | 352M | Apache-2.0 | ibm-granite (official) | Q4_K_M **237 MB** (Q8_0 378 MB) | 100,352 | yes |
| **Qwen3-0.6B** | 596M | Apache-2.0 | Qwen (official) | Q8_0 **639 MB** (official repo has no Q4) | 151,936 | no |
| **Gemma 3 270M** (base) | 268M | Gemma Terms of Use | ggml-org | Q8_0 **292 MB** (170M of the params are embeddings) | 262,144 | no |
| Not fetched: LFM2.5-350M (LiquidAI) | 354M | LFM Open License 1.0 (custom, revenue-capped) | LiquidAI | Q4_K_M 229 MB | n/a | licence needs review |
| Not fetched: Qwen3.5-0.8B | 753M | Apache-2.0 | ggml-org / unsloth | Q4_K_M 533 MB | ~250k | no |
| Not fetched: Gemma 3n E2B / Gemma 4 E2B | 4.5-5.1B stored | Gemma terms / Apache-2.0 | ggml-org / google | 4.8 GB (3n Q8) | n/a | no |
| Not considered: SmolLM3 (3B), MobileLLM-R1 ("other" licence) | | | | | | no |

**Gemma terms:** Gemma 3 is under the Gemma Terms of Use, not an OSI licence. Redistribution is allowed
but each recipient must be bound by the use restrictions (the Prohibited Use Policy), the notice has to
travel with the weights, and Google reserves a right to require stopping use. Shipping that inside an
APK is workable but needs an in-app terms notice, and nothing in this benchmark argues for it, so
Gemma is excluded from the default (kept only as a comparison). The Gemma 4 E2B release is Apache-2.0
but is ~5B stored parameters, far over the size budget. No sub-1B Gemma 3n/4 exists.

### Desktop benchmark (x86-64, 16 threads machine, CPU-only, relative numbers only)

llama-bench `pp32` (32-token prompt, 5 reps), warm file cache. Load time and RSS from the scoring tool
(context 256). Phone numbers will be several times lower; use these only to rank the models.

| Model | pp32 t/s, 1 thread | pp32 t/s, 4 threads | Load (warm) | Peak RSS | Top-1 (1000 items) | MRR |
|---|---:|---:|---:|---:|---:|---:|
| SmolLM2-135M Q8_0 | 543 | 1922 | 64 ms | 168 MB | 0.911 | 0.953 |
| SmolLM2-360M Q4_K_M | 202 | 729 | 63 ms | 314 MB | 0.912 | 0.952 |
| Granite 4.0 350M Q4_K_M | 388 | 1302 | 118 ms | 385 MB | 0.894 | 0.944 |
| Gemma 3 270M Q8_0 | 589 | 1839 | 228 ms | 335 MB | 0.904 | 0.950 |
| Qwen3-0.6B Q8_0 | 158 | 552 | 145 ms | 688 MB | 0.916 | 0.956 |

### Reranking quality signal

A small scoring tool (C++ against the built libllama, kept in the scratchpad, not committed) sums
log P(" " + word | preceding words) for each candidate, with no KV reuse. Items: 1000 sentences
sampled (seed 7, 6-20 words) from `data/processed/heldout.txt`; the cut is after the 3rd or later
word. Each item has the true next word plus 4 distractors: 2 of similar unigram frequency (0.5x-2x)
and 2 within edit distance 2 of the true word (from the top 50k unigrams, count >= 50). A candidate
set of 5 gives 20% chance; a unigram-frequency prior alone gets **40.2%** top-1. All models pick the
true word 89-92% of the time, i.e. the context signal is strong for every one of them. The standard
error of each top-1 is about 0.9 points, so SmolLM2-135M, SmolLM2-360M, Gemma 270M and Qwen3-0.6B
are **statistically indistinguishable** here; Granite is slightly behind.

Caveats: the distractors are artificial (not the classic engine's real candidate lists), so this says
only that the LM carries enough context signal to rerank; the real go/no-go is the Phase 7 eval arm in
`tools/eval`, which measures accuracy, false corrections and latency inside the keyboard pipeline.
The scoring tool's per-item time (SmolLM2-135M 38 ms vs Qwen3-0.6B 137 ms for 5 candidates, 4 threads)
includes a full-vocabulary softmax per position, which penalises the large-vocab models (Gemma 262k,
Qwen 152k) and is not what the shipped scorer would do; the real scorer shares the context prompt
(KV reuse) and can restrict the output projection.

### Recommendation

- **Default: SmolLM2-135M (base), Q8_0, 145 MB** (Q4_K_M, 101 MB, is the fallback if the pack is tight and
  the eval shows no loss). Apache-2.0, redistributable; the fastest and smallest candidate (3.5x faster
  prompt processing than Qwen3-0.6B, 168 MB RSS); equal quality to the larger models in this test; a
  small 49k vocabulary keeps the output projection cheap; Q8 avoids quantisation noise on a model this
  small. Fits the 250 MB pack with room for the runtime libs.
- **Optional upgrade: SmolLM2-360M Q4_0/Q4_K_M** (229-271 MB) for users who import it via file import; not
  default because it is 2.7x slower prompt processing for no measurable gain here, and Q4_K_M is just over
  budget.
- Exclude: Gemma 3 270M (terms, 262k vocab, 292 MB), Qwen3-0.6B (639 MB, kept only as a quality
  reference), Granite 350M (largest vocab of the fitting models, lowest accuracy), LFM2.5 (custom
  licence).
- The final choice is still the user's; this ranking should be re-checked with the real eval arm before
  the pack is built, and the on-device numbers on the Tab S9 (Snapdragon 8 Gen 2) measured with the
  NDK build.

## Implementation (2026-10-18)

The user confirmed the default model, **SmolLM2-135M Q8_0**. The implementation contracts follow; each work package builds against them.

### Modules
| Module | Kind | What |
|---|---|---|
| `:core:llama` | JVM library | `LlamaNative` (JNI `external fun`s) and a Kotlin wrapper `LlamaModel` / `LlamaSession`. The C++ glue `core/llama/src/main/cpp/kc_llama_jni.cpp` and its `CMakeLists.txt` take `-DLLAMA_SRC=<llama.cpp source>`. `tools/llama/build-host.sh` builds the glue plus llama.cpp for the desktop (eval and tests). JVM tests skip unless the host library and a model are present. |
| `:neuralpack` | Android application `dev.keycorrect.neural` | Like `:voicepack`: no code, no permissions. It builds llama.cpp plus the glue with the NDK (CMake, arm64-v8a and x86_64) into `libkcllama.so` and stores the default GGUF as an **uncompressed asset** `models/default.gguf`. It is built only when `data/raw/neural` has the source and model (CI skips it). `release.sh` attaches it. |
| `:core:packs` | Android library | The signed-pack locator taken out of `VoicePackLocator`: package name, native libraries and asset list are parameters. It keeps the signature check, the stamp re-check and library hashes with NeedsRestart. `VoicePackLocator` becomes a thin wrapper with the same public API. |
| `:core:engine-sdk` | Android library (AIDL) | The plugin contract (below) and a client `EngineConnection` that binds a service and exposes synchronous calls with timeouts. Plugins and the built-in engine use it alike. |
| `:sampleplugin` | Android application `dev.keycorrect.sampleplugin` | A sample engine built on the SDK. |

### Native API (`LlamaNative`, handles are `Long`, 0 means failure)
- `loadModelFile(path, useMmap)` and `loadModelFd(fd, offset, useMmap)`. The fd variant opens the pack's uncompressed asset through `llama_model_load_from_file_ptr` after seeking to `offset`. It uses mmap only when `offset % 32 == 0`; otherwise it reads the model into memory.
- `newContext(model, nCtx = 512, nThreads = 4)` and `freeContext` / `freeModel`.
- `score(ctx, context, candidates[], timeoutMs)` returns `FloatArray?`: for each candidate, the sum of log P over the tokens of `" " + candidate`, given `context`. An empty context starts from BOS.
  - KV reuse: the tokens of the last context stay in sequence 0. A new call keeps the longest common token prefix, removes the rest and decodes only the new suffix.
  - The candidates are decoded in one batch, sequence i+1 each (`llama_memory_seq_cp` from sequence 0), and removed afterwards.
  - It returns null when the timeout fires (llama's abort callback).
- `generate(ctx, prompt, maxTokens, timeoutMs)` returns `String?`: greedy decoding that stops at a newline or sentence-ending punctuation. It is used for phrase completion.
- `describe(model)` returns a short description (architecture, parameter count, vocabulary size) for the settings screen.

### Plugin contract (`dev.keycorrect.TEXT_ENGINE`, AIDL `dev.keycorrect.engine.ITextEngine`)
```
int apiVersion();                 // 1
Bundle info();                    // id, name, capabilities (String[] "Score"/"Generate"), languages, status
void configure(in Bundle options); // engine-specific; the built-in engine takes "modelPath"/"modelFd"
float[] score(String context, in String[] candidates, long timeoutMs);   // null = no answer
String generate(String prompt, int maxTokens, long timeoutMs);          // null = no answer
```
- **The built-in engine:** `NeuralEngineService` runs in `android:process=":engine"`, is not exported, and implements the same interface.
- **How a plugin is used:**
  - It is used only after the user approves it in Settings → Engines. The approval pins the package name plus the SHA-256 of its signing certificate.
  - A plugin whose certificate changes is unapproved.
  - The approval screen warns when a plugin holds INTERNET.
  - Approvals and the model choice are never carried by backup/transfer.
- **How the engine process loads a model:** it never touches DataStore. The keyboard passes the model through `configure`.

### Pipeline (SCORE tier)
- **The request:** `PredictRequest.lmBonus: Map<String, Float>` (default empty). `ClassicEngine` adds the bonus to the fused score of the candidate (or literal) with that text, before ranking and the autocorrect policy.
- **Who may use an engine:** `PredictRequest.engineAllowed` (default false) is set by the keyboard only when all of these hold:
  - suggestions are on;
  - the field isn't sensitive;
  - learning is on for the field and in the settings;
  - incognito is off;
  - `neuralRerank` is on.
- **`Predictor.refine(request, result, stale)`:** returns a `PredictResult?` (default null). `DecoderLoop.submit` calls it after publishing the classic result, on the decoder thread. If it returns a result that isn't stale, that result replaces `completed` and is posted.
  - A separator never waits for it: the classic result is already `completed`.
- **`NeuralReranker` (app):**
  - It collects the literal plus the candidates (at most 16) and calls the scorer on its own thread.
  - The decoder thread polls in 5 ms steps, gives up when stale or after `RERANK_DEADLINE_MS` (40 ms), and allows one call in flight.
  - It then re-decodes with `lmBonus = lmStrength × LM_WEIGHT × (lp − mean lp)`. `LM_WEIGHT` is tuned on the eval; it starts at 1.0.
  - Next-word predictions (no literal) are reranked the same way.
- **GENERATE:** only when `neuralPhrases` is on, through the same gate and asynchronously after scoring. It completes the top candidate with up to 3 words and returns them as `PredictResult.phrase` (source `Engine`).
- **Eval:** the reranker arm in `tools/eval` drives the same `NeuralReranker` logic, with `:core:llama` on the desktop.
