package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
object TitleCandidates {
    fun isKorean(title: String): Boolean = title.any { it in '\uac00'..'\ud7af' }
    fun namu(html: String, query: String): String? {
        fun normalize(text: String) = text.lowercase().replace("…", "...").replace(Regex("\\s+"), "")
        val q = normalize(query)
        val tokens = query.split(Regex("\\s+")).filter { it.length >= 2 }.map(::normalize)
        return Ksoup.parse(html, "https://namu.wiki/").select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href")
            val title = anchor.text().trim()
            if (!href.startsWith("https://namu.wiki/w/") || !isKorean(title) ||
                title in listOf("나무위키", "최근 변경", "최근 토론", "편집", "역링크")) return@mapNotNull null
            val card = normalize(anchor.closest("section")?.text().orEmpty())
            var score = if (q.isNotBlank() && card.contains(q)) 10000 else 0
            score += tokens.count { card.contains(it) } * 500
            if (anchor.parent()?.tagName() == "h4") score += 300
            title to score
        }.maxByOrNull { it.second }?.takeIf { it.second >= 500 }?.first
    }
}
