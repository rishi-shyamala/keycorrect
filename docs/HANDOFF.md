# KeyCorrect — Handoff (Phase 4 in progress, 2026-10-16)

Read this first when resuming. Other docs:
- `docs/PLAN.md`: the design and roadmap. §16 has the decisions and §17 the phases.
- `docs/EVAL.md`: the evaluation harness and its results.
- `docs/PERF.md`: the pre-PR performance check and reference numbers.
- `docs/PHASE4.md`: the Phase 4 plan and its decisions.
- `docs/DATA_SOURCES.md` and `LICENSE-DATA.md`: data licences.
- `docs/LAYOUT_FORMAT.md`: the layout JSON format.
- `docs/SETUP.md`: the toolchain.
- `core/dictionary/FORMAT.md`: the binary dictionary format.

---

## 0. Resume here (2026-10-16)

**PR C, the clipboard history (plus the planning for feedback round 1), is open as
[rishi-shyamala/keycorrect#6](https://github.com/rishi-shyamala/keycorrect/pull/6) from branch
`phase-4-clipboard`, waiting for CI and the user's merge.** Its working
tree is clean except the user's own untracked `user-feedback.md`: never commit that file.

**PR C: done**
- **Built:** core:clipboard (retention policy, URL cleaner), encrypted clip store (schema v5), Keystore-encrypted image blobs, the clipboard panel (long-press on the emoji key), the strip chip, capture and paste wiring (`ClipImageProvider`, not exported), Settings → Clipboard, and the security instrumented test.
- **Simplified per the user (2026-10-12):** everything copied is captured; there is NO detection of passwords, cards, tokens or OTPs and no masking. The user deletes items. The privacy goal is only that **other apps can't read the stored data**.
- **Reviewed:** a full review, then fixes (ba9aa1e), then a follow-up review, then fixes (e1e839d).
- **Instrumented tests on the Tab S9 (2026-10-16, final code):** core:data 48/48 and app `ClipboardSecurityTest` 5/5.
- **Device test on the Tab S9 (2026-10-16): all pass.** Copy → chip → paste; long-press emoji = clipboard, tap = emoji; pin/delete/clear (pinned stays); image paste into the field lab's image field (`received image/png (235 bytes)`) and the text-field fallback to the system clipboard; the chip in the password field; history off → nothing captured (the panel is then unavailable and the long-press acts as a tap, by design); data at rest (no test text in the DB/WAL; the blob starts `01 0c…`, not the PNG magic); no crashes. Samsung keyboard restored.
  - Note: in a password field Bitwarden's inline suggestion takes the strip over the chip (autofill outranks the chip by design).
- **Performance check:** no hot-path changes (docs/PERF.md findings log, 2026-10-16).
- **CI set run locally:** `spotlessCheck detektMain testDebugUnitTest test lintDebug assembleDebug` all pass (~1400 unit tests).
- **Release manifest checked:** only the launcher, the IME (BIND_INPUT_METHOD) and the profileinstaller receiver (DUMP) are exported; providers aren't.
- **Bottom row reordered (user request):** ?123, emoji, comma (@ or / in email/URL), space, period, enter.
- **History rewritten before the first push (user decision, 2026-10-16):** GitHub push protection blocked a since-deleted test fixture (`ClipSensitivityTest.kt`, commit c0a57cc) holding documentation-example tokens (Stripe, GitHub, Slack, AWS, Google, OpenAI). Every token-shaped literal there is now split in half (`"ghp_16C7…" + "…"`); the final tree is unchanged. **Never put realistic token strings in test fixtures:** split them.

**PR D status (2026-10-16):** open as
[rishi-shyamala/keycorrect#7](https://github.com/rishi-shyamala/keycorrect/pull/7) (branch
`phase-4-feedback`). It holds every feedback-round-1 item plus the user's later changes:
- the animated tool strip (edit and emoji at the left, clipboard and settings at the right);
- no hold on space;
- bottom-row keys at 75% of a letter key; ?123 and Enter at 115% (own slider).

Device-tested on the Tab S9, reviewed, review fixes in, and the perf check done. Not run: the frame
check of typing on a release build (the tablet was locked). Next, after the user merges: Phase 5,
swipe typing. The build/install guide is docs/INSTALL.md.

**After PR C: PR D, feedback round 1**
- The full plan and the user's decisions are in **docs/PHASE4.md, "Feedback round 1"**. Stack the branch on `phase-4-clipboard` (e.g. `phase-4-feedback`).
- The items:
  1. Equal key sizes on every layer.
  2. Emoji scroll lag: measure on a RELEASE build first.
  3. and 4. A Samsung-like symbols layout, shift+digit order, without the rare symbols. **Photograph the Samsung keyboard's letter, symbol and number pages on the Tab S9 first.**
  5. A Samsung-style shared shortcut row: edit panel, emoji, clipboard and settings icons when not typing; suggestions while typing.
  6. Two sliders: edge padding and key gap.
  7. Spacebar: **left half selects, right half moves**, with a faint split line; hold+release still opens the panel.
  8. Bigger vector key icons.
  9. **Bug:** Word/Line/Sentence do nothing when `getSurroundingText` reports offset −1. The fix is designed in PHASE4.md: fall back to the tracked cursor when `OwnEditTracker.pendingCount == 0`; the backspace swipe retries instead of setting `swipeTried`; at a word end, Word selects the previous word.
  10. Swipe typing: Phase 5.
  11. Separate the emoji tabs from the grid: own surface, divider, outline icons.
- The user also asked to keep the overall layout as close as possible to the Samsung keyboard.

**After PR D: Phase 5, swipe typing** (PLAN §7, D7: own SHARK² decoder).

**User preferences learned this phase (follow them)**
- **Keep things simple.** The user rejected heuristic sensitivity detection as over-complicated. Prefer simple behaviour with user control over clever automation.
- **Match the Samsung keyboard's layout and feel** where reasonable.
- The user decides UX questions; ask with AskUserQuestion and recommend an option.
- The weekly model limits were hit during this phase. Keep subagent briefs tight, and resume workers with SendMessage when they stop.

## 1. Status

| Phase | State |
|---|---|
| 0 Foundations | merged to `main`, pushed |
| 1 Core keyboard | merged to `main`, pushed |
| 2 Prediction core | merged to `main`, pushed |
| 3 Learning | merged to `main` (PR [rishi-shyamala/keycorrect#1](https://github.com/rishi-shyamala/keycorrect/pull/1), 2026-10-11) |
| Pre-Phase-4 cleanup, perf pass | merged (#2, #3) |
| 4 Editing, clipboard, emoji | **in progress.** PR A (editing + terminal row, #4) and PR B (emoji, #5) are merged. PR C (clipboard) is open as #6, waiting for merge (see §0). PR D (feedback round 1) is planned. |
| 5–10 | see PLAN §17 |

- **Remote:** `origin` = `https://github.com/rishi-shyamala/keycorrect.git`. Merged phase branches are deleted locally and on `origin`; GitHub deletes a PR's branch when it merges.
- **CI:** `.github/workflows/ci.yml` (GitHub Actions) runs `spotlessCheck detektMain testDebugUnitTest test lintDebug assembleDebug :benchmark:assembleReleaseAndroidTest` on pushes to `main` and on PRs. The eval harness's misspell data isn't fetched in CI.
- **Tests:** ~1400 unit tests pass on `phase-4-clipboard` (Android + JVM modules, incl. tools). Instrumented: core:data 48, app 5 (`ClipboardSecurityTest`). In androidTest, test bodies must be `runBlocking<Unit> { }`: a non-void test makes JUnit reject the whole class. Spotless, Detekt (against baselines), lint, debug and release builds are clean.
- **Git identity (repo-local):** Rishi Shyamala <rishis007@gmail.com>. Commits end with the `Co-Authored-By: Claude …` line, and PR bodies end with the "Generated with Claude Code" line.

## 2. Workflow rules the user set (follow these)

1. **The user decides design questions.** Present options with a recommendation, using AskUserQuestion. Don't silently pick architecture, licensing, defaults that change behaviour, or data/privacy trade-offs.
2. **Subagents (from `~/.claude/CLAUDE.md`).**
   - Opus plans, reviews and talks to the user.
   - `sonnet-worker` takes scoped implementation, debugging, reviews and device tests.
   - `haiku-helper` takes mechanical, tool-heavy tasks. It struggled on long device tests: 15-turn limit, misread geometry.
   - Sonnet workers often hit the 40-turn limit. When they do, resume with SendMessage and a narrowed brief ("wrap up: make tests green, report"). Give each agent exclusive file ownership when running them in parallel, and commit each agent's paths separately.
3. **Review before push.**
   - Run a separate reviewer agent on every branch or PR before pushing to the remote.
   - Fix verified findings, then run a short follow-up review of the fix commits.
   - The flow is: phase branch → implementation → device test → **performance check** → review → fixes → follow-up review → push branch → `gh pr create` → bind with `mcp__ccd_pr__get_status`/`bind_pr`.
4. **Performance check before every PR** (user rule, 2026-10-11; `docs/PERF.md`).
   - Run `tools/profile/profile.sh` (add `--device` with the Tab S9 attached), compare with the reference numbers in `docs/PERF.md`, and fix obvious overhead on hot paths.
   - The devices are ARM/ART and this machine is x86/HotSpot: desktop numbers are relative only, and absolute latency is judged on the device. Don't tune for this CPU.
5. **Device testing rules.**
   - The user stopped using the S23; test on the **Tab S9**.
   - **Never use Gmail or any app or site with live data.** Use only our app, plus Chrome/Firefox on the **local test page** (below).
   - Never unlock the device or enter a PIN. If `isKeyguardShowing=true`, stop and ask the user to unlock.
   - Never tap settings switches unless the test needs it, and verify the result. Earlier test helpers flipped auto-cap and autofill by accident.
   - **Always restore the Samsung keyboard** afterwards: `ime set com.samsung.android.honeyboard/.service.HoneyBoardService`.
   - If another app comes to the foreground, back out; never tap inside it.
   - The user wants device tests run **before** the code review when both are pending.
   - Use the **field lab** (debug builds; §4) for terminal, image-paste and field-type tests instead of third-party apps.

## 3. Environment and devices

- **Host:** CachyOS (Arch), shell fish. The Bash tool's shell is zsh-like: globs like `--include=*.kt` fail, so quote them. A variable holding a command (`A="adb -s X"; $A …`) doesn't word-split. Use `export ANDROID_SERIAL=…` instead.
- **JDK:** 21 (`jdk21-openjdk`, archlinux-java default). `android-udev` is installed.
- **SDK:** installed with Google's **Android CLI** (`~/.local/bin/android`; always pass `--no-metrics`) into `~/Android/Sdk`.
  - Installed packages: platforms/android-37.0, build-tools 37.0.0 (AGP also pulled in 36.0.0), ndk 29.0.14206865, cmake 3.31.6, platform-tools 37.0.1 (installed by AGP).
  - The system `adb` is `/usr/bin/adb` (android-tools 37). Both versions coexist.
  - `local.properties` (git-ignored) holds `sdk.dir`.
- **Toolchain:**
  - Gradle 9.8.1 (checksum-pinned wrapper) and AGP 9.4.1 with **built-in Kotlin**; KGP 2.4.21 is forced via the build-logic classpath.
  - Compose BOM 2026.09.00 with material3 1.4 (Expressive APIs not used yet).
  - compileSdk/targetSdk 37, minSdk 31, NDK 29.
  - `abiFilters` are arm64-v8a and x86_64 (32-bit is intentionally unsupported).
- **Devices:**

| Device | Serial | Notes |
|---|---|---|
| Galaxy Tab S9 (SM-X710) | `R52X204HLZV` | Android 16 / One UI 8.5, SD 8 Gen 2, 1600×2560, density 300 (override). Rotates; tests were run in both portrait and landscape. The `adb` authorisation once dropped ("unauthorized"); the user had to re-accept the RSA prompt. |
| Galaxy S23 Ultra (SM-S918U) | `R5CW22BB7YY` | Android 16 / One UI 8.5, 1440×3088, density 560. **The user needs the phone; don't use it unless asked.** Phase 3 hasn't run on it. |

- **IME ids:**
  - debug: `dev.keycorrect.debug/dev.keycorrect.ime.KeyCorrectImeService` (debug builds use the `.debug` applicationIdSuffix)
  - release: `dev.keycorrect/.ime.KeyCorrectImeService`
  - Right after install, `ime enable/set` often says "Unknown input method". Wait 2–3 s and retry.
- **Release builds for testing:** `./gradlew :app:assembleRelease`, then sign with the debug key using `~/Android/Sdk/build-tools/37.0.0/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out <out.apk> app/build/outputs/apk/release/app-release-unsigned.apk`. Uninstall the release test package afterwards.
- **Local web test page:**
  - Files: `scratchpad/webtest/index.html`, plus a logging server at `scratchpad/webtest_server.py` (it serves `127.0.0.1:8765` and appends events to `webtest/events.jsonl`, masking passwords).
  - The page has text, search, email, url, password, number, tel, textarea and contenteditable fields, and logs beforeinput/composition events.
  - Start it with `python3 -I webtest_server.py <dir>` (as a background task), then `adb reverse tcp:8765 tcp:8765`, then open `http://localhost:8765/` in Chrome or Firefox.
  - The scratchpad is session-specific: **recreate it next session** (the files may be gone). The user stopped the server at the end of this session.
  - The page's text `<input>` has `autocomplete=off`, so Chrome sends NO_SUGGESTIONS and the keyboard correctly stays inert there. Use the textarea for autocorrect tests.

## 4. Device-test playbook (Tab S9, portrait)

- **Keyboard geometry (default key height 54 dp, no number row):**
  - The IME window top is at ≈1959. Confirm it with `dumpsys window InputMethod` (frame=…).
  - Content starts at top + 7.5 px; the 40 dp strip is 75 px tall, so the strip centre is at y≈2004. Its three slots are at x≈267 / 800 / 1333.
  - Key rows are 101.25 px tall, with centres at y≈2092 / 2193 / 2294 / 2396.
  - The key unit is u = 158.5 px.
    - Row 1: x = 7.5+(i+0.5)u
    - Row 2: x = 7.5+(i+1)u
    - Row 3: z..m at x = 7.5+(2+i)u, shift at 126, backspace at 1473
    - Row 4: space at 800
  - In landscape (2560×1600) the rows were at y 1133/1233/1335/1436, with q=134 … p=2423. Recompute from a screenshot when in doubt.
- **Reading text:** `uiautomator dump` shows the EditText `text` attribute of our app and Chrome fields. It does NOT see the IME window, and the keys are drawn on a canvas.
- **Long-press:** `input motionevent DOWN x y`, sleep, then `MOVE`/`UP`. A screenshot taken while holding takes longer than 300 ms, so it triggers the long-press popup.
- **Perf:**
  - `dumpsys gfxinfo <pkg> reset`, type, then read `dumpsys gfxinfo <pkg>`.
  - Measure the **release** build. Debug Compose is far slower: a debug build showed 55% janky frames, while release showed 3.4% with frame p50 5 ms and p99 10 ms at about 22 keys/s.
  - Samsung hides `Log.d`, so the `DecoderLatency` debug log isn't visible.
  - A fast typing script, `input tap` chains in one `adb shell "…;…"`, manages about 22 taps/s.
- **Haptics:** `dumpsys vibrator_manager`. Word events show as `effect` entries with usage TOUCH:
  - autocorrect: a double 12 ms pulse
  - unknown word: a single 40 ms pulse
- **Field lab (debug builds only):** `adb shell am start -n dev.keycorrect.debug/dev.keycorrect.fieldlab.FieldLabActivity`. It has every field type, plus a `TYPE_NULL` terminal view that logs the key events it receives (`adb logcat -s FieldLab`). Use it instead of third-party apps.
- **Instrumented tests:** `ANDROID_SERIAL=R52X204HLZV ./gradlew :core:data:connectedDebugAndroidTest` works even while the device is locked.
- **Test data on the tablet:** our app has "thier" learned (it's in Learned words and Never corrected) from test 2. The user was told to remove it.

## 5. Architecture map

```
app/                 IME service, settings app (Compose), AppContainer, LearningHost, PredictionHost
core/designsystem    Material 3 dynamic theme
core/ime             ComposeInputMethodService (lifecycle/saved-state owners for Compose in an IME)
core/input           EditorSession/TextEditor, FieldProfile (privacy gates), EnterAction, Graphemes, AutoCaps
core/layout          JSON layout format + built-ins + KeyboardComposer + KeyboardGeometry (JVM)
core/settings        KeyboardSettings + DataStore (DEVICE-PROTECTED storage; non-sensitive only)
core/data            SQLCipher DB (Keystore-wrapped key), user dictionary, shortcuts, SqlLearningStore (schema v3)
core/engine-api      TextEngine contract (Generate/Score/Predict tiers), PredictRequest/Result (JVM)
core/dictionary      KCD1 mmap binary dictionary reader + writer (JVM)
core/decoder         ClassicEngine: spatial model, A* trie search, back-off LM, autocorrect policy, contractions,
                     cognitive-misspelling rules, personalization terms, tie rule (JVM)
core/emoji           Emoji catalog (Unicode 17.0 + CLDR 48.2 asset), search, strongMatch; FORMAT.md (JVM)
core/learning        Events/updates contract, SentenceContext (context buckets, shared with the decoder),
                     OutcomeClassifier (PLAN §6.3), PersonalModel + persistence/journal (JVM)
feature/keyboard-ui  Keyboard UI (single-canvas renderer, overlay, semantics), controller, PredictiveInput
                     (composing/autocorrect/undo/learning events) with TextContextCache, StripPrompts and
                     PhraseLearning, TouchStateMachine (+ CursorDragTracker), OwnEditTracker, strip,
                     KeyFeedback; Phase 4: EditingController (spacebar cursor, backspace swipe, panel and
                     selection-strip actions), EditingPanel, SelectionStrip, TextUnits, TerminalModifiers
tools/dictbuild      Offline: fetch sources → count n-grams → compile en_US.kcd (committed asset, 8.9 MB)
tools/emojibuild     Offline: emoji-test.txt + CLDR annotations -> emoji_en.kce (committed asset)
tools/eval           Offline harness: spatial typist, learn mode (simulated user), misspell mode
build-logic          Convention plugins (android app/library/compose, jvm library, kotlin serialization, detekt)
```

- **Data flow for a keystroke:**
  1. The TouchStateMachine maps the touch to a key.
  2. `KeyboardController` and `PredictiveInput` handle composing.
  3. `DecoderLoop` runs on its own thread and calls `ClassicEngine.predictNow` with `personalization = { learningHost.personalization() }`, which is `model.snapshot()`.
  4. The suggestion strip updates.
  5. On a separator, autocorrect is applied. If no current result is ready, it waits at most 30 ms, then commits as typed.
  6. Learning events go through one privacy gate to `LearningSink`, then the `LearningHost` thread, then `OutcomeClassifier`, then `PersonalModel`, then `SqlLearningStore` (atomic `commit`/`replaceAll`).
- **Privacy invariants (tested; keep them):**
  - Sensitive (password) fields never compose, decode, read text or learn. `EditorSession` refuses reads as well.
  - No learning in Email, Url or PostalAddress fields. PersonName fields learn, but none of the four records sentence words.
  - No learning when the field has the no-learning flag, in incognito, or with learning off. The keyboard gate and the host gate both check.
  - Logs contain only exception class names, never user text.
  - No INTERNET permission, ever (D3). Backup and device-transfer are disabled. Settings live in device-protected storage and must stay non-sensitive.
  - The direct-boot-aware IME is **intentionally deferred** to hardening. If it's enabled, the IME can become the lock-screen keyboard, and a bug there could lock the user out of the device.

## 6. Decisions log (user decisions; all in PLAN)

- **D1:** built from scratch, in Kotlin with Compose. **D2:** GPL-3.0-or-later. **D3:** fully local, with no INTERNET permission. Network engines may only ever come as separate plugin APKs.
- **D4:** English-first. **D5:** minSdk 31. **D6:** a small STT default (≤ ~50 MB weights, loaded only while dictating).
- **D7:** own shape-matching (SHARK²-style) swipe decoder first, with a neural option later.
- **D8:** single-canvas key renderer. **D9:** SQLCipher. **D10:** recent-sentence buffer ON by default, 7-day TTL.
- **Dictionary data:** AOSP wordlist (Apache-2.0), Simple English Wikipedia (CC BY-SA 4.0) and Tatoeba (CC BY 2.0 FR).
  - Tatoeba's placeholder names "Tom" and "Mary" are filtered out.
  - Only the compiled dictionary is committed; it is licensed CC BY-SA 4.0 (`LICENSE-DATA.md`).
  - The Wikipedia misspellings list is used for evaluation only and is never shipped.
- **Learning:**
  - An undo counts as a *keep* only if the restored word is finished unchanged.
  - Implicit add after **N** keeps and suppression after **M ≥ N** keeps, both default 2 and set in Advanced.
  - The tie rule for non-words is **ON**. The user accepted the trade-off: wrong corrections go from 2.5 to about 4.6 per 1000 in eval, and each one self-heals.
  - Name fields learn; email, URL and address fields don't.
  - Keyboard-fit recommendations (PLAN §6.5): the stats are collected now, and the report UI comes in Phase 8.
- **Phase 4** (all in docs/PHASE4.md):
  - **Shortcuts and spacebar:** no toolbar at first, but feedback round 1 adds a Samsung-style shared shortcut row. The spacebar becomes left half selects, right half moves.
  - **Emoji:** an emoji key left of the comma, whose long-press opens the clipboard. Emoji strip suggestions replace the word and are not learned. The default skin tone is an ordinary setting.
  - **Clipboard:** simple; it captures everything, with encryption against other apps.
  - **Data:** Unicode 17.0 + CLDR 48.2 emoji data, downloaded with the user's approval.
- **Haptics:** distinct vibrations for an applied autocorrect and for an unknown word that wasn't corrected (Feedback screen).
- **Tuning (from data):** spatial sigma is 0.6, plus the cognitive-misspelling rules. See docs/EVAL.md "Applied defaults" for the current numbers.

## 7. Evaluation (tools/eval)

- **Spatial typing:**
  - Command: `./gradlew -q :tools:eval:run --args="--sentences 2000 --sigma 0.25 --seed 7"`. Use the test split; sweeps tune on the dev split.
  - Current results: about 94.0% accuracy. False corrections are about 4.5/1000 at σ0.25 with the tie rule on.
- **Learning:**
  - Command: `--mode learn --words 20000 --seed 1`, with `--model k=v` and `--classifier k=v` for overrides.
  - Results: false corrections about 0.5/1000 with learning versus 2.7–5.2 without. 0 wrong corrections after the keep threshold.
- **Misspellings:**
  - Command: `--mode misspell`, after the data has been fetched with `tools/data/fetch-sources.sh`.
  - Results: 76.7% accuracy, top-3 91%.
- **Data:** `data/raw` and `data/processed` are git-ignored. Refetch with `tools/data/fetch-sources.sh` (checksums are in `tools/data/sources.sha256`), then run `./gradlew :tools:dictbuild:run --args="count"` and `"compile"`.
- **Probing a single word with the real dictionary:** write a temporary JUnit test in `tools/eval/src/test` that opens `../../app/src/main/assets/dictionaries/en_US.kcd`, builds `ClassicEngine(dict, config)` and an `EvalKeyboard()`, and prints `predictNow`. Delete it afterwards.

## 8. Known issues, limitations and tech debt

- "tset" is now autocorrected because of the tie rule, but real-word misspellings ("form"→"from") are almost never corrected, by design: the literal is a valid word.
- 2-edit misspellings only reach about 9% accuracy, and 3+ edits 0%.
- Promoting personal words from typing alone is slow on purpose (≥3 sessions, plus the typo guard). Explicit adds and undo-keeps are the fast paths. Junk typos 2+ edits away from a frequent word can still be promoted, rarely ("rheh").
- After an undo with a punctuation separator, backspacing the punctuation and finishing the word again records it twice in the sentence buffer.
- Low-severity review leftovers:
  - A device clock jumping forward by more than 7 days wipes the sentence buffer.
  - A tiny forget-vs-drain race can delete a pre-existing dictionary word.
  - A suppressed runner-up makes real-word near-ties abstain (on the safe side).
- `forgetSince` can only subtract n-gram, correction and touch evidence from the last 24 h, because that is the journal's retention.
- The delete-and-retype device failure was fixed by a session-independent check. The exact device repro was never reproduced in unit tests, but it passed on the device afterwards.
- Settings icons are text glyphs, because material-icons isn't a dependency.
- Phase 4:
  - The password-like/cards/tokens clipboard filters were removed on purpose (user).
  - Copied image clips aren't deduped.
  - Search results stay open after a pick.
  - The strip emoji has no undo.
  - Detekt prints "compiler errors found during analysis" for core:layout and app (generated code); that's harmless.
- On a landscape tablet the keys are very wide. Split/floating layouts are planned for Phase 8.
- The Tab S9 reports `mIsHapticSupported=false` (coin motor) but still plays the effects. Strength isn't adjustable there.
- In debug builds, the Compose keyboard is janky. Always judge performance on release builds.
- `LearningSink.onSentenceWord(..., field)` has a default overload. The keyboard gates PersonName itself, and the host also discards those sessions.

## 9. Quirks learned (save time)

- **AGP 9 built-in Kotlin:**
  - Don't apply `org.jetbrains.kotlin.android`.
  - Upgrade KGP by putting `kotlin-gradle-plugin` on the build-logic `implementation` classpath.
  - Plugins such as kotlin-serialization must be applied *inside a convention plugin* (`keycorrect.kotlin.serialization`). Applying them by id in a module fails.
- **Compose in an IME:**
  - The service must be its own LifecycleOwner and SavedStateRegistryOwner, initialised before `super.onCreate()`.
  - Lifecycle: RESUMED while shown, STOP while hidden.
  - Bottom insets must be `WindowInsets.navigationBars.union(WindowInsets.tappableElement)`. Samsung's IME nav-bar buttons need the 48 dp `tappableElement`; `navigationBars` alone gives only about 15 dp.
- **Inline autofill:** it worked with Bitwarden once the user had enabled Bitwarden's own inline-autofill setting, and once KeyCorrect's "Password manager suggestions" setting was on. A test helper had switched it off.
- **SQLCipher:**
  - The AAR bundles LibTomCrypt (noted in the licences screen).
  - Reading 120k rows with one cursor is slow (6.8 s), so `SqlLearningStore` pages by rowid in chunks of 4096 (0.5 s).
  - NaN doubles are stored as NULL, so never persist NaN.
- **Dictionary asset:** stored uncompressed (`androidResources.noCompress += "kcd"`) so it can be memory-mapped from the APK through `AssetManager.openFd`.
- **Device tests:** `uiautomator dump` sometimes brings the Google app to the foreground. Check `topResumedActivity` before tapping.
- **Spotless:** it's a root-level task only. Parallel agents running it reformat each other's files; that's harmless.
- **Detekt:**
  - Run `./gradlew detektMain` (type-resolved). The plain `detekt` task skips rules that need types, e.g. UnusedPrivateFunction.
  - The plugin is on build-logic's classpath (it needs KGP), applied by the module conventions; config in `config/detekt/detekt.yml`, baselines per module (`detekt-baseline-main.xml` / `-debug.xml`).
  - Its analysis can't see compiler-plugin or generated code (kotlinx.serialization `serializer()`, `BuildConfig`), so it prints "compiler errors found during analysis" for core:layout and app. Harmless; the resulting false positives (LayoutParser UnreachableCode) are baselined.
  - Its Gradle plugin triggers a `Configuration.setVisible` deprecation warning (removed in Gradle 11).
- **Subagent turn limits:** state "be economical with turns, batch edits" in briefs, and give a priority order so a partial result is still useful.

## 10. Next steps

See **§0**: PR C (finish and open), then PR D (feedback round 1), then Phase 5 (swipe). Later phases
follow PLAN §17:
- **6:** voice (STT ≤ 50 MB);
- **7:** neural and plugin engines;
- **8:** UI/UX customisation and the keyboard-fit report;
- **9:** hardening, including direct boot and `accessibilityDataSensitive`.

Deferred cleanup items are in `docs/CODEBASE_REVIEW.md` §0.

## 11. Memory

Claude's persistent memory for this project is in `~/.claude/projects/-home-rishi-Projects-keycorrect/memory/`:
- `keycorrect-project-plan.md` (status and decisions)
- `user-final-say-on-design.md`
- `review-before-push.md`
- `profile-before-pr.md`

Update `keycorrect-project-plan.md` when the status changes.
