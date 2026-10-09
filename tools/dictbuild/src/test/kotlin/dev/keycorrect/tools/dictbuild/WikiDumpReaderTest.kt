package dev.keycorrect.tools.dictbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WikiDumpReaderTest {
    private val xml =
        """
        <mediawiki xmlns="http://www.mediawiki.org/xml/export-0.11/">
          <siteinfo><sitename>Wikipedia</sitename></siteinfo>
          <page>
            <title>Cat</title><ns>0</ns><id>1</id>
            <revision><id>10</id><text bytes="40" xml:space="preserve">A '''cat''' &lt;ref&gt;x&lt;/ref&gt; &amp;amp; purrs.</text></revision>
          </page>
          <page>
            <title>Kitty</title><ns>0</ns><id>2</id><redirect title="Cat" />
            <revision><id>11</id><text bytes="12" xml:space="preserve">#REDIRECT [[Cat]]</text></revision>
          </page>
          <page>
            <title>Talk:Cat</title><ns>1</ns><id>3</id>
            <revision><id>12</id><text bytes="3" xml:space="preserve">Hi</text></revision>
          </page>
          <page>
            <title>Empty</title><ns>0</ns><id>4</id>
            <revision><id>13</id><text bytes="0" /></revision>
          </page>
        </mediawiki>
        """
            .trimIndent()

    @Test
    fun streamsPagesWithNamespaceRedirectAndDecodedText() {
        val pages = ArrayList<WikiPage>()
        WikiDumpReader.read(xml.byteInputStream()) {
            pages.add(it)
            true
        }
        assertThat(pages.map { it.title })
            .containsExactly("Cat", "Kitty", "Talk:Cat", "Empty")
            .inOrder()
        assertThat(pages.map { it.ns }).containsExactly(0, 0, 1, 0).inOrder()
        assertThat(pages.map { it.redirect }).containsExactly(false, true, false, false).inOrder()
        assertThat(pages[0].text).isEqualTo("A '''cat''' <ref>x</ref> &amp; purrs.")
        assertThat(pages[3].text).isEmpty()
    }

    @Test
    fun canStopEarly() {
        var n = 0
        WikiDumpReader.read(xml.byteInputStream()) { ++n < 2 }
        assertThat(n).isEqualTo(2)
    }
}
