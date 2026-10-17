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
  - Not yet improved: `LearningBenchmark` (learning thread, off the typing path) and the
    personalized decoder's ~925 allocations per prediction (ranking features, candidate objects).
    Candidates for a later pass, measured on the device.
