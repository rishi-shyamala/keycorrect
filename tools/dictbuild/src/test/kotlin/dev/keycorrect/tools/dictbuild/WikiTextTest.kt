package dev.keycorrect.tools.dictbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WikiTextTest {
    private fun sentences(src: String): List<String> =
        ArrayList<String>().also { WikiText.sentences(src, it::add) }

    @Test
    fun removesNestedTemplates() {
        assertThat(WikiText.removeTemplates("A {{foo|{{bar|x}}|y}} B {{baz}}C")).isEqualTo("A  B C")
    }

    @Test
    fun unterminatedTemplateKeepsFollowingText() {
        assertThat(WikiText.removeTemplates("Before {{broken and then more text"))
            .isEqualTo("Before broken and then more text")
    }

    @Test
    fun removesRefsCommentsAndGalleries() {
        val src =
            "Cats<ref name=\"a\">Some {{cite|x}} source</ref> purr.<ref name=b />" +
                "<!-- hidden --> Dogs<gallery>File:A.jpg|cap</gallery> bark."
        assertThat(WikiText.strip(src).trim()).isEqualTo("Cats purr. Dogs bark.")
    }

    @Test
    fun removesTablesIncludingNestedOnes() {
        val src = "Intro.\n{| class=\"x\"\n|-\n| a\n{|\n| inner\n|}\n| b\n|}\nOutro."
        assertThat(sentences(src)).containsExactly("Intro.", "Outro.").inOrder()
    }

    @Test
    fun linksKeepLabelsAndDropFilesCategoriesInterwiki() {
        val src =
            "The [[cat|big cat]] and [[dog]] sat [[File:Cat.jpg|thumb|A [[cat]] here]]" +
                "[[Category:Animals]][[fr:Chat]] on [[Mars#Moons|]]."
        assertThat(WikiText.replaceLinks(src)).isEqualTo("The big cat and dog sat  on Mars.")
    }

    @Test
    fun colonLinksToCategoriesShowText() {
        assertThat(WikiText.replaceLinks("See [[:Category:Cats|cats]] and [[:Category:Dogs]]."))
            .isEqualTo("See cats and Dogs.")
    }

    @Test
    fun externalLinks() {
        assertThat(WikiText.replaceExternalLinks("a [http://x.org the site] b [https://y.org] c"))
            .isEqualTo("a the site b  c")
        assertThat(WikiText.strip("see http://example.com/page now")).doesNotContain("example")
    }

    @Test
    fun boldItalicHeadingsAndLists() {
        val src =
            "== History ==\n'''Bold''' and ''italic'' text.\n* A complete item.\n* fragment\n" +
                "# Another full one!\n: Indented sentence?"
        assertThat(sentences(src))
            .containsExactly(
                "Bold and italic text.",
                "A complete item.",
                "Another full one!",
                "Indented sentence?",
            )
            .inOrder()
    }

    @Test
    fun htmlTagsAndEntities() {
        assertThat(
                WikiText.strip("a<br/>b <b>bold</b>&nbsp;x &amp; y &#233; &#x41; &ndash; z").trim()
            )
            .isEqualTo("a b bold x & y é A  –  z")
    }

    @Test
    fun entityEncodedTagsAreNotReinterpreted() {
        // After XML decoding "&amp;lt;b&amp;gt;" becomes the text "&lt;b&gt;", which must stay
        // text.
        assertThat(WikiText.strip("x &lt;b&gt; y")).isEqualTo("x <b> y")
    }

    @Test
    fun magicWordsAreRemoved() {
        assertThat(WikiText.strip("__NOTOC__Hello")).isEqualTo("Hello")
    }

    @Test
    fun splitsSentencesButNotOnAbbreviationsOrDecimals() {
        val parts = ArrayList<String>()
        WikiText.splitSentences(
            "Dr. J. Smith paid 3.5 dollars in the U.S. today. He left! Did she? \"Yes.\" Then (maybe) not.",
            parts::add,
        )
        assertThat(parts)
            .containsExactly(
                "Dr. J. Smith paid 3.5 dollars in the U.S. today.",
                "He left!",
                "Did she?",
                "\"Yes.\"",
                "Then (maybe) not.",
            )
            .inOrder()
    }

    @Test
    fun lowercaseContinuationDoesNotSplit() {
        val parts = ArrayList<String>()
        WikiText.splitSentences("It cost approx. five dollars. Ok then... maybe not.", parts::add)
        assertThat(parts).containsExactly("It cost approx. five dollars.", "Ok then... maybe not.")
    }

    @Test
    fun realisticSnippet() {
        val src =
            "{{Infobox person|name=Foo}}\n'''Foo Bar''' (born 1 May 1950) is a [[writer|author]] " +
                "from [[London]].<ref>{{cite web|url=http://x}}</ref> She wrote many books.\n\n" +
                "== Works ==\n* ''A Book'' (1980)\n\n[[Category:People]]"
        assertThat(sentences(src))
            .containsExactly(
                "Foo Bar (born 1 May 1950) is a author from London.",
                "She wrote many books.",
            )
            .inOrder()
    }
}
