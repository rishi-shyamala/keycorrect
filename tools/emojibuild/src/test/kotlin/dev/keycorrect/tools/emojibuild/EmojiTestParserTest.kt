package dev.keycorrect.tools.emojibuild

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class EmojiTestParserTest {
    @Test
    fun parsesVersionGroupsStatusAndNames() {
        val f = EmojiTestParser.parse(EMOJI_TEST_FIXTURE, "fixture")
        assertThat(f.unicodeVersion).isEqualTo("17.0")
        val heart = f.entries.filter { it.name == "red heart" }
        assertThat(heart.map { it.status })
            .containsExactly("fully-qualified", "unqualified")
            .inOrder()
        assertThat(heart[0].value).isEqualTo("❤️")
        assertThat(heart[0].group).isEqualTo("Smileys & Emotion")
        val tone = f.entries.first { it.name == "thumbs up: light skin tone" }
        assertThat(tone.codepoints).containsExactly(0x1F44D, 0x1F3FB).inOrder()
        assertThat(f.entries.first { it.status == "component" }.group).isEqualTo("Component")
    }

    @Test
    fun failsClearlyOnBadInput() {
        val missing =
            assertThrows(InputException::class.java) { EmojiTestParser.parse(File("nope/x.txt")) }
        assertThat(missing).hasMessageThat().contains("not found")
        fun bad(text: String) =
            assertThrows(InputException::class.java) { EmojiTestParser.parse(text, "t") }
        assertThat(bad("# Version: 17.0\n# group: A\n1F600 ; fully-qualified # x").message)
            .contains("t:3")
        assertThat(bad("# Version: 17.0\n# group: A\nZZZ ; fully-qualified # 😀 E1.0 a").message)
            .contains("code point")
        assertThat(bad("# Version: 17.0\n# group: A\n1F600 ; weird # 😀 E1.0 a").message)
            .contains("status")
        assertThat(bad("# group: A\n1F600 ; fully-qualified # 😀 E1.0 a").message)
            .contains("Version")
        assertThat(bad("# Version: 17.0\n1F600 ; fully-qualified # 😀 E1.0 a").message)
            .contains("group")
        assertThat(bad("# Version: 17.0\n# group: A\n").message).contains("no emoji")
    }
}
