# Decision log

One line per user decision, distilled from the original design and phase documents (now in [archive/](archive/README.md)). Text is quoted from their decision tables, shortened where long. Ids match the source tables. "Where" names the module or area that implements it. The source documents hold the reasoning and the full wording. New decisions: add a row here (id, date, decision, where) in the PR that makes them.

## Foundations and product (PLAN section 16)

Source: PLAN.md section 16 (D1-D12; D11 was a placeholder name, kept).

| Id | Date | Decision | Where |
|---|---|---|---|
| D1 | 2026-10-09 | from scratch: Starting point -> (a). Pluggable engines + learning layer are the core and must be designed in; HeliBoard's AOSP Java core is hard to restructure and its swipe needs a proprietary lib; | whole repo |
| D2 | 2026-10-09 | GPL-3.0-or-later: License -> GPL-3.0-or-later: lets us also borrow from HeliBoard/Unexpected Keyboard, and keeps forks of a privacy keyboard open. Apache-2.0 if you want max permissiveness (then no HeliBoard code). | LICENSE, LICENSE-DATA.md |
| D3 | 2026-10-09 | fully local: Network -> (a). Decided: keyboard fully local; network engines only ever as an optional separate plugin APK. | ManifestSecurityTest; docs/SECURITY.md |
| D4 | 2026-10-09 | English only: Languages at launch -> Affects dictionaries, STT and LM defaults. English-first architecture with multilingual support built in, other languages added as dictionary packs. | core/dictionary, tools/dictbuild |
| D5 | 2026-10-09 | minSdk / target -> minSdk 31, compileSdk/targetSdk 37 (Android 17) if the platform package is final, else 36. You run Android 16+. | build-logic KeyCorrectBuild.kt |
| D6 | 2026-10-09 | Default STT -> Small model ≤ ~50 MB (Moonshine v2 Streaming Tiny expected; Zipformer-20M / Moonshine v1 tiny / Whisper tiny.en benchmarked in Phase 6). Larger models opt-in only. | voicepack, feature/voice-sherpa |
| D7 | 2026-10-09 | Swipe engine -> Own SHARK² first, neural later (Phase 10) with your choice then. | core/decoder (swipe) |
| D8 | 2026-10-09 | Key renderer (how keys are drawn internally — not handwritin... -> Single-Canvas in Compose. Handwriting input: out of scope. | feature/keyboard-ui |
| D9 | 2026-10-09 | Encryption at rest -> SQLCipher (small perf cost, defends rooted/forensic access). | core/data |
| D10 | 2026-10-09 | Raw-text buffer for engine finetuning -> On by default with a toggle, encrypted, 7-day retention, never from sensitive fields. | core/learning, core/data |
| D11 | 2026-10-09 | App identity -> "KeyCorrect", dev.keycorrect (placeholder — your call). | applicationId dev.keycorrect |
| D12 | 2026-10-09 | Build environment -> You install JDK 21 + Android cmdline tools/SDK/NDK; I verify versions (see docs/DEVELOPMENT.md). | docs/DEVELOPMENT.md |
| P4 | 2026-10-11 | Phase 4 entry points, defaults, delivery (feedback round 1 o... -> Decided 2026-10-11, see docs/archive/PHASE4.md: no toolbar (emoji key left of space, whose long-press opens the clipboard; long-press space opens the editing panel); clipboard history on, text and images; emoji strip suggestions on; | feature/keyboard-ui |

## Editing, clipboard, emoji, layout (Phase 4)

Source: archive/PHASE4.md decision and feedback tables (FRn-m = feedback round n, row m).

| Id | Date | Decision | Where |
|---|---|---|---|
| P4-1 | 2026-10-11 | How the panels are reached: Keys only, no toolbar. An emoji key opens the emoji panel, and its long-press opens the clipboard. A long-press on space opens the editing panel. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| P4-2 | 2026-10-11 | Emoji key placement: A new key left of space in the text, email and URL bottom rows; space gets one unit narrower. It's absent from number, phone and date pads and from password fields. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| P4-3 | 2026-10-11 | Space long-press: A hold released without moving opens the editing panel. A hold followed by a drag selects from the cursor. A quick drag with no hold moves the cursor. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| P4-4 | 2026-10-11 | Clipboard history: On by default, text and images (20 MB image cap), with the §9 rules: 30 items, 7-day TTL, pinned items kept, encrypted, off in incognito. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| P4-5 | 2026-10-11 | Emoji in the suggestion strip: On. The right-hand slot shows an emoji only for a strong match (an exact CLDR keyword). A setting turns it off. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| P4-6 | 2026-10-11 | Delivery: Three PRs: A editing + terminal row, B emoji, C clipboard. Each gets a device test, the performance check and a review. B comes before C so the new emoji key works from the start: its tap opens the emoji panel. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-1 | 2026-10-16 | Keys are a different size in the symbols view (seen on the S23): Decision (user, 2026-10-16): one key unit for every layer, plus min/max widths. u is the letter key width (keyboard width / 10), shared by every layer, with the letters' key height and row count (3 rows + bottom). Character keys are 1u. Function keys (shift, ⌫, ?123/ABC, 1/2, enter) are capped at 1.5u. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-2 | 2026-10-16 | The emoji grid lags when scrolling fast: Measure on the Tab S9 with a release build (debug Compose is far slower). Then fix: stable keys and contentType, glyphs drawn from a cached TextLayoutResult or bitmap per emoji instead of a Text per cell, and nothing recomposed per scroll frame. Add a scroll-jank check to the device test. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-3 | 2026-10-16 | Symbols should sit where they are on a regular keyboard: The symbols page 1 top row is digits 1–0, with the shift+digit symbols ! @ # $ % ^ & * ( ) in that order directly beneath (US layout). | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-4 | 2026-10-16 | Organise symbols like Samsung's, drop the rare ones: / ~ € £ ¥ ¢ ° • × ÷ / [2/2] ¡ ¿ « » … § © [⌫]. Dropped: card suits, ○ ● □ ■ ▪ ☆ ¤ 《 》 ₩ √ π ¶ ∆ ® ™ ✓. Bottom row on both pages: ABC, emoji, space, comma, period, enter. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-5 | 2026-10-16 | Shortcuts to the edit/select panel, emoji and clipboard in the top row: Decision: Samsung-style shared row. When not typing, the strip shows shortcut icons (editing panel, emoji, clipboard, settings); while typing, suggestions take that row. Supersedes P4-1's "no toolbar"; the emoji key and the long-press stay. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-6 | 2026-10-16 | Options to remove the edge padding and the gaps between keys: Decision: two sliders, in Settings → Appearance: edge padding and key gap, each from 0 up to today's size (the default). | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-7 | 2026-10-16 | Use the left/right side of the spacebar for select / move: Decision: dragging on the left half selects, dragging on the right half moves the cursor, with a faint line on the spacebar at the split. Supersedes P4-3's hold+drag select. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-8 | 2026-10-16 | Bigger, clearer key symbols (tab, enter, shift, …): Vector icons drawn on the key canvas (shift and its caps-lock form, enter and its action variants, backspace, tab, arrows, emoji, the layer switch), about 1.6× today's glyph size, with consistent stroke weight and contrast. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-9 | 2026-10-16 | Word/Line/Sentence in the select panel don't work: Bug. getSurroundingText reports offset −1 ("unknown") in editors that use the platform default, and those actions then did nothing. Fall back to the tracked cursor when no own edit is pending; at a word end (e.g. after a space), Word selects the previous word. Verify on device in EditText and Chrome. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-10 | 2026-10-16 | No swipe typing: Decision: Phase 5 (own SHARK² decoder), right after this round. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-11 | 2026-10-16 | The emoji header blends into the grid: The tab row gets its own surface colour and a divider, monochrome outline icons (not emoji) for the categories, and a clear selected indicator. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR1-12 | 2026-10-16 | Bottom row like Samsung's (user, 2026-10-16): Comma and period move right of the space bar: ?123, emoji, space, comma, period, enter (email: @ and URL: / in the comma's place). Side-key widths per #1. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR3-A | 2026-10-17 | Editing panel layout: Gboard-style move panel with helper columns (separate change). | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR3-B | 2026-10-17 | Spacebar joystick: The joystick on the spacebar drags, with rate-based movement beyond the ring (separate change). | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR3-1 | 2026-10-17 | Keyboard opens on letters: The keyboard opens on the field's home layer (letters for text) whenever it is opened for input: a new field, or the same field shown again after being hidden. The layer, shift and a manual field mode are kept only for the restart that a configuration change (rotation) causes. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR3-2 | 2026-10-17 | ABC from a panel: ABC in the emoji, clipboard and editing panels returns to the home layer (letters, or the number pad), not the symbols page that was up before. The strip's Edit tool and pasting a clip keep the layer. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR3-3 | 2026-10-17 | Symbols pages: Row 3 of both pages: 1u layer key, eight 1u character keys, 1u backspace (page 2 gains ® after ©), so nothing jumps when switching. Bottom rows are the same by construction (tested). | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR3-4 | 2026-10-17 | Clip chip icon: The recent-clip chip uses the strip's vector clipboard icon instead of the emoji. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR3-5 | 2026-10-17 | Haptic pulse length: Each pulse of the autocorrect double pulse and the unknown-word triple buzz is 20 ms (was 12 ms) and set by two sliders in Settings → Feedback (autocorrectPulseMs, unknownWordPulseMs, 5-60 ms, disabled with their switches). Both are plain waveforms (primitive ticks have no settable length); gaps stay 43 ms and 48 ms. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR3-6 | 2026-10-17 | Edit tool flash: The Edit tool is selected only while the editing panel is open (editToolSelected, derived from panel). Not reproducible without a device; | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR3-7 | 2026-10-17 | Manual layout switch: Decision (user): long-press ?123 in plain text fields (FieldKind.Text) offers Text, Email (@), URL (/), Number (123), Phone (☎) (fieldMode popup entries, KeyAction.SetFieldMode). The choice replaces the profile for layout, bottom row and suggestion/autocorrect/learning rules (FieldProfile.withKind) until the field changes; | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR4-1 | 2026-10-17 | The selection strip becomes [◀ ▶] Word · Sentence · Line [◀ ▶]. Unit buttons: expand to whole units as before, and make that unit current. Left pair: moves the selection's start one unit back or forward. Right pair: moves its end one unit back or forward. The selection can shrink to nothing but never inverts. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR4-2 | 2026-10-17 | Joystick: Pure rate control from the centre. A dead zone in the middle doesn't move. Just outside it the cursor inches along, speeding up towards the ring; past the ring it moves fast. Downwards, the ring shortens to the room below the finger, and reaching the keyboard's bottom edge counts as full speed. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR4-3 | 2026-10-17 | Number row: On by default. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR4-4 | 2026-10-17 | ?123 and Enter: 115% of a letter key by default, with their own slider. The other bottom-row side keys keep theirs. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR4-5 | 2026-10-17 | Number hints with the number row on: No digit hints and no digit popup entries on the top letter row. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR4-6 | 2026-10-17 | Hints on the other letters: "Symbol hints on letters" (on by default): each letter key's hint is the symbol at the same row and column of symbols page 1, composed the same way (F → $, A → !, Z → -). It is the corner hint and the first popup entry. With the number row on, the top row maps to + × ÷ = / _ < > [ ]. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR4-7 | 2026-10-17 | Align the three pages: Row 3 on letters, symbols 1 and symbols 2: 1.5u shift or page key, 7 keys, 1.5u ⌫. Page 1 drops + (it is in the number-row extra row and the - popup); page 2 drops ®. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR4-8 | 2026-10-17 | Suggestions for a missed typo: Tapping into a word that isn't in the dictionary shows corrections in the strip; tapping one replaces the word. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |
| FR4-9 | 2026-10-17 | Back to letters after symbols: Space typed on a symbols page right after a symbol returns to letters. After a digit it stays. | feature/keyboard-ui (editing, panels, layout), core/emoji, core/clipboard |

## Swipe typing (Phase 5)

Source: archive/PHASE5.md decisions.

| Id | Date | Decision | Where |
|---|---|---|---|
| P5-1 | 2026-10-16 | Spacing between swiped words: Auto-space (user, 2026-10-16). A swiped word is committed without a trailing space; the next swiped or typed word adds one first. Punctuation attaches to the word ("hello."). | core/decoder (gesture engine), feature/keyboard-ui (trail, commit) |
| P5-2 | 2026-10-16 | Backspace right after a swipe: Deletes the whole swiped word (user). After that, backspace deletes characters as usual. | core/decoder (gesture engine), feature/keyboard-ui (trail, commit) |
| P5-3 | 2026-10-16 | Swipe eval data: Synthetic only (user). Noisy gestures generated from the test sentences; no download. Real-world accuracy is judged on the device. | core/decoder (gesture engine), feature/keyboard-ui (trail, commit) |
| P5-4 | 2026-10-16 | Where the live preview shows: In the strip's centre slot (user). Nothing covers the keys. | core/decoder (gesture engine), feature/keyboard-ui (trail, commit) |
| P5-5 | 2026-10-16 | Where swipe is on: Decided (user, 2026-10-16): on in text, email, URL and name fields; off in password, numeric, phone, date and terminal fields, and where the app asks for no suggestions. | core/decoder (gesture engine), feature/keyboard-ui (trail, commit) |
| P5-6 | 2026-10-16 | Settings: Decided (user): Gesture typing on/off (on), Show trail (on). Nothing else until daily use asks for it. | core/decoder (gesture engine), feature/keyboard-ui (trail, commit) |
| P5-7 | 2026-10-16 | Learning: Decided (user): a swiped word is learned like a picked suggestion (n-grams, keep or replace via the strip). Gestures add no touch-offset statistics. | core/decoder (gesture engine), feature/keyboard-ui (trail, commit) |
| P5-8 | 2026-10-16 | Delivery: Decided (user): two PRs. A: engine API + decoder + eval (pure JVM). B: keyboard integration (touch, trail, preview, commit, settings, learning), device test. | core/decoder (gesture engine), feature/keyboard-ui (trail, commit) |

## Voice typing (Phase 6)

Source: archive/PHASE6.md decisions.

| Id | Date | Decision | Where |
|---|---|---|---|
| P6-1 | 2026-10-18 | Getting the model onto the phone (no INTERNET permission, D3): A separate voice-pack APK (dev.keycorrect.voice) holds the model(s). The keyboard reads them from the installed pack: package context and assets, with a <queries> entry for package visibility. Both APKs are signed with the same release key and published on the same releases page. The keyboard APK stays small. | core/voice, feature/voice-sherpa, voicepack, app voice/ |
| P6-2 | 2026-10-18 | Runtime and downloads: sherpa-onnx (Apache-2.0) for on-device models. Its Android native libraries and the candidate models are downloaded from the official releases, with checksums recorded like the dictionary sources. Only what ships is committed or packed. | core/voice, feature/voice-sherpa, voicepack, app voice/ |
| P6-3 | 2026-10-18 | Mic button: Long-press the comma, as on Samsung, with a small mic icon on the comma key as its hint. Not offered in password fields. | core/voice, feature/voice-sherpa, voicepack, app voice/ |
| P6-4 | 2026-10-18 | Delivery: One PR. | core/voice, feature/voice-sherpa, voicepack, app voice/ |
| P6-5 | 2026-10-18 | Phone recognizer: Never a fallback. The engine setting is BuiltIn (default: the voice pack only) or PhoneRecognizer (an explicit opt-in; the settings text says the phone's recognizer may send audio to its provider's servers). | core/voice, feature/voice-sherpa, voicepack, app voice/ |

## Neural and plugin engines (Phase 7)

Source: archive/PHASE7.md decisions.

| Id | Date | Decision | Where |
|---|---|---|---|
| P7-1 | 2026-10-18 | Getting the model onto the phone (no INTERNET, D3): A neural-pack APK plus file import. An optional dev.keycorrect.neural APK holds the default model and the llama.cpp runtime. It is signature-checked like the voice pack. Settings → Engines → "Import model" also accepts any GGUF the user picks (SAF). | neuralpack, core/llama, core/engine-sdk, app neural/, core/decoder rerank/ |
| P7-2 | 2026-10-18 | Downloads: Approved: llama.cpp (MIT) source at a pinned release, built with the NDK; candidate models from their official repos (SmolLM2-135M and 360M, Qwen3-0.6B, and the small Gemma models for testing). There is also a research step for newer small models released since the project began. | neuralpack, core/llama, core/engine-sdk, app neural/, core/decoder rerank/ |
| P7-3 | 2026-10-18 | Default: On when the pack is installed: the reranker is on automatically and phrase completion is off. Both switches and the LM-strength slider are in Settings → Engines. | neuralpack, core/llama, core/engine-sdk, app neural/, core/decoder rerank/ |
| P7-4 | 2026-10-18 | Delivery: One PR. | neuralpack, core/llama, core/engine-sdk, app neural/, core/decoder rerank/ |

## UI/UX customization (Phase 8)

Source: archive/PHASE8.md decisions.

| Id | Date | Decision | Where |
|---|---|---|---|
| P8-1 | 2026-11-06 | Scope: The keyboard-fit report and the look (theme and keys). One-handed, floating and split modes, toolbar ordering, strip options and per-event haptics or sounds move to a later phase. | core/settings (profiles), core/designsystem (theme), feature/keyboard-ui |
| P8-2 | 2026-11-06 | Profiles: Automatic size profiles. Size settings are remembered separately per device class (phone, tablet) and orientation (portrait, landscape), and the current one is used automatically. The look stays shared by all profiles. | core/settings (profiles), core/designsystem (theme), feature/keyboard-ui |
| P8-3 | 2026-11-06 | Delivery: One PR and test build per part: 8a profiles, 8b the fit report, 8c the look. | core/settings (profiles), core/designsystem (theme), feature/keyboard-ui |
| P8-4 | 2026-11-06 | Colors: Wallpaper colors (Material You) are the default. The options are presets (today's look, Samsung-like light and dark, high contrast) and a custom editor for background, keys, modifier keys, text and accent, each set separately for light and dark. | core/settings (profiles), core/designsystem (theme), feature/keyboard-ui |
| P8-5 | 2026-11-06 | Keys: Essentials: a corner-radius slider (square to pill), a border on/off switch, label size and bold, hint labels on/off, the key preview popup on/off, and the press-highlight style. One style covers all keys, plus an accent color for Enter. | core/settings (profiles), core/designsystem (theme), feature/keyboard-ui |
| P8-6 | 2026-11-06 | Fit report: Preview, then Apply. The report shows a heat map and a suggestion (for example "keys 8% taller"). The suggestion comes with an estimate, from replaying past touches against the proposed geometry, of how many fewer words would have been mistyped. The user taps Apply and can undo. | core/settings (profiles), core/designsystem (theme), feature/keyboard-ui |

## Hardening (Phase 9)

Source: archive/PHASE9.md decisions.

| Id | Date | Decision | Where |
|---|---|---|---|
| P9-1 | 2026-11-06 | Lock screen before the first unlock (direct boot): No. The keyboard stays credential-encrypted only (no directBootAware). Android uses its fallback keyboard until the first unlock. | app manifests, ManifestSecurityTest, feature/keyboard-ui (hardware keyboard), baselineprofile |
| P9-2 | 2026-11-06 | Password fields: No FLAG_SECURE, so screenshots and recordings work as now. Key views in password fields are marked accessibilityDataSensitive (API 34+), so accessibility services that aren't screen readers can't read keystrokes. The existing protections stay: no suggestions, learning, engines or key preview, and the mic is hidden. | app manifests, ManifestSecurityTest, feature/keyboard-ui (hardware keyboard), baselineprofile |
| P9-3 | 2026-11-06 | Performance on the S23 Ultra: Allowed only when the user says so. Ask right before each run. A run installs the benchmark and test APKs, runs, then uninstalls them. Never touch the user's KeyCorrect install or its data. | app manifests, ManifestSecurityTest, feature/keyboard-ui (hardware keyboard), baselineprofile |
| P9-4 | 2026-11-06 | Tab S9 hardware-keyboard mode: Build it now and verify it on a tablet emulator with a hardware keyboard. The device pass comes when the Tab S9 is back. | app manifests, ManifestSecurityTest, feature/keyboard-ui (hardware keyboard), baselineprofile |
| P9-5 | 2026-11-06 | Delivery: One PR per part, each opened against main. | app manifests, ManifestSecurityTest, feature/keyboard-ui (hardware keyboard), baselineprofile |

## Backup and transfer

Source: TRANSFER.md decisions.

| Id | Date | Decision | Where |
|---|---|---|---|
| T-1 | 2026-10-18 | Protection: Optional passphrase. You can export a plain file or an encrypted one. Encrypted: AES-256-GCM with a key derived from the passphrase (PBKDF2-HMAC-SHA256, random salt, high iteration count). A wrong passphrase fails cleanly. | core/transfer, app transfer/ |
| T-2 | 2026-10-18 | Contents: Everything: settings; the personal dictionary (added, learned and blocked words) and text shortcuts; learned typing data (n-grams / phrase learning, correction memory, voice substitution pairs, emoji recents); touch and key-position data. | core/transfer, app transfer/ |
| T-3 | 2026-10-18 | Import into a keyboard that already has data: Ask each time: *Merge* adds counts, unions words and shortcuts, and keeps this device's data. *Replace* wipes this device's learned data and settings first. | core/transfer, app transfer/ |
| T-4 | 2026-10-18 | The exporting device's screen differs from the importing one: Warn, and skip touch and key-position data by default. An "import anyway" box brings it in. The same rule applies to the size settings tied to the screen (key height, padding, gap, bottom-row widths). | core/transfer, app transfer/ |
## Cleanup audit (2026-11-15)

Source: user decisions during the 2026-11 cleanup audit.

| Id | Date | Decision | Where |
|---|---|---|---|
| CA-1 | 2026-11-15 | The recent-sentence buffer stays as it is (no change from the cleanup audit). | core/learning, core/data |
| CA-2 | 2026-11-15 | The unused voice helpers `SubstitutionLearner` and `HotwordPicker` are kept for a later voice-learning feature; do not delete them as dead code. | core/voice, feature/voice-sherpa |

## After Phase 9 (2026-11-19)

Source: user decision after the cleanup audit merged (#29).

| Id | Date | Decision | Where |
|---|---|---|---|
| N-1 | 2026-11-19 | Next stages, in order: close out Phase 9 with an on-device test (the §14 budgets on the S23 Ultra, plus the security follow-ups), then Phase 10 neural swipe. On-device LoRA finetuning is a long-term stretch goal. | docs/PERF.md, docs/SECURITY.md, Phase 10 |
| N-2 | 2026-11-19 | APK size: about 16 MB is acceptable when the size buys performance. The dictionary stays stored uncompressed (memory-mapped from the APK: no decompression, no second copy), and the published test APK drops the emulator-only x86_64 libraries (`release.sh` builds with `-Pkeycorrect.abis=arm64-v8a`); local, CI and benchmark builds keep x86_64. Supersedes the §14 "≤15 MB" APK budget. | app, tools/release |
| N-3 | 2026-11-19 | Neural swipe model: FUTO Swipe's encoder (`model_int8.pte`, ExecuTorch) first, shipped only in a separately installed pack under the FUTO Model Weights License (visible "Powered by FUTO Swipe" notice, licence copy with every redistribution). An encoder of our own, trained on the MIT swipe.futo.org data (10b), replaces it once it matches on the real-swipe eval, which drops the attribution. | docs/PHASE10.md, :swipepack |
| N-4 | 2026-11-19 | Integration: fuse the encoder's emissions (decoded against our dictionary and LM) with SHARK²'s candidates by a score tuned on the dev split. SHARK² stays the decoder without the pack. The fusion is kept only if it beats SHARK² on the real test split. | docs/PHASE10.md, core/decoder |
| N-5 | 2026-11-19 | Phase 11, simplification: remove as many lines as possible with no feature or performance loss, and justify every line of app code. Baseline: about 50.5k lines of app Kotlin and 40.2k lines of tests. It gets its own plan when it starts. | docs/PLAN.md §17 |
| N-6 | 2026-11-20 | ExecuTorch runtime built from source at v1.5.1 (the version `model_int8.pte` was exported with), with the NDK for arm64-v8a and x86_64 and on the host for tools/eval: selective op list plus XNNPACK, statically linked into one small JNI library of ours (`libkcswipenet.so`). Not the prebuilt Maven AAR (9-12 MB per ABI, fbjni/SoLoader, no bool tensors in its Java API, Android-only). | core/swipenet, tools/swipenet, :swipepack |
| N-7 | 2026-11-20 | From FUTO's GPL-3.0 swipe-library, port only the input resampler; the beam search stays ours (N-4). Attribution in THIRD_PARTY_NOTICES.md. | core/swipenet (`SwipeResampler`) |
| N-8 | 2026-11-20 | Golden outputs for the 10a-2 exit test come from the official `executorch==1.5.1` pip wheel, run once in a scratch venv outside the repo; only the small golden file is committed. | tools/swipenet/make-goldens.py |
| N-9 | 2026-11-20 | Phase 10 parts may be developed ahead on local branches while the previous PR awaits merge, then rebased onto main before their own PR (PRs are still never stacked). | docs/DEVELOPMENT.md workflow |
| N-10 | 2026-11-20 | Swipe learning becomes part 10a-5, after the fusion decoder: misdecode memory from clear signals only (an immediate strip pick, or a quick delete and re-swipe to an earlier candidate; changes of mind are ignored), plus a per-user path calibration from accepted swipes. The encoder's weights stay fixed on the device. Each piece is kept only if the eval shows a gain without new wrong commits. | docs/PHASE10.md, core/learning |
| N-11 | 2026-11-20 | The encoder's intention gate (lambda) stays as a tunable setting, default 0 (off), because every gate > 0 lost accuracy on dev. | `NeuralSwipeConfig.gate`, docs/EVAL.md |
| N-12 | 2026-11-20 | The swipe pack loads lazily on the first swipe. A missing pack is looked for again at most every 60 s on a swipe; any other failure disables neural swipe until the process restarts. | `SwipeNetHost`, docs/PHASE10.md |
| N-14 | 2026-11-22 | Suggestion text: two settings (Theme → Suggestions), text size (default 14 sp, 10-24) and minimum size (default 12 sp, 8-20). A word that doesn't fit shrinks continuously to the minimum, and only then loses its start ("…puting"), so completions that share a prefix stay distinguishable. Thin dividers between the slots, always on. | `FitText.kt`, `KeyboardSettings.suggestionTextSize` |
| N-15 | 2026-11-22 | While the suggestion strip shows suggestions, the tool icons collapse into one chevron button at the left edge so the three slots get nearly the full width; tapping it shows the tools in place of the suggestions until the next strip change, a tool tap, a new field or a second tap. An idle strip keeps today's layout. | `StripTool.kt` (`stripToolsMode`), `KeyboardController.toolsRevealed` |
| N-16 | 2026-11-22 | The tools reveal (N-15) also resets when the never-suggest prompt opens or closes and when the selection strip shows or hides, and the chevron is not offered while the never-suggest prompt is up, so Yes/Cancel are never replaced. The minimum suggestion size slider tops out at the current text size, and the chevron announces collapsed or expanded to TalkBack. | `KeyboardController.toolsRevealed`, `suggestionsShown` |
