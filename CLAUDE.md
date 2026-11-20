# KeyCorrect

Private, fully local Android keyboard (Kotlin, Compose, GPL-3.0-or-later): classic autocorrect and
swipe decoder, on-device learning, optional signed voice and neural packs, plugin engines.
Phases 0-9 are done (see the banner in docs/PLAN.md); builds are test releases (`0.5.0-test.N`).

## Docs

- `docs/ARCHITECTURE.md`: module map, processes, data flows, invariants (read first).
- `docs/DEVELOPMENT.md`: commands, eval, profiling, emulator, release, PR conventions.
- `docs/DECISIONS.md`: dated decision log. Add a row for every new user decision.
- Reference: EVAL, PERF, SECURITY, TRANSFER, PLUGINS, LAYOUT_FORMAT, DATA_SOURCES, INSTALL.
- `docs/archive/` is history (old phase plans); do not treat it as current.

## Commands

- Format: `./gradlew spotlessApply`
- CI equivalent: `./gradlew spotlessCheck detektMain testDebugUnitTest test lintDebug assembleDebug :benchmark:assembleReleaseAndroidTest`
- One module: `./gradlew :core:decoder:test` (JVM) or `:feature:keyboard-ui:testDebugUnitTest` (Android)
- Eval: `./gradlew -q :tools:eval:run --args="--sentences 2000 --sigma 0.25 --seed 7 --threads 1"` (docs/EVAL.md)
- Perf: `tools/profile/profile.sh` before every PR (docs/PERF.md)
- Test build: `tools/release/release.sh bump` then `publish`
- Do not run parallel Gradle builds in one checkout.

## Invariants (never weaken)

- No `android.permission.INTERNET`, ever; `ManifestSecurityTest` enforces the manifest rules.
  Only the IME service and the launcher activity are exported; clipboard images only via the
  non-exported `ClipImageProvider`.
- Privacy gates: password fields never compose, decode, read or learn; no learning in
  email/URL/address fields, incognito, no-learning flag or learning-off. No user text in logs.
- The main thread never blocks on the engine beyond the bounded waits in `DecoderLoop` (30 ms
  separator, 50 ms swipe).
- Eval output stays byte-identical for performance and refactor changes (`--threads 1`).
- Packs (voice, neural) must be signed with the app's key; the `:engine` process never opens
  DataStore or the app container.

## Workflow rules

- Plan before code. The user has the final say on design: present options with a recommendation.
- A separate reviewer agent reviews every PR before it is pushed.
- Performance check before every PR (desktop numbers are relative; ARM device numbers are real).
- PRs go against `main` and are never stacked. One PR per part.
- Device testing: emulator only (AVD `s23u`), always `ANDROID_SERIAL=emulator-5554`. Never touch a
  personal phone without the owner's go-ahead.
- Never commit `user-feedback.md`. Test builds go through `tools/release/release.sh`.
- Agent worktrees start from `main`: reset to your branch and commit WIP early.
- Docs and comments describe what is; dated plans go to `docs/archive/`.

## Style

ktfmt (kotlinlang) via Spotless; detekt baselines are never regenerated to hide new findings; every
`@Suppress` carries a reason comment; user-visible strings in `strings.xml`; settings rows from
`SettingsRows.kt`. Commits end with a `Co-Authored-By:` trailer.
