# KeyCorrect — Phase 4 plan: editing, clipboard, emoji

Scope from `docs/PLAN.md` §8 (terminal row), §9, §10 and §11. Exit criterion (§17): the feature
checklist below is done, and instrumented tests show no password-field leaks.

## Decisions (user, 2026-10-11)

| # | Decision | Choice |
|---|---|---|
| P4-1 | How the panels are reached | **Keys only, no toolbar.** An emoji key opens the emoji panel, and its long-press opens the clipboard. A long-press on space opens the editing panel. |
| P4-2 | Emoji key placement | **A new key left of space** in the text, email and URL bottom rows; space gets one unit narrower. It's absent from number, phone and date pads and from password fields. |
| P4-3 | Space long-press | A hold released **without moving** opens the editing panel. A hold **followed by a drag** selects from the cursor. A quick drag with no hold moves the cursor. |
| P4-4 | Clipboard history | **On by default, text and images** (20 MB image cap), with the §9 rules: 30 items, 7-day TTL, pinned items kept, encrypted, off in incognito. |
| P4-5 | Emoji in the suggestion strip | **On.** The right-hand slot shows an emoji only for a strong match (an exact CLDR keyword). A setting turns it off. |
| P4-6 | Delivery | **Three PRs**: A editing + terminal row, B emoji, C clipboard. Each gets a device test, the performance check and a review. B comes before C so the new emoji key works from the start: its tap opens the emoji panel. |

Technical defaults (no objection raised):
- **Moves:** cursor and line moves send arrow key events, which work in every editor and terminal
  and leave graphemes and bidi to the editor. Selections use `setSelection`.
- **Undo/redo:** Ctrl+Z / Ctrl+Shift+Z key events. There is no undo stack of our own until a real
  app needs one.
- **No EmojiCompat** (~10 MB). Emoji the device font can't draw (`Paint.hasGlyph`) are filtered.

## Shared foundation (PR A)

- **Panels.**
  - `KeyboardController` gets a `panel` state: `Keys | Editing | Emoji | Clipboard`.
  - `KeyboardScreen` draws the panel in place of the key surface, at the same height, so nothing
    jumps.
  - Each panel has an ABC key back to the letters.
  - Starting input in a new field closes the panel.
- **Editor operations** (`TextEditor` / `EditorSession`):
  - `setSelection(start, end)`;
  - `sendKey(keyCode, metaState)` (down and up);
  - `contextMenu(Cut | Copy | Paste | SelectAll)` (`performContextMenuAction`);
  - `selectedText(maxChars)`.
  - The known selection already comes from `onUpdateSelection` (`EditorSelection`).
  - In sensitive fields nothing is read, as today: any operation that needs the field's text is
    unavailable, and key events and context-menu actions still work.
- **Our own cursor moves.**
  - The controller finishes the composing word as typed (`finishComposingText`) before moving.
  - The editor's selection update then reaches `PredictiveInput.onExternalChange`, the same path
    as a tap elsewhere in the text, so composing, held reverts and learning settle as they do
    today.
  - Moves never record learning events beyond what moving away already does.

## PR A — editing and terminal row

1. **Spacebar gestures** (`TouchStateMachine`).
   - A drag past the slop on space becomes a cursor drag. Horizontal steps follow distance, with
     acceleration (faster drag, bigger steps). Vertical steps move lines (up/down arrows; in a
     terminal that's shell history).
   - Hold then drag selects (shift+arrows from the cursor). Hold then release opens the editing
     panel. A light haptic tick per step, which follows the haptics setting.
2. **Backspace swipe left.**
   - Past the slop, each step selects one more whole word back, using the editor's own selection
     as the preview. Release deletes the selection; dragging back to the start cancels it.
   - It needs the text before the cursor, so in sensitive fields it falls back to the plain held
     backspace.
3. **Editing panel.**
   - Arrows, word left/right (Ctrl+arrow), Home/End, and a select toggle (on: moves extend the
     selection).
   - Select word/sentence/line/all; word, sentence and line need the text, so not in sensitive
     fields.
   - Cut, copy, paste, undo, redo, backspace/delete, and ABC.
   - Labels and accessibility come from strings.xml.
4. **Selection strip.** While a range is selected (not in sensitive fields), the strip offers
   expand to word, expand to sentence, a case cycle (Aa → AA → aa, keeping the selection) and
   copy. The "send to engine" hook exists but stays hidden until Phase 7.
5. **Terminal extra-keys row** (`FieldKind.Terminal`, `TYPE_NULL`).
   - A row above the letters: Esc, Tab, Ctrl, Alt, ←, ↓, ↑, →. Long-press gives Home/End/PgUp/PgDn.
   - Ctrl and Alt are one-shot: a tap applies to the next key, a double tap locks.
   - Letters typed with a modifier are sent as key events with the meta state.
   - Defined in layout JSON, as a `terminal_row` composed above the letter rows for terminal
     fields.
6. **Layout format.**
   - New key actions: `KeyCode(code)`, `Modifier(Ctrl|Alt)` and `Emoji` (used by PR B).
   - New layout kind for the terminal row.
   - `docs/LAYOUT_FORMAT.md` is updated.
7. **Settings → Editing.** Spacebar cursor control (on), cursor speed (slow / normal / fast) and
   backspace swipe deletes words (on).
8. **Field lab** (debug builds only).
   - An activity in our app with text, multi-line, password and `TYPE_NULL` fields; the
     `TYPE_NULL` one is a custom view that logs the key events it receives.
   - It lets the device test cover terminals without installing third-party apps.
9. **Tests and checks.**
   - Unit tests: the gesture state machine (slop, hold/release, hold/drag, acceleration);
     controller with selection and key events in `SimEditor`; backspace swipe; terminal
     modifiers; panel actions; the selection strip.
   - Device test (Tab S9): our app, the field lab, and the local Chrome test page.
   - Performance check, then review.

## PR B — emoji

1. **Data.**
   - A `tools:emojibuild` command fetches Unicode `emoji-test.txt` and CLDR annotations: pinned
     versions, checksums in `tools/data/sources.sha256`, Unicode License v3 recorded in
     `LICENSE-DATA.md`.
   - It compiles a compact committed asset (`emoji.kce`): groups, ordering, skin-tone and gender
     variants, and keywords.
2. **Emoji key** (P4-2). Tap opens the emoji panel; long-press opens clipboard (PR C; until then
   the long-press does nothing).
3. **Panel.**
   - Category tabs, a lazy grid, and recents/frequent.
   - Long-press offers skin tone and gender variants, with the choice remembered per emoji; a
     default skin tone in settings.
   - Search: a search row in the panel, with typing on the letter keys going to the search query
     rather than the app.
   - A kaomoji tab, a backspace key and ABC.
   - Emoji the font can't draw are hidden.
4. **Strip suggestions** (P4-5): exact CLDR keyword → emoji in the right-hand slot. Learning
   events treat it as a normal suggestion.
5. **Storage.** Recents and per-emoji variant choices live in the encrypted DB (schema
   migration). Nothing is recorded in incognito.
6. **Tests and checks.** Asset round-trip tests, search/keyword tests, panel unit tests, device
   test, the performance check (cold panel open and grid scrolling on the device), then review.

## PR C — clipboard

1. **Capture.**
   - `addPrimaryClipChangedListener` while we're the default keyboard. Nothing is captured in
     incognito, during direct boot, or while history is off.
2. **Store** (SQLCipher, schema migration).
   - Everything copied is captured as is (no detection, no masking, no short-lived clips). The
     user deletes what they don't want: per clip, or clear all.
   - Text clips: the last 30, with a 7-day TTL; pinned clips never expire.
   - Images are copied into app-private storage at capture time, with a 20 MB total cap.
3. **Paste.**
   - Text is committed. Images go through `commitContent` and a non-exported `FileProvider` with
     a per-URI grant, if the editor accepts the type. Otherwise the clip is put back on the
     primary clipboard.
4. **UI.**
   - A panel (long-press on the emoji key) with pin, delete and clear all.
   - A strip chip for a clip copied in the last 60 s, shown in any field (password fields too). The panel opens
     from the emoji key's long-press, which doesn't exist in password fields.
   - URL clips offer a "clean tracking parameters" action.
5. **Settings.** On/off, history size, TTL and images.
6. **Tests.**
   - Unit tests for retention (max items, TTL, image budget, pinned kept).
   - **Instrumented tests (the exit criterion):** nothing captured in incognito; stored
     data unreadable by other apps (encrypted DB and image blobs, no exported provider).
   - Device test, the performance check, then review.

## PR A device-test notes (Tab S9, 2026-10-11)

- **EditText needs a real Shift.** Selecting with arrows works only while Shift has really gone
  down (it is tracked in the text); the meta flag alone works in Chrome and terminals but not in
  EditText. `EditorSession.sendKey` therefore holds SHIFT_LEFT around shifted keys.
- **Acceleration was too strong.** It now starts at 20 steps/s, ramps over 40 and caps at 2.5×;
  the user may still want to tune it.
- **Backspace swipe.** Backspace still deletes on press, so a swipe starts one character later. A
  cancelled swipe keeps that one deletion, as a tap would.
- **No axis lock.** Vertical moves need a whole key height of travel, so ordinary wobble doesn't
  move lines.

## PR B notes (2026-10-11)

- **Data.** Unicode 17.0 + CLDR 48.2, matching each other: CLDR 48 annotates exactly the 17.0 set.
  - Unicode 18.0 is out, but its new emoji have no CLDR keywords yet, and the device fonts don't
    draw them.
  - The asset has 1752 base emoji, 2192 skin-tone/gender variants and 117 kaomoji of our own.
- **Strip emoji (user decision).** Tapping it replaces the word, and nothing about that word is
  learned. It shows in the user's chosen variant or default skin tone, like the panel.
- **Tab S9 test.** All checks pass.
  - The tabs moved into the strip area while the panel is open; it was empty there and the grid
    was cramped.
  - Search never types into the app.
- **Default skin tone (user decision).** It stays an ordinary setting in device-protected storage,
  treated like a theme preference. Per-emoji choices and recents stay in the encrypted DB.
- **Cost on the Tab S9.** A cold panel open takes ~3.2 ms (catalog read) plus ~2–3.6 ms (glyph
  filter) in the background. The search index is built in that background load.

## PR C decisions (user, 2026-10-12)

- **Image clips are encrypted.** AES-GCM with a Keystore-backed key, like the database key, and
  decrypted only when pasted, streamed through our non-exported content provider.
- **Clearing the system clipboard after pasting a sensitive clip is off by default.** It is a
  setting. *Superseded by the decision below: there is no sensitive-paste handling.*
- **Simple history (user, 2026-10-12).** No sensitivity detection: no password, card, IBAN,
  token, key or OTP heuristics, no masking, no short-lived or expiring clips, no "sensitive"
  flag, no clear-after-paste. Everything copied is captured as is, and the user deletes what
  they don't want (per clip, clear all). Protection is against other apps: SQLCipher for the
  rows, Keystore-encrypted image blobs, a non-exported provider, and no backup. The keyboard
  still never reads, decodes or learns from password-field text; that is about typing and is
  unrelated to clipboard capture.

- **Reset flows (app wiring).** "Erase personal data" destroys the database, deletes every clip
  image file (`noBackupFilesDir/clips`) and reloads the clipboard memory. "Reset all learning" and
  "Forget recent learning" do **not** touch the clipboard: a clipboard history is not learning. It
  is cleared only by "Settings > Clipboard > Clear clipboard history" (everything, pinned too),
  per clip, or by erasing personal data.
- **Paste of images.** `commitContent` with a per-URI read grant on `ClipImageProvider` (not
  exported, `grantUriPermissions`, authority `<applicationId>.clips`) when the field accepts the
  mime type; otherwise the URI is put on the system clipboard under the label
  `dev.keycorrect.clip`, which our own capture ignores. Text over 20 000 characters is not kept.

## Feedback round 1 (user, 2026-10-16) → PR D

From `user-feedback.md`, after daily use. PR C (clipboard) is finished first, and PR D stacks on it.

| # | Feedback | Plan |
|---|---|---|
| 1 | Keys are a different size in the symbols view (seen on the S23) | **Decision (user, 2026-10-16): one key unit for every layer, plus min/max widths.** `u` is the letter key width (keyboard width / 10), shared by every layer, with the letters' key height and row count (3 rows + bottom). Character keys are 1u. **Function keys** (shift, ⌫, ?123/ABC, 1/2, enter) are capped at **1.5u**. **Bottom-row side keys** (?123, emoji, comma/@//, period, enter) shrink to a **minimum width, default 75% of u** (45% at first; raised to 75% by the user, 2026-10-16, after trying it); **Enter is never narrower than 1.25u** (user), set by a slider in Settings → Appearance (30–150%). Spare width goes to the space bar in the bottom row; other rows are centred, never stretched. **Cause on the S23:** the keyboard height is fixed to the letters layer, so with the number row on, letters had 5 rows and symbols 4 (taller keys). **With the number row on, the symbol pages get Samsung's extra row** (user): page 1 gains `+ × ÷ = / _ < > [ ]` under the digits, page 2 gains a digits row on top, so every layer has the same row count. Numeric pads keep their own grid. |
| 2 | The emoji grid lags when scrolling fast | Measure on the Tab S9 with a **release** build (debug Compose is far slower). Then fix: stable keys and contentType, glyphs drawn from a cached `TextLayoutResult` or bitmap per emoji instead of a `Text` per cell, and nothing recomposed per scroll frame. Add a scroll-jank check to the device test. |
| 3 | Symbols should sit where they are on a regular keyboard | The symbols page 1 top row is digits 1–0, with the shift+digit symbols `! @ # $ % ^ & * ( )` in that order directly beneath (US layout). |
| 4 | Organise symbols like Samsung's, drop the rare ones | **Decision (user, 2026-10-16): match Samsung's *phone* layout; adapt the Tab S9's symbol pages (photographed) into our 3 rows.** Page 1/2: `1–0` / `! @ # $ % ^ & * ( )` / `[1/2] - ' " : ; / ? + [⌫]`, long-press `-`→`_ – —`, `/`→`\`, `'`→`` ` ‘ ’``, `"`→`“ ”`, `?`→`¿`, `!`→`¡`, `$`→`€ £ ¥ ¢`. Page 2/2: `= _ < > [ ] { } \ \|` / `` ` ~ € £ ¥ ¢ ° • × ÷`` / `[2/2] ¡ ¿ « » … § © [⌫]`. Dropped: card suits, `○ ● □ ■ ▪ ☆ ¤ 《 》 ₩ √ π ¶ ∆ ® ™ ✓`. Bottom row on both pages: ABC, emoji, space, comma, period, enter. |
| 5 | Shortcuts to the edit/select panel, emoji and clipboard in the top row | **Decision: Samsung-style shared row.** When not typing, the strip shows shortcut icons (editing panel, emoji, clipboard, settings); while typing, suggestions take that row. Supersedes P4-1's "no toolbar"; the emoji key and the long-press stay. |
| 6 | Options to remove the edge padding and the gaps between keys | **Decision: two sliders**, in Settings → Appearance: edge padding and key gap, each from 0 up to today's size (the default). |
| 7 | Use the left/right side of the spacebar for select / move | **Decision: dragging on the left half selects, dragging on the right half moves the cursor**, with a faint line on the spacebar at the split. Supersedes P4-3's hold+drag select. **Update (user, 2026-10-16): no hold on space for the editing panel**: an edit button at the far left of the strip opens it (tap again: back to the keys), shown with the keys and the editing panel; a held space just types a space; the shortcut row drops its own edit icon. |
| 8 | Bigger, clearer key symbols (tab, enter, shift, …) | Vector icons drawn on the key canvas (shift and its caps-lock form, enter and its action variants, backspace, tab, arrows, emoji, the layer switch), about 1.6× today's glyph size, with consistent stroke weight and contrast. |
| 9 | Word/Line/Sentence in the select panel don't work | **Bug.** `getSurroundingText` reports offset −1 ("unknown") in editors that use the platform default, and those actions then did nothing. Fall back to the tracked cursor when no own edit is pending; at a word end (e.g. after a space), Word selects the previous word. Verify on device in EditText and Chrome. |
| 10 | No swipe typing | **Decision:** Phase 5 (own SHARK² decoder), right after this round. |
| 11 | The emoji header blends into the grid | The tab row gets its own surface colour and a divider, monochrome outline icons (not emoji) for the categories, and a clear selected indicator. |

| 12 | Bottom row like Samsung's (user, 2026-10-16) | **Comma and period move right of the space bar:** `?123, emoji, space, comma, period, enter` (email: `@` and URL: `/` in the comma's place). Side-key widths per #1. |

Also requested: keep the overall layout as close as possible to the Samsung keyboard's **phone** layout (user, 2026-10-16). The Tab S9 shows Samsung's tablet layout (number row, Del, Ctrl, arrows, `!#1`); a tablet-specific layout is out of scope (Phase 8 at the earliest). 



- [x] Space drag moves the cursor (horizontal and vertical); hold + drag selects; hold + release
      opens the editing panel (Tab S9: field lab EditText and Chrome textarea)
- [x] Backspace swipe deletes words, and can be cancelled
- [x] Editing panel: every action works in an EditText and in Chrome (textarea; contenteditable
      checked by unit tests only)
- [x] Selection strip: expand and case cycle
- [x] Terminal row: Esc, Tab, Ctrl/Alt (one-shot and lock), arrows, Home/End/PgUp/PgDn
- [x] Emoji panel: categories, recents, skin tones, search, kaomoji; strip suggestion (Tab S9)
- [ ] Clipboard: capture, pin/delete/clear, images, strip chip, TTL
- [ ] Instrumented: clipboard data unreadable by other apps (encrypted DB and image blobs, no exported provider)

## Feedback round 2 (user, 2026-10-17)

1. **Comma left of the space bar.** Bottom rows are `?123, emoji, comma (@ email, / URL), space, period, enter`; the period keeps its popup (`.com` first for URLs). Width rules unchanged (side keys = bottomKeyWidth, Enter at least 1.25u).
2. **Tool order.** Edit, Emoji (left) | Settings, Clipboard (right, clipboard at the far edge). Idle (spread) order is the same.
3. **Tool timing.** The tools spread out only when the field is empty and nothing else needs the strip. "Empty" comes from the selection updates (cursor past 0 or a range means text). A cursor at 0 counts as empty until text has been seen in the field; after that the editor is asked for one char after the cursor. Sensitive fields are never read (they stay compact once typed in). Limitation: a prefilled field opened with the cursor at 0 counts as empty until the cursor moves. 250 ms animation kept.
4. **Unknown-word haptic.** A triple short buzz (three 12 ms pulses, 60 ms apart), distinct from the key tick and the autocorrect double pulse.
5. **Editing panel.** Joystick layout: Home/Up/End, Left/Select/Right, WordLeft/Down/WordRight, ABC/Backspace/Delete on the left; Word/Sent/Line, Cut/Copy/Paste, Undo/Redo/All on the right after a divider. Arrows are vector icons; D-pad rows are a little taller and the middle column wider.
6. **Selection strip.** Word, Sentence, case and Copy are vector icons with content descriptions.
7. **Suggestion slots.** Widths are proportional to the text (min 5, max 14 chars); the font shrinks to 80% before ellipsizing.
8. **Emoji search button.** A vector magnifier replaces the glyph.

## Feedback round 3 (user, 2026-10-17)

| # | Item | Decision |
|---|---|---|
| A | Editing panel layout | Gboard-style move panel with helper columns (separate change). |
| B | Spacebar joystick | The joystick on the spacebar drags, with rate-based movement beyond the ring (separate change). |
| 1 | Keyboard opens on letters | **The keyboard opens on the field's home layer (letters for text) whenever it is opened for input**: a new field, or the same field shown again after being hidden. The layer, shift and a manual field mode are kept only for the restart that a configuration change (rotation) causes. The service tells these apart with `ConfigRestartTracker`: `onConfigurationChanged` (while the keyboard shows) opens a 1.5 s window in which `onStartInputView` counts as `restarting`; every other start is passed as `restarting = false`, so `EditorIdentity.continues` is false. An app's own `restartInput` on the same field therefore also opens on letters. Limit: if an app recreates its activity on rotation, the field is new and resets. |
| 2 | ABC from a panel | ABC in the emoji, clipboard and editing panels returns to the **home layer** (letters, or the number pad), not the symbols page that was up before. The strip's Edit tool and pasting a clip keep the layer. |
| 3 | Symbols pages | Row 3 of both pages: 1u layer key, eight 1u character keys, 1u backspace (page 2 gains `®` after `©`), so nothing jumps when switching. Bottom rows are the same by construction (tested). |
| 4 | Clip chip icon | The recent-clip chip uses the strip's vector clipboard icon instead of the emoji. |
| 5 | Haptic pulse length | Each pulse of the autocorrect double pulse and the unknown-word triple buzz is **20 ms** (was 12 ms) and set by two sliders in Settings → Feedback (`autocorrectPulseMs`, `unknownWordPulseMs`, 5-60 ms, disabled with their switches). Both are plain waveforms (primitive ticks have no settable length); gaps stay 43 ms and 48 ms. |
| 6 | Edit tool flash | The Edit tool is selected only while the editing panel is open (`editToolSelected`, derived from `panel`). Not reproducible without a device; most likely cause: the tool buttons were focusable `clickable`s, so when the strip content changed on a selection event (selection strip in, focused strip button out) focus moved to the first focusable, the Edit tool, and its focus highlight looked selected. The tools are now not focusable. |
| 7 | Manual layout switch | **Decision (user): long-press ?123 in plain text fields** (`FieldKind.Text`) offers Text, Email (@), URL (/), Number (123), Phone (☎) (`fieldMode` popup entries, `KeyAction.SetFieldMode`). The choice replaces the profile for layout, bottom row and suggestion/autocorrect/learning rules (`FieldProfile.withKind`) until the field changes; it survives a rotation restart. Number and phone get a thin ABC key under the pad to go back. Not offered in fields that declare a kind. |
