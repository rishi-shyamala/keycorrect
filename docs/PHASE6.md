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
