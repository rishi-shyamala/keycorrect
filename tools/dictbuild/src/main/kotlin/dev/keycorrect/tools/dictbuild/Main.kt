package dev.keycorrect.tools.dictbuild

import kotlin.system.exitProcess

private const val USAGE =
    """
dictbuild <command> [options]

commands:
  count         count n-grams from the raw sources into data/processed/  (see: dictbuild count --help)
  compile       turn data/processed/ into the binary dictionary (app/src/main/assets/dictionaries/en_US.kcd)
                (see: dictbuild compile --help)
  eval-lm       perplexity / next-word accuracy of a compiled dictionary on data/processed/heldout.txt
  extract-wiki  debug: print extracted sentences of a few Wikipedia articles
                (--wiki PATH --pages N --skip M)
"""

fun main(args: Array<String>) {
    val code =
        when (args.firstOrNull()) {
            "count" -> CountCommand.run(args.drop(1))
            "compile" -> CompileCommand.run(args.drop(1))
            "eval-lm" -> EvalLmCommand.run(args.drop(1))
            "extract-wiki" -> ExtractWikiCommand.run(args.drop(1))
            null,
            "-h",
            "--help" -> {
                println(USAGE)
                0
            }
            else -> {
                System.err.println("unknown command: ${args[0]}\n$USAGE")
                2
            }
        }
    if (code != 0) exitProcess(code)
}
