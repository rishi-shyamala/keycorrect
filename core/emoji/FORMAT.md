# KCE1 emoji catalogue format

`emoji_en.kce` (in `app/src/main/assets/emoji/`) is compiled by `tools:emojibuild` and read by
`EmojiCatalog.read(InputStream)`. It is UTF-8 text, one record per line (`\n`), fields separated
by a single TAB. No field may contain TAB, CR or LF. Output is deterministic (no timestamps), so
rebuilding from the same sources gives a byte-identical file.

## Records

| first field | fields | meaning |
|---|---|---|
| header (line 1) | `KCE1` `<format version>` `<Unicode version>` `<CLDR version>` | magic; currently version `1`; e.g. `17.0` and `48.2` |
| `G` | `G` `<group name>` | starts an emoji group (Unicode group, picker order) |
| `E` | `E` `<value>` `<name>` `<keywords>` | a base emoji in the current group. `value` is the fully-qualified sequence; `name` the CLDR short name; `keywords` are the CLDR keywords joined with `\|` (may be empty) |
| `V` | `V` `<value>` `<label>` | a variant (skin tone and/or gender) of the preceding `E`; `label` is an accessibility description, e.g. `medium skin tone`, `woman`, `man, light skin tone` |
| `C` | `C` `<group name>` | starts a kaomoji group (after all emoji groups) |
| `K` | `K` `<text>` | a kaomoji in the current kaomoji group |
| `Z` (last line) | `Z` `<base emoji>` `<variants>` `<kaomoji>` | trailer with record counts; a missing trailer or mismatching counts means a truncated file |

```
KCE1	1	17.0	48.2
G	Smileys & Emotion
E	😀	grinning face	cheerful|cheery|face|grin|grinning|happy|laugh|nice|smile|smiling|teeth
...
E	👍	thumbs up	+1|good|hand|like|thumb|up|yes
V	👍🏻	light skin tone
...
C	Happy
K	(^_^)
Z	1752	2192	117
```

A record that is not valid (wrong field count, `E` before `G`, `V` before `E`, unknown tag, data
after `Z`) makes the reader throw `EmojiFormatException`.

## What goes in

- Source: Unicode `emoji-test.txt` (fully-qualified entries only; the "Component" group dropped)
  and CLDR English `annotations` + `annotationsDerived`. Names and keywords come from CLDR
  (`type="tts"` is the name); if an emoji has no CLDR name the emoji-test name is used.
- Order: emoji-test (CLDR) order, groups as in emoji-test.
- Skin tones: a tone form is a variant of its tone-less emoji, in emoji-test order. Mixed-tone
  forms with no tone-less sequence (handshake) join the emoji of the same name.
- Gender: a gender form (`...200D 2642/2640`, or a leading man/woman where a "person" form
  exists, e.g. man health worker) is a variant of the matching person form, followed by its own
  tone forms. Forms with no person form (couples, families, `man`, `woman`) stay grid entries.
- Kaomoji: a hand-written list in `tools/emojibuild/.../Kaomoji.kt` (no third-party list).

## Rebuilding

```
tools/data/fetch-sources.sh        # downloads and verifies data/raw/ (git-ignored)
./gradlew :tools:emojibuild:run    # writes app/src/main/assets/emoji/emoji_en.kce
```

Options: `--emoji-test`, `--annotations`, `--derived`, `--cldr-version`, `--out` (see `--help`).
Missing or unparseable inputs fail with a message naming the file (and line, for emoji-test).
