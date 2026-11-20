# KeyCorrect architecture

What is true now, checked against the code (2026-11-15). The original design is
[PLAN.md](PLAN.md) (see its status banner); decisions and their reasons are in
[DECISIONS.md](DECISIONS.md). Reference docs: [EVAL.md](EVAL.md), [PERF.md](PERF.md),
[SECURITY.md](SECURITY.md), [TRANSFER.md](TRANSFER.md), [PLUGINS.md](PLUGINS.md),
[LAYOUT_FORMAT.md](LAYOUT_FORMAT.md), [DATA_SOURCES.md](DATA_SOURCES.md).

## Module map

The list is `settings.gradle.kts`; dependencies are the `project(":...")` lines in each
`build.gradle.kts`. Modules marked (JVM) are pure Kotlin and unit-test without a device.

| Module | Role |
|---|---|
| `:app` | The only application. IME service, settings UI (Compose), `KeyCorrectApp` (wiring), `LearningHost`, `PredictionHost`, `NeuralHost`/`EngineClient` (talk to the engine process), `VoiceHost`, backup/transfer UI, clipboard image provider |
| `:feature:keyboard-ui` | The keyboard: single-canvas renderer, `TouchStateMachine`, `KeyboardController`, `PredictiveInput`, `DecoderLoop`, strip, editing/emoji/clipboard panels, swipe, voice controller |
| `:feature:voice-sherpa` | sherpa-onnx speech engine (loaded from the voice pack), segmented dictation |
| `:core:engine-api` | `TextEngine` tiers (Predict, Gesture) and request/result types (JVM) |
| `:core:engine-sdk` | AIDL contract and `TextEngineService` base class for plugins and the built-in engine |
| `:core:decoder` | `ClassicEngine`: spatial model, trie search, back-off LM, autocorrect policy, gesture decoder, `NeuralReranker` (JVM) |
| `:core:dictionary` | KCD1 memory-mapped dictionary reader/writer (JVM) |
| `:core:learning` | Learning events, `OutcomeClassifier`, `PersonalModel` and snapshots, touch model, fusion ranker (JVM) |
| `:core:data` | SQLCipher database (`SqlLearningStore`), user dictionary, shortcuts, clipboard store |
| `:core:settings` | `KeyboardSettings` and DataStore (device-protected, non-sensitive only), size profiles |
| `:core:input` | `EditorSession`/`TextEditor`, `FieldProfile` (privacy gates), auto-caps, graphemes |
| `:core:layout` | JSON layout format, built-in layouts, composer, geometry (JVM) |
| `:core:ime` | `ComposeInputMethodService` (lifecycle and saved-state owners for Compose in an IME) |
| `:core:designsystem` | Material 3 theme, wallpaper colors, presets, key style |
| `:core:emoji`, `:core:clipboard` | Emoji catalog and search; clipboard history model (JVM) |
| `:core:voice` | `SttEngine`/`SttSession` contract, VAD, dictation types (JVM) |
| `:core:packs` | Optional-pack discovery and the signature check; `SwipePackLoader` opens the swipe encoder from the swipe pack |
| `:core:llama` | JNI binding for llama.cpp (the native library lives in the neural pack) |
| `:core:swipenet` | FUTO Swipe encoder on ExecuTorch (JVM): input resampler (ported from FUTO's swipe-library), layout frame, JNI binding; the C++ glue in `src/main/cpp` is built by `tools/swipenet/build.sh` into the swipe pack. Not used by the keyboard yet (Phase 10a-3) |
| `:core:transfer` | `.kcbackup` export/import format and crypto |
| `:voicepack`, `:neuralpack`, `:swipepack` | Separate APKs (`dev.keycorrect.voice`, `dev.keycorrect.neural`, `dev.keycorrect.swipe`): models and native libraries, no code, no permissions |
| `:sampleplugin` | Example third-party engine APK (uses `:core:engine-sdk`) |
| `:baselineprofile`, `:benchmark` | Baseline profile generation and macrobenchmarks; on-device microbenchmarks |
| `:tools:dictbuild`, `:tools:emojibuild`, `:tools:eval` | Offline JVM tools: dictionary and emoji data builders; the evaluation harness |
| `build-logic` | Convention plugins (not a `:` module) |

**Direction:** `:app` -> `:feature:*` -> `:core:*`. `:feature:keyboard-ui` depends on decoder,
designsystem, emoji, engine-api, input, layout, learning, llama, settings and voice, never on
`:core:data`. `:core:decoder` depends on dictionary, engine-api, layout and learning; `:core:learning`
only on engine-api; `:core:data` on learning. Storage reaches the keyboard through interfaces the
app implements (`*Environment`, `LearningSink`, `PredictionBackend`), so `:feature:keyboard-ui` stays
testable on the JVM against simulated editors. Tools never depend on `:app` or `:feature:*`
(`:tools:eval` uses decoder, dictionary, layout, learning, llama). `:core:packs` depends on `:core:swipenet`, which depends on `:core:layout`.

## Processes and threads

- **Keyboard process** (default): IME service, settings activity, everything above.
- **`:engine` process** (`app/.../neural/NeuralEngineService`, `android:process=":engine"`): runs the
  language model so a crash or out-of-memory there never takes the keyboard down. It must never open
  DataStore or the app container. The keyboard binds it through `EngineClient` over the same AIDL
  contract that third-party plugins use (explicit component, per-plugin approval pinned to the
  signing certificate).
- **Threads:** main (UI, touch, `InputConnection`); `keycorrect-decoder` (`DecoderLoop`, one
  request at a time, latest wins); `keycorrect-learning` (`LearningHost`, below normal priority);
  `kc-rerank` (`NeuralReranker` scoring, called from the decoder thread, deadline 40 ms);
  `keycorrect-stt` (dictation worker); `kc-engine-client`/`kc-engine-load` (engine binding and
  model load); `keycorrect-clip-pipe` (decrypting clip images into a pipe).

## Keystroke data flow

1. Touch: `TouchStateMachine` maps pointer events to a key or gesture (tap, long-press, swipe,
   spacebar drag) on the main thread.
2. `KeyboardController` applies the key to the editor; `PredictiveInput` owns composing text,
   autocorrect, undo and learning events.
3. Each change posts a request to `DecoderLoop`, which runs `ClassicEngine.predictNow` on the
   decoder thread with the current personalization (`PersonalModel.snapshot()`).
4. If the request allows engines, the decoder thread then calls `NeuralReranker.refine`; the
   reranked result replaces the classic one only if it arrives within the deadline. Otherwise the
   classic result stands.
5. The result goes to the suggestion strip (and the autocorrect decision).
6. On a separator, the main thread waits for the current result at most `SEPARATOR_WAIT_MS` = 30 ms
   (swipe lift: `GESTURE_WAIT_MS` = 50 ms), then commits as typed if nothing is ready.

## Learning flow

`PredictiveInput` emits events (word finished, correction accepted or undone, strip pick, sentence
words, touch offsets) through one privacy gate to `LearningSink` -> `LearningHost` (learning
thread) -> `OutcomeClassifier` (PLAN section 6.3 outcome rules, keep thresholds N/M) ->
`PersonalModel` (n-grams, correction memory, personal words, touch statistics; a journal for
recent forgetting) -> `SqlLearningStore` (atomic commit/replaceAll) and a fresh immutable
snapshot published to the engine. Personalization never lives inside an engine; engines only read
the snapshot. Evidence is eval-checked ([EVAL.md](EVAL.md), "Learning").

## Optional packs and plugins

- **Voice pack**, **neural pack** and **swipe pack** (FUTO Swipe encoder, Phase 10; not loaded by the keyboard yet): separate APKs, installed by the user, found through
  `<queries>` and `PackageManager`. `:core:packs` (`PackSigning.kt`) refuses a pack unless its
  signing certificates equal the keyboard's own set exactly; the keyboard loads the pack's native
  code into its process, so the check is the trust boundary. After a pack update the keyboard asks
  for a restart. The keyboard never falls back to a network or phone recognizer unless the user opts
  in (`PhoneRecognizer`; not used in incognito or no-learning fields).
- **Models:** the neural pack holds the default GGUF; Settings -> Engines can also import a GGUF
  into app-private `filesDir`.
- **Plugins:** any APK exposing a service for action `dev.keycorrect.TEXT_ENGINE`
  ([PLUGINS.md](PLUGINS.md)); off until the user approves that package and certificate.

## Storage

- **DataStore** (preferences, device-protected storage): settings only; must stay non-sensitive.
- **SQLCipher** (`core:data`, plain `androidx.sqlite` + `sqlcipher-android`, no Room): learned data,
  user dictionary, shortcuts, clipboard rows. The 32-byte passphrase is sealed with an
  AndroidKeyStore AES-GCM key.
- **Files:** clip images (Keystore-sealed) and engine load markers under `noBackupFilesDir`;
  imported models and the memory-mapped dictionary asset (stored uncompressed in the APK) as noted.
- Backup and device transfer are disabled (`allowBackup=false`, extraction rules); the only export
  is the user-initiated `.kcbackup` file ([TRANSFER.md](TRANSFER.md)).

## Invariants

Enforced by tests where noted; do not weaken without a recorded decision.

1. **No `INTERNET` permission, ever** (D3). `ManifestSecurityTest` (in `testDebugUnitTest`) also
   limits exported components to the IME service and the launcher activity (plus androidx's
   `ProfileInstallReceiver`, guarded by DUMP), and forbids `directBootAware`, debuggable and cleartext. See [SECURITY.md](SECURITY.md).
2. **Privacy gates:** password (sensitive) fields never compose, decode, read text, learn or show
   key previews; no learning in email, URL or address fields; none when the field sets no-learning,
   in incognito, or with learning off. Both the keyboard and `LearningHost` check. Engines get no
   text in those cases. Logs carry exception class names only, never user text.
3. **The main thread never blocks on the engine** beyond the bounded separator and swipe waits
   (30 ms / 50 ms, `DecoderLoop`).
4. **Eval output is byte-identical** for performance and refactoring changes (`--threads 1`;
   [DEVELOPMENT.md](DEVELOPMENT.md)).
5. **Packs are signed with the app's key**; unsigned or differently signed packs are ignored.
6. **`:engine` process** never touches DataStore or the app container.
7. Settings live in device-protected storage and must stay non-sensitive; the IME is not direct-boot
   aware (P9-1).
