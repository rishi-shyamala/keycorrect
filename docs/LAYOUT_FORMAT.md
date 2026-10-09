# Keyboard layout format (v1)

Layouts are JSON files. The built-in ones live in `core/layout/src/main/resources/layouts/<id>.json`. Users can later import or edit their own in the layout editor (Phase 8). The parser accepts `//` comments and trailing commas, so files are easy to edit by hand.

## File
```jsonc
{
  "format": 1,                 // schema version (required)
  "id": "qwerty",              // unique id (required, [a-z0-9_]+)
  "name": "QWERTY",            // display name (required)
  "kind": "letters",           // letters | symbols | numpad | phone | datetime | bottom
  "bottomRow": "auto",         // auto (default for letters/symbols): a bottom row is appended at runtime
                               // included (default for numpad/phone/datetime/bottom): rows are complete
  "rows": [ /* Row */ ]
}
```

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
| `type` | string | `char` | `char` · `text` · `shift` · `backspace` · `space` · `enter` · `layer` · `modeSwitch` · `spacer` |
| `label` | string | per type | Text drawn on the key. Required for `char`/`text` unless `text` is set, and for `layer`. Ignored for `modeSwitch` |
| `text` | string | `label` | What gets typed (`char`/`text`) |
| `shifted` | string | upper-cased `text` | What gets typed while shift is on (`char` only). Labels follow it |
| `popup` | array of key/string | `[]` | Long-press alternatives, in display order (`char`/`text` keys only; setting it on any other type is an error). See "Popup entries" |
| `hint` | string or `null` | first `popup` entry | Small corner label. `null` hides it |
| `width` | number | `1.0` (`space` 5.0, `shift`/`backspace`/`enter`/`layer`/`modeSwitch` 1.5) | Width in standard-key units |
| `target` | string | — | Layer id to switch to (`layer` only, required) |
| `style` | string | per type | `character` · `modifier` · `space` · `action` (visual class) |
| `description` | string | per type | Accessibility label (TalkBack) |

Setting `text`, `shifted` or `target` on a type that doesn't use it is an error. Default `description` is the label, `Shift`, `Delete`, `Space`, `Enter`, `Switch to <target>` (`layer`), or `Switch to symbols`/`Switch to letters` (`modeSwitch`). A single cased letter gets an upper-case `shifted` by default; anything else is unchanged.

Popup entries are keys too (a bare string works), but the popup panel draws them as fixed-size cells. So an entry may set `label`, `text`, `shifted`, `description` and `type` (`char`/`text` only), and nothing else: nested `popup`, `hint`, `width` and `style` on an entry are errors (for example `rows[0].keys[3].popup[1].width`).

Type semantics:
- `char`: types `text` (or `shifted` while shift is on) and consumes a one-shot shift.
- `text`: types a multi-character string such as `.com` as-is. Shift doesn't change it.
- `shift`, `backspace`, `space`, `enter`: the usual. Enter adapts to the field's IME action at runtime.
- `layer`: switches to `target`, which is a layer id from the keyboard set (below).
- `modeSwitch`: only used in bottom rows. It is resolved at runtime to `?123 → symbols` when on the letters layer, and `ABC → letters` otherwise.
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

An optional number row (setting) is prepended to the `letters` layer.
