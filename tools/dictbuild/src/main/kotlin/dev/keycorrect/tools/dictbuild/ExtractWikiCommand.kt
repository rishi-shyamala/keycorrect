package dev.keycorrect.tools.dictbuild

import java.nio.file.Path

/** Debug helper: prints the extracted sentences of a few articles to eyeball markup stripping. */
object ExtractWikiCommand {
    fun run(args: List<String>): Int {
        var wiki: Path? = null
        var pages = 5
        var skip = 0
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--wiki" -> wiki = Path.of(args[++i])
                "--pages" -> pages = args[++i].toInt()
                "--skip" -> skip = args[++i].toInt()
                else -> {
                    System.err.println("unknown option: ${args[i]}")
                    return 2
                }
            }
            i++
        }
        val dump = wiki ?: CountOptions.findWikiDump(Path.of("data/raw")) ?: return 1
        var seen = 0
        var shown = 0
        Sources.openStream(dump).use { input ->
            WikiDumpReader.read(input) { page ->
                if (page.ns != 0 || page.redirect) return@read true
                if (seen++ < skip) return@read true
                println("=== ${page.title}")
                WikiText.sentences(page.text) { println(it) }
                ++shown < pages
            }
        }
        return 0
    }
}
