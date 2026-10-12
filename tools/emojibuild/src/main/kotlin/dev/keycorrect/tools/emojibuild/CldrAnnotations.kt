package dev.keycorrect.tools.emojibuild

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/** CLDR short name and keywords for one emoji. Either part may be absent. */
data class Annotation(val name: String?, val keywords: List<String>)

/** CLDR `annotations/en.xml`-style files: `<annotation cp="…" [type="tts"]>…</annotation>`. */
object CldrAnnotations {
    /** Key used to match annotations to emoji: the text without emoji variation selectors. */
    fun key(text: String): String = text.replace("\uFE0F", "")

    fun parse(files: List<File>): Map<String, Annotation> {
        val names = HashMap<String, String>()
        val keywords = HashMap<String, List<String>>()
        for (f in files) {
            if (!f.isFile) invalid("CLDR annotations file not found: ${f.path}")
            try {
                f.inputStream().use { readFile(it, names, keywords) }
            } catch (e: XMLStreamException) {
                throw InputException("${f.path}: not valid CLDR XML (${e.message})", e)
            }
        }
        if (names.isEmpty()) invalid("no CLDR annotations found in $files")
        return (names.keys + keywords.keys).associateWith {
            Annotation(names[it], keywords[it].orEmpty())
        }
    }

    private fun readFile(
        input: java.io.InputStream,
        names: MutableMap<String, String>,
        keywords: MutableMap<String, List<String>>,
    ) {
        val factory = XMLInputFactory.newFactory()
        // Data only: never load the DTD or any external entity.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.IS_COALESCING, true)
        val r = factory.createXMLStreamReader(input)
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.localName == "annotation") {
                    record(r, names, keywords)
                }
            }
        } finally {
            r.close()
        }
    }

    private fun record(
        r: javax.xml.stream.XMLStreamReader,
        names: MutableMap<String, String>,
        keywords: MutableMap<String, List<String>>,
    ) {
        val cp = r.getAttributeValue(null, "cp")
        val tts = r.getAttributeValue(null, "type") == "tts"
        val text = r.elementText.trim()
        if (cp == null) return
        // The first file wins, so list the base annotations before the derived ones.
        if (tts) names.putIfAbsent(key(cp), text)
        else keywords.putIfAbsent(key(cp), splitKeywords(text))
    }

    private fun splitKeywords(text: String): List<String> =
        text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
}
