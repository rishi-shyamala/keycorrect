package dev.keycorrect.core.clipboard

/** What the clipboard history should do with a piece of copied text. */
sealed interface Sensitivity {
    /** Do not store at all. */
    data object Never : Sensitivity

    /** Store briefly (OTP-like codes); previews are masked. */
    data class ShortLived(val ttlMillis: Long) : Sensitivity

    /** Store as an ordinary clip. */
    data object Normal : Sensitivity
}

/** Each heuristic can be switched off from settings. */
data class SensitivityRules(
    val paymentCards: Boolean = true,
    val ibans: Boolean = true,
    val apiKeys: Boolean = true,
    val highEntropy: Boolean = true,
    /**
     * Single token with upper, lower, digit and symbol, 8-64 chars. This has a real false-positive
     * risk (for example "Covid-19" or "Hello123!" are flagged); the cost of a miss is a leaked
     * password, so it defaults to on.
     */
    val passwordLike: Boolean = true,
    val otpCodes: Boolean = true,
    val otpTtlMillis: Long = DEFAULT_OTP_TTL_MILLIS,
) {
    companion object {
        const val DEFAULT_OTP_TTL_MILLIS = 180_000L
    }
}

/**
 * Heuristic classifier for copied text. Pure: never logs, never retains the text.
 *
 * Order: card, IBAN, key patterns, high-entropy token, password-like (all -> [Sensitivity.Never]),
 * then OTP -> [Sensitivity.ShortLived], else [Sensitivity.Normal].
 */
object ClipSensitivity {
    fun classify(text: String, rules: SensitivityRules = SensitivityRules()): Sensitivity {
        val t = text.trim()
        return when {
            t.isEmpty() -> Sensitivity.Normal
            isNeverStored(t, rules) -> Sensitivity.Never
            rules.otpCodes && isOtpLike(t) -> Sensitivity.ShortLived(rules.otpTtlMillis)
            else -> Sensitivity.Normal
        }
    }

    private fun isNeverStored(t: String, r: SensitivityRules): Boolean =
        isFinancial(t, r) || isSecret(t, r)

    private fun isFinancial(t: String, r: SensitivityRules): Boolean =
        (r.paymentCards && FinancialHeuristics.isPaymentCard(t)) ||
            (r.ibans && FinancialHeuristics.isIban(t))

    private fun isSecret(t: String, r: SensitivityRules): Boolean =
        (r.apiKeys && SecretHeuristics.matchesKeyPattern(t)) ||
            (r.highEntropy && SecretHeuristics.isHighEntropyToken(t)) ||
            (r.passwordLike && SecretHeuristics.isPasswordLike(t))

    // --- OTP -----------------------------------------------------------------------------

    private val OTP_ALONE = Regex("^(?:\\d{4,8}|\\d{2,4} \\d{2,4})$")
    private val OTP_GROUP = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
    private val OTP_WORD =
        Regex("\\b(?:code|otp|verification|passcode)\\b", RegexOption.IGNORE_CASE)
    private const val OTP_TEXT_MAX = 200
    private const val OTP_ALONE_DIGITS_MAX = 8
    private const val OTP_MIN_DIGITS = 4

    /**
     * 4-8 digits alone (optionally split by one space), or a short text (<= 200 chars) that
     * mentions "code"/"OTP"/"verification" and contains a 4-8 digit group.
     */
    internal fun isOtpLike(t: String): Boolean {
        if (OTP_ALONE.matches(t))
            return t.count { it.isDigit() } in OTP_MIN_DIGITS..OTP_ALONE_DIGITS_MAX
        return t.length <= OTP_TEXT_MAX &&
            OTP_WORD.containsMatchIn(t) &&
            OTP_GROUP.containsMatchIn(t)
    }
}
