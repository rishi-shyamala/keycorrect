package dev.keycorrect.tools.dictbuild

import com.google.common.truth.Truth.assertThat
import java.io.StringReader
import org.junit.Test

class AospWordlistParserTest {
    private val sample =
        """
        dictionary=main:en_us,locale=en_US,description=English (US),date=1414726260,version=54
         word=the,f=222,flags=,originalFreq=222
          shortcut=teh,f=whitelist
         word=km,f=155,flags=abbreviation,originalFreq=155
         word=Danny,f=0,flags=,originalFreq=108,possibly_offensive=true
         word=i,f=0,not_a_word=true
          shortcut=I,f=whitelist
         word=hello,f=120,flags=,originalFreq=80
          bigram=world,f=200
         word=e-mail,f=108,flags=hand-added,originalFreq=108
        """
            .trimIndent()

    private val list = AospWordlistParser.parse(StringReader(sample).buffered())

    @Test
    fun parsesHeader() {
        assertThat(list.header).containsEntry("locale", "en_US")
        assertThat(list.header).containsEntry("version", "54")
        assertThat(list.header).containsEntry("description", "English (US)")
    }

    @Test
    fun skipsShortcutAndBigramLines() {
        assertThat(list.entries.map { it.word })
            .containsExactly("the", "km", "Danny", "i", "hello", "e-mail")
            .inOrder()
        assertThat(list.skippedChildLines).isEqualTo(3)
    }

    @Test
    fun parsesFrequenciesAndFlags() {
        val by = list.entries.associateBy { it.word }
        assertThat(by.getValue("the").freq).isEqualTo(222)
        assertThat(by.getValue("hello").freq).isEqualTo(120)
        assertThat(by.getValue("hello").originalFreq).isEqualTo(80)
        assertThat(by.getValue("km").flags).containsExactly("abbreviation")
        assertThat(by.getValue("Danny").flags).containsExactly("possibly_offensive")
        assertThat(by.getValue("Danny").freq).isEqualTo(0)
        assertThat(by.getValue("Danny").originalFreq).isEqualTo(108)
        assertThat(by.getValue("e-mail").flags).containsExactly("hand-added")
        assertThat(by.getValue("i").notAWord).isTrue()
        assertThat(by.getValue("i").originalFreq).isEqualTo(0) // defaults to f
    }
}
