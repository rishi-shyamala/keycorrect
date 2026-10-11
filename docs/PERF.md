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

### Device (ARM, ART, release)

To be filled from the first `--device` run.

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
