package dev.keycorrect.core.swipenet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * One case of `swipenet-goldens.json`, written by tools/swipenet/make-goldens.py with the official
 * executorch==1.5.1 wheel. Float arrays are base64 little-endian float32.
 */
class GoldenCase(
    val name: String,
    val chars: String,
    val x: FloatArray,
    val y: FloatArray,
    val t: FloatArray,
    val features: FloatArray,
    val keys: FloatArray,
    val keyCount: Int,
    val logEmissions: FloatArray,
    val coefficients: FloatArray,
    val lambda: FloatArray,
) {
    val mask: ByteArray
        get() = ByteArray(SwipeLayout.MAX_KEYS) { if (it < keyCount) 1 else 0 }

    /** Greedy CTC decode of [emissions] over this case's key characters. */
    fun greedy(emissions: FloatArray): String = buildString {
        var prev = -1
        for (step in 0 until SwipeEmissions.STEPS) {
            var best = 0
            for (c in 0 until SwipeEmissions.CLASSES) {
                val row = step * SwipeEmissions.CLASSES
                if (emissions[row + c] > emissions[row + best]) best = c
            }
            if (best != prev && best != SwipeEmissions.BLANK && best < keyCount) append(chars[best])
            prev = best
        }
    }
}

object Goldens {
    val cases: List<GoldenCase> by lazy { parse(load()) }

    private fun load(): String =
        checkNotNull(Goldens::class.java.getResourceAsStream("/swipenet-goldens.json")) {
                "swipenet-goldens.json missing"
            }
            .use { it.readBytes().decodeToString() }

    // A tiny reader for the generator's flat JSON (string and integer fields only), so the module
    // needs no JSON library.
    private fun parse(json: String): List<GoldenCase> {
        val body = json.substringAfter("\"cases\"")
        return OBJECT.findAll(body)
            .map { obj ->
                val fields =
                    FIELD.findAll(obj.value).associate { it.groupValues[1] to it.groupValues[2] }
                fun str(key: String) = fields.getValue(key).trim('"')
                fun floats(key: String) = floatsOf(str(key))
                GoldenCase(
                    name = str("name"),
                    chars = str("chars"),
                    x = floats("x"),
                    y = floats("y"),
                    t = floats("t"),
                    features = floats("features"),
                    keys = floats("keys"),
                    keyCount = str("keyCount").toInt(),
                    logEmissions = floats("logEmissions"),
                    coefficients = floats("coefficients"),
                    lambda = floats("lambda"),
                )
            }
            .toList()
    }

    private fun floatsOf(b64: String): FloatArray {
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(buf.remaining() / Float.SIZE_BYTES) { buf.getFloat() }
    }

    private val OBJECT = Regex("""\{[^{}]*\}""")
    private val FIELD = Regex(""""(\w+)":\s*("[^"]*"|-?\d+)""")
}
