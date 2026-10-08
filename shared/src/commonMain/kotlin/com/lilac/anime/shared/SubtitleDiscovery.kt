package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.*
import com.lilac.anime.shared.compat.*
import io.ktor.http.*
data class SubtitleAsset(val name: String, val url: String, val source: String, val score: Double = 0.0)
class SubtitleDiscovery(private val repository: SourceRepository = SourceRepository()) {
    private val blogs = mutableMapOf<String, List<KairanPost>>()
    suspend fun search(provider: String, title: String, episode: Int, episodeKey: String, anilistId: Int): List<SubtitleAsset> =
        when (provider) {
            "jimaku" -> jimaku(anilistId, episode)
            "anissia" -> AnissiaDiscovery(repository).search(title, episode, episodeKey)
            else -> blog(provider, title, episode, episodeKey)
        }
    suspend fun makers(title: String) = AnissiaDiscovery(repository).makers(title)
    suspend fun makerSubtitles(title: String, episode: Int, episodeKey: String, website: String) =
        AnissiaDiscovery(repository).search(title, episode, episodeKey, website)
    private suspend fun blog(provider: String, title: String, episode: Int, episodeKey: String): List<SubtitleAsset> {
        require(provider in listOf("kairan", "csora"))
        val base = if (provider == "kairan") "https://kairan03.blogspot.com" else "https://csora556.blogspot.com"
        val posts = blogs[provider] ?: run {
            val all = linkedMapOf<String, KairanPost>()
            var start = 1
            while (true) {
                val feed = JSONObject(repository.getText("$base/feeds/posts/default", mapOf("alt" to "json", "max-results" to "150", "start-index" to start.toString()))).optJSONObject("feed") ?: error("Blogger 목록 형식이 다릅니다.")
                val entries = feed.optJSONArray("entry") ?: break
                if (entries.length() == 0) break
                var added = 0
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val name = entry.optJSONObject("title")?.optString("\$t").orEmpty()
                    val links = entry.optJSONArray("link") ?: continue
                    for (j in 0 until links.length()) {
                        val link = links.optJSONObject(j) ?: continue
                        if (link.optString("rel") == "alternate") {
                            val url = link.optString("href")
                            if (url !in all) { all[url] = KairanPost(name, url); added++ }
                        }
                    }
                }
                if (added == 0 || entries.length() < 150) break
                start += entries.length()
                check(start < 100_000) { "자막 목록을 완료할 수 없습니다." }
            }
            all.values.toList().also { blogs[provider] = it }
        }
        val match = KairanPostMatcher.findBestMatch(title, episode, posts, episodeKey) ?: return emptyList()
        val html = repository.getText(match.post.url)
        val assets = Regex("""https?://(?:drive|docs)\.google\.com/[^\s"'<>\\]+""").findAll(html).mapNotNull { found ->
            val link = found.value.replace("&amp;", "&")
            val id = Regex("/file/d/([^/?]+)").find(link)?.groupValues?.get(1) ?: runCatching { Url(link).parameters["id"] }.getOrNull()
            id?.let { SubtitleAsset(match.post.title, "https://drive.google.com/uc?export=download&id=$it", provider, match.similarity) }
        }.toList()
        return assets.distinctBy { it.url } + SubtitleAsset("원본 자막 게시물", match.post.url, "post", match.similarity)
    }
    private suspend fun jimaku(anilistId: Int, episode: Int): List<SubtitleAsset> {
        require(anilistId > 0) { "Jimaku 검색에는 AniList ID가 필요합니다." }
        val base = "https://jimaku.cc"
        val document = Ksoup.parse(repository.getText("$base/"), base)
        val entry = document.select("div.entry[data-extra]").firstOrNull {
            runCatching { JSONObject(it.attr("data-extra")).optInt("anilist_id") == anilistId }.getOrDefault(false)
        } ?: return emptyList()
        val href = entry.selectFirst("a[href*=/entry/]")?.absUrl("href") ?: return emptyList()
        val files = Ksoup.parse(repository.getText(href), href)
        return files.select("div.entry[data-extra]").mapNotNull {
            val extra = runCatching { JSONObject(it.attr("data-extra")) }.getOrNull()
            val name = extra?.optString("name").orEmpty().ifBlank { it.selectFirst("a.file-name")?.text().orEmpty() }
            val link = extra?.optString("url").orEmpty().ifBlank { it.selectFirst("a.file-name")?.absUrl("href").orEmpty() }
            if (name.substringAfterLast('.').lowercase() !in listOf("ass", "ssa", "srt", "vtt", "smi", "zip", "7z", "rar", "ttml", "sub")) return@mapNotNull null
            SubtitleAsset(name, if (link.startsWith("http")) link else "$base/" + link.trimStart('/'), "jimaku", if (SubtitleEpisodeMatcher.matches(name, episode)) 1.0 else 0.0)
        }.sortedByDescending { it.score }.distinctBy { it.url }
    }
    fun close() = repository.close()
}
