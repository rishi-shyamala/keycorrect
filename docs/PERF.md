# KeyCorrect — Performance check

A profiling pass runs **before every PR** (workflow rule, `docs/HANDOFF.md` §2). It looks for
regressions and new hotspots in the code that runs on every keystroke or every word, and checks
the budgets in `docs/PLAN.md` §14.

## Desktop vs device

- **The devices are ARM running ART** (Galaxy Tab S9, S23 Ultra). The desktop is x86-64 running
  HotSpot: different JIT, garbage collector, caches and escape analysis.
- **Desktop profiles are relative.** Compare them with the previous run on the same machine. They
  find hotspots, algorithmic waste and allocation churn. Don't tune for their absolute numbers.
- **Device microbenchmarks are the real numbers** (`:benchmark`, androidx.benchmark, release
  build, non-debuggable). Judge budgets on them.
- **Prefer fixes that remove work:** recomputation, copies, allocation, bad data-structure use.
  These help on any CPU. CPU-specific micro-tuning needs a device measurement to justify it.
- **Allocation matters more on ART than the desktop shows.** ART's escape analysis is weaker, and
  garbage made in the keyboard process competes with drawing.

## How to run

```sh
tools/profile/profile.sh                                    # desktop: decoder, learning, keystroke
ANDROID_SERIAL=R52X204HLZV tools/profile/profile.sh --device # + on-device microbenchmarks
```

- **Output:** a summary goes to stdout, and the full output (JFR recordings, eval reports) to
  `build/profile/<timestamp>/`. Open a `.jfr` file in JDK Mission Control, or use
  `jfr view hot-methods|allocation-by-site <file>`.
- **Data:** the decoder and learning profiles use the eval data (`data/processed`, git-ignored;
  see `docs/EVAL.md`). Without it they are skipped.
- **Device run:**
  - It needs an attached, authorised device and installs and runs only the benchmark test APK. No
    UI is touched, and it works while the device is locked.
  - Clocks aren't locked (that needs root), so expect a few percent noise. Compare medians and
    minimums, and re-run when in doubt.

## What is profiled

| Path | Where it runs | Desktop harness | Device benchmark |
|---|---|---|---|
| Decode (`ClassicEngine.predictNow`) | decoder thread, every keystroke | eval `--threads 1` latency table + JFR | `DecoderBenchmark.composing` |
| Decode with personalization | decoder thread | learn eval (learning arm) | `DecoderBenchmark.composingPersonalized` |
| Keyboard's own per-key work | **UI thread**, every keystroke | `KeystrokeBenchmarkTest` (opt-in) | `KeystrokeBenchmark.cannedPredictor` |
| Learning updates, snapshot publish | learning thread, every word | learn eval + JFR | `LearningBenchmark` |
| Frames (Compose) | UI thread | (none) | `dumpsys gfxinfo` on a release build (HANDOFF §4), when UI changed |

## Checklist before a PR

1. Run `tools/profile/profile.sh`, with `--device` whenever the tablet is available.
2. Compare with the reference numbers below. Investigate:
   - any regression over ~10% on a hot path;
   - any new entry in the top hot methods or allocation sites;
   - anything near a §14 budget on the device.
3. Fix obvious overhead: work redone per candidate or per keystroke, copies, boxing, needless
   allocation, poor hashing.
   - A fix must keep results identical.
   - The eval reports (accuracy, false corrections, learn-mode outputs) must not change, except
     for timings.
4. Update the reference numbers when they move, and say why in the PR.

## Reference numbers

### Desktop (x86-64 HotSpot, relative only), 2026-10-11, branch `perf-pass`

| | Before the perf pass | After |
|---|---|---|
| Decode, composing, p50 / p95 / p99 (eval, 1 thread) | 0.095 / 0.603 / 1.345 ms | 0.063 / 0.454 / 0.987 ms |
| Learn eval, 20k words: learning arm / baseline arm | 32.3 s / 7.4 s | 18.8 s / 5.2 s |
| Learn eval allocation (JFR sampled) | ~10.0 GB | ~4.5 GB |
| Keystroke, canned predictor: bytes per key | 9.8 KB | 3.5–4.0 KB |
| Keystroke, real engine: bytes per key | 20.0 KB | 14.0 KB |

All eval outputs were identical before and after (accuracy 94.09%, false corrections 4.19/1000;
every learn-mode output file).

### Device (ARM, ART, release, speed-compiled), Galaxy Tab S9, 2026-10-11

Medians of `:benchmark`. Each measured iteration is a batch, so divide by the batch size for a
per-item cost. "Before" is this branch with the three perf commits reverted.

| Benchmark (batch) | Before | After | Per item, after |
|---|---|---|---|
| `DecoderBenchmark.composing` (200 predictions) | 73.9 ms, 116k allocs | 57.5 ms, 42k allocs | 0.29 ms |
| `DecoderBenchmark.composingPersonalized` (200) | 96.4 ms, 395k allocs | 69.1 ms, 185k allocs | 0.35 ms |
| `KeystrokeBenchmark.cannedPredictor` (~480 keys) | 10.6 ms, 139k allocs | 3.3 ms, 48k allocs | ~7 µs |
| `LearningBenchmark.applyWordEvents` (200 events) | 33.2 ms, 30k allocs | 33.2 ms, 28k allocs | 0.17 ms |
| `EmojiBenchmark.readCatalog` (PR B) | — | 3.2 ms | once per process |
| `EmojiBenchmark.filterByGlyph` (PR B) | — | 2.2–3.6 ms | once per process |

The keystroke path gained more on the device (−68%) than on the desktop: ART doesn't remove the
allocations HotSpot's escape analysis did. Decode stays far inside the §14 budget (≤8 ms p95).

To see where device time goes, add a stack-sampling profile to a run:
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.profiling.mode=StackSampling`.
The `.trace` files land next to the JSON results; open them in Android Studio or Perfetto.

### Phase 7: llama scorer (desktop, relative)

`:core:llama` on x86-64 (16 logical cores), SmolLM2-135M Q8_0, 4 threads, CPU only, 2026-10-18.
One `score` call = a context of 20 words (~24 tokens) and 8 candidates (`LlamaIntegrationTest.reportLatency`,
40 calls for cold and same-context, 45 for warm; ARM numbers are pending the Tab S9 NDK build).

| Case | p50 | p95 |
|---|---|---|
| Cold: nothing in common with the previous context | 16.7 ms | 17.8 ms |
| Warm: the previous context plus one word (prefix reused, ~2 new tokens decoded) | 8.8 ms | 9.2 ms |
| Same context again (1 token re-decoded) | 8.6 ms | 9.3 ms |
| `generate`, 12 tokens max (a prompt of the same context, stops at punctuation) | 13.3 ms | 13.6 ms |

- Prefix reuse roughly halves the call. What is left is mostly the candidate batch (8 sequences, ~16
  tokens) and one log-softmax per scored position.
- The log-sum-exp over the 49k-entry vocabulary was the first hotspot: with libm `expf` the same
  scenarios took 28.9 ms cold and 12.9 ms same-context. A vectorisable polynomial `exp` (relative
  error 2.4e-7) took that to the table above. Compile with `-O3`; the NDK build must not drop it.
- Not measured yet: the 40 ms rerank deadline on the device, the effect of repacked weights on ARM.

#### Scorer latency inside the eval (desktop, relative), 2026-10-19

`tools/eval --rerank` on 3000 test sentences, default options (about 82k scorer calls per run: every key press, completion prefix and next-word decode), real classic candidate lists of up to 16 texts, 40 words of context, 4 llama threads, uncached. Full tables are in docs/PHASE7.md "Eval (reranker arm)".

| Model | mean | p50 | p95 | p99 | max | Over 40 ms |
|---|---:|---:|---:|---:|---:|---:|
| SmolLM2-135M Q8_0, run alone | 5.0 ms | 3.7 | 9.2 | 11.3 | 37.1 | 0.0% |
| SmolLM2-360M Q4_K_M, three runs at once | 22.7 ms | 17.9 | 47.1 | 60.5 | 220.5 | 9.6% |
| Qwen3-0.6B Q8_0, three runs at once | 30.1 ms | 26.4 | 64.2 | 81.8 | 215.7 | 20.5% |

- The eval calls are cheaper than the micro-benchmark above (p50 3.7 ms against 8.6 ms) because consecutive decodes of one word extend the same context, so the prefix cache hits almost every time.
- The decoder thread polls in 5 ms steps, so the effective latency of a refine is the scorer time rounded up to the next 5 ms plus the re-decode.
- The 40 ms deadline cuts nothing at SmolLM2-135M on an idle desktop. The larger models would lose 10 to 20% of their answers even here (their rows were measured with two other runs competing for the cores).

## Findings log

- **2026-10-11 (perf pass):**
  - `Dictionary.word` rebuilt words by walking the trie for every call; now cached.
  - `ShardedMap` sharded on the same low hash bits HashMap uses for buckets, so chains treeified;
    the shard now comes from a mixed high-bits hash.
  - Personalization re-keyed the same context and typed word for every candidate.
  - `SpatialModel` rebuilt a constant accent table and boxed chars on every snapshot change.
  - The keystroke path parsed the sentence context on every key before checking whether it could
    matter.
  - The A* heap now keeps bounds inline.
  - Remaining decoder CPU is the beam search itself (`TrieSearch.expand`, heap): inherent to the
    algorithm.
  - **2026-10-11, PR A (editing).** Nothing new on the hot paths. On the device the keystroke
    benchmark stays at ~3.4 ms per ~480 keys (+~1 allocation per key, within noise), and the
    decoder is unchanged. The selection strip is driven by `derivedStateOf`, so typing doesn't
    recompose it.
  - **2026-10-11, PR B (emoji).** A cold panel open costs ~6 ms on the device, in the
    background. `strongMatch` is two hash lookups per keystroke; its index is warmed in the
    background load. The keystroke benchmark has no catalog, so its 3.4 → 3.6 ms move with the
    same allocation count is device noise.
  - **2026-10-16, PR C (clipboard).** Nothing new on the hot paths: capture runs off the
    keystroke path (clipboard listener → background queue). Device: decode 56.6 ms / 200,
    personalized 68.7 ms, keystroke 3.66 ms / ~480 keys with the same 48.7k allocations, learning
    33.1 ms, emoji 3.4 + 2.2 ms. Desktop: decode p50/p95/p99 0.060/0.432/0.960 ms, learn eval
    17.6 s / 5.0 s, keystroke 3.5 KB / 13.0 KB per key; eval outputs unchanged (94.08%, 4.19/1000).
  - **2026-10-16, PR D (feedback round 1).** The decoder and keystroke paths are unchanged.
    - Device: decode 56.6 ms per 200, personalized 69.4 ms, keystroke 3.65 ms per ~480 keys (same
      48.7k allocations), learning 33.1 ms.
    - Desktop: decode p50/p95/p99 0.059/0.444/1.001 ms, learn eval 17.5 s / 4.9 s, keystroke
      3.5 KB / 13.1 KB per key.
    - Eval accuracy moved 94.08% → 94.11% (false corrections unchanged at 4.19/1000). This is
      expected: the simulated typist uses the real layout, and the bottom row moved.
    - Emoji grid (release build, Tab S9, 5 fresh tabs × 6 flings): janky frames 2.6% → 1.9% after
      cells draw with one shared Paint. The release build was already smooth; the reported lag was
      mostly debug-build Compose.
    - The tool strip reads its animation progress only in the layout and draw phases, so typing
      doesn't recompose it.
  - **2026-10-17, Phase 5 PR A (swipe decoder).** It is a new path; typing is unchanged.
    - Tab S9 `GestureBenchmark`:
      - `decodeOnLift`: 149 ms per 60 words (~2.5 ms per decode), 20k allocations. Before the
        template pooling it was 418 ms and 461k.
      - `decodeCold`: the first decode on a fresh index takes 3.2 ms median, 17.9 ms max, inside
        the 30 ms budget.
      - `warmUp`: 11.6 ms. It runs off the engine lock, so typed predictions never wait for it.
    - Desktop swipe eval: p50/p95/p99 0.26/1.98/3.46 ms.
    - A key-reach pre-filter avoids 44% of template builds on cold decodes.
  - **2026-10-18, full performance sweep (branch `perf-sweep`).** Two audits (main thread / UI;
    engines and orchestration), then fixes; every eval output is byte-identical to before.
    - Decoder: `hypot` → `sqrt` in the gesture path (it was ~66% of swipe samples); the trie
      copied from the mmap into heap arrays (~2.6 MB) with `firstChild` precomputed; a 4-ary
      search heap; `Dictionary.word()` once per candidate; the spatial-model cache kept across
      learning publishes when the key offsets are unchanged; Gaussian rows reused within a word;
      the dictionary prefetched on load.
    - Learning: per-context n-gram factors cached, an `ArrayDeque` journal, quickselect eviction;
      ranking-feature decodes give way to typing (an interrupted run reports busy).
    - Main thread:
      - Held backspace does 0 editor reads (was 2 reads + 1 delete per repeat).
      - A joystick tick is one `setSelection` (was a batch of 2–4 key events).
      - The controller is ready before the first frame, and the layer is composed once.
      - The read gate is cached per field, and haptic effects are cached.
    - Rendering:
      - Prediction slots skip unchanged slots; a single-pass fit-text replaces
        `BoxWithConstraints`.
      - The strip busy watcher wakes only when the flag flips.
      - The key canvas is split into a static layer and a pressed-key layer, so a press redraws
        only the held keys; the overlay caches its layouts and strokes.
    - Desktop before → after:
      - Decode p50/p95/p99/max: 0.055/0.426/0.924/1.365 → 0.050/0.401/0.854/1.051 ms.
      - Swipe p50/p95/p99/max: 0.26/2.00/3.43/6.33 → 0.16/1.12/1.91/4.66 ms.
      - Learn eval: 16.5/4.7 → 15.3/4.3 s.
      - Keystroke: 3.49 → 3.32 KB per key on the keyboard path.
    - Device numbers are pending; the tablet was unavailable. The heap trie should gain more on
      ART than on HotSpot.
    - Not done: incremental learning saves (they write only dirty rows; this is off the typing
      path, but each save is an allocation burst).
  - Not yet improved: `LearningBenchmark` (learning thread, off the typing path) and the
    personalized decoder's ~925 allocations per prediction (ranking features, candidate objects).
    Candidates for a later pass, measured on the device.
