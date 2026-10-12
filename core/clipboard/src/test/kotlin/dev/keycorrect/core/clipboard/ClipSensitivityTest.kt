package dev.keycorrect.core.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClipSensitivityTest {
    private fun classify(text: String, rules: SensitivityRules = SensitivityRules()) =
        ClipSensitivity.classify(text, rules)

    private fun assertNever(text: String) = assertThat(classify(text)).isEqualTo(Sensitivity.Never)

    private fun assertNormal(text: String) =
        assertThat(classify(text)).isEqualTo(Sensitivity.Normal)

    private fun assertShortLived(text: String) =
        assertThat(classify(text)).isEqualTo(Sensitivity.ShortLived(180_000))

    // --- cards ---

    @Test
    fun luhnValidCardsAreNever() {
        listOf(
                "4111111111111111",
                "4111 1111 1111 1111",
                "5555-5555-5555-4444",
                "378282246310005",
                "4012 8888 8888 1881",
                "  4242 4242 4242 4242 \n",
                "6011111111111117",
            )
            .forEach { assertNever(it) }
    }

    @Test
    fun luhnInvalidDigitStringsAreNotCards() {
        listOf("4111 1111 1111 1112", "1234567890123456", "5555555555554443").forEach {
            assertNormal(it)
        }
    }

    @Test
    fun cardLengthBounds() {
        assertNormal("424242424242") // 12 digits, valid Luhn but too short
        assertNormal("4242424242424242424242") // far too long
    }

    @Test
    fun cardInsideSentenceIsNotFlaggedByCardRule() {
        assertNormal("My number is 4111 1111 1111 1111 ok")
    }

    // --- IBAN ---

    @Test
    fun realIbansAreNever() {
        listOf(
                "GB82 WEST 1234 5698 7654 32",
                "GB82WEST12345698765432",
                "DE89 3704 0044 0532 0130 00",
                "FR14 2004 1010 0505 0001 3M02 606",
                "NL91 ABNA 0417 1643 00",
                "gb82 west 1234 5698 7654 32",
            )
            .forEach { assertNever(it) }
    }

    @Test
    fun ibanWithBadChecksumIsNormal() {
        assertNormal("GB82 WEST 1234 5698 7654 33")
        assertNormal("DE00 3704 0044 0532 0130 00")
    }

    @Test
    fun ibanLikeButShortIsNormal() {
        assertNormal("DE12 3456")
    }

    // --- API keys ---

    @Test
    fun knownKeyFormatsAreNever() {
        listOf(
                "sk-proj-abcdefghijkl" + "mnopqrstuvwxyz012345",
                "sk-ant-api03-A" + "bCdEfGhIjKlMnOp",
                "sk_" + "live_4eC39HqLyjWDarjtT1zdp7dc",
                "ghp_16C7e42F292c6912" + "E7710c838347Ae178B4a",
                "gho_16C7e42F292c6912" + "E7710c838347Ae178B4a",
                "github_pat_11ABCDEFG0abcdefghijk" + "l_mnopqrstuvwxyz0123456789ABCDEF",
                "xoxb-123456789012-1234567890" + "123-AbCdEfGhIjKlMnOpQrStUvWx",
                "AKIAIOSFOD" + "NN7EXAMPLE",
                "AIzaSyA-1234567890ab" + "cdefghijklmnopqrstuv",
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0N" + "TY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
                "-----BEGIN RSA PRIVATE KEY-----\nMII" + "Eow...\n-----END RSA PRIVATE KEY-----",
                "-----BEGIN PR" + "IVATE KEY-----",
                "export OPENAI_API_KEY=sk-" + "abcdefghijklmnop1234567890",
            )
            .forEach { assertNever(it) }
    }

    @Test
    fun keyPatternLookalikesAreNormal() {
        listOf(
                "task-management-w" + "ith-kotlin-is-fun",
                "sk-short",
                "The risk-ave" + "rse investor",
                "ghp_ is the prefix of GitHub tokens",
                "-----BEGIN CE" + "RTIFICATE-----",
            )
            .forEach { assertNormal(it) }
    }

    // --- high entropy ---

    @Test
    fun randomLookingTokensAreNever() {
        listOf(
                "hQ9x2LmPz7Rk4VtYw8NbC3dFg6Js",
                "q8Z2xLp0VnT4mKa9RcYb6WdE1sHu7GfJ",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "Zm9vYmFyOjB4N1FyVDJrTGM5dVhBcG5Q",
                "3f8A!k2Lq9Zx7Pm4Rt6Vb1Nc5Wd0Sy8H",
            )
            .forEach { assertNever(it) }
    }

    @Test
    fun entropyRuleCanBeDisabled() {
        val text = "q8Z2xLp0VnT4mKa9RcYb6WdE1sHu7GfJ"
        val rules = SensitivityRules(highEntropy = false, passwordLike = false)
        assertThat(classify(text, rules)).isEqualTo(Sensitivity.Normal)
    }

    @Test
    fun ordinaryUrlsAreNormal() {
        listOf(
                "https://github.com/rishi-shyamala/keycorrect/pull/5/files",
                "https://www.example.com/blog/2024/10/how-to-write-a-good-commit-message",
                "https://en.wikipedia.org/wiki/Donaudampfschifffahrtsgesellschaftskapit%C3%A4n",
                "https://maps.google.com/maps?q=Berlin+Hauptbahnhof&hl=de&z=15",
                "https://youtu.be/dQw4w9WgXcQ?si=AbCdEfGhIjKlMnOp",
                "http://localhost:8080/api/v1/users/12345/profile?expand=true",
            )
            .forEach { assertNormal(it) }
    }

    @Test
    fun emailsAndPathsAreNormal() {
        listOf(
                "firstname.lastname+newsletter@some-company-name.example.com",
                "/home/rishi/Projects/keycorrect/core/clipboard/src/main/kotlin",
                "~/Documents/Taxes/2024/Steuererklaerung_final_v2.pdf",
                "C:\\Users\\Rishi\\Documents\\Quarterly Reports\\Q3-2024.xlsx",
            )
            .forEach { assertNormal(it) }
    }

    @Test
    fun longGermanWordsAreNormal() {
        listOf(
                "Donaudampfschifffahrtsgesellschaftskapitaen",
                "Rindfleischetikettierungsueberwachungsaufgabenuebertragungsgesetz",
                "Arbeiterunfallversicherungsgesetz",
                "Kraftfahrzeughaftpflichtversicherung",
                "Rechtsschutzversicherungsgesellschaften",
            )
            .forEach { assertNormal(it) }
    }

    @Test
    fun slugsAndIdentifiersAreNormal() {
        listOf(
                "my-super-long-article-title-for-the-blog",
                "com.example.myapplication.feature.MainActivity",
                "ClipboardHistoryRepositoryImplementation",
                "this_is_a_very_long_snake_case_name_2024",
                "src/main/kotlin/dev/keycorrect/core/Foo.kt",
            )
            .forEach { assertNormal(it) }
    }

    // --- password-like ---

    @Test
    fun filenameWithAllFourClassesIsAKnownPasswordFalsePositive() {
        val name = "Steuererklaerung_final_v2_Berlin_2024.pdf"
        assertNever(name)
        assertThat(classify(name, SensitivityRules(passwordLike = false)))
            .isEqualTo(Sensitivity.Normal)
    }

    @Test
    fun passwordLikeTokensAreNeverByDefault() {
        listOf("Tr0ub4dor&3", "P@ssw0rd!", "c0rrect-Horse_battery").forEach { assertNever(it) }
    }

    @Test
    fun passwordLikeRuleCanBeDisabled() {
        val rules = SensitivityRules(passwordLike = false)
        assertThat(classify("Tr0ub4dor&3", rules)).isEqualTo(Sensitivity.Normal)
    }

    @Test
    fun passwordLikeNeedsAllFourClassesAndOneToken() {
        assertNormal("password123")
        assertNormal("Password123")
        assertNormal("Tr0ub4dor 3 &")
        assertNormal("Ab1!") // too short
        assertNormal("Ab1!" + "x".repeat(70)) // too long
    }

    @Test
    fun urlsAndEmailsAreNotPasswordLike() {
        assertNormal("https://Example.com/Path-1")
        assertNormal("John.Smith1@Example.com")
    }

    // --- OTP ---

    @Test
    fun bareDigitCodesAreShortLived() {
        listOf("1234", "482913", "12345678", " 482913\n", "482 913", "1234 5678").forEach {
            assertShortLived(it)
        }
    }

    @Test
    fun codeInAMessageIsShortLived() {
        listOf(
                "Your verification code is 482913",
                "G-482913 is your Google verification code.",
                "OTP: 7391",
                "Use code 55-1234 to confirm",
            )
            .forEach { assertShortLived(it) }
    }

    @Test
    fun otpTtlFollowsRules() {
        val rules = SensitivityRules(otpTtlMillis = 60_000)
        assertThat(classify("482913", rules)).isEqualTo(Sensitivity.ShortLived(60_000))
    }

    @Test
    fun otpRuleCanBeDisabled() {
        assertThat(classify("482913", SensitivityRules(otpCodes = false)))
            .isEqualTo(Sensitivity.Normal)
    }

    @Test
    fun nonOtpDigitStringsAreNormal() {
        listOf("123", "123456789", "12 3", "12345 678901", "1,234", "3.14159").forEach {
            assertNormal(it)
        }
    }

    @Test
    fun yearsInSentencesAreNormal() {
        listOf(
                "In 2024 we moved to Berlin.",
                "The meeting is on 12.10.2026 at 10:30.",
                "2024-10-11",
                "Copyright 2019-2024 Example GmbH",
                "Das Haus wurde 1998 gebaut und 2015 renoviert.",
            )
            .forEach { assertNormal(it) }
    }

    @Test
    fun codeWordWithoutDigitGroupIsNormal() {
        assertNormal("Please review my code before merging")
        assertNormal("Source code of the verification module")
    }

    // --- general false-positive guards ---

    @Test
    fun ordinarySentencesAreNormal() {
        listOf(
                "Hello, how are you doing today?",
                "Let's meet at the cafe around 5 pm.",
                "Bitte schick mir die Rechnung bis Freitag, danke!",
                "Remember to buy milk, eggs, bread and 2 kg of potatoes.",
                "word",
                "a".repeat(40),
            )
            .forEach { assertNormal(it) }
    }

    @Test
    fun phoneNumbersAreNormal() {
        listOf("+49 170 1234567", "+1 (415) 555-0132", "030 12345678", "0170/1234567").forEach {
            assertNormal(it)
        }
    }

    @Test
    fun datesAndEmailsAreNormal() {
        listOf("2026-10-11", "11.10.2026", "10/11/2026", "rishi@example.com").forEach {
            assertNormal(it)
        }
    }

    @Test
    fun emptyAndBlankAreNormal() {
        assertNormal("")
        assertNormal("   \n ")
    }

    @Test
    fun allRulesOffTreatsEverythingAsNormal() {
        val off =
            SensitivityRules(
                paymentCards = false,
                ibans = false,
                apiKeys = false,
                highEntropy = false,
                passwordLike = false,
                otpCodes = false,
            )
        listOf("4111111111111111", "GB82 WEST 1234 5698 7654 32", "AKIAIOSFOD" + "NN7EXAMPLE", "123456")
            .forEach { assertThat(classify(it, off)).isEqualTo(Sensitivity.Normal) }
    }

    @Test
    fun entropyIsCalibratedBelowTheThresholdForNaturalText() {
        assertThat(SecretHeuristics.shannonEntropy("aaaa")).isWithin(1e-9).of(0.0)
        assertThat(SecretHeuristics.shannonEntropy("abcd")).isWithin(1e-9).of(2.0)
        listOf(
                "my-super-long-article-title-for-the-blog",
                "com.example.myapplication.feature.MainActivity",
                "Steuererklaerung_final_v2_Berlin_2024.pdf",
            )
            .forEach {
                assertThat(SecretHeuristics.entropyRatio(it))
                    .isLessThan(SecretHeuristics.ENTROPY_RATIO_THRESHOLD)
            }
        assertThat(SecretHeuristics.entropyRatio("q8Z2xLp0VnT4mKa9RcYb6WdE1sHu7GfJ"))
            .isGreaterThan(SecretHeuristics.ENTROPY_RATIO_THRESHOLD)
    }
}
