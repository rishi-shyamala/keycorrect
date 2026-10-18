# KeyCorrect — Phase 6 plan: voice typing

Goal (PLAN §5, §17; decision D6): offline dictation. A small default model (≤ ~50 MB of weights,
≤ ~150 MB RAM while dictating) is loaded only while the mic is on. Small models get their accuracy
from the keyboard around them: biasing with your vocabulary, rescoring with the text engine, and
learned substitutions. Stacked on Phase 5 (#11).

## Decisions (user, 2026-10-18)

| # | Question | Decision |
|---|---|---|
| P6-1 | Getting the model onto the phone (no INTERNET permission, D3) | **A separate voice-pack APK** (`dev.keycorrect.voice`) holds the model(s). The keyboard reads them from the installed pack: package context and assets, with a `<queries>` entry for package visibility. Both APKs are signed with the same release key and published on the same releases page. The keyboard APK stays small. |
| P6-2 | Runtime and downloads | **sherpa-onnx** (Apache-2.0) for on-device models. Its Android native libraries and the candidate models are downloaded from the official releases, with checksums recorded like the dictionary sources. Only what ships is committed or packed. |
| P6-3 | Mic button | **Long-press the comma**, as on Samsung, with a small **mic icon** on the comma key as its hint. Not offered in password fields. |
| P6-4 | Delivery | **One PR.** |

## Scope
- **Speech API:** `SttEngine` / `SttSession` (PLAN §5.1), in a JVM module.
- **Audio:** capture at 16 kHz mono in the IME (visible window, no foreground service), with
  voice-activity detection.
- **Engines:**
  - sherpa-onnx (streaming and offline), with the model loaded from the voice pack;
  - an adapter for any installed `RecognitionService` (Google, FUTO Voice Input, …), used as a
    fallback when no pack is installed.
- **Dictation UX:**
  - a listening panel with a level meter and partial text;
  - partial results as composing text, with the stable prefix committed;
  - auto-punctuation and capitalisation;
  - tap to stop, cancel;
  - basic spoken commands ("new line", "delete that").
- **Accuracy:**
  - hotword biasing from your words;
  - n-best rescoring with the text engine;
  - STT substitution pairs learned from your corrections right after dictating (PLAN §5.4).
  - Dictated text feeds learning at reduced weight. Nothing is learned in incognito or sensitive
    fields.
- **Benchmark:** the candidates (Moonshine, Zipformer-20M, Whisper tiny.en): word error rate,
  latency and RAM on the desktop now, then on the device. The default comes from the device
  numbers.

## Open (decided from data)
- The default model: Moonshine v2 streaming if the runtime supports it, else the best of the
  benchmarked candidates.

## Implementation notes (voice pack and built-in engine)

- **`:voicepack`** (application, package `dev.keycorrect.voice`): Moonshine v2 tiny (English,
  quantized) and Silero VAD as stored (uncompressed) assets under `moonshine/` and the root, plus
  `libonnxruntime.so` and `libsherpa-onnx-jni.so` for arm64-v8a and x86_64, extracted to disk
  (`useLegacyPackaging`). No code, no activity, no permissions. Version = the app's, suffixed
  `-voice`; release-signed with the same `keycorrect.release.*` properties as `:app`. Built from
  `data/raw/voice/` (git-ignored, `tools/data/fetch-voice.sh`); without those files the debug
  variant isn't created (so `assembleDebug` and CI skip it) and a release build stops with a message.
  About 65 MB.
- **`:feature:voice-sherpa`** (library): the vendored sherpa-onnx Kotlin API (package
  `com.k2fsa.sherpa.onnx`, which the JNI symbols need; `System.loadLibrary` removed, presets
  dropped), `VoicePackLocator` and `SherpaMoonshineEngine`.
- **Loading.** `VoicePackLocator(context).locate()` returns `Available(VoicePack)` or
  `Unavailable(NotInstalled | SignatureMismatch | Broken)`. It reads both packages' signing
  certificates (`GET_SIGNING_CERTIFICATES`), compares the SHA-256 sets for equality (the pure
  `checkPackSigners`; unreadable or empty means refused), and only then `System.load()`s
  `libonnxruntime.so` then `libsherpa-onnx-jni.so` from the pack's `nativeLibraryDir`, once per
  process. The models are read in place through `createPackageContext(...).assets` (checked with
  `openFd`, so they must be stored uncompressed). Missing libraries or assets give `Broken`; so does
  a pack updated while the keyboard process lives (a new path), until the process restarts.
- **Engine.** `SherpaMoonshineEngine(pack)`: not streaming. A session's worker thread loads the VAD
  and recognizer (2 threads), feeds 16 kHz audio to Silero VAD (0.5 s silence ends a segment, speech
  is cut at 15 s), decodes each finished segment with Moonshine, and emits
  `Partial(all segments so far, stablePrefixLength = its length)`; `finish()` flushes the VAD, decodes
  the rest and emits `Final(text)`, or `Error(NoMatch)` for no text. Failures give `Error(Engine)`
  (never logging text). The models are freed when the session ends, `cancel()` emits nothing, and
  `release()` cancels the running session. Hotwords are ignored (Moonshine has none).
- **For the app side:** add `<queries><package android:name="dev.keycorrect.voice"/></queries>` to
  the app manifest (package visibility), and have `VoiceHost` call
  `VoicePackLocator(context).locate()` and build `SherpaMoonshineEngine(pack)` on `Available`
  (falling back to the system recognizer otherwise); `acceptAudio` may be called from the capture
  thread, results arrive on the worker thread. Third-party licences: `THIRD_PARTY_NOTICES.md`; the
  in-app licence screen (app strings) should list sherpa-onnx (Apache-2.0), ONNX Runtime (MIT),
  Moonshine (MIT) and Silero VAD (MIT).
