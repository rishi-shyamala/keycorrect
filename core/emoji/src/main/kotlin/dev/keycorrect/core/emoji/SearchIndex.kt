package dev.keycorrect.core.emoji

/** Lowercased lookup tables over a catalog; built once, lazily, on the first query. */
internal class SearchIndex(groups: List<EmojiGroup>) {
    private val emoji: List<Emoji> = groups.flatMap { it.emoji }
    private val names = emoji.map { it.name.lowercase() }
    private val keywords = emoji.map { e -> e.keywords.map { it.lowercase() }.distinct() }
    private val byValue = HashMap<String, Emoji>(emoji.size * 2)
    private val byName = HashMap<String, Emoji>(emoji.size * 2)
    private val keywordOwners = HashMap<String, Emoji>()
    private val keywordShared = HashSet<String>()

    init {
        for ((i, e) in emoji.withIndex()) {
            byValue.putIfAbsent(e.value, e)
            for (v in e.variants) byValue.putIfAbsent(v.value, e)
            byName.putIfAbsent(names[i], e)
            for (k in keywords[i]) {
                if (keywordOwners.putIfAbsent(k, e) != null) keywordShared += k
            }
        }
    }

    fun byValue(value: String): Emoji? = byValue[value]

    fun strongMatch(word: String): Emoji? {
        val w = word.trim().lowercase()
        if (w.length < MIN_STRONG_LENGTH || w in STOP_WORDS) return null
        return byName[w] ?: keywordOwners[w]?.takeIf { w !in keywordShared }
    }

    fun search(query: String, limit: Int): List<Emoji> {
        val q = query.trim().lowercase()
        if (q.isEmpty() || limit <= 0) return emptyList()
        val tiers = List(TIERS) { ArrayList<Emoji>() }
        for ((i, e) in emoji.withIndex()) {
            val tier = tierOf(i, q)
            if (tier >= 0 && tiers[tier].size < limit) tiers[tier] += e
        }
        return tiers.flatten().take(limit)
    }

    /** 0 = exact name, 1 = exact keyword, 2 = prefix of name/keyword (or of a word in it). */
    private fun tierOf(i: Int, q: String): Int =
        when {
            names[i] == q -> 0
            q in keywords[i] -> 1
            hasWordPrefix(names[i], q) || keywords[i].any { hasWordPrefix(it, q) } -> 2
            else -> -1
        }

    private fun hasWordPrefix(text: String, q: String): Boolean {
        var from = -1
        do {
            if (text.startsWith(q, from + 1)) return true
            from = text.indexOf(' ', from + 1)
        } while (from >= 0)
        return false
    }

    private companion object {
        const val TIERS = 3
        const val MIN_STRONG_LENGTH = 3

        /** Very common words, and generic names, that must never trigger a suggestion. */
        val STOP_WORDS: Set<String> =
            ("about after again all also and any are back because been but can come could day " +
                    "did does down for from get going good had has have her here him his how into " +
                    "its just know like little make many more much must new not now off one only " +
                    "other our out over really right said see she should some than that the their " +
                    "them then there these they this time too two very want was way well were what " +
                    "when where which who will with would yes you your man woman boy girl person " +
                    "people face hand hands baby set top end")
                .split(' ')
                .toSet()
    }
}
