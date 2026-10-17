package dev.keycorrect.core.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LayoutParserTest {
    private fun layout(
        rows: String,
        kind: String = "letters",
        extra: String = "",
        format: Int = 1,
    ): String =
        """{ "format": $format, "id": "t", "name": "T", "kind": "$kind", $extra "rows": $rows }"""

    private fun parse(json: String) = LayoutParser.parse(json)

    private fun keys(json: String, kind: String = "letters"): List<ResolvedKey> =
        parse(layout("[[${json}]]", kind)).rows("letters")[0].keys

    private fun key(json: String) = keys(json).single()

    private fun errorOf(json: String): String =
        assertThrows(LayoutParseException::class.java) { parse(json) }.message!!

    // Shorthand and syntax

    @Test
    fun bareStringIsCharKey() {
        val k = key("\"q\"")
        assertThat(k.label).isEqualTo("q")
        assertThat(k.action).isEqualTo(KeyAction.Text("q", "Q"))
    }

    @Test
    fun bareArrayIsRow() {
        val parsed = parse(layout("""[["a","b"], {"height": 2, "keys": ["c"]}]"""))
        val rows = parsed.rows("letters")
        assertThat(rows.map { it.keys.size }).containsExactly(2, 1).inOrder()
        assertThat(rows.map { it.height }).containsExactly(1f, 2f).inOrder()
    }

    @Test
    fun shorthandWorksInsidePopups() {
        val k = key("""{"label":"e","popup":["é","è"]}""")
        assertThat(k.popup.map { it.label }).containsExactly("é", "è").inOrder()
    }

    @Test
    fun commentsAndTrailingCommasAreAccepted() {
        val text =
            """
            // header
            { "format": 1, "id": "t", "name": "T", /* inline */
              "rows": [ ["a", "b",], ], }
            """
        assertThat(parse(text).rows("letters")[0].keys).hasSize(2)
    }

    // Defaults

    @Test
    fun defaultWidthsStylesAndDescriptions() {
        val ks =
            keys(
                """"a",{"type":"shift"},{"type":"backspace"},{"type":"space"},{"type":"enter"},
                {"type":"layer","label":"x","target":"symbols2"},{"type":"spacer"},{"type":"text","label":".com"}"""
            )
        assertThat(ks.map { it.width })
            .containsExactly(1f, 1.5f, 1.5f, 5f, 1.5f, 1.5f, 1f, 1f)
            .inOrder()
        assertThat(ks.map { it.style })
            .containsExactly(
                KeyStyle.Character,
                KeyStyle.Modifier,
                KeyStyle.Modifier,
                KeyStyle.Space,
                KeyStyle.Action,
                KeyStyle.Modifier,
                KeyStyle.Character,
                KeyStyle.Character,
            )
            .inOrder()
        assertThat(ks.map { it.description })
            .containsExactly(
                "a",
                "Shift",
                "Delete",
                "Space",
                "Enter",
                "Switch to symbols2",
                "",
                ".com",
            )
            .inOrder()
        assertThat(ks[2].repeatable).isTrue()
        assertThat(ks[0].repeatable).isFalse()
        assertThat(ks[6].isSpacer).isTrue()
        assertThat(ks[6].action).isEqualTo(KeyAction.None)
    }

    @Test
    fun explicitFieldsOverrideDefaults() {
        val k = key("""{"label":"x","width":2.5,"style":"action","description":"Ex","text":"y"}""")
        assertThat(k.width).isEqualTo(2.5f)
        assertThat(k.style).isEqualTo(KeyStyle.Action)
        assertThat(k.description).isEqualTo("Ex")
        assertThat(k.action).isEqualTo(KeyAction.Text("y", "Y"))
    }

    @Test
    fun hintDefaultsToFirstPopupLabel() {
        assertThat(key("""{"label":"e","popup":["3","è"]}""").hint).isEqualTo("3")
    }

    @Test
    fun explicitHintWinsAndNullHides() {
        assertThat(key("""{"label":"e","hint":"X","popup":["3"]}""").hint).isEqualTo("X")
        assertThat(key("""{"label":"e","hint":null,"popup":["3"]}""").hint).isNull()
    }

    @Test
    fun noPopupMeansNoHint() {
        assertThat(key("\"e\"").hint).isNull()
    }

    @Test
    fun shiftedDefaultsToUppercaseForLetters() {
        val k = key("\"q\"")
        assertThat(k.action).isEqualTo(KeyAction.Text("q", "Q"))
        assertThat(k.shiftedLabel).isEqualTo("Q")
        val accented = key("\"é\"")
        assertThat(accented.action).isEqualTo(KeyAction.Text("é", "É"))
    }

    @Test
    fun shiftedIsNullForNonLettersAndMultiChar() {
        listOf("\"1\"", "\".\"", "\"ß\"", """{"label":"ab"}""", """{"type":"text","label":"a"}""")
            .forEach {
                val k = key(it)
                assertThat((k.action as KeyAction.Text).shiftedText).isNull()
                assertThat(k.shiftedLabel).isNull()
            }
    }

    @Test
    fun explicitShiftedIsUsedForActionAndLabel() {
        val k = key("""{"label":"1","shifted":"!"}""")
        assertThat(k.action).isEqualTo(KeyAction.Text("1", "!"))
        assertThat(k.shiftedLabel).isEqualTo("!")
    }

    @Test
    fun textKeyIgnoresShift() {
        assertThat(key("""{"type":"text","label":".com"}""").action)
            .isEqualTo(KeyAction.Text(".com"))
    }

    @Test
    fun textDefaultsToLabelAndLabelToText() {
        assertThat(key("""{"text":"x"}""").label).isEqualTo("x")
        assertThat(key("""{"label":"A","text":"b"}""").action).isEqualTo(KeyAction.Text("b", "B"))
    }

    // modeSwitch

    @Test
    fun modeSwitchResolvesPerLayer() {
        val bottom =
            parse(
                layout(
                    """[[{"type":"modeSwitch"}]]""",
                    kind = "bottom",
                    extra = """"bottomRow":"included",""",
                )
            )
        val onLetters = bottom.rows("letters")[0].keys[0]
        assertThat(onLetters.label).isEqualTo("?123")
        assertThat(onLetters.action).isEqualTo(KeyAction.SwitchLayer("symbols"))
        assertThat(onLetters.description).isEqualTo("Switch to symbols")
        for (layer in listOf("symbols", "symbols2")) {
            val other = bottom.rows(layer)[0].keys[0]
            assertThat(other.label).isEqualTo("ABC")
            assertThat(other.action).isEqualTo(KeyAction.SwitchLayer("letters"))
            assertThat(other.description).isEqualTo("Switch to letters")
        }
    }

    // numberRowExtra

    @Test
    fun numberRowExtraParses() {
        val parsed =
            parse(
                layout(
                    """[["a"],["b"]]""",
                    kind = "symbols",
                    extra =
                        """"numberRowExtra":{"insertAt":1,"row":["1",{"label":"2","popup":["x"]}]},""",
                )
            )
        val extra = parsed.numberRowExtra!!
        assertThat(extra.insertAt).isEqualTo(1)
        assertThat(extra.row.keys.map { it.label }).containsExactly("1", "2").inOrder()
        assertThat(extra.row.keys[1].popup.single().label).isEqualTo("x")
        assertThat(parse(layout("""[["a"]]""", kind = "symbols")).numberRowExtra).isNull()
    }

    @Test
    fun numberRowExtraIsValidated() {
        fun err(extra: String, kind: String = "symbols") =
            errorOf(layout("""[["a"],["b"]]""", kind = kind, extra = extra))
        assertThat(err(""""numberRowExtra":{"insertAt":3,"row":["1"]},"""))
            .contains("numberRowExtra.insertAt")
        assertThat(err(""""numberRowExtra":{"insertAt":-1,"row":["1"]},"""))
            .contains("numberRowExtra.insertAt")
        assertThat(err(""""numberRowExtra":{"insertAt":0,"row":[]},"""))
            .contains("numberRowExtra.row.keys")
        assertThat(err(""""numberRowExtra":{"insertAt":0,"row":[{"type":"bogus"}]},"""))
            .contains("numberRowExtra.row.keys[0].type")
        assertThat(
                err(
                    """"numberRowExtra":{"insertAt":0,"row":["1"]},"bottomRow":"included",""",
                    "bottom",
                )
            )
            .contains("numberRowExtra")
    }

    // Validation

    @Test
    fun unknownTypeNamesPath() {
        val msg = errorOf(layout("""[["a"],["b","c",{"type":"bogus"}]]"""))
        assertThat(msg).contains("'t'")
        assertThat(msg).contains("rows[1].keys[2]")
        assertThat(msg).contains("bogus")
    }

    @Test
    fun unknownStyleNamesPath() {
        val msg = errorOf(layout("""[["a",{"label":"b","style":"fancy"}]]"""))
        assertThat(msg).contains("rows[0].keys[1]")
        assertThat(msg).contains("fancy")
    }

    @Test
    fun unknownKindNamesPath() {
        val msg = errorOf(layout("""[["a"]]""", kind = "weird"))
        assertThat(msg).contains("kind")
        assertThat(msg).contains("weird")
    }

    @Test
    fun missingLabelNamesPath() {
        val msg = errorOf(layout("""[["a"],["b"],["c",{"type":"char"}]]"""))
        assertThat(msg).contains("rows[2].keys[1]")
        assertThat(msg).contains("label")
        assertThat(errorOf(layout("""[[{"type":"text"}]]"""))).contains("rows[0].keys[0]")
        assertThat(errorOf(layout("""[[{"type":"layer","target":"x"}]]"""))).contains("label")
    }

    @Test
    fun layerWithoutTargetNamesPath() {
        val msg = errorOf(layout("""[["a",{"type":"layer","label":"x"}]]"""))
        assertThat(msg).contains("rows[0].keys[1]")
        assertThat(msg).contains("target")
    }

    @Test
    fun nonPositiveWidthAndHeight() {
        assertThat(errorOf(layout("""[["a",{"label":"b","width":0}]]""")))
            .contains("rows[0].keys[1].width")
        assertThat(errorOf(layout("""[["a",{"label":"b","width":-1}]]"""))).contains("width")
        assertThat(errorOf(layout("""[["a"],{"height":0,"keys":["b"]}]""")))
            .contains("rows[1].height")
        assertThat(errorOf(layout("""[["a"],{"height":-2,"keys":["b"]}]"""))).contains("height")
    }

    @Test
    fun popupErrorsIncludeNestedPath() {
        val msg =
            errorOf(layout("""[["a",{"label":"b","popup":["x",{"label":"y","type":"shift"}]}]]"""))
        assertThat(msg).contains("rows[0].keys[1].popup[1].type")
        assertThat(errorOf(layout("""[[{"label":"b","popup":[{"type":"shift"}]}]]""")))
            .contains("popup[0]")
    }

    @Test
    fun popupOnNonTypedKeysIsRejected() {
        for (type in listOf("shift", "backspace", "space", "enter", "spacer")) {
            val msg = errorOf(layout("""[["a",{"type":"$type","popup":["x"]}]]"""))
            assertThat(msg).contains("rows[0].keys[1].popup")
            assertThat(msg).contains("char/text")
        }
        assertThat(
                errorOf(layout("""[[{"type":"layer","label":"L","target":"x","popup":["x"]}]]"""))
            )
            .contains("keys[0].popup")
        // text keys may have popups
        assertThat(key("""{"type":"text","label":".com","popup":[".net"]}""").popup).hasSize(1)
    }

    @Test
    fun popupEntriesRejectNestedPopupHintWidthStyle() {
        fun entry(field: String) =
            errorOf(layout("""[["a",{"label":"b","popup":["x",{"label":"y",$field}]}]]"""))
        assertThat(entry(""""popup":["z"]""")).contains("rows[0].keys[1].popup[1].popup")
        assertThat(entry(""""hint":"h"""")).contains("rows[0].keys[1].popup[1].hint")
        assertThat(entry(""""hint":null""")).contains("rows[0].keys[1].popup[1].hint")
        assertThat(entry(""""width":2""")).contains("rows[0].keys[1].popup[1].width")
        assertThat(entry(""""style":"modifier"""")).contains("rows[0].keys[1].popup[1].style")
        // description and shifted are still meaningful
        val k = key("""{"label":"b","popup":[{"label":"y","shifted":"Y","description":"why"}]}""")
        assertThat(k.popup.single().description).isEqualTo("why")
    }

    @Test
    fun wrongFormatVersion() {
        val msg = errorOf(layout("""[["a"]]""", format = 2))
        assertThat(msg).contains("format")
        assertThat(msg).contains("2")
        assertThat(errorOf("""{"id":"t","name":"T","rows":[["a"]]}""")).contains("format")
    }

    @Test
    fun emptyRows() {
        assertThat(errorOf(layout("[]"))).contains("rows")
        val msg = errorOf(layout("""[["a"],[]]"""))
        assertThat(msg).contains("rows[1]")
    }

    @Test
    fun modeSwitchOutsideBottomLayout() {
        val msg = errorOf(layout("""[["a",{"type":"modeSwitch"}]]"""))
        assertThat(msg).contains("rows[0].keys[1]")
        assertThat(msg).contains("modeSwitch")
    }

    @Test
    fun misplacedFieldsAreRejected() {
        assertThat(errorOf(layout("""[[{"type":"shift","shifted":"x"}]]"""))).contains("shifted")
        assertThat(errorOf(layout("""[[{"label":"a","target":"x"}]]"""))).contains("target")
    }

    @Test
    fun unknownFieldsAreRejectedWithPath() {
        val msg = errorOf(layout("""[["a",{"label":"b","colour":"red"}]]"""))
        assertThat(msg).contains("'t'")
        assertThat(msg).contains("colour")
    }

    @Test
    fun badIdAndBottomAuto() {
        assertThat(errorOf("""{"format":1,"id":"Bad Id","name":"T","rows":[["a"]]}"""))
            .contains("id")
        val msg = errorOf(layout("""[["a"]]""", kind = "bottom", extra = """"bottomRow":"auto","""))
        assertThat(msg).contains("bottomRow")
    }

    @Test
    fun invalidJsonIsWrapped() {
        assertThat(errorOf("{ nope")).contains("invalid JSON")
        assertThat(errorOf("[]")).contains("object")
    }

    @Test
    fun bottomRowDefaultsFollowKind() {
        val letters = parse(layout("""[["a"]]"""))
        assertThat(letters.bottomRow).isEqualTo(BottomRowMode.Auto)
        val pad = parse(layout("""[["1"]]""", kind = "numpad"))
        assertThat(pad.bottomRow).isEqualTo(BottomRowMode.Included)
    }

    // keycode, modifier, emoji

    @Test
    fun keycodeKeysResolveCodeLabelAndDescription() {
        val k = key("""{"type":"keycode","code":"pageUp"}""")
        assertThat(k.action).isEqualTo(KeyAction.KeyCode(KeyCodes.PAGE_UP))
        assertThat(k.label).isEqualTo("PgUp")
        assertThat(k.description).isEqualTo("Page up")
        assertThat(k.style).isEqualTo(KeyStyle.Modifier)
        assertThat(k.width).isEqualTo(1f)
        val custom =
            key("""{"type":"keycode","code":"escape","label":"Esc","description":"Leave"}""")
        assertThat(custom.action).isEqualTo(KeyAction.KeyCode(KeyCodes.ESCAPE))
        assertThat(custom.description).isEqualTo("Leave")
        val codes =
            mapOf(
                "escape" to KeyCodes.ESCAPE,
                "tab" to KeyCodes.TAB,
                "left" to KeyCodes.DPAD_LEFT,
                "right" to KeyCodes.DPAD_RIGHT,
                "up" to KeyCodes.DPAD_UP,
                "down" to KeyCodes.DPAD_DOWN,
                "home" to KeyCodes.MOVE_HOME,
                "end" to KeyCodes.MOVE_END,
                "pageUp" to KeyCodes.PAGE_UP,
                "pageDown" to KeyCodes.PAGE_DOWN,
                "forwardDelete" to KeyCodes.FORWARD_DEL,
            )
        for ((name, code) in codes) {
            assertThat(key("""{"type":"keycode","code":"$name"}""").action)
                .isEqualTo(KeyAction.KeyCode(code))
        }
    }

    @Test
    fun keycodeErrors() {
        assertThat(errorOf(layout("""[[{"type":"keycode"}]]"""))).contains("keys[0].code")
        val unknown = errorOf(layout("""[["a",{"type":"keycode","code":"f13"}]]"""))
        assertThat(unknown).contains("rows[0].keys[1].code")
        assertThat(unknown).contains("unknown code 'f13'")
        assertThat(errorOf(layout("""[[{"type":"char","label":"a","code":"tab"}]]""")))
            .contains("keys[0].code")
        assertThat(errorOf(layout("""[[{"type":"keycode","code":"tab","text":"x"}]]""")))
            .contains("keys[0].text")
    }

    @Test
    fun modifierKeys() {
        val ctrl = key("""{"type":"modifier","modifier":"ctrl"}""")
        assertThat(ctrl.action).isEqualTo(KeyAction.Modifier(KeyModifier.Ctrl))
        assertThat(ctrl.label).isEqualTo("Ctrl")
        assertThat(ctrl.description).isEqualTo("Control")
        assertThat(ctrl.style).isEqualTo(KeyStyle.Modifier)
        val alt = key("""{"type":"modifier","modifier":"alt","label":"⌥"}""")
        assertThat(alt.action).isEqualTo(KeyAction.Modifier(KeyModifier.Alt))
        assertThat(alt.label).isEqualTo("⌥")
        assertThat(alt.description).isEqualTo("Alt")
    }

    @Test
    fun modifierErrors() {
        assertThat(errorOf(layout("""[[{"type":"modifier"}]]"""))).contains("keys[0].modifier")
        assertThat(errorOf(layout("""[[{"type":"modifier","modifier":"meta"}]]""")))
            .contains("unknown modifier 'meta'")
        assertThat(errorOf(layout("""[[{"type":"shift","modifier":"ctrl"}]]""")))
            .contains("keys[0].modifier")
    }

    @Test
    fun emojiKey() {
        val k = key("""{"type":"emoji"}""")
        assertThat(k.action).isEqualTo(KeyAction.Emoji)
        assertThat(k.description).isEqualTo("Emoji")
    }

    @Test
    fun keycodeKeysTakeKeycodePopups() {
        val k =
            key(
                """{"type":"keycode","code":"left","label":"←",""" +
                    """"popup":[{"type":"keycode","code":"home","label":"Home"}]}"""
            )
        assertThat(k.popup.single().action).isEqualTo(KeyAction.KeyCode(KeyCodes.MOVE_HOME))
        assertThat(k.hint).isEqualTo("Home")
        // Popup entries may not be other types, and modifier/emoji keys take no popups.
        assertThat(
                errorOf(
                    layout("""[[{"type":"keycode","code":"left","popup":[{"type":"modifier"}]}]]""")
                )
            )
            .contains("popup[0].type")
        for (type in listOf("modifier", "emoji")) {
            assertThat(errorOf(layout("""[[{"type":"$type","modifier":"ctrl","popup":["x"]}]]""")))
                .contains("keys[0].popup")
        }
    }

    @Test
    fun extraKindParsesAndDefaultsToIncludedBottomRow() {
        val parsed = parse(layout("""[[{"type":"emoji"}]]""", kind = "extra"))
        assertThat(parsed.kind).isEqualTo(LayoutKind.Extra)
        assertThat(parsed.bottomRow).isEqualTo(BottomRowMode.Included)
    }
}
