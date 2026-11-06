# KeyCorrect: Phase 9 plan (hardening and polish)

Goal (PLAN §17 row 9, §15): audit security and privacy, support hardware keyboards on the tablet,
make startup fast, and measure performance on real hardware.

## Decisions (user, 2026-11-06)

| # | Question | Decision |
|---|---|---|
| P9-1 | Lock screen before the first unlock (direct boot) | **No.** The keyboard stays credential-encrypted only (no `directBootAware`). Android uses its fallback keyboard until the first unlock. |
| P9-2 | Password fields | **No FLAG_SECURE**, so screenshots and recordings work as now. Key views in password fields are marked `accessibilityDataSensitive` (API 34+), so accessibility services that aren't screen readers can't read keystrokes. The existing protections stay: no suggestions, learning, engines or key preview, and the mic is hidden. |
| P9-3 | Performance on the S23 Ultra | **Allowed only when the user says so.** Ask right before each run. A run installs the benchmark and test APKs, runs, then uninstalls them. Never touch the user's KeyCorrect install or its data. |
| P9-4 | Tab S9 hardware-keyboard mode | **Build it now and verify it on a tablet emulator with a hardware keyboard.** The device pass comes when the Tab S9 is back. |
| P9-5 | Delivery | One PR per part, each opened against `main`. |

## Parts

### 9a. Security and privacy audit
- **Manifests:** check exported components and permissions. There must be no INTERNET, and nothing unexpected merged in from libraries. Check `allowBackup` and `dataExtractionRules`, and FileProvider exposure.
- **Logs:** no user text anywhere, including release builds. There is a lint rule and log stripping, and release logcat is checked on the emulator.
- **Password fields:** `accessibilityDataSensitive` on the key views (virtual nodes) and the strip.
- **IPC surfaces:**
  - the engine service and plugin binding;
  - content URIs (the clipboard images provider);
  - the debug-only Field Lab and seed tools stay out of release builds.
- **Storage:**
  - SQLCipher key handling;
  - files under `filesDir` and `noBackupFilesDir`;
  - imported models;
  - the backup passphrase.

### 9b. Hardware-keyboard mode
- When a physical keyboard is attached, there are no on-screen keys. A compact bar shows suggestions and autocorrect, with a toggle to show the full keyboard.
- **Physical key events feed the same prediction pipeline:** composing, space and punctuation, backspace, and modifier shortcuts.
- **Learning:** this runs under a separate touch profile (`HardwareKeyboard`, which has no touch statistics).

### 9c. Startup and performance
- Baseline profiles (`androidx.baselineprofile`), generated on the emulator.
- **The perf pass:** the §14 budgets checked on the S23 Ultra with the user's go-ahead, and on the Tab S9 when it is back. Results go in docs/PERF.md.
