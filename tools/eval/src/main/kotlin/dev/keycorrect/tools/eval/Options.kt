package dev.keycorrect.tools.eval

import java.nio.file.Path
import java.nio.file.Paths

/** Command line of the harness. */
data class Options(
    val sentences: Int = 5000,
    val offset: Int = 0,
    val split: Split = Split.Test,
    val seed: Long = 1,
    val typist: TypistConfig = TypistConfig(),
    val oracleContext: Boolean = false,
    val names: NameMode = NameMode.Skip,
    val config: Map<String, String> = emptyMap(),
    val heldout: Path = Paths.get("data/processed/heldout.txt"),
    val dict: Path = Paths.get("app/src/main/assets/dictionaries/en_US.kcd"),
    val out: Path = Paths.get("tools/eval/build/eval"),
    val threads: Int = maxOf(1, minOf(8, Runtime.getRuntime().availableProcessors() - 2)),
    val failures: Int = 20_000,
    val completion: Boolean = true,
    val nextWord: Boolean = true,
    val phraseWords: Int = 6,
    val latencySentences: Int = 300,
    val sweep: Boolean = false,
    val sweepSentences: Int = 3000,
    val sweepGrid: String = DEFAULT_GRID,
    val targetFalseCorrections: Double = 3.0,
    val whatIf: Set<WhatIf> = emptySet(),
    val label: String = "",
    val help: Boolean = false,
) {
    companion object {
        /** Back-off penalties of the language model, whatever the decoder calls them today. */
        private val BACKOFF_KEYS: String by lazy {
            ConfigOverrides.KEYS.filter {
                    it.startsWith("scoring.") && it.contains("backoff", ignoreCase = true)
                }
                .joinToString("+")
                .ifEmpty { "scoring.lambda" }
        }

        /** Threshold x back-off (multiples of the default) x model sigma. */
        val DEFAULT_GRID: String by lazy {
            "autocorrect.threshold=1.5,2.0,2.5,3.0,3.5,4.5;" +
                "$BACKOFF_KEYS=x0.55,x1,x1.75;" +
                "spatial.sigma=0.35,0.45,0.6,0.85,1.0,1.2"
        }

        val USAGE =
            """
            :tools:eval  offline evaluation of the classic engine with a simulated typist

              --sentences N        held-out sentences to type (default 5000; 0 = all ~100k)
              --split S            dev (20% of lines, for tuning) | test (80%, default, for reporting) | all
              --offset N           start of the sample in the shuffled held-out list (default 0)
              --seed N             seed of the sample shuffle and of all typist noise (default 1)
              --sigma F            touch sigma as a fraction of key width AND height (default 0.35)
              --sigma-x F --sigma-y F
              --omission P --insertion P --transposition P   cognitive typo rates per word (0.01)
              --no-typos           all three typo rates 0
              --oracle-context     context = true previous words instead of the committed output
              --names MODE         skip (default) | substitute | keep  -- Tatoeba's Tom/Mary
              --config K=V,K=V     ClassicConfig overrides, e.g. autocorrect.threshold=3,spatial.sigma=0.4
              --heldout FILE --dict FILE   inputs (default data/processed/heldout.txt, the app asset)
              --out DIR            output directory (default tools/eval/build/eval)
              --threads N          worker threads for the accuracy pass (default min(8, cores-2))
              --failures N         failure rows written to failures.csv (default 20000; 0 = none)
              --no-completion      skip the keystroke-savings simulation (faster)
              --no-next-word       skip next-word and phrase evaluation (faster)
              --phrase-words N     longest phrase requested (default 6; <2 disables phrases)
              --latency-sentences N  sentences in the single-threaded latency pass (default 300; 0 = off)
              --sweep              parameter sweep, then re-evaluate default vs recommended
              --sweep-sentences N  sentences per sweep point, always from the dev split (default 3000)
              --sweep-grid SPEC    'key=v1,v2;key=v1,v2' (default: threshold x backoff x sigma)
              --target-fc F        false corrections per 1000 words allowed by the recommendation (3)
              --what-if A,B        post-process answers to size a decoder change: ${WhatIf.entries.joinToString { it.flag }}
              --label TEXT         free text stored in the JSON

            config keys: ${ConfigOverrides.KEYS.joinToString(", ")}
            """
                .trimIndent()

        fun parse(args: List<String>): Options {
            var o = Options()
            var typist = o.typist
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                val eq = arg.indexOf('=')
                val flag = if (arg.startsWith("--") && eq > 0) arg.substring(0, eq) else arg
                val inline = if (arg.startsWith("--") && eq > 0) arg.substring(eq + 1) else null

                fun v(): String {
                    if (inline != null) return inline
                    require(i + 1 < args.size) { "$flag needs a value" }
                    return args[++i]
                }
                when (flag) {
                    "--sentences" -> o = o.copy(sentences = v().toInt())
                    "--split" -> {
                        val value = v()
                        o =
                            o.copy(
                                split =
                                    Split.entries.firstOrNull { it.name.equals(value, true) }
                                        ?: throw IllegalArgumentException(
                                            "--split: dev | test | all"
                                        )
                            )
                    }
                    "--offset" -> o = o.copy(offset = v().toInt())
                    "--seed" -> o = o.copy(seed = v().toLong())
                    "--sigma" ->
                        v().toFloat().let { typist = typist.copy(sigmaX = it, sigmaY = it) }
                    "--sigma-x" -> typist = typist.copy(sigmaX = v().toFloat())
                    "--sigma-y" -> typist = typist.copy(sigmaY = v().toFloat())
                    "--omission" -> typist = typist.copy(omissionRate = v().toFloat())
                    "--insertion" -> typist = typist.copy(insertionRate = v().toFloat())
                    "--transposition" -> typist = typist.copy(transpositionRate = v().toFloat())
                    "--no-typos" ->
                        typist =
                            typist.copy(
                                omissionRate = 0f,
                                insertionRate = 0f,
                                transpositionRate = 0f,
                            )
                    "--oracle-context" -> o = o.copy(oracleContext = true)
                    "--names" -> {
                        val value = v()
                        o =
                            o.copy(
                                names =
                                    NameMode.entries.firstOrNull { it.name.equals(value, true) }
                                        ?: throw IllegalArgumentException(
                                            "--names: skip | substitute | keep"
                                        )
                            )
                    }
                    "--config" -> o = o.copy(config = o.config + ConfigOverrides.parse(v()))
                    "--heldout" -> o = o.copy(heldout = Paths.get(v()))
                    "--dict" -> o = o.copy(dict = Paths.get(v()))
                    "--out" -> o = o.copy(out = Paths.get(v()))
                    "--threads" -> o = o.copy(threads = v().toInt())
                    "--failures" -> o = o.copy(failures = v().toInt())
                    "--no-completion" -> o = o.copy(completion = false)
                    "--no-next-word" -> o = o.copy(nextWord = false)
                    "--phrase-words" -> o = o.copy(phraseWords = v().toInt())
                    "--latency-sentences" -> o = o.copy(latencySentences = v().toInt())
                    "--sweep" -> o = o.copy(sweep = true)
                    "--sweep-sentences" -> o = o.copy(sweepSentences = v().toInt())
                    "--sweep-grid" -> o = o.copy(sweepGrid = v())
                    "--target-fc" -> o = o.copy(targetFalseCorrections = v().toDouble())
                    "--what-if" ->
                        o =
                            o.copy(
                                whatIf = o.whatIf + v().split(',').map { WhatIf.parse(it.trim()) }
                            )
                    "--label" -> o = o.copy(label = v())
                    "--help",
                    "-h" -> o = o.copy(help = true)
                    else -> throw IllegalArgumentException("unknown option: $arg (try --help)")
                }
                i++
            }
            return o.copy(typist = typist)
        }
    }
}
