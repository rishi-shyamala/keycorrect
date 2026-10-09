package dev.keycorrect.tools.dictbuild

import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

data class WikiPage(val title: String, val ns: Int, val redirect: Boolean, val text: String)

/** Streaming (StAX) reader for MediaWiki XML export / `pages-articles` dumps. */
object WikiDumpReader {
    init {
        // Dump text is full of &lt; / &amp; escapes; lift the JDK's entity-size guards for
        // this trusted-format, data-only parse (no DTD processing is enabled below).
        for (p in
            listOf(
                "jdk.xml.entityExpansionLimit",
                "jdk.xml.totalEntitySizeLimit",
                "jdk.xml.maxGeneralEntitySizeLimit",
                "jdk.xml.maxParameterEntitySizeLimit",
                "jdk.xml.maxElementDepth",
                "jdk.xml.elementAttributeLimit",
            )) {
            System.setProperty(p, "0")
        }
    }

    /** Calls [onPage] for every `<page>` in document order. Stops early if it returns false. */
    fun read(input: InputStream, onPage: (WikiPage) -> Boolean) {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.IS_COALESCING, true)
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        val r = factory.createXMLStreamReader(input)
        try {
            var title = ""
            var ns = -1
            var redirect = false
            var text = ""
            var inPage = false
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT ->
                        when (r.localName) {
                            "page" -> {
                                inPage = true
                                title = ""
                                ns = -1
                                redirect = false
                                text = ""
                            }
                            "title" -> if (inPage) title = r.elementText
                            "ns" -> if (inPage) ns = r.elementText.trim().toIntOrNull() ?: -1
                            "redirect" -> if (inPage) redirect = true
                            "text" -> if (inPage) text = r.elementText
                        }
                    XMLStreamConstants.END_ELEMENT ->
                        if (r.localName == "page") {
                            inPage = false
                            if (!onPage(WikiPage(title, ns, redirect, text))) return
                        }
                }
            }
        } finally {
            r.close()
        }
    }
}
