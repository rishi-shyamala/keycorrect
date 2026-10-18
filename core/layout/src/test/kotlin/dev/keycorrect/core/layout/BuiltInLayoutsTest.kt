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
            "terminal_row",
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
                        assertThat(rowUnits(layer.rows.last())).isWithin(1e-4).of(10.0)
                    }
                    layer.rows.forEach { assertThat(rowUnits(it)).isAtMost(10.0) }
                }
            }
        }
    }

    @Test
    fun withoutTheEmojiKeyTheSpaceTakesItsWidth() {
        val withKey = composer.compose(KeyboardSet.Default, "letters").rows.last()
        val without =
            composer
                .compose(KeyboardSet.Default, "letters", options = ComposeOptions(emojiKey = false))
                .rows
                .last()
        assertThat(without.keys.map { it.action }).doesNotContain(KeyAction.Emoji)
        assertThat(without.width).isWithin(1e-4f).of(withKey.width)
        assertThat(without.keys.first { it.action == KeyAction.Space }.width)
            .isWithin(1e-4f)
            .of(10f - 2 * 0.75f - 2 * 1.15f)
    }

    @Test
    fun autoLayersGetTheVariantBottomRow() {
        fun bottomKeys(variant: BottomRowVariant) =
            composer.compose(KeyboardSet.Default, "letters", variant).rows.last().keys

        assertThat(bottomKeys(BottomRowVariant.Text).map { it.label })
            .containsExactly("?123", "☺", ",", "", ".", "↵")
            .inOrder()
        assertThat(bottomKeys(BottomRowVariant.Email).map { it.label })
            .containsExactly("?123", "☺", "@", "", ".", "↵")
            .inOrder()
        val url = bottomKeys(BottomRowVariant.Url)
        assertThat(url.map { it.label }).containsExactly("?123", "☺", "/", "", ".", "↵").inOrder()
        assertThat(url[4].popup.map { it.label }).contains(".com")
        assertThat(bottomKeys(BottomRowVariant.Text)[4].popup.map { it.label }).contains("?")
    }

    private fun bottomRow(
        variant: BottomRowVariant = BottomRowVariant.Text,
        layerId: String = "letters",
        options: ComposeOptions = ComposeOptions(),
    ) = composer.compose(KeyboardSet.Default, layerId, variant, options).rows.last()

    @Test
    fun bottomRowSideKeysUseTheOptionAndSpaceTakesTheRest() {
        for (variant in BottomRowVariant.entries) {
            for (layerId in listOf("letters", "symbols", "symbols2")) {
                val row = bottomRow(variant, layerId)
                row.keys
                    .filter { it.isModeOrEnter().not() && it.action != KeyAction.Space }
                    .forEach { assertThat(it.width).isEqualTo(0.75f) }
                row.keys
                    .filter { it.isModeOrEnter() }
                    .forEach {
                        assertThat(it.width).isEqualTo(1.15f)
                    }
                val space = row.keys.first { it.action == KeyAction.Space }
                assertThat(space.width).isWithin(1e-4f).of(10f - 3 * 0.75f - 2 * 1.15f)
                assertThat(row.width).isWithin(1e-4f).of(10f)
            }
        }
        val wide = bottomRow(options = ComposeOptions(bottomKeyWidth = 1f))
        assertThat(wide.keys.filter { it.action != KeyAction.Space }.map { it.width })
            .containsExactly(1.15f, 1f, 1f, 1f, 1.15f)
            .inOrder()
        assertThat(wide.keys.first { it.action == KeyAction.Space }.width).isWithin(1e-4f).of(4.7f)
    }

    @Test
    fun modeSwitchAndEnterHaveTheirOwnWidth() {
        for (width in listOf(0.75f, 1f, 1.15f, 1.5f)) {
            val row = bottomRow(options = ComposeOptions(modeEnterKeyWidth = width))
            val modeEnter = row.keys.filter { it.isModeOrEnter() }
            assertThat(modeEnter.map { it.label }).containsExactly("?123", "\u21b5").inOrder()
            modeEnter.forEach { assertThat(it.width).isEqualTo(width) }
            row.keys
                .filter { it.action != KeyAction.Space && !it.isModeOrEnter() }
                .forEach { assertThat(it.width).isEqualTo(0.75f) }
            assertThat(row.keys.first { it.action == KeyAction.Space }.width)
                .isWithin(1e-4f)
                .of(10f - 3 * 0.75f - 2 * width)
            assertThat(row.width).isWithin(1e-4f).of(10f)
        }
    }

    private fun ResolvedKey.isModeOrEnter() =
        action == KeyAction.Enter || action is KeyAction.SwitchLayer

    @Test
    fun bottomRowWithoutEmojiStillFillsTenUnits() {
        for (variant in BottomRowVariant.entries) {
            for (width in listOf(0.3f, 0.75f, 1f, 1.5f)) {
                val row =
                    bottomRow(
                        variant,
                        options = ComposeOptions(emojiKey = false, bottomKeyWidth = width),
                    )
                assertThat(row.keys.map { it.action }).doesNotContain(KeyAction.Emoji)
                assertThat(row.keys).hasSize(5)
                assertThat(row.width).isWithin(1e-4f).of(10f)
                assertThat(row.keys.first { it.action == KeyAction.Space }.width)
                    .isWithin(1e-4f)
                    .of(10f - 2 * width - 2 * 1.15f)
            }
        }
    }

    @Test
    fun bottomRowSideKeysShrinkRatherThanSqueezeTheSpaceBelowOneUnit() {
        val row = bottomRow(options = ComposeOptions(bottomKeyWidth = 3f))
        assertThat(row.keys.first { it.action == KeyAction.Space }.width).isWithin(1e-4f).of(1f)
        assertThat(row.width).isWithin(1e-4f).of(10f)
    }

    @Test
    fun bottomKeyWidthIsPartOfTheCacheKey() {
        val a = composer.compose(KeyboardSet.Default, "letters")
        val b =
            composer.compose(
                KeyboardSet.Default,
                "letters",
                options = ComposeOptions(bottomKeyWidth = 1f),
            )
        assertThat(b).isNotEqualTo(a)
        assertThat(composer.compose(KeyboardSet.Default, "letters")).isSameInstanceAs(a)
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
    fun numberRowAddsOneRowToLettersAndSymbolsButNotNumpads() {
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
        // Only the hints and popups of the letter keys differ (see symbol hints).
        assertThat(withRow.rows.drop(1).map { r -> r.keys.map { it.label to it.width } })
            .isEqualTo(plain.rows.map { r -> r.keys.map { it.label to it.width } })
        for (id in listOf("numpad", "phone", "datetime")) {
            assertThat(composer.compose(KeyboardSet.Default, id, options = ComposeOptions(true)))
                .isEqualTo(composer.compose(KeyboardSet.Default, id))
        }
        for (id in listOf("symbols", "symbols2")) {
            val sym = composer.compose(KeyboardSet.Default, id)
            val symRow =
                composer.compose(
                    KeyboardSet.Default,
                    id,
                    options = ComposeOptions(numberRow = true),
                )
            assertThat(symRow.rows).hasSize(sym.rows.size + 1)
        }
    }

    private val letterLayers = listOf("letters", "symbols", "symbols2")

    @Test
    fun letterAndSymbolLayersHaveEqualRowCounts() {
        for (numberRow in listOf(false, true)) {
            for (terminal in listOf(false, true)) {
                val counts = letterLayers.map {
                    composer
                        .compose(
                            KeyboardSet.Default,
                            it,
                            options = ComposeOptions(numberRow = numberRow, terminalRow = terminal),
                        )
                        .rows
                        .size
                }
                assertThat(counts.distinct()).hasSize(1)
                assertThat(counts[0])
                    .isEqualTo(4 + (if (numberRow) 1 else 0) + (if (terminal) 1 else 0))
            }
        }
    }

    @Test
    fun extraRowsLandWhereTheLayoutSays() {
        val opts = ComposeOptions(numberRow = true)
        val p1 = composer.compose(KeyboardSet.Default, "symbols", options = opts).rows
        assertThat(p1[0].keys.map { it.label }.joinToString("")).isEqualTo("1234567890")
        assertThat(p1[1].keys.map { it.label }.joinToString("")).isEqualTo("+×÷=/_<>[]")
        assertThat(p1[2].keys.map { it.label }.joinToString("")).isEqualTo("!@#$%^&*()")
        val p2 = composer.compose(KeyboardSet.Default, "symbols2", options = opts).rows
        assertThat(p2[0].keys.map { it.label }.joinToString("")).isEqualTo("1234567890")
        assertThat(p2[1].keys.map { it.label }.joinToString("")).isEqualTo("=_<>[]{}\\|")
    }

    @Test
    fun allLettersAndSymbolsLayersShareOneKeyUnit() {
        for (numberRow in listOf(false, true)) {
            for (terminal in listOf(false, true)) {
                for (variant in BottomRowVariant.entries) {
                    val units = letterLayers.map {
                        val layer =
                            composer.compose(
                                KeyboardSet.Default,
                                it,
                                variant,
                                ComposeOptions(numberRow = numberRow, terminalRow = terminal),
                            )
                        val g = KeyboardGeometry(layer, 1080f, 600f)
                        val row = layer.rows.first { r -> r.keys.size == 10 }
                        val slot = g.slots.first { s -> s.key === row.keys[0] }
                        slot.right - slot.left
                    }
                    units.forEach { assertThat(it).isWithin(1e-3f).of(108f) }
                }
            }
        }
    }

    @Test
    fun numpadsKeepTheirOwnUnit() {
        val numpad = composer.compose(KeyboardSet.Default, "numpad")
        val g = KeyboardGeometry(numpad, 1000f, 400f)
        val one = g.slots.first { it.key.label == "1" }
        assertThat(one.right - one.left).isWithin(1e-3f).of(250f)
    }

    @Test
    fun functionKeysAreCappedAtOneAndAHalfUnits() {
        val layers = letterLayers.flatMap { id ->
            listOf(false, true).map {
                composer.compose(
                    KeyboardSet.Default,
                    id,
                    options = ComposeOptions(numberRow = it, terminalRow = true),
                )
            }
        }
        for (layer in layers) {
            layer.rows
                .flatMap { it.keys }
                .filter { it.style == KeyStyle.Modifier || it.style == KeyStyle.Action }
                .forEach { assertThat(it.width).isAtMost(1.5f) }
        }
        // The cap is applied by the composer, not only by the files' own widths.
        val wide =
            """{"format":1,"id":"w","name":"W","kind":"letters","rows":[[
            {"type":"shift","width":3},"a",{"type":"enter","width":2},{"type":"space","width":6}]]}"""
        val repo = LayoutRepository { id -> if (id == "w") wide else BuiltInLayouts.loader(id) }
        val capped =
            KeyboardComposer(repo)
                .compose(KeyboardSet(mapOf("letters" to "w")), "letters")
                .rows
                .first()
                .keys
        assertThat(capped.map { it.width }).containsExactly(1.5f, 1f, 1.5f, 6f).inOrder()
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

    private fun labels(row: ResolvedRow) = row.keys.map { it.label }

    @Test
    fun symbolsPageOneContent() {
        val rows = repository.get("symbols").rows("symbols")
        assertThat(rows).hasSize(3)
        assertThat(labels(rows[0]).joinToString("")).isEqualTo("1234567890")
        assertThat(labels(rows[1]).joinToString("")).isEqualTo("!@#$%^&*()")
        assertThat(labels(rows[2]))
            .containsExactly("1/2", "-", "'", "\"", ":", ";", "/", "?", "⌫")
            .inOrder()
        val layerKey = rows[2].keys.first()
        assertThat(layerKey.action).isEqualTo(KeyAction.SwitchLayer("symbols2"))
        assertThat(layerKey.description).isEqualTo("More symbols")
        assertThat(rows[2].keys.last().action).isEqualTo(KeyAction.Backspace)
        assertThat(rows.all { it.width == 10f }).isTrue()
        fun popup(label: String) =
            rows.flatMap { it.keys }.first { it.label == label }.popup.map { it.label }
        assertThat(popup("-")).containsExactly("+", "_", "–", "—").inOrder()
        assertThat(popup("/")).containsExactly("\\")
        assertThat(popup("'")).containsExactly("`", "‘", "’").inOrder()
        assertThat(popup("\"")).containsExactly("“", "”").inOrder()
        assertThat(popup("?")).containsExactly("¿")
        assertThat(popup("!")).containsExactly("¡")
        assertThat(popup("$")).containsExactly("€", "£", "¥", "¢").inOrder()
        assertThat(popup("1")).contains("½")
    }

    @Test
    fun symbolsPageTwoContent() {
        val rows = repository.get("symbols_more").rows("symbols2")
        assertThat(rows).hasSize(3)
        assertThat(labels(rows[0]).joinToString("")).isEqualTo("=_<>[]{}\\|")
        assertThat(labels(rows[1]).joinToString("")).isEqualTo("`~€£¥¢°•×÷")
        assertThat(labels(rows[2]))
            .containsExactly("2/2", "¡", "¿", "«", "»", "…", "§", "©", "⌫")
            .inOrder()
        val layerKey = rows[2].keys.first()
        assertThat(layerKey.action).isEqualTo(KeyAction.SwitchLayer("symbols"))
        assertThat(layerKey.description).isEqualTo("Switch to symbols")
        assertThat(rows.all { it.width == 10f }).isTrue()
    }

    @Test
    fun symbolsRowThreeHasTheSameControlsAndSevenCharacterKeysOnBothPages() {
        val rows =
            listOf("symbols", "symbols2").map { id ->
                composer.compose(KeyboardSet.Default, id).rows[2].keys
            }
        for (keys in rows) {
            assertThat(keys).hasSize(9)
            assertThat(keys.first().width).isEqualTo(1.5f)
            assertThat(keys.last().width).isEqualTo(1.5f)
            assertThat(keys.first().action).isInstanceOf(KeyAction.SwitchLayer::class.java)
            assertThat(keys.last().action).isEqualTo(KeyAction.Backspace)
            assertThat(
                    keys.drop(1).dropLast(1).all { it.action is KeyAction.Text && it.width == 1f }
                )
                .isTrue()
        }
        assertThat(rows[0].map { it.width }).isEqualTo(rows[1].map { it.width })
    }

    private fun modeSwitchOf(layer: KeyboardLayer) = layer.rows.last().keys.first()

    @Test
    fun fieldModePopupIsOnTheModeSwitchOfEveryTextBottomRowOnlyWhenOffered() {
        for (variant in BottomRowVariant.entries) {
            val on =
                composer.compose(
                    KeyboardSet.Default,
                    "letters",
                    variant,
                    ComposeOptions(fieldModes = true),
                )
            assertThat(modeSwitchOf(on).popup.map { it.action })
                .containsExactly(
                    KeyAction.SetFieldMode(FieldMode.Text),
                    KeyAction.SetFieldMode(FieldMode.Email),
                    KeyAction.SetFieldMode(FieldMode.Url),
                    KeyAction.SetFieldMode(FieldMode.Number),
                    KeyAction.SetFieldMode(FieldMode.Phone),
                )
                .inOrder()
            assertThat(modeSwitchOf(on).hint).isNull()
            val off = composer.compose(KeyboardSet.Default, "letters", variant)
            assertThat(modeSwitchOf(off).popup).isEmpty()
            // Nothing else about the row changes.
            assertThat(off.rows.last().keys.map { it.width })
                .isEqualTo(on.rows.last().keys.map { it.width })
        }
    }

    @Test
    fun numberAndPhonePadsGetAWayBackOnlyWhenFieldModesAreOffered() {
        for (id in listOf("numpad", "phone")) {
            val plain = composer.compose(KeyboardSet.Default, id)
            val withBack =
                composer.compose(
                    KeyboardSet.Default,
                    id,
                    options = ComposeOptions(fieldModes = true),
                )
            assertThat(withBack.rows.dropLast(1)).isEqualTo(plain.rows)
            val back = withBack.rows.last().keys.single()
            assertThat(back.label).isEqualTo("ABC")
            assertThat(back.action).isEqualTo(KeyAction.SetFieldMode(FieldMode.Text))
            assertThat(back.width).isEqualTo(plain.rows.maxOf { it.width })
            assertThat(back.popup).hasSize(5)
        }
        // The datetime pad is not a switch target: it never gets the row.
        val datetime =
            composer.compose(
                KeyboardSet.Default,
                "datetime",
                options = ComposeOptions(fieldModes = true),
            )
        assertThat(datetime.rows).isEqualTo(composer.compose(KeyboardSet.Default, "datetime").rows)
    }

    @Test
    fun theBottomRowsOfBothSymbolsPagesAreIdentical() {
        for (variant in BottomRowVariant.entries) {
            for (numberRow in listOf(false, true)) {
                val options = ComposeOptions(numberRow = numberRow)
                val bottoms =
                    listOf("symbols", "symbols2").map {
                        composer.compose(KeyboardSet.Default, it, variant, options).rows.last()
                    }
                assertThat(bottoms[0]).isEqualTo(bottoms[1])
            }
        }
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

    private fun numpad(signed: Boolean = true, decimal: Boolean = true) =
        composer.compose(
            KeyboardSet.Default,
            "numpad",
            options = ComposeOptions(signedNumber = signed, decimalNumber = decimal),
        )

    @Test
    fun numpadDefaultsKeepEveryKey() {
        val labels = numpad().rows.flatMap { it.keys }.map { it.label }
        assertThat(labels).containsAtLeast("-", ",", ".")
        assertThat(ComposeOptions().signedNumber).isTrue()
        assertThat(ComposeOptions().decimalNumber).isTrue()
    }

    @Test
    fun unsignedNumpadHidesMinusOnly() {
        val plain = numpad()
        val layer = numpad(signed = false)
        val minus = layer.rows[0].keys[3]
        assertThat(minus.isSpacer).isTrue()
        assertThat(minus.width).isEqualTo(plain.rows[0].keys[3].width)
        assertThat(layer.rows.flatMap { it.keys }.map { it.label }).containsAtLeast(",", ".")
        assertThat(layer.rows.map { it.width }).isEqualTo(plain.rows.map { it.width })
    }

    @Test
    fun nonDecimalNumpadHidesBothSeparators() {
        val plain = numpad()
        val layer = numpad(decimal = false)
        assertThat(layer.rows[1].keys[3].isSpacer).isTrue()
        assertThat(layer.rows[3].keys[2].isSpacer).isTrue()
        val labels = layer.rows.flatMap { it.keys }.map { it.label }
        assertThat(labels).contains("-")
        assertThat(labels).containsNoneOf(",", ".")
        assertThat(layer.rows.map { it.keys.size }).isEqualTo(plain.rows.map { it.keys.size })
    }

    @Test
    fun numberFlagsAreCachedSeparatelyAndIgnoredElsewhere() {
        assertThat(numpad(signed = false)).isNotEqualTo(numpad())
        assertThat(numpad(signed = false, decimal = false)).isNotEqualTo(numpad(decimal = false))
        val opts = ComposeOptions(signedNumber = false, decimalNumber = false)
        for (id in listOf("letters", "symbols", "phone", "datetime")) {
            assertThat(composer.compose(KeyboardSet.Default, id, options = opts))
                .isEqualTo(composer.compose(KeyboardSet.Default, id))
        }
    }

    // Terminal row

    private fun units(layer: KeyboardLayer) = layer.rows.sumOf { it.height.toDouble() }

    @Test
    fun terminalRowContent() {
        val row = repository.get("terminal_row").rows("letters").single()
        assertThat(row.keys.map { it.label })
            .containsExactly("Esc", "Tab", "Ctrl", "Alt", "←", "↓", "↑", "→")
            .inOrder()
        assertThat(rowUnits(row)).isEqualTo(10.0)
        assertThat(row.keys.map { it.action })
            .containsExactly(
                KeyAction.KeyCode(KeyCodes.ESCAPE),
                KeyAction.KeyCode(KeyCodes.TAB),
                KeyAction.Modifier(KeyModifier.Ctrl),
                KeyAction.Modifier(KeyModifier.Alt),
                KeyAction.KeyCode(KeyCodes.DPAD_LEFT),
                KeyAction.KeyCode(KeyCodes.DPAD_DOWN),
                KeyAction.KeyCode(KeyCodes.DPAD_UP),
                KeyAction.KeyCode(KeyCodes.DPAD_RIGHT),
            )
            .inOrder()
        val popups = row.keys.drop(4).map { it.popup.single().action }
        assertThat(popups)
            .containsExactly(
                KeyAction.KeyCode(KeyCodes.MOVE_HOME),
                KeyAction.KeyCode(KeyCodes.PAGE_DOWN),
                KeyAction.KeyCode(KeyCodes.PAGE_UP),
                KeyAction.KeyCode(KeyCodes.MOVE_END),
            )
            .inOrder()
        assertThat(row.keys.map { it.description }.take(4))
            .containsExactly("Escape", "Tab", "Control", "Alt")
            .inOrder()
    }

    @Test
    fun terminalRowIsOnlyPresentWithTheOptionAndOnEveryLayer() {
        val set = KeyboardSet.Default
        for (layerId in listOf(KeyboardSet.LETTERS, KeyboardSet.SYMBOLS, KeyboardSet.SYMBOLS2)) {
            val plain = composer.compose(set, layerId)
            val terminal =
                composer.compose(set, layerId, options = ComposeOptions(terminalRow = true))
            assertThat(plain.rows.flatMap { it.keys }.map { it.label }).doesNotContain("Esc")
            assertThat(terminal.rows.first().keys.map { it.label }).contains("Esc")
            assertThat(terminal.rows.drop(1)).isEqualTo(plain.rows)
            assertThat(units(terminal)).isGreaterThan(units(plain))
        }
    }

    @Test
    fun terminalRowSitsAboveTheNumberRowAndAddsToTheHeight() {
        val set = KeyboardSet.Default
        val both =
            composer.compose(
                set,
                KeyboardSet.LETTERS,
                options = ComposeOptions(numberRow = true, terminalRow = true),
            )
        assertThat(both.rows[0].keys.first().label).isEqualTo("Esc")
        assertThat(both.rows[1].keys.first().label).isEqualTo("1")
        val rowHeight = repository.get("terminal_row").rows("letters").single().height
        val withoutRow =
            composer.compose(set, KeyboardSet.LETTERS, options = ComposeOptions(numberRow = true))
        assertThat(units(both)).isWithin(1e-6).of(units(withoutRow) + rowHeight)
    }

    // Symbol hints, number-row hints and row 3 alignment (feedback round 4)

    private fun letters(numberRow: Boolean, symbolHints: Boolean = true) =
        composer
            .compose(
                KeyboardSet.Default,
                KeyboardSet.LETTERS,
                options = ComposeOptions(numberRow = numberRow, symbolHints = symbolHints),
            )
            .rows
            .flatMap { it.keys }
            .filter { it.style == KeyStyle.Character }
            .associateBy { it.label }

    @Test
    fun symbolHintsFollowTheSymbolsPageWithTheNumberRowOn() {
        val keys = letters(numberRow = true)
        val expected =
            mapOf(
                "q" to "+",
                "w" to "×",
                "e" to "÷",
                "r" to "=",
                "t" to "/",
                "y" to "_",
                "u" to "<",
                "i" to ">",
                "o" to "[",
                "p" to "]",
                "a" to "!",
                "s" to "@",
                "d" to "#",
                "f" to "$",
                "g" to "%",
                "h" to "^",
                "j" to "&",
                "k" to "*",
                "l" to "(",
                "z" to "-",
                "x" to "'",
                "c" to "\"",
                "v" to ":",
                "b" to ";",
                "n" to "/",
                "m" to "?",
            )
        for ((letter, symbol) in expected) {
            assertThat(keys.getValue(letter).hint).isEqualTo(symbol)
            assertThat(keys.getValue(letter).popup.first().label).isEqualTo(symbol)
        }
        // The number row itself has no hints.
        "1234567890".forEach { assertThat(keys.getValue(it.toString()).hint).isNull() }
    }

    @Test
    fun symbolHintsWithTheNumberRowOffUseTheDigitsRowOnTop() {
        val keys = letters(numberRow = false)
        "qwertyuiop".zip("1234567890").forEach { (letter, digit) ->
            assertThat(keys.getValue(letter.toString()).hint).isEqualTo(digit.toString())
            assertThat(keys.getValue(letter.toString()).popup.first().label)
                .isEqualTo(digit.toString())
        }
        assertThat(keys.getValue("a").hint).isEqualTo("!")
        assertThat(keys.getValue("z").hint).isEqualTo("-")
        // Accents come after the symbol.
        assertThat(keys.getValue("e").popup.map { it.label })
            .containsExactly("3", "è", "é", "ê", "ë", "ē")
            .inOrder()
    }

    @Test
    fun topRowHasNoDigitHintsOrPopupEntriesWithTheNumberRowOn() {
        for (symbolHints in listOf(true, false)) {
            val keys = letters(numberRow = true, symbolHints = symbolHints)
            for (letter in "qwertyuiop") {
                val key = keys.getValue(letter.toString())
                assertThat(key.hint).isNotIn("1234567890".map { it.toString() })
                assertThat(key.popup.map { it.label }).containsNoneIn("1234567890".map { "$it" })
            }
        }
        val e = letters(numberRow = true)
        assertThat(e.getValue("e").popup.map { it.label })
            .containsExactly("÷", "è", "é", "ê", "ë", "ē")
            .inOrder()
        assertThat(letters(numberRow = true, symbolHints = false).getValue("e").hint).isNull()
        assertThat(
                letters(numberRow = true, symbolHints = false).getValue("e").popup.map { it.label }
            )
            .containsExactly("è", "é", "ê", "ë", "ē")
            .inOrder()
    }

    @Test
    fun withoutSymbolHintsOnlyTheTopRowDigitsRemainWhenTheNumberRowIsOff() {
        val keys = letters(numberRow = false, symbolHints = false)
        assertThat(keys.getValue("q").hint).isEqualTo("1")
        assertThat(keys.getValue("q").popup.map { it.label }).containsExactly("1")
        for (letter in "asdfghjklzxcvbnm") {
            val key = keys.getValue(letter.toString())
            assertThat(key.hint).isNull()
            assertThat(key.popup.map { it.label }).doesNotContain("!")
        }
        assertThat(keys.getValue("d").popup).isEmpty()
        assertThat(keys.getValue("a").popup.map { it.label }.first()).isEqualTo("à")
    }

    @Test
    fun symbolHintIsNeverDuplicatedInPopups() {
        for (numberRow in listOf(false, true)) {
            letters(numberRow).values.forEach { key ->
                assertThat(key.popup.map { it.label }).containsNoDuplicates()
            }
        }
    }

    @Test
    fun symbolHintsAreCachedPerOption() {
        val a = letters(numberRow = true, symbolHints = true)
        val b = letters(numberRow = true, symbolHints = false)
        assertThat(a.getValue("f").hint).isEqualTo("$")
        assertThat(b.getValue("f").hint).isNull()
    }

    @Test
    fun row3AlignsOnLettersAndBothSymbolPages() {
        for (numberRow in listOf(false, true)) {
            val options = ComposeOptions(numberRow = numberRow)
            val rows =
                listOf("letters", "symbols", "symbols2").map {
                    val layer = composer.compose(KeyboardSet.Default, it, options = options)
                    // The row above the bottom row.
                    layer.rows[layer.rows.size - 2]
                }
            for (row in rows) {
                assertThat(row.keys).hasSize(9)
                assertThat(row.keys.first().width).isEqualTo(1.5f)
                assertThat(row.keys.last().width).isEqualTo(1.5f)
                assertThat(row.keys.first().style).isEqualTo(KeyStyle.Modifier)
                assertThat(row.keys.last().action).isEqualTo(KeyAction.Backspace)
                assertThat(row.keys.drop(1).dropLast(1).map { it.width }.toSet())
                    .containsExactly(1f)
                assertThat(row.width).isEqualTo(10f)
            }
            assertThat(rows[1].keys.drop(1).dropLast(1).joinToString("") { it.label })
                .isEqualTo("-'\":;/?")
            assertThat(rows[2].keys.drop(1).dropLast(1).joinToString("") { it.label })
                .isEqualTo("¡¿«»…§©")
        }
        assertThat(
                composer.compose(KeyboardSet.Default, "symbols").rows[2].keys[1].popup.map {
                    it.label
                }
            )
            .contains("+")
    }
}
