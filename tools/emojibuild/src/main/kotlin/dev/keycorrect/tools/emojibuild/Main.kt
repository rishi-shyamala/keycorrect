package dev.keycorrect.tools.emojibuild

import dev.keycorrect.core.emoji.EmojiCatalogWriter
import java.io.File
import kotlin.system.exitProcess

private const val USAGE =
    """
emojibuild [options]   compile the emoji picker asset (see core/emoji/FORMAT.md)

options (paths are relative to the repository root; the defaults are the pinned sources):
  --emoji-test PATH   Unicode emoji-test.txt         (default data/raw/emoji-test-17.0.0.txt)
  --annotations PATH  CLDR annotations/en.xml        (default data/raw/cldr-release-48-2-annotations-en.xml)
  --derived PATH      CLDR annotationsDerived/en.xml (default data/raw/cldr-release-48-2-annotationsDerived-en.xml)
  --cldr-version V    recorded in the header          (default: taken from the --annotations file name)
  --out PATH          output asset                    (default app/src/main/assets/emoji/emoji_en.kce)
"""

internal data class Options(
    val emojiTest: File,
    val annotations: File,
    val derived: File,
    val cldrVersion: String,
    val out: File,
) {
    companion object {
        private val CLDR_NAME = Regex("""cldr-release-(\d+)-(\d+)-""")

        fun parse(args: List<String>): Options {
            val m = HashMap<String, String>()
            var i = 0
            while (i < args.size) {
                val key = args[i]
                val value = args.getOrNull(i + 1)
                if (key !in FLAGS || value == null)
                    throw InputException("bad or incomplete option '$key'")
                m[key] = value
                i += 2
            }
            val annotations =
                File(m["--annotations"] ?: "data/raw/cldr-release-48-2-annotations-en.xml")
            val version =
                m["--cldr-version"]
                    ?: CLDR_NAME.find(annotations.name)?.let {
                        "${it.groupValues[1]}.${it.groupValues[2]}"
                    }
                    ?: throw InputException(
                        "cannot derive the CLDR version from '${annotations.name}'; use --cldr-version"
                    )
            return Options(
                File(m["--emoji-test"] ?: "data/raw/emoji-test-17.0.0.txt"),
                annotations,
                File(m["--derived"] ?: "data/raw/cldr-release-48-2-annotationsDerived-en.xml"),
                version,
                File(m["--out"] ?: "app/src/main/assets/emoji/emoji_en.kce"),
            )
        }

        private val FLAGS =
            setOf("--emoji-test", "--annotations", "--derived", "--cldr-version", "--out")
    }
}

private const val MIN_BASE_EMOJI = 1_000

internal fun run(options: Options): String {
    val test = EmojiTestParser.parse(options.emojiTest)
    val annotations = CldrAnnotations.parse(listOf(options.annotations, options.derived))
    val catalog = CatalogBuilder.build(test, annotations, options.cldrVersion, Kaomoji.groups)
    val bases = catalog.groups.sumOf { it.emoji.size }
    if (bases < MIN_BASE_EMOJI)
        throw InputException("only $bases base emoji parsed; wrong input file?")
    options.out.absoluteFile.parentFile.mkdirs()
    options.out.outputStream().buffered().use { EmojiCatalogWriter.write(catalog, it) }
    val variants = catalog.groups.sumOf { g -> g.emoji.sumOf { it.variants.size } }
    val unnamed = catalog.groups.sumOf { g -> g.emoji.count { it.keywords.isEmpty() } }
    return "wrote ${options.out} (${options.out.length()} bytes): Unicode ${catalog.unicodeVersion}, " +
        "CLDR ${catalog.cldrVersion}, ${catalog.groups.size} groups, $bases base emoji, $variants variants, " +
        "$unnamed without keywords, ${catalog.kaomoji.size} kaomoji"
}

fun main(args: Array<String>) {
    if (args.any { it == "-h" || it == "--help" }) {
        println(USAGE)
        return
    }
    try {
        println(run(Options.parse(args.toList())))
    } catch (e: InputException) {
        System.err.println("emojibuild: ${e.message}\n$USAGE")
        exitProcess(1)
    }
}
