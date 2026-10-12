package dev.keycorrect.core.clipboard

/** Removes tracking query parameters from a copied URL. */
object UrlCleaner {
    private val SINGLE_URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    /** Exact (lowercase) parameter names that are tracking only. */
    private val TRACKING_NAMES =
        setOf(
            "fbclid",
            "gclid",
            "dclid",
            "msclkid",
            "mc_eid",
            "mc_cid",
            "igshid",
            "ref_src",
            "_hsenc",
            "_hsmi",
            "yclid",
            "twclid",
            "ttclid",
            "wbraid",
            "gbraid",
            "mkt_tok",
            "vero_id",
            "oly_enc_id",
            "oly_anon_id",
        )

    /** Parameter prefixes that are tracking only (utm_source, utm_medium, ...). */
    private const val UTM_PREFIX = "utm_"

    /** `si` is a share-tracking id, but only on these hosts (it means other things elsewhere). */
    private val SI_HOSTS = listOf("youtube.com", "youtu.be", "spotify.com", "spotify.link")

    /**
     * The URL without tracking parameters (order of the rest and the fragment kept), or null when
     * nothing changed or [url] is not a single http(s) URL.
     */
    fun clean(url: String): String? {
        val t = url.trim()
        return if (SINGLE_URL.matches(t)) cleanQuery(t) else null
    }

    private fun cleanQuery(t: String): String? {
        val hashAt = t.indexOf('#').let { if (it < 0) t.length else it }
        val fragment = t.substring(hashAt)
        val beforeFragment = t.substring(0, hashAt)
        val qAt = beforeFragment.indexOf('?')
        val base = if (qAt < 0) beforeFragment else beforeFragment.substring(0, qAt)
        val params = if (qAt < 0) emptyList() else beforeFragment.substring(qAt + 1).split('&')
        val siAllowed = isSiHost(hostOf(base))
        val kept = params.filterNot { isTracking(it.substringBefore('='), siAllowed) }
        return if (kept.size == params.size) {
            null
        } else {
            base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
        }
    }

    private fun isTracking(name: String, siAllowed: Boolean): Boolean {
        val n = name.lowercase()
        return n.startsWith(UTM_PREFIX) || n in TRACKING_NAMES || (siAllowed && n == "si")
    }

    private fun hostOf(base: String): String =
        base
            .substringAfter("://")
            .substringBefore('/')
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()

    private fun isSiHost(host: String): Boolean = SI_HOSTS.any {
        host == it || host.endsWith(".$it")
    }
}
