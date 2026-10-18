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
| P6-5 | Phone recognizer | **Never a fallback.** The engine setting is `BuiltIn` (default: the voice pack only) or `PhoneRecognizer` (an explicit opt-in; the settings text says the phone's recognizer may send audio to its provider's servers). With `BuiltIn` and no, untrusted or damaged pack, dictation shows a message ("Install the KeyCorrect voice pack to dictate", "Voice pack isn't trusted (signature)", "Voice pack is damaged") instead of switching. In incognito and in fields with `IME_FLAG_NO_PERSONALIZED_LEARNING` the phone recognizer is not used even when selected (a message is shown). Stored `Automatic` becomes `BuiltIn`, `System` becomes `PhoneRecognizer`. |

## Scope
- **Speech API:** `SttEngine` / `SttSession` (PLAN §5.1), in a JVM module.
- **Audio:** capture at 16 kHz mono in the IME (visible window, no foreground service), with
  voice-activity detection.
- **Engines:**
  - sherpa-onnx (streaming and offline), with the model loaded from the voice pack;
  - an adapter for any installed `RecognitionService` (Google, FUTO Voice Input, …), used only
    when the user chooses it (P6-5), never as a fallback.
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
  `openFd`, so they must be stored uncompressed). The assets are taken before the signature check
  and the pack's version code and update time are compared again after loading (a pack replaced
  meanwhile gives `Broken`); a verified result is cached per (version code, update time). Missing
  libraries or assets give `Broken`; so does
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
  (no fallback: P6-5; the libraries are only warmed up in the background when voice is on and the
  engine is `BuiltIn`); `acceptAudio` may be called from the capture
  thread, results arrive on the worker thread. Third-party licences: `THIRD_PARTY_NOTICES.md`; the
  in-app licence screen (app strings) should list sherpa-onnx (Apache-2.0), ONNX Runtime (MIT),
  Moonshine (MIT) and Silero VAD (MIT).
- **R8.** The app minifies release builds, and the JNI library looks the vendored
  `com.k2fsa.sherpa.onnx` classes up by name: `:feature:voice-sherpa` ships `consumer-rules.pro`
  keeping that package and native method names.
- **Stop.** After Stop the controller waits 4 s for the final result, then keeps waiting (checking
  every second, 15 s in all) while the session reports `busy` (still decoding); on timeout the
  text stands and a message says the last words may be missing.
- **Release.** `tools/release/release.sh publish` checks that the voice pack and the keyboard APK
  have the same signing certificate (SHA-256) and stops otherwise.

## Long dictation & live words

**The bug.** Speaking for a long time ended with "Didn't catch that": Moonshine v2 tiny fails
outright on audio longer than about 9.25 s (ONNX Runtime: the decoder's cross-attention shapes don't
broadcast), sherpa-onnx catches that and returns an empty result, so a long segment decoded to
nothing. The VAD's own maximum didn't help: Silero cuts only at a quiet moment and with a 3 s maximum
segments of 12 s were still produced (desktop, `sherpa-onnx-vad-with-offline-asr`).

**Experiment** (desktop, sherpa-onnx 1.13.8 Linux CLI, Moonshine v2 tiny quantized, 2 threads;
clips cut from the bundled test wavs, A = 6.6 s clip then the 16.7 s clip, B = the reverse):

| Clip length | A | B |
|---|---|---|
| 4 s | ok | ok |
| 6 s | ok (cut mid-word: repeated "the brothel") | ok |
| 8 s | ok | ok |
| 9.24 s | ok | ok |
| 9.28 s and longer (9.4, 10, 12, 15, 17 s) | empty (exception) | empty (exception) |

Decoding takes 30 to 90 ms per clip on the desktop (RTF about 0.006), so the live re-decode is
cheap there; the phone is to be measured. Failure is a hard, sharp threshold at about 9.25 s, not a
gradual loss of quality.

**Limits.** Phrase limit default **7 s** (76% of the first failure); the user can set 3 s to
**8.5 s** (92%, never nearer than the chunk and pre-roll margin to the failure). The session cuts a
phrase itself when its copy of the speech reaches the limit: at the quietest 30 ms frame of the
second half, committing the part before, resetting the VAD and feeding the rest back. Settings
(Settings, Voice, "Long dictation", all clamped on read and write): longest phrase 3 to 8.5 s
(default 7); pause before a phrase is final 0.3 to 1.5 s (0.5); live update interval Auto, 0.5 to
2.0 s or Off (Auto); stop after silence 3 to 30 s or Off (10 s).

**Robustness.** A segment longer than 4 s that decodes to nothing, or to fewer than one word per
2.5 s, is cut at its quietest point near the middle and the halves are decoded (again when still
long); the result with more words wins. If the whole session heard at least 1 s of speech and made
no text, the error is `SttError.Unclear` ("Couldn't make that out - try shorter phrases"), not
`NoMatch`.

**Live words.** `SegmentedDictation` keeps its own copy of the phrase in progress (from the VAD's
speech start, with 0.3 s of pre-roll) and, while speech goes on, decodes it on the worker about
every 0.7 s: the wait to the next decode is twice the last one's duration, within 0.5 to 1.5 s
(Auto), or a fixed interval that is raised to twice the decode time when the phone is slower. A live
decode runs inline on the one worker, so none ever queues behind another. `Partial(committed +
live, stablePrefixLength = committed.length)`; at the pause the segment is decoded once more and
committed (if that decode is empty the live words are kept). Output that is clearly a decoder loop
(more than 6 words per second) is not shown. Buffers are freed per segment.

**Progress.** `SttResult.Progress(phraseMillis, phraseLimitMillis, speaking)` about every 200 ms of
audio; the phone recognizer sends none.

**Listening bar.** Total time (m:ss), a ring around the mic that fills with
`phraseMillis / phraseLimitMillis` (amber from 75%), a pulsing dot while speech is heard, the hint
"pause briefly to keep going" from 60%, live words greyed until stable, and (with auto-stop on) a
"Stopping in N" countdown in the last 3 s of silence. Progress is read in draw scopes only. Auto-stop
applies only to engines that report progress.
