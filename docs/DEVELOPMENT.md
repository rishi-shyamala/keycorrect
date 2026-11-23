# Development guide

Day-to-day commands and workflow. Toolchain installation is in [SETUP.md](SETUP.md); the module
map is in [ARCHITECTURE.md](ARCHITECTURE.md); installing on a phone is in [INSTALL.md](INSTALL.md).

## Build, test, lint (as CI runs them)

CI (`.github/workflows/ci.yml`, on pushes to `main` and every PR) runs exactly:

```sh
./gradlew --console=plain spotlessCheck detektMain testDebugUnitTest test lintDebug assembleDebug \
  :benchmark:assembleReleaseAndroidTest
```

| Task | Use |
|---|---|
| `./gradlew spotlessApply` | Format (ktfmt kotlinlang style). Root task only; do not run it in parallel from two agents |
| `./gradlew detektMain` | Static analysis with type resolution (the plain `detekt` task skips type rules). Baselines per module (`detekt-baseline-*.xml`): never regenerate to hide new findings. Every `@Suppress` carries a reason comment |
| `./gradlew testDebugUnitTest test` | All unit tests: Android modules (`testDebugUnitTest`) and JVM modules (`test`) |
| `./gradlew :core:decoder:test` | One module. App tests need the merged release manifest; the task dependency is automatic |
| `./gradlew lintDebug assembleDebug` | Android lint and a debug build |
| `./gradlew :core:data:connectedDebugAndroidTest` | Instrumented tests (SQLCipher store, clipboard security); needs a device or the emulator. Test bodies must be `runBlocking<Unit> { }` |

Do not run several Gradle builds in parallel against one checkout. Strings go in `strings.xml`;
settings rows come from `SettingsRows.kt`.

Learning-curve tests (slow, minutes) are skipped unless asked for:

```sh
./gradlew :feature:keyboard-ui:testDebugUnitTest --tests "*LearningCurveTest*" -Pkeycorrect.learncurve=true --rerun
```

The swipe encoder's golden and latency tests (`:core:swipenet`, checked against the official
ExecuTorch wheel) run only with the host build of its library and the model; otherwise they are
skipped:

```sh
tools/data/fetch-swipe-model.sh                          # model, licence, ExecuTorch v1.5.1 source
export KC_ET_PYTHON=/path/to/venv/bin/python             # a Python with torch 2.14.0 and pyyaml (codegen)
export KC_SWIPENET_LIB=$(tools/swipenet/build.sh host | tail -1)
./gradlew :core:swipenet:test
```

`tools/swipenet/make-goldens.py` regenerates the golden file (see its header; the wheel lives in a
scratch venv outside the repo). `tools/swipenet/ops.txt` lists the kernels the build compiles;
regenerate it when the model changes.

The swipe eval's `--neural` arm (EVAL.md, "Neural fusion") uses the same host library and model:
`KC_SWIPENET_LIB` (or `--swipenet-lib`) and `--swipenet-model`.

The neural arm also needs the host build of llama.cpp (`tools/llama/build-host.sh`) and
`-Pkeycorrect.learncurve.lib` / `.model` (see `feature/keyboard-ui/build.gradle.kts`); the report lands in `feature/keyboard-ui/build/learning-curve/report.md`.

## Evaluation harness

`:tools:eval` simulates typing against the real dictionary and reports accuracy, false corrections
and latency. Modes, flags and results are in [EVAL.md](EVAL.md). Misspell mode needs the downloaded
data: `tools/data/fetch-sources.sh` (checksums in `tools/data/sources.sha256`), then
`./gradlew :tools:dictbuild:run --args="count"` and `"compile"` to rebuild the dictionary.

**Refactors and performance changes must leave eval output byte-identical.** Run before and after,
single-threaded and with the same seed, into different directories, and diff the results:

```sh
./gradlew -q :tools:eval:run --args="--sentences 2000 --sigma 0.25 --seed 7 --threads 1 --out /tmp/eval-before"
# ... change ...
./gradlew -q :tools:eval:run --args="--sentences 2000 --sigma 0.25 --seed 7 --threads 1 --out /tmp/eval-after"
diff /tmp/eval-before/failures.csv /tmp/eval-after/failures.csv    # must be empty
diff <(grep -v -i latency /tmp/eval-before/report.txt) <(grep -v -i latency /tmp/eval-after/report.txt)
```

Latency rows differ run to run; every accuracy and false-correction figure must match. Repeat for
`--mode learn --words 20000 --seed 1` when learning code changed. To probe one word, write a
temporary JUnit test in `tools/eval/src/test` that opens `app/src/main/assets/dictionaries/en_US.kcd`
and prints `ClassicEngine.predictNow`; delete it afterwards.

## Profiling and baseline profile

Before every PR run the performance check ([PERF.md](PERF.md)):

```sh
tools/profile/profile.sh                                # desktop: decoder, learning, keystroke (relative numbers only)
ANDROID_SERIAL=emulator-5554 tools/profile/profile.sh --device   # plus on-device microbenchmarks
```

Desktop numbers (x86, HotSpot) are for finding hotspots and allocation churn; absolute latency is
judged on ARM device numbers. Do not tune for the desktop CPU. Baseline profile (commit the output):

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
```

Macrobenchmark commands and numbers are in the baseline-profile section of [PERF.md](PERF.md).

## Testing on a device or emulator

- **Emulator only.** AVD `s23u` (1440x3088, x86_64). Always `export ANDROID_SERIAL=emulator-5554`
  (or `adb -s emulator-5554`); the host may have a personal phone attached and adb picks it if no
  serial is set. **Never run on a personal phone without the owner's explicit go-ahead**, ask right
  before each run, and never touch their KeyCorrect install or data. Never unlock a device or enter
  a PIN, never use apps with live data (use the debug-only Field Lab), and always restore the
  default keyboard afterwards.
- IME ids: debug `dev.keycorrect.debug/dev.keycorrect.ime.KeyCorrectImeService`; release
  `dev.keycorrect/.ime.KeyCorrectImeService`. Right after install `ime enable/set` may say
  "Unknown input method": wait 2-3 s and retry.
- Field Lab (debug builds): `adb shell am start -n dev.keycorrect.debug/dev.keycorrect.fieldlab.FieldLabActivity`
  has every field type and a `TYPE_NULL` terminal view that logs key events (`adb logcat -s FieldLab`).
- Judge smoothness only on release builds (`dumpsys gfxinfo <pkg>`); debug Compose is far slower.
- Reading text: `uiautomator dump` sees app fields but not the IME window (keys are drawn on a
  canvas). Check `topResumedActivity` before tapping; a dump can bring another app forward.
- Shell note: the tool shell quotes globs strictly; use `export ANDROID_SERIAL=...` instead of a
  variable holding an `adb` command. In zsh, never name a loop variable `path` (it overwrites
  `$PATH`).
- Force-stopping the *selected* IME on the emulator makes the system fall back to Gboard, and later
  typing silently goes there. Check `settings get secure default_input_method` before trusting a
  typing test.
- The `s23u` emulator sometimes shuts itself down mid-session; check `adb devices` first.
- One UI binds a selected keyboard immediately, so a benchmark that selects the IME in setup starts
  its process (`KeyboardBenchmark`'s cold start handles this).
- Instrumentation filters: `-Pandroid.testInstrumentationRunnerArguments.class=A#m1,A#m2` runs only
  `m1`. Run methods one at a time.

## Release flow (test builds)

Test builds are GitHub pre-releases with the signed APK (and optionally the voice, neural and swipe
pack APKs). Signing key and properties live outside the repo ([INSTALL.md](INSTALL.md), "Test releases").

```sh
tools/release/release.sh bump      # next -test.N in version.properties, versionCode + 1; commit and push it
tools/release/release.sh publish   # signed build of the clean, pushed HEAD -> pre-release v<versionName>
```

The bump goes to `main` through a PR, and `publish` runs on `main` after the merge, so local builds
of `main` carry the version of the latest test build.

`publish` attaches the packs only if their files are fetched (`tools/data/fetch-voice.sh`,
`tools/data/fetch-neural.sh`; the neural pack also needs the NDK). Build packs alone with
`./gradlew :voicepack:assembleRelease :neuralpack:assembleRelease`; they are not part of CI.
The swipe pack (Phase 10) is attached the same way (`publish_pack swipe`), when its files are
there: `tools/data/fetch-swipe-model.sh` and `tools/swipenet/build.sh android` (NDK, and
`KC_ET_PYTHON` as above) before `./gradlew :swipepack:assembleRelease`. Its release-notes entry says
what it does, that it is optional, its size (about 3.2 MB) and the FUTO attribution; the FUTO
Model Weights License text ships in the pack (`swipenet/LICENSE.md`), and Settings → About →
Open-source licences shows it from the installed pack. Without the swipe files `publish` says so
and releases without it. The "Neural swipe" row (Settings → Editing, on by default) gates it: off,
the pack isn't loaded and swipes decode with SHARK² alone.

When the pack is installed the keyboard fuses the encoder into every swipe decode (docs/PHASE10.md, 10a-3). Its cost on a device: install the debug pack
(`./gradlew :swipepack:installDebug`) and run `SwipeNetBenchmark` (part of the device
microbenchmarks; skipped without the pack). `emissionsOneThread` is one encoder call;
`fusedDecodeOnLift` and `sharkDecodeOnLift` are one whole swipe decode with and without the
encoder, one swipe per iteration, so the reported times are per swipe (each run averages its
iterations; target on the S23 Ultra: fused p95 ≤ 30 ms, judged on the runs' maximum):

```sh
./gradlew :benchmark:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.keycorrect.benchmark.SwipeNetBenchmark
# on the emulator also: -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

## Data fetch scripts

All downloads are git-ignored (`data/raw`, `data/processed`) with pinned checksums:
`tools/data/fetch-sources.sh` (dictionary and eval corpora), `fetch-voice.sh` (speech models and
sherpa-onnx), `fetch-neural.sh` (llama.cpp source and candidate GGUFs), `fetch-swipe.sh` (recorded
swipes for the real-swipe eval), `fetch-swipe-model.sh` (FUTO Swipe encoder and the ExecuTorch
source), `tools/llama/build-host.sh` (host build of llama.cpp for eval), `tools/swipenet/build.sh`
(the swipe encoder's library, host and Android). Licences: [DATA_SOURCES.md](DATA_SOURCES.md).

## Workflow and PR conventions

1. **Plan before code**; the user decides design questions (architecture, defaults that change
   behaviour, privacy trade-offs). Record decisions in [DECISIONS.md](DECISIONS.md).
2. Branch from `main`; one PR per part, **opened against `main`, never stacked** (a PR stacked on
   another lands in a stale base when the first merges).
3. Run the device or emulator check, then the **performance check** (above), then a **separate
   reviewer pass** (a different agent or person) on the whole diff before pushing. Fix verified
   findings and re-review the fix commits.
4. CI green: `spotlessCheck`, `detektMain`, tests, lint, builds. Commits end with a
   `Co-Authored-By:` trailer; PR bodies state the test and perf results.
5. Never commit `user-feedback.md` (local notes).
6. Agent worktrees start from `main`: reset to your branch and commit work-in-progress early.

## Gotchas

- AGP 9 has built-in Kotlin: do not apply `org.jetbrains.kotlin.android`; plugins such as
  kotlin-serialization are applied inside a convention plugin (`build-logic`).
- Compose in an IME: the service is its own `LifecycleOwner`/`SavedStateRegistryOwner`, initialised
  before `super.onCreate()`; bottom insets are `navigationBars` union `tappableElement`.
- SQLCipher: page reads by rowid (a single 120k-row cursor is very slow); never persist NaN.
- The dictionary asset is stored uncompressed (`noCompress += "kcd"`) so it can be memory-mapped.
- Detekt prints "compiler errors found during analysis" for modules with generated code; harmless.
  On new code it wants no magic numbers, at most two returns per function, one jump per loop and
  functions under 60 lines: fix the code, never add baseline entries.
- If the JDK is upgraded while a Gradle daemon runs, spawning `adb` fails ("Failed to exec spawn
  helper"): `./gradlew --stop` and retry.
- ExecuTorch's CMake insists that its source directory is named exactly `executorch` (hence
  `data/raw/swipenet/x/executorch-v1.5.1-src/executorch`), and its size-optimised build turns off
  C++ exceptions, which XNNPACK's weight cache needs: `core/swipenet/src/main/cpp/CMakeLists.txt`
  turns them back on for that one target, so the source stays unpatched.
