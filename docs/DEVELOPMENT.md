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
  variable holding an `adb` command.

## Release flow (test builds)

Test builds are GitHub pre-releases with the signed APK (and optionally the voice and neural pack
APKs). Signing key and properties live outside the repo ([INSTALL.md](INSTALL.md), "Test releases").

```sh
tools/release/release.sh bump      # next -test.N in version.properties, versionCode + 1; commit and push it
tools/release/release.sh publish   # signed build of the clean, pushed HEAD -> pre-release v<versionName>
```

`publish` attaches the packs only if their files are fetched (`tools/data/fetch-voice.sh`,
`tools/data/fetch-neural.sh`; the neural pack also needs the NDK). Build packs alone with
`./gradlew :voicepack:assembleRelease :neuralpack:assembleRelease`; they are not part of CI.

## Data fetch scripts

All downloads are git-ignored (`data/raw`, `data/processed`) with pinned checksums:
`tools/data/fetch-sources.sh` (dictionary and eval corpora), `fetch-voice.sh` (speech models and
sherpa-onnx), `fetch-neural.sh` (llama.cpp source and candidate GGUFs), `fetch-swipe.sh` (recorded
swipes for the real-swipe eval), `tools/llama/build-host.sh`
(host build of llama.cpp for eval). Licences: [DATA_SOURCES.md](DATA_SOURCES.md).

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
