package dev.keycorrect.core.decoder

import dev.keycorrect.core.dictionary.BigramEntry
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.DictionaryData
import dev.keycorrect.core.dictionary.DictionaryMetadata
import dev.keycorrect.core.dictionary.DictionaryWriter
import dev.keycorrect.core.dictionary.TrigramEntry
import dev.keycorrect.core.dictionary.WordEntry
import dev.keycorrect.core.dictionary.WordFlags
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * A small English dictionary for tests: a few hundred words with Zipf-like frequencies (plus rare
 * words so the probability range is realistic) and n-grams counted from a tiny corpus.
 */
object TestDictionary {
    private const val TOTAL = 2_000_000L

    /** Most frequent first. */
    private val common =
        ("the be to of and a in that have it for not on with he as you do at this but his by from " +
                "they we say her she or an will my one all would there their what so up out if about " +
                "who get which go me when make can like time no just him know take people into year " +
                "your good some could them see other than then now look only come its over think also " +
                "back after use two how our work first well way even new want because any these give " +
                "day most us is was are were has had did said went here where hello help held hell " +
                "world home house keys key way very much thank please tomorrow today love life man " +
                "woman child school water small large long great little own old right big high " +
                "different next early young important few public bad same able store park city " +
                "friend friends family heads head hand eyes face place week company system program " +
                "question government number night point area money story fact month lot study book " +
                "eye job word business issue side kind four feel try leave call keep let begin seem " +
                "help talk turn start show hear play run move live believe bring happen write " +
                "provide sit stand lose pay meet include continue set learn change lead understand " +
                "watch follow stop create speak read allow add spend grow open walk win offer " +
                "remember consider appear buy wait serve die send expect build stay fall cut reach " +
                "kill remain suggest raise pass sell require report decide pull receive definitely " +
                "tell form from them they're wrote written hallo hellos helmet helpful " +
                "helping hello's weather whether wheel when while white whole whose why wide wife " +
                "will wind window winter wish within without wonder wood works worth write wrong " +
                "worry lost")
            .split(' ')
            .distinct()

    /** Words with near-zero frequency: they stretch the unigram range to a realistic -15. */
    private val rare =
        ("zebra quixotic abacus bivouac cadaver dirigible effervescent fjord gazebo hiatus " +
                "iguana juxtapose kayak lacquer mnemonic nebula oblique pylon quagmire rhubarb " +
                "sextant tundra umbra vellum wombat xylophone yodel zephyr damp dam")
            .split(' ')

    /** `count to sentence`; "i" is the dictionary word "I". */
    private val corpus =
        listOf(
            30 to "i went to the store",
            30 to "i want to go home",
            10 to "he went to the store",
            20 to "they lost their keys",
            10 to "they lost their way",
            25 to "put it over there",
            15 to "look over there",
            10 to "i will see you there",
            3 to "it is over their heads",
            20 to "thank you for the help",
            25 to "thank you very much",
            20 to "i'm here",
            15 to "don't worry about it",
            10 to "hello world",
            10 to "hello there",
            10 to "can you help me",
            10 to "please help me",
            12 to "i have a friend",
            8 to "what is the time",
            8 to "see you tomorrow",
        )

    private val flagged =
        mapOf(
            "I" to WordFlags.PROPER_NOUN,
            "iPhone" to WordFlags.PROPER_NOUN,
            "Monday" to WordFlags.PROPER_NOUN,
            "damn" to WordFlags.POSSIBLY_OFFENSIVE,
            "etc" to WordFlags.NOT_A_WORD,
        )

    private fun token(w: String) = if (w == "i") "I" else w

    val data: DictionaryData by lazy {
        val counts = LinkedHashMap<String, Long>()
        common.forEachIndexed { i, w ->
            counts[w] = maxOf(5L, (260_000.0 / (i + 1).toDouble().pow(0.97)).toLong())
        }
        rare.forEachIndexed { i, w -> counts[w] = 1L + i % 3 }
        counts["I"] = 90_000L
        counts["I'm"] = 20_000L
        counts["I'll"] = 8_000L
        counts["don't"] = 30_000L
        counts["he'll"] = 4_000L
        counts["iPhone"] = 40L
        counts["Monday"] = 300L
        counts["damn"] = 900L
        counts["etc"] = 700L
        val words = counts.map { (w, c) -> WordEntry.fromCount(w, c, TOTAL, flagged[w] ?: 0) }

        val bi = HashMap<Pair<String, String>, Long>()
        val tri = HashMap<Triple<String, String, String>, Long>()
        for ((n, sentence) in corpus) {
            val seq =
                listOf(Dictionary.BOS_TOKEN) +
                    sentence.split(' ').map { token(if (it == "i'm") "I'm" else it) } +
                    listOf(Dictionary.EOS_TOKEN)
            for (i in 0 until seq.size - 1) bi.merge(seq[i] to seq[i + 1], n.toLong(), Long::plus)
            for (i in 0 until seq.size - 2) {
                tri.merge(Triple(seq[i], seq[i + 1], seq[i + 2]), n.toLong(), Long::plus)
            }
        }
        val biContext =
            bi.entries.groupBy({ it.key.first }, { it.value }).mapValues { it.value.sum() }
        val triContext =
            tri.entries.groupBy({ it.key.first to it.key.second }, { it.value }).mapValues {
                it.value.sum()
            }
        DictionaryData(
            DictionaryMetadata("en-US", "2026-10-09", 1, "test data"),
            words,
            bi.map { (k, c) ->
                BigramEntry.fromCount(k.first, k.second, c, biContext.getValue(k.first))
            },
            tri.map { (k, c) ->
                TrigramEntry.fromCount(
                    k.first,
                    k.second,
                    k.third,
                    c,
                    triContext.getValue(k.first to k.second),
                )
            },
        )
    }

    val dictionary: Dictionary by lazy {
        Dictionary.open(ByteBuffer.wrap(DictionaryWriter.write(data, skipUnknownNgrams = true)))
    }
}
