package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.*
import com.lilac.anime.shared.compat.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
data class SubtitleAsset(val name: String, val url: String, val source: String, val score: Double = 0.0, val episode: Int? = null, val strict: Boolean = false, val bundle: Boolean = false, val postURL: String = "")
@Serializable
internal data class CommunityCache(val time: Long, val posts: List<CommunityPost>)
internal expect fun readCommunityCache(name: String): String?
internal expect fun writeCommunityCache(name: String, value: String)
class SubtitleDiscovery(private val repository: SourceRepository = SourceRepository()) {
    private val blogs = mutableMapOf<String, CommunityCache>()
    private val metadata = DesktopMetadataRepository()
    suspend fun offsets(anilist: Int, title: String): List<Int> = try { metadata.previousEpisodeOffsets(anilist, title) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
    suspend fun search(provider: String, title: String, episode: Int, episodeKey: String, anilistId: Int): List<SubtitleAsset> {
        val offsets = if (provider == "jimaku") emptyList() else offsets(anilistId, title)
        return when (provider) {
            "jimaku" -> jimaku(anilistId, title, episode)
            "anissia" -> AnissiaDiscovery(repository).search(title, episode, episodeKey, offsets = offsets)
            else -> blog(provider, title, episode, episodeKey, offsets)
        }
    }
    suspend fun makers(title: String) = AnissiaDiscovery(repository).makers(title)
    suspend fun makerSubtitles(title: String, episode: Int, episodeKey: String, website: String, anilistId: Int) =
        AnissiaDiscovery(repository).search(title, episode, episodeKey, website, offsets(anilistId, title))
    private suspend fun communityPosts(provider: String, force: Boolean = false): List<CommunityPost> {
        val base = if (provider == "kairan") "https://kairan03.blogspot.com" else "https://csora556.blogspot.com"
        val cached = blogs[provider] ?: runCatching { readCommunityCache(provider)?.let { Json.decodeFromString<CommunityCache>(it) } }.getOrNull()
        val now = Clock.System.now().toEpochMilliseconds()
        if (cached != null && cached.posts.isNotEmpty() && now - cached.time < (if (force) 600000 else 86400000)) { blogs[provider] = cached; return cached.posts }
        try {
            val all = linkedMapOf<String, CommunityPost>(); var start = 1
            while (true) {
                val feed = JSONObject(repository.getText("$base/feeds/posts/default", mapOf("alt" to "json", "max-results" to "150", "start-index" to start.toString()))).optJSONObject("feed") ?: error("Blogger 목록 형식이 다릅니다.")
                val entries = feed.optJSONArray("entry") ?: break
                if (entries.length() == 0) break
                var added = 0
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val name = entry.optJSONObject("title")?.optString("$"+"t").orEmpty()
                    val html = entry.optJSONObject("content")?.optString("$"+"t").orEmpty().ifBlank { entry.optJSONObject("summary")?.optString("$"+"t").orEmpty() }
                    val links = entry.optJSONArray("link") ?: continue
                    for (j in 0 until links.length()) {
                        val link = links.optJSONObject(j) ?: continue
                        if (link.optString("rel") == "alternate") {
                            val url = link.optString("href")
                            if (url !in all) { all[url] = CommunityPost(name, url, html); added++ }
                        }
                    }
                }
                if (added == 0 || entries.length() < 150) break
                start += entries.length(); check(start < 100000) { "자막 목록을 완료할 수 없습니다." }
            }
            check(all.isNotEmpty()) { "Blogger 목록이 비어 있습니다." }
            val fresh = CommunityCache(now, all.values.toList()); blogs[provider] = fresh
            runCatching { writeCommunityCache(provider, Json.encodeToString(fresh)) }
            return fresh.posts
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (cached?.posts?.isNotEmpty() == true) return cached.posts; throw error }
    }
    private suspend fun blog(provider: String, title: String, episode: Int, episodeKey: String, offsets: List<Int>): List<SubtitleAsset> {
        require(provider in listOf("kairan", "csora"))
        var matches = DesktopCommunity.rank(communityPosts(provider), title, episode, offsets).take(5)
        if (matches.isEmpty()) matches = DesktopCommunity.rank(communityPosts(provider, force = true), title, episode, offsets).take(5)
        return matches.flatMap { match -> match.links.map { link ->
            val id = Regex("/file/d/([^/?]+)").find(link)?.groupValues?.get(1) ?: runCatching { Url(link).parameters["id"] }.getOrNull()
            val url = if (id != null && (link.contains("drive.google.com") || link.contains("docs.google.com"))) "https://drive.usercontent.google.com/download?id=" + id.encodeURLParameter() + "&export=download&confirm=t" else link
            SubtitleAsset(match.post.title, url, provider, match.score, match.episode, match.strict, match.bundle, match.post.url)
        } + SubtitleAsset("원본 자막 게시물", match.post.url, "post", match.score) }
    }
    private var jimakuEntries: Map<Int, String> = emptyMap()
    private var jimakuLoaded = kotlin.time.TimeSource.Monotonic.markNow() - kotlin.time.Duration.parse("7h")
    private suspend fun jimaku(anilistId: Int, title: String, episode: Int): List<SubtitleAsset> {
        require(anilistId > 0) { "Jimaku 검색에는 AniList ID가 필요합니다." }
        val base = "https://jimaku.cc"
        if (jimakuEntries.isEmpty() || jimakuLoaded.elapsedNow() > kotlin.time.Duration.parse("6h") ||
            anilistId !in jimakuEntries && jimakuLoaded.elapsedNow() > kotlin.time.Duration.parse("10m")) {
            val document = Ksoup.parse(repository.getText("$base/"), base)
            val entries = document.select("div.entry[data-extra]").mapNotNull {
                val id = runCatching { JSONObject(it.attr("data-extra")).optInt("anilist_id") }.getOrDefault(0)
                val href = it.selectFirst("a[href*=/entry/]")?.absUrl("href").orEmpty()
                if (id <= 0 || href.isEmpty()) null else id to href
            }.toMap()
            if (entries.isNotEmpty()) { jimakuEntries = entries; jimakuLoaded = kotlin.time.TimeSource.Monotonic.markNow() }
        }
        val href = jimakuEntries[anilistId] ?: return emptyList()
        val files = Ksoup.parse(repository.getText(href), href)
        val assets = files.select("div.entry[data-extra]").mapNotNull {
            val extra = runCatching { JSONObject(it.attr("data-extra")) }.getOrNull()
            val name = extra?.optString("name").orEmpty().ifBlank { it.selectFirst("a.file-name")?.text().orEmpty() }
            val link = extra?.optString("url").orEmpty().ifBlank { it.selectFirst("a.file-name")?.absUrl("href").orEmpty() }
            if (name.substringAfterLast('.').lowercase() !in listOf("ass", "ssa", "srt", "vtt", "smi", "zip", "7z", "rar", "ttml", "sub")) return@mapNotNull null
            SubtitleAsset(name, if (link.startsWith("http")) link else "$base/" + link.trimStart('/'), "jimaku", if (SubtitleEpisodeMatcher.matches(name, episode)) 1.0 else 0.0)
        }.distinctBy { it.url }
        return JimakuRules.rank(assets, title, episode, "")
    }
    fun close() { repository.close(); metadata.close() }
}
