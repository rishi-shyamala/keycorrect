package dev.keycorrect.core.dictionary

import com.google.common.truth.Truth.assertThat
import java.util.Random
import org.junit.Test

class NgramTest {
    private val data = Fixtures.smallData
    private val dict = Fixtures.small
    private val bgTol = halfStep(data.bigrams.map { it.logProb }, 126)
    private val tgTol = halfStep(data.trigrams.map { it.logProb }, 126)

    private val bigramById: Map<Pair<Int, Int>, Float> =
        data.bigrams.associate { (dict.idOfToken(it.w1) to dict.idOfToken(it.w2)) to it.logProb }
    private val trigramById: Map<Triple<Int, Int, Int>, Float> =
        data.trigrams.associate {
            Triple(dict.idOfToken(it.w1), dict.idOfToken(it.w2), dict.idOfToken(it.w3)) to
                it.logProb
        }

    @Test
    fun everyBigramIsFoundWithinQuantizationError() {
        assertThat(bigramById).hasSize(data.bigrams.size)
        for ((k, lp) in bigramById) {
            assertThat(dict.bigramLogProb(k.first, k.second)).isNotNull()
            assertThat(dict.bigramLogProb(k.first, k.second)!!).isWithin(bgTol).of(lp)
            assertThat(dict.bigramLogProbOrNaN(k.first, k.second)).isWithin(bgTol).of(lp)
        }
    }

    @Test
    fun everyTrigramIsFoundWithinQuantizationError() {
        assertThat(trigramById).hasSize(data.trigrams.size)
        for ((k, lp) in trigramById) {
            assertThat(dict.trigramLogProb(k.first, k.second, k.third)!!).isWithin(tgTol).of(lp)
        }
    }

    @Test
    fun absentNgramsReturnNullSoCallersCanBackOff() {
        val rnd = Random(9)
        val n = dict.wordCount
        var checked = 0
        while (checked < 5000) {
            val a = rnd.nextInt(n)
            val b = rnd.nextInt(n)
            val c = rnd.nextInt(n)
            if (bigramById.containsKey(a to b)) continue
            assertThat(dict.bigramLogProb(a, b)).isNull()
            assertThat(dict.bigramLogProbOrNaN(a, b).isNaN()).isTrue()
            if (!trigramById.containsKey(Triple(a, b, c))) {
                assertThat(dict.trigramLogProb(a, b, c)).isNull()
            }
            checked++
        }
        // Out-of-range ids are absent, not errors.
        assertThat(dict.bigramLogProb(-1, 3)).isNull()
        assertThat(dict.bigramLogProb(n + 1000, 3)).isNull()
        assertThat(dict.trigramLogProb(n + 1000, 3, 4)).isNull()
        assertThat(dict.trigramLogProb(2, n + 1000, 4)).isNull()
        // Word that is never a context
        val rare = dict.wordCount - 1
        assertThat(dict.topSuccessors(rare, 5).size).isAtMost(5)
    }

    @Test
    fun trigramContextWithoutBigramBacksOffToNull() {
        val orphan = trigramById.keys.filter { bigramById[it.first to it.second] == null }
        assertThat(orphan).isNotEmpty()
        for (t in orphan) {
            assertThat(dict.bigramLogProb(t.first, t.second)).isNull()
            assertThat(dict.trigramLogProb(t.first, t.second, t.third)).isNotNull()
        }
        // A bigram that exists but has no trigram list
        val noTri =
            bigramById.keys.first { p ->
                trigramById.keys.none { it.first == p.first && it.second == p.second }
            }
        assertThat(dict.trigramLogProb(noTri.first, noTri.second, 5)).isNull()
        assertThat(dict.trigramList(noTri.first, noTri.second)).isEqualTo(-1)
        assertThat(dict.topSuccessors(noTri.first, noTri.second, 5)).isEmpty()
    }

    private fun bruteForceBigramTop(w1: Int, k: Int): List<Successor> =
        bigramById.entries
            .filter { it.key.first == w1 }
            .map { Successor(it.key.second, dict.bigramLogProbOrNaN(w1, it.key.second)) }
            .sortedWith(compareByDescending<Successor> { it.logProb }.thenBy { it.id })
            .take(k)

    private fun bruteForceTrigramTop(w1: Int, w2: Int, k: Int): List<Successor> =
        trigramById.entries
            .filter { it.key.first == w1 && it.key.second == w2 }
            .map { Successor(it.key.third, dict.trigramLogProbOrNaN(w1, w2, it.key.third)) }
            .sortedWith(compareByDescending<Successor> { it.logProb }.thenBy { it.id })
            .take(k)

    @Test
    fun bigramSuccessorsAreSortedAndComplete() {
        val contexts =
            bigramById.keys
                .groupingBy { it.first }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
        val rnd = Random(3)
        val picked =
            contexts.take(5).map { it.key } + List(40) { contexts[rnd.nextInt(contexts.size)].key }
        for (w1 in picked) {
            for (k in intArrayOf(1, 3, 10, 50, 100000)) {
                val got = dict.topSuccessors(w1, k)
                assertThat(got).isEqualTo(bruteForceBigramTop(w1, k))
                assertThat(got.map { it.logProb }).isInOrder(Comparator.reverseOrder<Float>())
            }
        }
        assertThat(dict.topSuccessors(picked[0], 0)).isEmpty()
        assertThat(dict.topSuccessors(picked[0], -3)).isEmpty()
    }

    @Test
    fun trigramSuccessorsAreSortedAndComplete() {
        val contexts =
            trigramById.keys
                .groupingBy { it.first to it.second }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
        assertThat(contexts.first().value).isGreaterThan(3)
        val rnd = Random(4)
        val picked =
            contexts.take(5).map { it.key } + List(60) { contexts[rnd.nextInt(contexts.size)].key }
        for ((w1, w2) in picked) {
            for (k in intArrayOf(1, 2, 5, 1000)) {
                assertThat(dict.topSuccessors(w1, w2, k)).isEqualTo(bruteForceTrigramTop(w1, w2, k))
            }
        }
    }

    @Test
    fun allocationLightVariantsMatch() {
        val ids = IntArray(8)
        val lps = FloatArray(8)
        val big = bigramById.keys.groupingBy { it.first }.eachCount().maxByOrNull { it.value }!!.key
        val n = dict.topSuccessors(big, 8, ids, lps)
        assertThat(n).isEqualTo(8)
        val ref = dict.topSuccessors(big, 8)
        for (i in 0 until n) {
            assertThat(ids[i]).isEqualTo(ref[i].id)
            assertThat(lps[i]).isEqualTo(ref[i].logProb)
        }
        // output arrays smaller than k cap the result
        assertThat(dict.topSuccessors(big, 100, IntArray(3), FloatArray(5))).isEqualTo(3)
        assertThat(dict.topSuccessors(big, 0, ids, lps)).isEqualTo(0)
        val tri = trigramById.keys.first()
        val tn = dict.topSuccessors(tri.first, tri.second, 8, ids, lps)
        assertThat(tn).isEqualTo(dict.topSuccessors(tri.first, tri.second, 8).size)

        // List handles agree with the one-shot API.
        val (w1, w2) = bigramById.keys.first()
        val list = dict.bigramList(w1)
        assertThat(dict.logProbInBigramList(list, w2)).isEqualTo(dict.bigramLogProbOrNaN(w1, w2))
        assertThat(dict.logProbInBigramList(-1, w2).isNaN()).isTrue()
        val tl = dict.trigramList(tri.first, tri.second)
        assertThat(dict.logProbInTrigramList(tl, tri.third))
            .isEqualTo(dict.trigramLogProbOrNaN(tri.first, tri.second, tri.third))
    }

    @Test
    fun sentenceStartBigrams() {
        val starts = bigramById.filterKeys { it.first == Dictionary.BOS_ID }
        assertThat(starts.size).isAtLeast(100) // large enough to need skip tables
        for ((k, lp) in starts) {
            assertThat(dict.bigramLogProb(Dictionary.BOS_ID, k.second)!!).isWithin(bgTol).of(lp)
        }
        val top = dict.topSuccessors(Dictionary.BOS_ID, 5)
        assertThat(top).hasSize(5)
        assertThat(top).isEqualTo(bruteForceBigramTop(Dictionary.BOS_ID, 5))
        // nothing ever follows... into <s>
        assertThat(dict.bigramLogProb(dict.wordId("the"), Dictionary.BOS_ID)).isNull()
        // </s> as successor
        val withEos = bigramById.keys.filter { it.second == Dictionary.EOS_ID }
        assertThat(withEos).isNotEmpty()
        assertThat(dict.bigramLogProb(withEos[0].first, Dictionary.EOS_ID)).isNotNull()
        // sentence-start trigrams (<s> w1 w2)
        val startTri = trigramById.keys.filter { it.first == Dictionary.BOS_ID }
        assertThat(startTri).isNotEmpty()
        assertThat(dict.trigramLogProb(startTri[0].first, startTri[0].second, startTri[0].third))
            .isNotNull()
    }

    @Test
    fun quantizationStepIsAtMostAFewHundredthsOfNepers() {
        assertThat(bgTol).isLessThan(0.06f)
        assertThat(tgTol).isLessThan(0.06f)
    }
}
