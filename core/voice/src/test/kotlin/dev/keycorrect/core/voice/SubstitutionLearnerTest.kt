package dev.keycorrect.core.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubstitutionLearnerTest {
    private fun learnerWithKubectl(times: Int) =
        SubstitutionLearner().apply {
            repeat(times) {
                learn("please run cube control apply now", "please run kubectl apply now")
            }
        }

    @Test
    fun extractsTheChangedPhrase() {
        val learner = learnerWithKubectl(1)
        assertThat(learner.export()).containsExactly(Substitution("cube control", "kubectl", 1))
    }

    @Test
    fun needsTwoOccurrencesBeforeRewriting() {
        assertThat(learnerWithKubectl(1).apply("use cube control")).isEqualTo("use cube control")
        assertThat(learnerWithKubectl(2).apply("use cube control")).isEqualTo("use kubectl")
    }

    @Test
    fun appliesWholeWordsOnlyAndKeepsPunctuation() {
        val learner = learnerWithKubectl(2)
        assertThat(learner.apply("a cube controller, cube control. cube control!"))
            .isEqualTo("a cube controller, kubectl. kubectl!")
        assertThat(learner.apply("cube  control"))
            .isEqualTo("cube  control".replace("cube  control", "kubectl"))
        assertThat(learner.apply("cube, control")).isEqualTo("cube, control")
    }

    @Test
    fun preservesCaseWhereSensible() {
        val learner = learnerWithKubectl(2)
        assertThat(learner.apply("Cube control is here")).isEqualTo("Kubectl is here")
        assertThat(learner.apply("CUBE CONTROL")).isEqualTo("KUBECTL")
        val gh =
            SubstitutionLearner().apply { repeat(2) { learn("push to get hub", "push to GitHub") } }
        assertThat(gh.apply("Get hub")).isEqualTo("GitHub")
        assertThat(gh.apply("GET HUB")).isEqualTo("GitHub")
    }

    @Test
    fun learningIgnoresCaseOnlyAndPunctuationOnlyDifferences() {
        val learner = SubstitutionLearner()
        learner.learn("hello world", "Hello, world.")
        assertThat(learner.export()).isEmpty()
    }

    @Test
    fun ignoresInsertionsDeletionsAndBigRewrites() {
        val learner = SubstitutionLearner()
        learner.learn("hello world", "hello big world") // pure insertion
        learner.learn("hello big world", "hello world") // pure deletion
        learner.learn("one two three four five", "alpha beta gamma delta epsilon") // too long
        assertThat(learner.export()).isEmpty()
    }

    @Test
    fun learnsSeveralPairsAndOneToManyWords() {
        val learner = SubstitutionLearner()
        learner.learn("tell rishi shy mala about cube control", "tell Rishi Shyamala about kubectl")
        // "rishi" to "Rishi" differs in case only: not a recognition error.
        assertThat(learner.export().map { it.from to it.to })
            .containsExactly(
                "shy mala" to "Shyamala",
                "cube control" to "kubectl",
            )
        learner.learn("run kubectl", "run K8s ctl")
        assertThat(learner.export().map { it.from to it.to }).contains("kubectl" to "K8s ctl")
    }

    @Test
    fun theMostFrequentTargetWins() {
        val learner = SubstitutionLearner()
        repeat(2) { learner.learn("say hi there", "say hey there") }
        repeat(3) { learner.learn("say hi there", "say yo there") }
        assertThat(learner.apply("hi")).isEqualTo("yo")
    }

    @Test
    fun leastRecentlyUsedPairsAreDroppedAtTheBound() {
        val learner = SubstitutionLearner(maxPairs = 3)
        for (w in listOf("aa", "bb", "cc")) repeat(2) {
            learner.learn("say $w now", "say ${w}x now")
        }
        learner.apply("bb") // touches bb (it is already usable)
        repeat(2) { learner.learn("say dd now", "say ddx now") }
        assertThat(learner.export().map { it.from }).containsExactly("cc", "bb", "dd")
        // aa was the least recently used and is gone.
        assertThat(learner.apply("aa")).isEqualTo("aa")
    }

    @Test
    fun exportImportRoundTrips() {
        val a = learnerWithKubectl(3)
        val b = SubstitutionLearner()
        b.import(a.export())
        assertThat(b.export()).isEqualTo(a.export())
        assertThat(b.apply("cube control")).isEqualTo("kubectl")
        b.import(
            listOf(Substitution("", "x", 5), Substitution("a", "b", 0))
        ) // invalid entries are skipped
        assertThat(b.export()).isEmpty()
        SubstitutionLearner(maxPairs = 1).apply {
            import(listOf(Substitution("a", "b", 2), Substitution("c", "d", 2)))
            assertThat(export().map { it.from }).containsExactly("c")
        }
    }
}
