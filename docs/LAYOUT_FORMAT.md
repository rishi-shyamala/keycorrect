# Keyboard layout format (v1)

Layouts are JSON files. The built-in ones live in `core/layout/src/main/resources/layouts/<id>.json`. Users can later import or edit their own in the layout editor (Phase 8). The parser accepts `//` comments and trailing commas, so files are easy to edit by hand.

## File
```jsonc
{
  "format": 1,                 // schema version (required)
  "id": "qwerty",              // unique id (required, [a-z0-9_]+)
  "name": "QWERTY",            // display name (required)
  "kind": "letters",           // letters | symbols | numpad | phone | datetime | bottom | extra
  "bottomRow": "auto",         // auto (default for letters/symbols): a bottom row is appended at runtime
                               // included (default for numpad/phone/datetime/bottom): rows are complete
  "rows": [ /* Row */ ],
  "numberRowExtra": { "insertAt": 1, "row": [ /* Key | "shorthand" */ ] }   // optional, see below
}
```

`numberRowExtra` (optional, not on `bottom`/`extra` layouts): a row the composer inserts at row index `insertAt` (0..number of rows) only when the "number row" setting is on. The symbol pages use it so every layer has as many rows as the letters layer: page 1 inserts `+ × ÷ = / _ < > [ ]` under its digits (`insertAt: 1`), page 2 a digits row on top (`insertAt: 0`). `row` takes the same forms as a normal row (bare array or `{ "height", "keys" }`).

## Row
```jsonc
{ "height": 1.0, "keys": [ /* Key | "shorthand" */ ] }
```
- `height` is relative to the other rows (default 1.0).
- A row can be written as a bare array of keys: `["q","w","e"]`.
- Rows narrower than the widest row are centred.

## Key
A plain string is shorthand for a character key: `"q"` means `{ "label": "q" }`.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `type` | string | `char` | `char` · `text` · `shift` · `backspace` · `space` · `enter` · `layer` · `modeSwitch` · `spacer` · `keycode` · `modifier` · `emoji` · `fieldMode` |
| `label` | string | per type | Text drawn on the key. Required for `char`/`text` unless `text` is set, and for `layer`. Ignored for `modeSwitch` |
| `text` | string | `label` | What gets typed (`char`/`text`) |
| `shifted` | string | upper-cased `text` | What gets typed while shift is on (`char` only). Labels follow it |
| `popup` | array of key/string | `[]` | Long-press alternatives, in display order (`char`/`text`/`keycode` keys, and `modeSwitch` keys with `fieldMode` entries only; setting it on any other type is an error). See "Popup entries" |
| `hint` | string or `null` | first `popup` entry | Small corner label. `null` hides it |
| `width` | number | `1.0` (`space` 5.0, `shift`/`backspace`/`enter`/`layer`/`modeSwitch` 1.5) | Width in standard-key units |
| `target` | string | — | Layer id to switch to (`layer` only, required) |
| `code` | string | — | Key to send (`keycode` only, required): `escape` · `tab` · `left` · `right` · `up` · `down` · `home` · `end` · `pageUp` · `pageDown` · `forwardDelete` |
| `modifier` | string | — | `ctrl` or `alt` (`modifier` only, required) |
| `mode` | string | — | `text` · `email` · `url` · `number` · `phone` (`fieldMode` only, required) |
| `style` | string | per type | `character` · `modifier` · `space` · `action` (visual class) |
| `description` | string | per type | Accessibility label (TalkBack) |

Setting `text`, `shifted`, `target`, `code` or `modifier` on a type that doesn't use it is an error. Default `description` is the label, `Shift`, the code's name (`keycode`: `Escape`, `Tab`, `Left`, ..., `Page up`), `Control`/`Alt` (`modifier`), `Emoji`, `Delete`, `Space`, `Enter`, `Switch to <target>` (`layer`), or `Switch to symbols`/`Switch to letters` (`modeSwitch`). A single cased letter gets an upper-case `shifted` by default; anything else is unchanged.

Popup entries are keys too (a bare string works), but the popup panel draws them as fixed-size cells. So an entry may set `label`, `text`, `shifted`, `code`, `description` and `type` (`char`/`text`/`keycode`/`fieldMode`), and nothing else: nested `popup`, `hint`, `width` and `style` on an entry are errors (for example `rows[0].keys[3].popup[1].width`).

Type semantics:
- `char`: types `text` (or `shifted` while shift is on) and consumes a one-shot shift.
- `text`: types a multi-character string such as `.com` as-is. Shift doesn't change it.
- `shift`, `backspace`, `space`, `enter`: the usual. Enter adapts to the field's IME action at runtime.
- `layer`: switches to `target`, which is a layer id from the keyboard set (below).
- `modeSwitch`: only used in bottom rows. It is resolved at runtime to `?123 → symbols` when on the letters layer, and `ABC → letters` otherwise.
- `fieldMode`: only as an entry in the popup of a `modeSwitch` key (and a `modeSwitch` popup holds nothing else). Tapping it is `KeyAction.SetFieldMode(mode)`: the keyboard becomes that field kind's (layout, bottom row, suggestion and autocorrect rules) until the field changes; `text` goes back. Default labels `Abc`, `@`, `/`, `123`, `☎`; descriptions `Text keyboard`, `Email keyboard`, ... The built-in bottom rows offer all five on `modeSwitch` (long-press `?123`) with `"hint": null`. The composer keeps the popup only with `ComposeOptions.fieldModes`, which the keyboard sets in plain text fields (`FieldKind.Text`); elsewhere the popup is dropped. With `fieldModes` on, the number and phone pads get a thin full-width `ABC` key under them (tap: text; long-press: the same popup), since they have no mode-switch key.
- `keycode`: sends a raw key event (down and up) with the named Android key code, not text. The default label is `Esc`, `Tab`, `←`, `→`, `↑`, `↓`, `Home`, `End`, `PgUp`, `PgDn` or `Del`. Style `modifier`, width 1.0.
- `modifier`: Ctrl or Alt. A tap applies it to the next key (one-shot); a second tap within 400 ms locks it; the next tap unlocks. While it is on, a letter, digit, punctuation key, Space, Enter, Backspace or `keycode` key is sent as a key event with the Ctrl/Alt meta state (terminal fields only). The key is drawn highlighted when on, and differently when locked. Style `modifier`, width 1.0.
- `emoji`: opens the emoji panel (a long-press opens the clipboard). Parsed now, acted on from PR B. Style `modifier`, width 1.0.
- `spacer`: an empty, non-interactive gap of `width` units. Touches there go to the nearest real key.

## Number-field options (`numpad` layouts)
`ComposeOptions(signedNumber, decimalNumber)` mirror the field's `TYPE_NUMBER_FLAG_SIGNED` / `TYPE_NUMBER_FLAG_DECIMAL` (both default to true). When composing a `numpad` layout, a key that types `-` is replaced by a spacer of the same width if `signedNumber` is false. Keys that type `.` or `,` are replaced likewise if `decimalNumber` is false. Both are treated as decimal separators: the numpad offers both so one layout serves every locale, and number fields have no thousands-grouping character. Spacers keep the geometry stable and touches fall through to the nearest real key. Other layout kinds ignore these options. Only top-level keys are affected, not popup entries.

## Keyboard set (built-in, Phase 1)
The set maps layer ids to layout ids:
`letters → qwerty`, `symbols → symbols`, `symbols2 → symbols_more`, `numpad → numpad`, `phone → phone`, `datetime → datetime`.

## Bottom rows
Layouts with `"bottomRow": "auto"` get a bottom row appended at runtime, chosen by field type:

| Variant | Layout id |
|---|---|
| Normal text | `bottom_text` |
| Email address | `bottom_email` |
| URL | `bottom_url` |

Composer sizing (all layers except `numpad`/`phone`/`datetime`):
- One key unit `u = width / max(10, widest row)` is shared by letters, symbols and symbols2, so a standard key is the same size on every page. Numeric pads keep `width / widest row`. Narrower rows are centred, never stretched. Row 3 of both symbols pages has the same shape (1u layer key, eight 1u character keys, 1u backspace) so the controls do not move when switching pages, and both get the same appended bottom row.
- Keys with style `modifier` or `action` (shift, backspace, layer, enter, keycode, modifier) are capped at 1.5u.
- In the bottom row (the row with the space key) every key except space gets `ComposeOptions.bottomKeyWidth` units (default 0.75; Enter at least 1.25) and space takes the rest so the row is 10u. If that would leave space under 1u, the side keys shrink to leave exactly 1u. Widths written in `bottom_*.json` are placeholders. Order: `?123/ABC, emoji, comma (@ for email, / for URL), space, period, enter`.

An optional number row (setting) is prepended to the `letters` layer; the symbol layers get their `numberRowExtra` row instead.

## Terminal row
The `extra` kind holds keys that are composed above the rows of every layer. The built-in `terminal_row` layout (Esc, Tab, Ctrl, Alt, ←, ↓, ↑, →, with Home, PgDn, PgUp and End on the long-press popups of the arrows) is added when `ComposeOptions.terminalRow` is set, which the keyboard does for terminal fields (`TYPE_NULL`). It goes above the number row and above every layer, so it stays when the layer changes, and its height counts in the keyboard's height units. `extra` layouts default to `"bottomRow": "included"` and are not layers of the keyboard set.
