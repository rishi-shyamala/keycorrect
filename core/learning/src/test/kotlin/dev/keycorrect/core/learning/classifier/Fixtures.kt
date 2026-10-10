package dev.keycorrect.core.learning.classifier

import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.AutocorrectReverted
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.InputProfile
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.ShownSuggestion
import dev.keycorrect.core.learning.TouchSample
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.WordReplaced

val profile =
    InputProfile(
        DeviceClass.Phone,
        landscape = false,
        layoutKey = "qwerty@54dp",
        field = FieldClass.Text,
    )

const val BUCKET = "the|Text"
val ctx = listOf("see", "the")

/** A suggestion that was clearly visible (full weight). */
fun seen(text: String, rank: Int = 0) =
    ShownSuggestion(text, rank, shownAtChars = 1, visibleMillis = 800)

/** A suggestion that appeared on the last character (user likely didn't see it). */
fun late(text: String, rank: Int = 0, typedLength: Int) =
    ShownSuggestion(text, rank, shownAtChars = typedLength, visibleMillis = 80)

fun touches(word: String) = word.mapIndexed { i, c -> TouchSample(c, 0.1f * i, -0.05f) }

fun committed(
    typed: String,
    committed: String = typed,
    method: CommitMethod = CommitMethod.Typed,
    shown: List<ShownSuggestion> = emptyList(),
    pickedRank: Int? = null,
    known: Boolean = true,
    typedKnown: Boolean = known,
    touches: List<TouchSample?> = emptyList(),
    sentenceInitial: Boolean = false,
    tieRunnerUp: String? = null,
    sessionId: Long = 7,
    field: FieldClass = FieldClass.Text,
) =
    WordCommitted(
        timeMillis = 1_000,
        profile = profile.copy(field = field),
        context = ctx,
        typed = typed,
        committed = committed,
        method = method,
        pickedRank = pickedRank,
        shown = shown,
        touches = touches,
        sentenceInitial = sentenceInitial,
        committedIsKnown = known,
        typedIsKnown = typedKnown,
        tieRunnerUp = tieRunnerUp,
        sessionId = sessionId,
    )

fun reverted(
    typed: String,
    corrected: String,
    kind: RevertKind,
    replacement: String = typed,
) = AutocorrectReverted(1_000, profile, ctx, typed, corrected, kind, replacement)

fun replaced(
    original: String,
    replacement: String,
    picked: Boolean = false,
    millis: Long = 10_000,
) = WordReplaced(1_000, profile, ctx, original, replacement, picked, millis)
