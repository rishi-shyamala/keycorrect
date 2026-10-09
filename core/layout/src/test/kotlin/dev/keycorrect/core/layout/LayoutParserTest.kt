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
        val msg = errorOf(layout("""[["a",{"label":"b","popup":["x",{"label":"y","width":0}]}]]"""))
        assertThat(msg).contains("rows[0].keys[1].popup[1].width")
        assertThat(errorOf(layout("""[[{"label":"b","popup":[{"type":"shift"}]}]]""")))
            .contains("popup[0]")
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
}
