package dev.keycorrect.core.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BuiltInLayoutsTest {
    private val repository = BuiltInLayouts.repository()
    private val composer = KeyboardComposer(repository)

    private val allIds =
        listOf(
            "qwerty",
            "symbols",
            "symbols_more",
            "numpad",
            "phone",
            "datetime",
            "bottom_text",
            "bottom_email",
            "bottom_url",
        )

    private fun rowUnits(row: ResolvedRow) = row.keys.sumOf { it.width.toDouble() }

    @Test
    fun everyBuiltInLayoutParses() {
        allIds.forEach { id -> assertThat(repository.get(id).id).isEqualTo(id) }
    }

    @Test
    fun letterAndSymbolRowsFitTenUnitsAndBottomRowsAreExactlyTen() {
        listOf("qwerty", "symbols", "symbols_more").forEach { id ->
            repository.get(id).rows("letters").forEach { assertThat(rowUnits(it)).isAtMost(10.0) }
        }
        listOf("bottom_text", "bottom_email", "bottom_url").forEach { id ->
            val rows = repository.get(id).rows("letters")
            assertThat(rows).hasSize(1)
            assertThat(rowUnits(rows[0])).isEqualTo(10.0)
        }
    }

    @Test
    fun composeEveryLayerVariantAndOption() {
        for (layerId in KeyboardSet.Default.layouts.keys) {
            for (variant in BottomRowVariant.entries) {
                for (numberRow in listOf(false, true)) {
                    val layer =
                        composer.compose(
                            KeyboardSet.Default,
                            layerId,
                            variant,
                            ComposeOptions(numberRow),
                        )
                    assertThat(layer.id).isEqualTo(layerId)
                    val layout = repository.get(KeyboardSet.Default.layoutId(layerId))
                    if (layout.bottomRow == BottomRowMode.Auto) {
                        assertThat(rowUnits(layer.rows.last())).isEqualTo(10.0)
                    }
                    layer.rows.forEach { assertThat(rowUnits(it)).isAtMost(10.0) }
                }
            }
        }
    }

    @Test
    fun autoLayersGetTheVariantBottomRow() {
        fun bottomKeys(variant: BottomRowVariant) =
            composer.compose(KeyboardSet.Default, "letters", variant).rows.last().keys

        assertThat(bottomKeys(BottomRowVariant.Text).map { it.label })
            .containsExactly("?123", ",", "", ".", "↵")
            .inOrder()
        assertThat(bottomKeys(BottomRowVariant.Email).map { it.label })
            .containsExactly("?123", "@", "", ".", "↵")
            .inOrder()
        val url = bottomKeys(BottomRowVariant.Url)
        assertThat(url.map { it.label })
            .containsExactly("?123", "/", "", ".", ".com", "↵")
            .inOrder()
        assertThat(url[4].action).isEqualTo(KeyAction.Text(".com"))
        assertThat(url[4].popup.map { it.label }).contains(".dev")
        assertThat(url[2].width).isEqualTo(3f)
    }

    @Test
    fun includedLayersGetNoBottomRow() {
        for (id in listOf("numpad", "phone", "datetime")) {
            val layer = composer.compose(KeyboardSet.Default, id, BottomRowVariant.Url)
            assertThat(layer.rows).hasSize(4)
            layer.rows.forEach { assertThat(rowUnits(it)).isEqualTo(4.0) }
        }
        val numpad = composer.compose(KeyboardSet.Default, "numpad").rows
        assertThat(numpad[3].keys.map { it.label }).containsExactly("*", "0", ".", "↵").inOrder()
        assertThat(numpad[0].keys.map { it.style })
            .containsExactly(
                KeyStyle.Character,
                KeyStyle.Character,
                KeyStyle.Character,
                KeyStyle.Modifier,
            )
    }

    @Test
    fun phoneHints() {
        val keys = composer.compose(KeyboardSet.Default, "phone").rows.flatMap { it.keys }
        fun hint(label: String) = keys.first { it.label == label }.hint
        assertThat(hint("2")).isEqualTo("ABC")
        assertThat(hint("3")).isEqualTo("DEF")
        assertThat(hint("9")).isEqualTo("WXYZ")
        assertThat(hint("0")).isEqualTo("+")
        assertThat(hint("1")).isNull()
    }

    @Test
    fun modeSwitchPerLayer() {
        val letters = composer.compose(KeyboardSet.Default, "letters").rows.last().keys.first()
        assertThat(letters.label).isEqualTo("?123")
        assertThat(letters.action).isEqualTo(KeyAction.SwitchLayer("symbols"))
        assertThat(letters.description).isEqualTo("Switch to symbols")
        for (id in listOf("symbols", "symbols2")) {
            val key = composer.compose(KeyboardSet.Default, id).rows.last().keys.first()
            assertThat(key.label).isEqualTo("ABC")
            assertThat(key.action).isEqualTo(KeyAction.SwitchLayer("letters"))
            assertThat(key.description).isEqualTo("Switch to letters")
        }
    }

    @Test
    fun numberRowOnlyOnLetters() {
        val plain = composer.compose(KeyboardSet.Default, "letters")
        val withRow =
            composer.compose(
                KeyboardSet.Default,
                "letters",
                options = ComposeOptions(numberRow = true),
            )
        assertThat(withRow.rows).hasSize(plain.rows.size + 1)
        assertThat(withRow.rows.first().keys.map { it.label })
            .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
            .inOrder()
        assertThat(withRow.rows.drop(1)).isEqualTo(plain.rows)
        for (id in listOf("symbols", "symbols2", "numpad", "phone", "datetime")) {
            assertThat(composer.compose(KeyboardSet.Default, id, options = ComposeOptions(true)))
                .isEqualTo(composer.compose(KeyboardSet.Default, id))
        }
    }

    @Test
    fun qwertyContent() {
        val rows = repository.get("qwerty").rows("letters")
        assertThat(rows.map { it.keys.size }).containsExactly(10, 9, 9).inOrder()
        val top = rows[0].keys
        assertThat(top.map { it.label }.joinToString("")).isEqualTo("qwertyuiop")
        assertThat(top.map { it.hint })
            .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
            .inOrder()
        assertThat(top[2].popup.map { it.label })
            .containsExactly("3", "è", "é", "ê", "ë", "ē")
            .inOrder()
        assertThat(rows[2].keys.first().action).isEqualTo(KeyAction.Shift)
        assertThat(rows[2].keys.last().action).isEqualTo(KeyAction.Backspace)
    }

    @Test
    fun symbolsLayerKeysTargetSecondPage() {
        val symbols = repository.get("symbols").rows("symbols")
        val layerKey = symbols[2].keys.first()
        assertThat(layerKey.label).isEqualTo("=\\<")
        assertThat(layerKey.action).isEqualTo(KeyAction.SwitchLayer("symbols2"))
        assertThat(layerKey.width).isEqualTo(1.5f)
        val dollar = symbols[1].keys.first { it.label == "$" }
        assertThat(dollar.popup.map { it.label }).containsExactly("€", "£", "¥", "¢").inOrder()
        val more = repository.get("symbols_more").rows("symbols2")
        assertThat(more[2].keys.first().action).isEqualTo(KeyAction.SwitchLayer("symbols"))
        assertThat(more[2].keys.first().label).isEqualTo("?123")
    }

    @Test
    fun unknownLayerAndLayoutFail() {
        assertThrows(IllegalArgumentException::class.java) {
            composer.compose(KeyboardSet.Default, "nope")
        }
        val msg =
            assertThrows(LayoutParseException::class.java) { repository.get("missing") }.message
        assertThat(msg).contains("missing")
    }

    @Test
    fun loaderRejectsPathTraversal() {
        assertThat(BuiltInLayouts.loader("../layouts/qwerty")).isNull()
    }
}
