package dev.keycorrect.core.clipboard

/** Key-pattern, entropy and password-shape checks. Internal: use [ClipSensitivity.classify]. */
internal object SecretHeuristics {
    // --- API keys and tokens -------------------------------------------------------------

    /**
     * The single list of well-known secret formats. Each matches anywhere in the text (the
     * lookbehind keeps "task-..." from matching "sk-"). Add new providers here only.
     */
    private val KEY_PATTERNS: List<Regex> =
        listOf(
                // OpenAI / Anthropic style: sk-..., sk-ant-...
                "(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{16,}",
                // Stripe secret / restricted keys
                "(?<![A-Za-z0-9])[sr]k_(?:live|test)_[A-Za-z0-9]{16,}",
                // GitHub tokens: ghp_ gho_ ghu_ ghs_ ghr_
                "(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{30,}",
                "(?<![A-Za-z0-9])github_pat_[A-Za-z0-9_]{30,}",
                // Slack tokens
                "(?<![A-Za-z0-9])xox[abposr]-[A-Za-z0-9-]{10,}",
                // AWS access key id
                "(?<![A-Za-z0-9])AKIA[0-9A-Z]{16}(?![A-Za-z0-9])",
                // Google API key
                "(?<![A-Za-z0-9])AIza[0-9A-Za-z_-]{35}",
                // JWT: three base64url segments (header always starts with eyJ)
                "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*",
                // PEM private keys
                "-----BEGIN [A-Z ]*PRIVATE KEY-----",
            )
            .map { Regex(it) }

    fun matchesKeyPattern(t: String): Boolean = KEY_PATTERNS.any { it.containsMatchIn(t) }

    // --- high-entropy token --------------------------------------------------------------

    private const val ENTROPY_MIN_LENGTH = 24

    /**
     * A token is flagged when its Shannon entropy exceeds this fraction of log2(length), i.e. of
     * the most a string of that length could have. A fixed bits-per-char limit does not work:
     * entropy grows with length, so natural 40-char slugs and class names (3.9-4.1 bits, ratio
     * 0.73-0.77) overlap random 24-char tokens (about 4.0-4.5 bits) in absolute terms, while the
     * ratio separates them (random tokens: 0.84-1.0).
     */
    const val ENTROPY_RATIO_THRESHOLD = 0.82
    private const val ENTROPY_MIN_CLASSES = 3

    private val URL_START = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val EMAIL = Regex("^[^\\s@/]+@[^\\s@/]+\\.[A-Za-z]{2,}$")
    private val WINDOWS_PATH = Regex("^[A-Za-z]:[\\\\/]")

    /** A single token that is not obviously a URL, email address or absolute path. */
    private fun isPlainToken(t: String): Boolean =
        t.none { it.isWhitespace() } &&
            !URL_START.containsMatchIn(t) &&
            !EMAIL.matches(t) &&
            !t.startsWith("/") &&
            !t.startsWith("~/") &&
            !WINDOWS_PATH.containsMatchIn(t)

    private fun charClasses(t: String): Int =
        listOf(
                t.any { it.isLowerCase() },
                t.any { it.isUpperCase() },
                t.any { it.isDigit() },
                t.any { !it.isLetterOrDigit() },
            )
            .count { it }

    fun shannonEntropy(t: String): Double {
        val counts = t.groupingBy { it }.eachCount()
        val n = t.length.toDouble()
        return -counts.values.sumOf { c ->
            val p = c / n
            p * (kotlin.math.ln(p) / kotlin.math.ln(2.0))
        }
    }

    fun isHighEntropyToken(t: String): Boolean =
        t.length >= ENTROPY_MIN_LENGTH &&
            isPlainToken(t) &&
            charClasses(t) >= ENTROPY_MIN_CLASSES &&
            entropyRatio(t) > ENTROPY_RATIO_THRESHOLD

    fun entropyRatio(t: String): Double =
        shannonEntropy(t) / (kotlin.math.ln(t.length.toDouble()) / kotlin.math.ln(2.0))

    // --- password-like -------------------------------------------------------------------

    private const val PASSWORD_MIN = 8
    private const val PASSWORD_MAX = 64

    /** One token of 8-64 chars containing upper, lower, digit and symbol. */
    fun isPasswordLike(t: String): Boolean =
        t.length in PASSWORD_MIN..PASSWORD_MAX && isPlainToken(t) && charClasses(t) == ALL_CLASSES

    private const val ALL_CLASSES = 4
}
