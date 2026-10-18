package dev.keycorrect.feature.voicesherpa

import java.security.MessageDigest
import java.util.Locale

/**
 * The signing check, as a pure function (the Android lookups live in [VoicePackLocator]). The pack
 * carries native code that the keyboard loads into its own process, so it must be signed by exactly
 * the keyboard's own signer set; anything else, including an unreadable signature, is refused.
 * Signers are SHA-256 digests of the certificates (see [certDigest]).
 */
internal fun checkPackSigners(
    packSigners: Set<String>?,
    ownSigners: Set<String>,
): VoicePackProblem? =
    when {
        // We can't say who we are, so we can't vouch for anybody else.
        ownSigners.isEmpty() -> VoicePackProblem.SignatureMismatch
        packSigners.isNullOrEmpty() -> VoicePackProblem.SignatureMismatch
        packSigners != ownSigners -> VoicePackProblem.SignatureMismatch
        else -> null
    }

/** Lower-case hex SHA-256 of a DER certificate. */
internal fun certDigest(cert: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") {
        "%02x".format(Locale.ROOT, it)
    }
