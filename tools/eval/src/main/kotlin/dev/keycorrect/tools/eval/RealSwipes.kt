// The 3x3 least-squares solve in AffineMap is written out by matrix index.
@file:Suppress("MagicNumber")

package dev.keycorrect.tools.eval

import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.TouchPoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * One recorded swipe from the swipe.futo.org dataset (docs/EVAL.md, "Real swipes"), mapped onto the
 * [EvalKeyboard]: [target] is the word without surrounding punctuation, [context] the words of its
 * sentence before it (the dataset has no committed text, so this is oracle context).
 */
class RealSwipe(
    val target: String,
    val context: String,
    val gesture: GestureInput,
    /** Whether the dictionary has the word; no dictionary-bound decoder can return it otherwise. */
    val inDictionary: Boolean,
    /** The dataset's session id (one recording session of one person), "" in hand-made rows. */
    val session: String = "",
    /** Recording time of the row in epoch ms (0 when absent); orders a session's swipes. */
    val timestamp: Long = 0L,
    /** The dataset's row id (tie-break for equal timestamps). */
    val id: Long = 0L,
)

/** What [RealSwipes.load] kept and why it dropped the rest. */
class RealSwipeSet(
    val swipes: List<RealSwipe>,
    val rows: Int,
    val taps: Int,
    val unswipeable: Int,
    val map: AffineMap,
)

/**
 * Least-squares affine map from the dataset's normalised keyboard (both axes 0..1 over the three
 * letter rows) to [EvalKeyboard] pixels, fitted on the letter key centres both layouts share.
 * [rmsKeyWidths] is the fit's residual, so a layout mismatch shows up in the report.
 */
class AffineMap(private val x: DoubleArray, private val y: DoubleArray, val rmsKeyWidths: Double) {
    /** The six parameters `[a, b, c, d, e, f]` of `x' = a u + b v + c`, `y' = d u + e v + f`. */
    fun coefficients(): DoubleArray = doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2])

    fun mapX(u: Double, v: Double) = (x[0] * u + x[1] * v + x[2]).toFloat()

    fun mapY(u: Double, v: Double) = (y[0] * u + y[1] * v + y[2]).toFloat()

    companion object {
        /** Fits `dst ≈ A · (u, v, 1)` over corresponding points; [keyWidth] scales the residual. */
        fun fit(src: List<DoubleArray>, dst: List<DoubleArray>, keyWidth: Double): AffineMap {
            require(src.size == dst.size && src.size >= 3) { "need 3+ point pairs" }
            val n = Array(3) { DoubleArray(3) }
            val bx = DoubleArray(3)
            val by = DoubleArray(3)
            for (i in src.indices) {
                val r = doubleArrayOf(src[i][0], src[i][1], 1.0)
                for (a in 0 until 3) {
                    for (b in 0 until 3) n[a][b] += r[a] * r[b]
                    bx[a] += r[a] * dst[i][0]
                    by[a] += r[a] * dst[i][1]
                }
            }
            val ax = solve3(n, bx)
            val ay = solve3(n, by)
            var sq = 0.0
            for (i in src.indices) {
                val (u, v) = src[i][0] to src[i][1]
                val dx = ax[0] * u + ax[1] * v + ax[2] - dst[i][0]
                val dy = ay[0] * u + ay[1] * v + ay[2] - dst[i][1]
                sq += dx * dx + dy * dy
            }
            return AffineMap(ax, ay, sqrt(sq / src.size) / keyWidth)
        }

        /** Cramer's rule; the 3x3 normal matrix of a non-degenerate key layout is invertible. */
        private fun solve3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            fun det(a: Array<DoubleArray>) =
                a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
                    a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
                    a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
            val d = det(m)
            require(d != 0.0) { "degenerate layout fit" }
            return DoubleArray(3) { col ->
                det(Array(3) { r -> DoubleArray(3) { c -> if (c == col) b[r] else m[r][c] } }) / d
            }
        }
    }
}

object RealSwipes {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads up to [limit] rows (0 = all) of a swipe-1 JSON Lines split, in file order. Taps (fewer
     * than two points) and words that can't be swiped on [keyboard] (one letter, digits, letters
     * not on the layer) are counted and dropped, as the synthetic eval does.
     */
    fun load(
        file: Path,
        layoutFile: Path,
        keyboard: EvalKeyboard,
        limit: Int,
        known: (String) -> Boolean,
    ): RealSwipeSet {
        val map = fitLayout(layoutFile, keyboard)
        val swipes = ArrayList<RealSwipe>()
        var rows = 0
        var taps = 0
        var unswipeable = 0
        Files.newBufferedReader(file).useLines { lines ->
            val rowLines = lines.filter { it.isNotBlank() }
            for (line in if (limit > 0) rowLines.take(limit) else rowLines) {
                rows++
                when (
                    val r = parse(json.parseToJsonElement(line).jsonObject, keyboard, map, known)
                ) {
                    Row.Tap -> taps++
                    Row.Unswipeable -> unswipeable++
                    is Row.Swipe -> swipes += r.swipe
                }
            }
        }
        return RealSwipeSet(swipes, rows, taps, unswipeable, map)
    }

    private sealed interface Row {
        data object Tap : Row

        data object Unswipeable : Row

        class Swipe(val swipe: RealSwipe) : Row
    }

    private fun parse(
        row: JsonObject,
        keyboard: EvalKeyboard,
        map: AffineMap,
        known: (String) -> Boolean,
    ): Row {
        val points = row.getValue("data").jsonArray
        val target = trimPunctuation(row.str("word"))
        return when {
            points.size < 2 -> Row.Tap
            !swipeable(target, keyboard) -> Row.Unswipeable
            else -> {
                val t0 = points[0].jsonObject.getValue("t").jsonPrimitive.long
                val touches = points.map {
                    val p = it.jsonObject
                    val u = p.num("x")
                    val v = p.num("y")
                    TouchPoint(
                        map.mapX(u, v),
                        map.mapY(u, v),
                        p.getValue("t").jsonPrimitive.long - t0,
                    )
                }
                val index = row.getValue("word_idx").jsonPrimitive.int
                Row.Swipe(
                    RealSwipe(
                        target,
                        context(row.str("sentence"), index),
                        GestureInput(touches),
                        known(target),
                        row["session"]?.jsonPrimitive?.content.orEmpty(),
                        row["timestamp"]?.jsonPrimitive?.long ?: 0L,
                        row["id"]?.jsonPrimitive?.long ?: 0L,
                    )
                )
            }
        }
    }

    /** The affine map from the dataset's QWERTY (`layouts/qwerty.json`) onto [keyboard]. */
    fun fitLayout(layoutFile: Path, keyboard: EvalKeyboard): AffineMap {
        val src = ArrayList<DoubleArray>()
        val dst = ArrayList<DoubleArray>()
        for (k in
            json
                .parseToJsonElement(Files.readString(layoutFile))
                .jsonObject
                .getValue("keys")
                .jsonArray) {
            val key = k.jsonObject
            val ours = keyboard.key(key.str("letter").single()) ?: continue
            src += doubleArrayOf(key.num("cx"), key.num("cy"))
            dst += doubleArrayOf(ours.centerX.toDouble(), ours.centerY.toDouble())
        }
        val keyWidth = keyboard.layout.keys.map { it.width.toDouble() }.average()
        return AffineMap.fit(src, dst, keyWidth)
    }

    /**
     * "Atri," -> "Atri", "(the" -> "the"; digits and inner apostrophes stay ("4th" is not "th").
     */
    internal fun trimPunctuation(word: String): String = word.trim { !it.isLetterOrDigit() }

    /** Two or more letters, nothing but letters and apostrophes, every letter on the layer. */
    internal fun swipeable(word: String, keyboard: EvalKeyboard): Boolean =
        word.count { it.isLetter() } >= 2 &&
            word.all { (it.isLetter() && keyboard.hasKey(it)) || it == '\'' || it == '’' }

    /** The sentence's words before word [index] plus a space, or "" for the first word. */
    internal fun context(sentence: String, index: Int): String {
        val words = sentence.split(' ')
        if (index <= 0 || index > words.size) return ""
        return words.subList(0, index).joinToString(" ") + " "
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.double
}
