# KeyCorrect — Phase 5 plan: swipe typing

Goal (PLAN §7, §17; decision D7): gesture typing with our own SHARK²-style shape-matching decoder,
fully on-device, working on any layout because word templates come from the live key geometry.
A neural decoder stays optional for Phase 10.

**Exit criteria:** top-1 accuracy target met on the swipe eval; decode on lift < 30 ms on the
Tab S9 (release); no typing regressions (the tap eval stays identical).

## Decisions

| # | Question | Decision |
|---|---|---|
| P5-1 | Spacing between swiped words | **Auto-space (user, 2026-10-16).** A swiped word is committed without a trailing space; the next swiped or typed word adds one first. Punctuation attaches to the word ("hello."). |
| P5-2 | Backspace right after a swipe | **Deletes the whole swiped word (user).** After that, backspace deletes characters as usual. |
| P5-3 | Swipe eval data | **Synthetic only (user).** Noisy gestures generated from the test sentences; no download. Real-world accuracy is judged on the device. |
| P5-4 | Where the live preview shows | **In the strip's centre slot (user).** Nothing covers the keys. |
| P5-5 | Where swipe is on | **Decided (user, 2026-10-16):** on in text, email, URL and name fields; off in password, numeric, phone, date and terminal fields, and where the app asks for no suggestions. |
| P5-6 | Settings | **Decided (user):** Gesture typing on/off (on), Show trail (on). Nothing else until daily use asks for it. |
| P5-7 | Learning | **Decided (user):** a swiped word is learned like a picked suggestion (n-grams, keep or replace via the strip). Gestures add no touch-offset statistics. |
| P5-8 | Delivery | **Decided (user):** two PRs. **A:** engine API + decoder + eval (pure JVM). **B:** keyboard integration (touch, trail, preview, commit, settings, learning), device test. |

## Design

### Engine API (`core:engine-api`)
- `GestureInput(points: List<TouchPoint>)` and a `GestureRequest(context, gesture, layout,
  maxCandidates, sequence)`.
- A new tier, `GestureEngine : TextEngine { suspend fun decodeGesture(request): PredictResult }`.
  The classic engine implements it. The result reuses `PredictResult`: candidates ranked, and the
  top one is what gets committed.

### Decoder (`core:decoder`, new `gesture` package)
1. **Resample** the gesture to N equidistant points (N = 64). Very short paths are taps, not
   gestures (the keyboard decides; see below).
2. **Templates:** for a word, the polyline through its letters' key centres, with repeated
   letters collapsed ("hello" → h-e-l-o), resampled to N points. Built lazily per
   `KeyLayoutModel.id` and cached (LRU) for the words that survive pruning.
3. **Pruning (keeps decode < 30 ms):**
   - The first point lies within ~1 key of the word's first letter, and the last point within
     ~1 key of its last letter. A per-layout index maps (first key, last key) → candidate words.
   - The path length ratio (gesture / template) lies in a band (e.g. 0.6–1.8).
   - Only the most frequent ~60k dictionary words plus user words take part.
4. **Channels:**
   - **Shape:** both curves are normalised for scale and position; the mean point-to-point
     distance.
   - **Location:** the mean distance from gesture points to the template, weighted towards the
     start, end and corners.
   - Each channel gives a Gaussian likelihood; they combine in log space.
5. **Fusion:** the channel score plus the back-off LM prior given the context, plus the
   personalization terms (user words, n-grams), through the existing `FusionRanker` with
   gesture-specific weights. Tuned on the eval's dev split.
6. **Capitalisation:** shift once → Capitalised, caps lock → ALL CAPS, auto-cap at sentence
   start, as for typed words.

### Keyboard (`feature:keyboard-ui`)
- **Tap or gesture:** a press that starts on a letter key, travels more than ~0.6 key widths and
  crosses at least one other key before lifting is a gesture.
  - Long-press popups still open only if the finger stays still.
  - Spacebar and backspace gestures are unchanged (they start on those keys).
  - A second finger during a gesture is ignored.
- **Trail:** a fading polyline drawn in the overlay layer (no per-frame allocation; a ring buffer
  of points).
- **Live preview:** the decoder runs on the existing decoder thread every ~50 ms while the finger
  moves (stale results are dropped); the top candidate shows per P5-4.
- **On lift:** the final decode; the top word is committed (spacing per P5-1, backspace per P5-2). The strip
  shows the alternatives, and a tap replaces the swiped word.
- **Learning hooks** per P5-7, through the existing privacy gates (no learning in sensitive,
  incognito or no-learning fields).

### Eval (`tools/eval --mode swipe`)
- Gestures for test-split sentences, word by word, drawn through key centres with noise:
  - Gaussian jitter on each letter's point;
  - corner cutting and overshoot (Catmull-Rom smoothing);
  - variable speed, sampled at ~100 Hz.
- Metrics: top-1 and top-3 word accuracy, wrong commits, and decode latency (p50/p95/p99).
- The tap eval must stay identical.

### Performance
- Decode on lift < 30 ms, and preview decodes < 10 ms on the Tab S9, both on the decoder thread.
- A new `GestureBenchmark` in `:benchmark`; numbers go in `docs/PERF.md`.
