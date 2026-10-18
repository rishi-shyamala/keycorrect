package dev.keycorrect.core.transfer

internal val phone = DeviceProfile("Pixel 8", 1080, 2400, 420, 411, 54, true)

internal fun sampleBundle(
    version: Int = FORMAT_VERSION,
    device: DeviceProfile = phone,
): TransferBundle =
    TransferBundle(
        TransferManifest(version, "0.5.0", 1_700_000_000_000, device),
        SettingsSection(
            mapOf(
                "key_height_dp" to SettingValue.of(60),
                "bottom_key_width" to SettingValue.of(0.8f),
                "haptics_enabled" to SettingValue.of(false),
                "cursor_speed" to SettingValue.of("Fast"),
            )
        ),
        DictionarySection(
            words = listOf(DictWord("Rishi", 200, null, 5, 9)),
            learnedWords = listOf(LearnedWord("kcbd", "kcbd", 1, 7)),
            blockedWords = listOf(BlockedWord("badword", 3)),
            shortcuts = listOf(ShortcutEntry("omw", "on my way", "AUTO_EXPAND", 1, 2)),
        ),
        LearningSection(
            activeClockMillis = 99,
            ngrams = listOf(NgramEntry(2, "of the", 3.5, 90)),
            corrections = listOf(CorrectionEntry("teh", "the", "", 2.0, 80)),
            sentences = listOf(SentenceEntry(1, "héllo wörld \"quoted\" \n 日本語", 50)),
            emojiRecents = listOf(EmojiRecent("😀", "😀", 10, 4)),
            rankerWeights = listOf(0.1, -0.2),
            rankerUpdates = 12,
        ),
        TouchSection(
            listOf(
                TouchBucket(
                    "qwerty/p/en",
                    40,
                    listOf(TouchKeyStats(97, 30, 25.0, 1.0, 2.0, 3.0, 4.0, 5)),
                )
            )
        ),
    )
