package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.*
import com.lilac.anime.shared.compat.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
data class SubtitleAsset(val name: String, val url: String, val source: String, val score: Double = 0.0, val episode: Int? = null, val strict: Boolean = false, val bundle: Boolean = false, val postURL: String = "", val matchedEpisode: Double? = null)
@Serializable
internal data class CommunityCache(val time: Long, val posts: List<CommunityPost>)
internal expect fun readCommunityCache(name: String): String?
internal expect fun writeCommunityCache(name: String, value: String)
class SubtitleDiscovery(private val repository: SourceRepository = SourceRepository(), private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val blogs = mutableMapOf<String, CommunityCache>()
    private val requests = mutableMapOf<String, CompletableDeferred<List<CommunityPost>>>()
    private val retryAt = mutableMapOf<String, Long>()
    private val requestLock = Mutex()
    private val metadata = DesktopMetadataRepository()
    suspend fun offsets(anilist: Int, title: String): List<Int> = try { metadata.previousEpisodeOffsets(anilist, title) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
    suspend fun search(provider: String, title: String, episode: Int, episodeKey: String, anilistId: Int, fresh: Boolean = false): List<SubtitleAsset> {
        val offsets = if (provider == "jimaku") emptyList() else offsets(anilistId, title)
        return when (provider) {
            "jimaku" -> jimaku(anilistId, title, episode)
            "anissia" -> AnissiaDiscovery(repository, ::communityPosts).search(title, episode, episodeKey, offsets = offsets, fresh = fresh)
            else -> blog(provider, title, episode, episodeKey, offsets, fresh)
        }
    }
    suspend fun makers(title: String) = AnissiaDiscovery(repository).makers(title)
    suspend fun makerSubtitles(title: String, episode: Int, episodeKey: String, website: String, anilistId: Int) =
        AnissiaDiscovery(repository, ::communityPosts).search(title, episode, episodeKey, website, offsets(anilistId, title))
    internal suspend fun communityPosts(provider: String, force: Boolean = false): List<CommunityPost> {
        val base = if (provider.startsWith("https://")) provider else if (provider == "kairan") "https://kairan03.blogspot.com" else "https://csora556.blogspot.com"
        val cached = blogs[provider] ?: runCatching { readCommunityCache(provider)?.let { Json.decodeFromString<CommunityCache>(it) } }.getOrNull()
        val now = clock()
        if (cached != null && cached.posts.isNotEmpty() && (now - cached.time < (if (force) 600000 else 86400000) || now < (retryAt[provider] ?: 0))) { blogs[provider] = cached; return cached.posts }
        var owner = false
        val request = requestLock.withLock {
            requests[provider] ?: CompletableDeferred<List<CommunityPost>>().also { requests[provider] = it; owner = true }
        }
        if (!owner) return request.await()
        try {
            suspend fun page(start: Int) = JSONObject(repository.getText("$base/feeds/posts/default", mapOf("alt" to "json", "max-results" to "150", "start-index" to start.toString()))).optJSONObject("feed") ?: error("Blogger 목록 형식이 다릅니다.")
            val first = page(1)
            val total = first.optJSONObject("openSearch\$totalResults")?.optString("\$t")?.toIntOrNull() ?: 0
            check(total < 100000) { "자막 목록을 완료할 수 없습니다." }
            val pages = if (total > 0) coroutineScope {
                val permits = Semaphore(3)
                listOf(first) + (151..total step 150).map { start -> async { permits.withPermit { page(start) } } }.awaitAll()
            } else listOf(first)
            val all = linkedMapOf<String, CommunityPost>()
            for (feed in pages) {
                val entries = feed.optJSONArray("entry") ?: break
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val name = entry.optJSONObject("title")?.optString("$"+"t").orEmpty()
                    val html = entry.optJSONObject("content")?.optString("$"+"t").orEmpty().ifBlank { entry.optJSONObject("summary")?.optString("$"+"t").orEmpty() }
                    val links = entry.optJSONArray("link") ?: continue
                    for (j in 0 until links.length()) {
                        val link = links.optJSONObject(j) ?: continue
                        if (link.optString("rel") == "alternate") {
                            val url = link.optString("href")
                            if (url.isNotBlank() && url !in all) all[url] = CommunityPost(name, url, html)
                        }
                    }
                }
            }
            check(all.isNotEmpty() && (total <= 0 || all.size >= total)) { "자막 게시물 목록을 다 받지 못했습니다." }
            val fresh = CommunityCache(clock(), all.values.toList()); blogs[provider] = fresh
            runCatching { writeCommunityCache(provider, Json.encodeToString(fresh)) }
            retryAt.remove(provider); request.complete(fresh.posts)
            return fresh.posts
        } catch (error: CancellationException) { request.cancel(error); throw error }
        catch (error: Exception) {
            retryAt[provider] = clock() + 60000
            if (cached?.posts?.isNotEmpty() == true) { blogs[provider] = cached; request.complete(cached.posts); return cached.posts }
            request.completeExceptionally(error); throw error
        } finally { withContext(NonCancellable) { requestLock.withLock { requests.remove(provider) } } }
    }
    private suspend fun blog(provider: String, title: String, episode: Int, episodeKey: String, offsets: List<Int>, fresh: Boolean): List<SubtitleAsset> {
        require(provider in listOf("kairan", "csora"))
        val number = episodeKey.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: episode.toDouble()
        var matches = DesktopCommunity.rank(communityPosts(provider, force = fresh), title, episode, offsets, number).take(5)
        if (matches.isEmpty()) matches = DesktopCommunity.rank(communityPosts(provider, force = true), title, episode, offsets, number).take(5)
        return matches.flatMap { match -> match.links.map { link ->
            val id = Regex("/file/d/([^/?]+)").find(link)?.groupValues?.get(1) ?: runCatching { Url(link).parameters["id"] }.getOrNull()
            val url = if (id != null && (link.contains("drive.google.com") || link.contains("docs.google.com"))) "https://drive.usercontent.google.com/download?id=" + id.encodeURLParameter() + "&export=download&confirm=t" else link
            SubtitleAsset(match.post.title, url, provider, match.score, match.episode, match.strict, match.bundle, match.post.url, match.episodeNumber)
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
