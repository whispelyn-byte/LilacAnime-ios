package com.lilac.anime.shared

/** main.cjs tmdbQueries/titleCompareKey/siblingTitle. */
object DesktopTmdbRules {
    fun queries(titles: List<String>): List<String> = buildList {
        for (title in titles) {
            val base = title.replace("…", "...").replace(Regex("[◎○●☆★]+"), " ")
                .replace(Regex("\\s*(?:OVA|OAD|ONA|specials?|recap)\\s*\\d*\\s*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(?:season\\s*\\d+|\\d+(?:st|nd|rd|th)\\s*season|part\\s*\\d+|第\\d+期)\\s*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("[:：]\\s*$"), "").trim()
            for (query in listOf(base, base.split(Regex("\\s*[:：]\\s+|\\s+-\\s+")).first()))
                if (query.isNotBlank() && !Regex("[가-힣]").containsMatchIn(query) && query !in this) add(query)
        }
    }
    fun key(value: String) = normalizeDesktopTitle(value).lowercase().replace("…", "...")
        .replace(Regex("[\\s:：'’\"“”!！?？.,·・\\-–—~〜()（）]"), "")
    fun sibling(first: String, second: String): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false
        fun words(value: String) = DesktopTitleRules.simple(value).split(' ').filter { it.length >= 2 }.distinct()
        val a = words(first); val b = words(second)
        fun found(word: String, list: List<String>) = list.any { it.contains(word) || word.contains(it) }
        return a.count { found(it, b) } >= 2 && a.any { !found(it, b) } && b.any { !found(it, a) }
    }
}
