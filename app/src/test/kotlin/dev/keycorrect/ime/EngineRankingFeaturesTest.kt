package dev.keycorrect.ime

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.RankingFeatures
import java.io.File
import java.nio.ByteBuffer
import org.junit.Test

/** The ranking provider against the real shipped dictionary and engine. */
class EngineRankingFeaturesTest {
    private val engine: ClassicEngine by lazy {
        val file = File("src/main/assets/dictionaries/en_US.kcd")
        ClassicEngine(Dictionary.open(ByteBuffer.wrap(file.readBytes())))
    }

    private fun bridge(
        activity: DecodeActivity = DecodeActivity(),
        budget: DecodeBudget = DecodeBudget(),
        deferred: () -> Unit = {},
    ) = EngineRankingFeatures(engine, activity, budget, onDeferred = deferred)

    @Test
    fun featuresOfTheLiteralAndASuggestionWithoutTouches() {
        val b = bridge()
        val literal = b.features("teh", listOf("in"), "teh", FieldClass.Text)
        val suggestion = b.features("teh", listOf("in"), "the", FieldClass.Text)
        assertThat(literal).isNotNull()
        assertThat(literal!!.isLiteral).isTrue()
        assertThat(suggestion).isNotNull()
        assertThat(suggestion!!.isLiteral).isFalse()
        assertThat(suggestion.lm).isGreaterThan(literal.lm)
    }

    @Test
    fun emptyContextIsFine() {
        assertThat(bridge().features("helo", emptyList(), "hello", FieldClass.Text)).isNotNull()
    }

    @Test
    fun skippedWhileTheDecoderIsBusy() {
        var deferred = 0
        val activity = DecodeActivity()
        val b = bridge(activity, deferred = { deferred++ })
        activity.track {
            assertThat(b.features("teh", emptyList(), "the", FieldClass.Text)).isNull()
        }
        assertThat(deferred).isEqualTo(1)
        // And for a short while afterwards.
        assertThat(b.features("teh", emptyList(), "the", FieldClass.Text)).isNull()
        assertThat(deferred).isEqualTo(2)
    }

    @Test
    fun skippedWhenTheBudgetIsUsedUp() {
        var deferred = 0
        val b = bridge(budget = DecodeBudget(maxCalls = 1), deferred = { deferred++ })
        assertThat(b.features("teh", emptyList(), "the", FieldClass.Text)).isNotNull()
        assertThat(b.features("teh", emptyList(), "the", FieldClass.Text)).isNull()
        assertThat(deferred).isEqualTo(1)
    }

    @Test
    fun aCandidateOutsideThePoolIsNotDecodableAndNotDeferred() {
        var deferred = 0
        val b = bridge(deferred = { deferred++ })
        assertThat(b.features("teh", emptyList(), "zzzzzz", FieldClass.Text))
            .isSameInstanceAs(RankingFeatures.NOT_DECODABLE)
        assertThat(deferred).isEqualTo(0)
    }
}
