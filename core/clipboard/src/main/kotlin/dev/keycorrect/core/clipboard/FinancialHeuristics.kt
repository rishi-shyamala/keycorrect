package dev.keycorrect.core.clipboard

/** Card-number and IBAN checks. Internal: use [ClipSensitivity.classify]. */
internal object FinancialHeuristics {
    // --- payment cards -------------------------------------------------------------------

    private val CARD_SHAPE = Regex("^\\d(?:[ -]?\\d)*$")
    private const val CARD_MIN = 13
    private const val CARD_MAX = 19

    /** 13-19 digits, spaces or dashes allowed between digits, passing the Luhn check. */
    fun isPaymentCard(t: String): Boolean {
        if (!CARD_SHAPE.matches(t)) return false
        val digits = t.filter { it.isDigit() }
        return digits.length in CARD_MIN..CARD_MAX && luhnValid(digits)
    }

    fun luhnValid(digits: String): Boolean {
        var sum = 0
        var dbl = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (dbl) {
                d *= 2
                if (d > DIGIT_MAX) d -= DIGIT_MAX
            }
            sum += d
            dbl = !dbl
        }
        return sum % DECIMAL == 0
    }

    private const val DIGIT_MAX = 9
    private const val DECIMAL = 10

    // --- IBAN ----------------------------------------------------------------------------

    private val IBAN_SHAPE = Regex("^[A-Za-z]{2}\\d{2}(?: ?[A-Za-z0-9]){11,30}$")
    private const val IBAN_MIN = 15
    private const val IBAN_MAX = 34
    private const val IBAN_MOD = 97
    private const val LETTER_OFFSET = 10

    /**
     * Country code + 2 check digits + up to 30 alphanumerics (spaces allowed), ISO 13616 mod-97.
     */
    fun isIban(t: String): Boolean {
        val iban = t.replace(" ", "").uppercase()
        return IBAN_SHAPE.matches(t) && iban.length in IBAN_MIN..IBAN_MAX && mod97(iban) == 1
    }

    private fun mod97(iban: String): Int {
        var rem = 0
        for (c in iban.substring(IBAN_HEADER) + iban.substring(0, IBAN_HEADER)) {
            val v = if (c.isDigit()) c - '0' else c - 'A' + LETTER_OFFSET
            rem = (if (v >= DECIMAL) rem * TWO_DIGITS + v else rem * DECIMAL + v) % IBAN_MOD
        }
        return rem
    }

    private const val TWO_DIGITS = 100
    private const val IBAN_HEADER = 4
}
