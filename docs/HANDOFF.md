# Handoff (2026-11-19)

Where the work stands, for the next session. Read CLAUDE.md and docs/ARCHITECTURE.md first, then
this. Move it to `docs/archive/` once it's stale.

## State

- **`main`** is at `2d4d66b`: the cleanup audit (#29, squash-merged). It includes Phase 9 (#26 to
  #28, closed as landed in #29).
- **Test build `0.5.0-test.22`** is published as a pre-release, built from `7071a72`. That was before
  the arm64-only change, so its APK still has x86_64 (18.2 MB).
- **Two PRs are open, both green and mergeable, and independent of each other:**

| PR | Branch | What |
|---|---|---|
| [#30](https://github.com/rishi-shyamala/keycorrect/pull/30) | `phase-9-closeout` | Closes Phase 9: S23 Ultra numbers in PERF.md, security device checks in SECURITY.md, the `KeyboardBenchmark` cold-start fix for One UI, an arm64-only test APK (`-Pkeycorrect.abis`, 15.9 MB), decisions N-1 and N-2, and the `test.22` version bump |
| [#31](https://github.com/rishi-shyamala/keycorrect/pull/31) | `phase-10-swipe` | The Phase 10a plan (docs/PHASE10.md), Phase 11 in the PLAN §17 roadmap, and **10a-1**: the real-swipe eval (`tools/data/fetch-swipe.sh`, `eval --mode swipe --real`) with the SHARK² baseline in EVAL.md |

- **Before merging, both PRs get the reviewer pass and the CI command.** Both have had them.
- **After #30 merges:** copy decisions N-3, N-4 and N-5 from docs/PHASE10.md into DECISIONS.md, under
  the "After Phase 9" section that #30 adds. Rebase `phase-10-swipe` first; the two PRs touch
  different files today.
- **The user merges with squash.** That leaves earlier PRs on the same commits open, and branches
  that don't count as merged by ancestry. GitHub deletes the head branch on merge. To check whether
  a branch landed, compare trees, e.g. `git diff --quiet <branch> origin/main`.

## User decisions this session

- **N-1:** close out Phase 9 on the device, then neural swipe; LoRA finetuning is a long-term stretch goal.
- **N-2:** about 16 MB of APK is fine when the size buys performance. The dictionary stays
  uncompressed, for memory-mapping. Published test APKs are arm64-only.
- **N-3:** FUTO Swipe's encoder first; our own encoder (10b) later, which drops the attribution.
- **N-4:** fuse the neural decoder with SHARK²; keep the fusion only if it beats SHARK² on real swipes.
- **N-5:** Phase 11, simplification: remove as many lines as possible with no feature or
  performance loss, and justify every line of app code. Baseline: about 50.5k lines of app Kotlin
  and 40.2k lines of tests. It gets its own plan when it starts.

## Next: Phase 10a-2 (runtime and pack)

See docs/PHASE10.md for the full parts list and exit criteria.

1. **Check licences first.** FUTO's C++ `swipe-library` (gitlab.futo.org/keyboard/swipe-library) is
   said to be GPL; confirm before reading it for the encoder's input normalisation. Choose
   ExecuTorch 1.5: an NDK build, or the prebuilt AAR's `.so` if its licence and size fit.
2. **`:swipepack`** (`dev.keycorrect.swipe`, like `:voicepack`): the runtime, `model_int8.pte`
   (1.0 MB, from [futo-org/futo-swipe](https://huggingface.co/futo-org/futo-swipe)), and the FUTO
   licence. Add a fetch script with pinned SHA-256, like the others.
3. **`:core:swipenet`:** JNI glue loaded through `SignedPackLocator`, plus a desktop build for
   `tools/eval`. Exit: emissions match FUTO's reference on fixed inputs, and the cost per call is
   measured on the S23 Ultra.
4. **Licence requirement:** the visible "Powered by FUTO Swipe" notice and the licence copy ship in
   every build that includes the pack (10a-4). The licence ends automatically on breach.

**Encoder interface**, from the model card:
- **Inputs:** `features [1,2,64]` (the gesture resampled to 64 x,y points), `layout_keys [1,64,2]`
  (key centres) and `layout_mask [1,64]`.
- **Outputs:** `log_emissions [1,32,65]` (64 keys plus a blank), `coefficients [1,32,64]` and an
  intention gate `lambda [1,32,1]`.

## Real-swipe eval (10a-1)

- **Data:** `tools/data/fetch-swipe.sh` downloads 574 MB into `data/raw/swipe/` (already present on
  this machine).
- **Run:**
  ```
  ./gradlew :tools:eval:run --args="--mode swipe --real data/raw/swipe/test.jsonl"
  ```
  Use `dev.jsonl` for any tuning; `test.jsonl` is for reported results only.
- **SHARK² baseline:**
  - test split: 81.20% top-1, 88.92% top-3 (dictionary words: 83.96% / 91.94%);
  - dev split: 81.66% / 89.06% (84.63% / 92.31%);
  - long words are weakest, at 72–78% top-1.
- **Mapping:** an affine fit of the dataset's QWERTY onto `EvalKeyboard`, with a residual of 0.001
  key widths. Context is oracle (the sentence before the word). Rows are scored unfiltered.

## Open items

- **IME memory on `test.22` isn't measured cleanly.** The phone's installed `test.18` showed about
  17 MB resident and 104 MB PSS including swap. A clean figure means restarting the user's keyboard,
  so ask first.
- **The IME's own first frame isn't isolated** from the Settings activity in the cold-start benchmark.
- **The Tab S9 device pass is still pending:** hardware-keyboard mode (P9-4) and the tablet numbers,
  whenever the tablet is back.
- **Device rules:**
  - The user's S23 Ultra (`R5CW22BB7YY`) is usually on USB. Use it only with an explicit go-ahead,
    asked right before each run.
  - Otherwise always set `ANDROID_SERIAL=emulator-5554`.

## Gotchas found this session

- **zsh:** a loop variable named `path` overwrites `$PATH` (every command then says "not found").
  Use another name.
- **JDK upgrades:** if pacman upgrades the JDK while a Gradle daemon runs, spawning `adb` fails
  ("Failed to exec spawn helper"). Run `./gradlew --stop` and retry.
- **One UI** binds a selected keyboard immediately, so a benchmark that selects the IME in setup
  starts its process. The cold-start fix handles this.
- **Emulator keyboard fallback:** force-stopping the *selected* IME on the emulator makes the system
  fall back to Gboard, and later typing silently goes to Gboard. Check `default_input_method` before
  trusting a typing test.
- **Instrumentation filters:** `-Pandroid.testInstrumentationRunnerArguments.class=A#m1,A#m2` ran
  only `m1`. Run methods one at a time.
- **The s23u emulator** sometimes shuts itself down mid-session. Check `adb devices` before relying
  on it.
- **Detekt** on new tools code wants no magic numbers, at most two returns per function, one jump
  per loop and functions under 60 lines. Fix the code; never add baseline entries.

## Local machine

- **Branches:** `main`, `phase-9-closeout`, `phase-10-swipe`, plus the unmerged local-only
  `fix-ci-format` and `backup/phase-6-voice-local`. Leave those two alone.
- **`user-feedback.md`** is untracked local notes. Never commit it.
