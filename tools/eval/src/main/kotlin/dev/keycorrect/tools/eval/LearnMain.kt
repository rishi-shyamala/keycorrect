package dev.keycorrect.tools.eval

import dev.keycorrect.core.decoder.ClassicConfig
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * `--mode learn`: types a personal user's word stream through the engine twice, once with the
 * personal model learning from the user's reactions and once without, and writes the learning
 * curves (docs/EVAL.md, "Learning").
 */
class LearnMain(private val o: Options) {
    private val keyboard = EvalKeyboard()
    private val dictionary = EvalMain.mapDictionary(o.dict)
    private val config = ConfigOverrides.apply(ClassicConfig(), o.config)

    fun run() {
        val started = System.nanoTime()
        val out = o.out.resolve("learn")
        Files.createDirectories(out)
        val devLines =
            Corpus.sample(
                EvalMain.requireFile(o.heldout, "held-out sentences"),
                0,
                0,
                o.seed,
                Split.Dev,
            )
        val dev = devLines.map { Corpus.parse(it, dictionary, NameMode.Skip) }
        fun known(w: String): Boolean {
            val node = dictionary.findNode(w.replace('’', '\'').lowercase())
            return node >= 0 && dictionary.isTerminal(node)
        }
        val userConfig =
            PersonalUserConfig(biasX = o.biasX, biasY = o.biasY, personalRate = o.personalRate)
        val user = PersonalUser.build(userConfig, dev, ::known, Random(o.seed * 31 + 7))
        val stream = PersonalStream.build(dev, user, o.words, ::known, Random(o.seed * 17 + 3))
        val available = stream.sumOf { s ->
            s.tokens.count { it.evaluated && it.text.none(Char::isDigit) }
        }
        val limit = minOf(o.words, available)
        require(limit > 0) { "no words to type" }
        val ends = LearnArm.windowEnds(limit)
        val typist = o.typist.copy(biasX = o.biasX, biasY = o.biasY)
        val reactions = ReactionModel(ReactionConfig(tapProbability = o.tapProbability))
        val header = header(limit, user, stream.size)
        println(header)

        fun arm(name: String, learning: Boolean) =
            LearnArm(
                name,
                learning,
                dictionary,
                config,
                keyboard,
                typist,
                user,
                reactions,
                o.seed,
                log = { synchronized(this) { println(it) } },
                traceWords = o.traceWords,
            )
        val pool = Executors.newFixedThreadPool(2)
        val (learn, base) =
            try {
                val a = pool.submit(Callable { arm("learning", true).run(stream, limit, ends) })
                val b = pool.submit(Callable { arm("baseline", false).run(stream, limit, ends) })
                a.get() to b.get()
            } finally {
                pool.shutdown()
            }

        val text = LearnReport.text(header, user, learn, base, o.biasX to o.biasY)
        println(text)
        val options =
            linkedMapOf<String, Any>(
                "words" to limit,
                "seed" to o.seed,
                "typist" to
                    linkedMapOf(
                        "sigmaX" to typist.sigmaX,
                        "sigmaY" to typist.sigmaY,
                        "omissionRate" to typist.omissionRate,
                        "insertionRate" to typist.insertionRate,
                        "transpositionRate" to typist.transpositionRate,
                    ),
                "tapProbability" to o.tapProbability,
                "windowEnds" to ends,
                "configOverrides" to o.config,
                "label" to o.label,
            )
        val json = LearnReport.json(options, user, learn, base).toMutableMap()
        json["elapsedSeconds"] = (System.nanoTime() - started) / 1e9
        Files.writeString(out.resolve("learn.json"), Json.write(json) + "\n")
        Files.writeString(out.resolve("learn_report.txt"), text)
        Files.writeString(out.resolve("learn_curve.csv"), LearnReport.curveCsv(learn, base))
        Files.writeString(out.resolve("personal_words.csv"), LearnReport.wordsCsv(learn, base))
        if (o.traceWords > 0) {
            val head = "word,target,literal,strip,autocorrect,runner_up,reaction,final\n"
            Files.writeString(
                out.resolve("trace_learning.csv"),
                head + learn.trace.joinToString("\n"),
            )
            Files.writeString(
                out.resolve("trace_baseline.csv"),
                head + base.trace.joinToString("\n"),
            )
        }
        println(
            String.format(
                "done in %.1f s; wrote %s",
                (System.nanoTime() - started) / 1e9,
                out.toAbsolutePath().normalize(),
            )
        )
    }

    private fun header(words: Int, user: PersonalUser, sentences: Int): String {
        val t = o.typist
        return buildString {
            append(
                "KeyCorrect learn: $words words from $sentences dev-split sentences, seed ${o.seed}, "
            )
            append("typist sigma ${t.sigmaX}x${t.sigmaY} + bias ")
            append(String.format("%+.2f/%+.2f key sizes\n", o.biasX, o.biasY))
            append("personal user: ${user.words.size} words (")
            append("${(o.personalRate * 100)}% of tokens, Zipf ${user.config.zipfExponent}), ")
            append("${user.phrases.size} habitual phrases; tap probability ${o.tapProbability}\n")
            append("personal words: ${user.words.joinToString(", ")}\n")
            if (user.phrases.isNotEmpty()) {
                append("phrases: ${user.phrases.joinToString("; ") { it.joinToString(" ") }}\n")
            }
            if (o.config.isNotEmpty()) append("config overrides: ${o.config}\n")
        }
    }
}
