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

## Feature checklist (exit)

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
