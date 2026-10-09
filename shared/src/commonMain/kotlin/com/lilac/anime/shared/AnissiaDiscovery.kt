package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import com.lilac.anime.shared.compat.*
import com.lilac.anime.shared.ported.*
import io.ktor.http.Url
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException

/** Anissia maker discovery and linked/RSS post lookup, following Desktop's community flow. */
data class SubtitleMaker(val name: String, val website: String, val status: String)
internal class AnissiaDiscovery(private val repository: SourceRepository) {
    suspend fun makers(title: String): List<SubtitleMaker> {
        val rows = api("/anime/list/0", mapOf("q" to title)).optJSONObject("data")?.optJSONArray("content") ?: return emptyList()
        val anime = (0 until rows.length()).mapNotNull(rows::optJSONObject)
            .filter { DesktopTitleRules.season(it.optString("subject")) == DesktopTitleRules.season(title) }
            .maxByOrNull { DesktopCommunity.score(title, it.optString("subject")) } ?: return emptyList()
        if (DesktopCommunity.score(title, anime.optString("subject")) < 0.52) return emptyList()
        val captions = api("/anime/caption/animeNo/" + anime.optInt("animeNo")).optJSONArray("data") ?: return emptyList()
        return (0 until captions.length()).mapNotNull { index ->
            val row = captions.optJSONObject(index) ?: return@mapNotNull null
            val website = row.optString("website")
            if (!website.startsWith("https://")) null else SubtitleMaker(row.optString("name"), website, row.optString("status"))
        }.distinctBy { it.website }
    }
    suspend fun search(title: String, episode: Int, episodeKey: String, makerWebsite: String = "", offsets: List<Int> = emptyList()): List<SubtitleAsset> {
        val query = title.replace(Regex("[!?！？.,:;·'\"“”‘’♡♥☆★]"), " ").replace(Regex("\\s+"), " ").trim()
        val root = api("/anime/list/0", mapOf("q" to query)).optJSONObject("data") ?: return emptyList()
        val entries = root.optJSONArray("content") ?: return emptyList()
        val anime = (0 until entries.length()).mapNotNull(entries::optJSONObject)
            .filter { DesktopTitleRules.season(it.optString("subject")) == DesktopTitleRules.season(title) }
            .maxByOrNull { DesktopCommunity.score(query, it.optString("subject")) } ?: return emptyList()
        if (DesktopCommunity.score(query, anime.optString("subject")) < 0.52) return emptyList()
        val captions = api("/anime/caption/animeNo/" + anime.optInt("animeNo")).optJSONArray("data") ?: return emptyList()
        val output = mutableListOf<SubtitleAsset>()
        for (index in 0 until captions.length()) {
            val maker = captions.optJSONObject(index) ?: continue
            val website = maker.optString("website")
            if (!website.startsWith("https://") || makerWebsite.isNotBlank() && website != makerWebsite) continue
            try {
                val address = Url(website)
                if (address.host in listOf("kairan03.blogspot.com", "csora556.blogspot.com")) continue
                val origin = address.protocol.name + "://" + address.host
                val subject = anime.optString("subject")
                val naver = address.host in listOf("blog.naver.com", "m.blog.naver.com")
                val parts = address.encodedPath.split('/').filter(String::isNotBlank)
                val blogId = address.parameters["blogId"] ?: parts.firstOrNull()?.takeUnless { it.endsWith(".naver") }.orEmpty()
                val logNo = address.parameters["logNo"] ?: parts.getOrNull(1)?.takeIf { it.toLongOrNull() != null }.orEmpty()
                val postUrl = if (naver && logNo.isNotBlank()) "https://blog.naver.com/PostView.naver?blogId=" + blogId.encodeURLPathPart() + "&logNo=" + logNo else website
                val linked = attempt { repository.getText(postUrl) }.orEmpty()
                val posts = mutableListOf(KairanPost(subject + " " + Ksoup.parse(linked).selectFirst("title")?.text().orEmpty(), postUrl))
                val pages = mutableMapOf(postUrl to linked)
                if (naver && blogId.isNotBlank()) {
                    val base = "https://m.blog.naver.com/api/blogs/" + blogId.encodeURLPathPart()
                    fun addRows(rows: JSONArray?) {
                        if (rows == null) return
                        for (row in 0 until rows.length()) {
                            val item = rows.optJSONObject(row) ?: continue
                            val number = item.optString("logNo")
                            val name = Ksoup.parse(item.optString("title").ifBlank { item.optString("titleWithInspectMessage") }).text()
                            if (number.toLongOrNull() != null && name.isNotBlank()) posts += KairanPost(name,
                                "https://blog.naver.com/PostView.naver?blogId=" + blogId.encodeURLPathPart() + "&logNo=" + number)
                        }
                    }
                    attempt { JSONObject(repository.getText(base + "/post-list", mapOf("categoryNo" to "0", "itemCount" to "30", "page" to "1"))).optJSONObject("result") }
                        ?.let { addRows(it.optJSONArray("items")) }
                    val series = subject.replace(Regex("\\s*(?:\\d+\\s*기|season\\s*\\d+|시즌\\s*\\d+)\\s*$", RegexOption.IGNORE_CASE), "").trim()
                    for (query in listOf(subject, series).distinct()) for (page in 1..4) {
                        val result = attempt { JSONObject(repository.getText(base + "/search/post", mapOf("query" to query, "page" to page.toString()))).optJSONObject("result") } ?: break
                        val list = result.optJSONArray("list") ?: break
                        addRows(list)
                        if (list.length() == 0 || page * list.length() >= result.optInt("totalCount")) break
                    }
                }
                if (address.host.endsWith(".blogspot.com")) {
                    val feed = attempt { JSONObject(repository.getText(origin + "/feeds/posts/default", mapOf("alt" to "json", "q" to subject, "max-results" to "100"))).optJSONObject("feed")?.optJSONArray("entry") }
                    if (feed != null) for (row in 0 until feed.length()) {
                        val entry = feed.optJSONObject(row) ?: continue
                        val links = entry.optJSONArray("link") ?: continue
                        for (i in 0 until links.length()) {
                            val link = links.optJSONObject(i) ?: continue
                            if (link.optString("rel") == "alternate") posts += KairanPost(entry.optJSONObject("title")?.optString("$"+"t").orEmpty(), link.optString("href"))
                        }
                    }
                }
                if (address.host.endsWith(".tistory.com") || address.host.endsWith(".blogspot.com")) {
                    val rss = attempt { repository.getText(origin + if (address.host.endsWith(".blogspot.com")) "/feeds/posts/default?alt=rss" else "/rss") }.orEmpty()
                    val document = Ksoup.parse(rss, parser = Parser.xmlParser())
                    for (item in document.select("item")) {
                        val url = item.selectFirst("link")?.text().orEmpty()
                        if (url.startsWith("https://")) posts += KairanPost(item.selectFirst("title")?.text().orEmpty(), url)
                    }
                    val html = attempt { repository.getText(origin + "/search/" + subject.encodeURLPathPart()) }.orEmpty()
                    val doc = Ksoup.parse(html, origin)
                    posts += doc.select("a[href]").filter { Regex("/(?:entry/)?\\d+$").containsMatchIn(it.attr("href")) }
                        .map { KairanPost(it.text(), it.absUrl("href")) }
                }
                val candidates = posts.distinctBy { it.url }
                val likely = candidates.filter { post ->
                    val own = DesktopCommunity.episodes(post.title)
                    val season = DesktopTitleRules.explicitSeason(post.title)
                    ((season ?: 1) == DesktopTitleRules.season(subject) && own.has(episode) ||
                        season == null && offsets.any { own.has(episode + it) }) &&
                        maxOf(DesktopCommunity.score(subject, post.title), DesktopCommunity.score(title, post.title)) >= .52
                }.take(4)
                for (post in likely) if (pages[post.url] == null) attempt { repository.getText(post.url) }?.let { pages[post.url] = it }
                val hydrated = candidates.mapNotNull { post -> pages[post.url]?.let { CommunityPost(post.title, post.url, it) } }
                val direct = DesktopCommunity.rank(hydrated, subject, episode, offsets).firstOrNull()
                    ?: DesktopCommunity.rank(hydrated, title, episode, offsets).firstOrNull()
                if (direct != null) {
                    output += direct.links.map { link -> SubtitleAsset("Anissia · " + maker.optString("name"), link, "anissia", direct.score, direct.episode, direct.strict) }
                    continue
                }
                val match = DesktopEpisodeRules.findPost(subject, episode, candidates, episodeKey, offsets) ?: continue
                val html = pages[match.post.url] ?: repository.getText(match.post.url)
                output += attachments(html, match.post.url, maker.optString("name"), episode)
                output += SubtitleAsset("Anissia · " + maker.optString("name") + " 원본 게시물", match.post.url, "post", match.similarity)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Try the next maker. */ }
        }
        return output.distinctBy { it.url }.sortedByDescending { it.score }
    }
    private suspend fun api(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val root = JSONObject(repository.getText("https://api.anissia.net" + path, params))
        check(root.optString("code").let { it.isEmpty() || it == "ok" }) { "Anissia 요청이 실패했습니다." }
        return root
    }
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
    private fun attachments(html: String, page: String, maker: String, episode: Int): List<SubtitleAsset> {
        val doc = Ksoup.parse(html, page)
        val links = doc.select("a[href]").map { it.text() to it.absUrl("href") }.toMutableList()
        Regex("aPostFiles\\[\\d+\\]\\s*=\\s*JSON\\.parse\\('(.*?)'\\.replace").findAll(html).forEach { match ->
            val files = runCatching { JSONArray(match.groupValues[1].replace("\\'", "")) }.getOrNull() ?: return@forEach
            for (i in 0 until files.length()) files.optJSONObject(i)?.let { links += it.optString("encodedAttachFileName") to it.optString("encodedAttachFileUrl") }
        }
        val assets = links.mapNotNull { (name, link) ->
            if (!link.startsWith("https://")) return@mapNotNull null
            val driveId = Regex("/file/d/([^/?]+)").find(link)?.groupValues?.get(1) ?: runCatching { Url(link).parameters["id"] }.getOrNull().takeIf { Url(link).host in listOf("drive.google.com", "docs.google.com") }
            val suffix = Url(link).encodedPath.substringAfterLast('.').lowercase()
            if (driveId == null && suffix !in listOf("ass", "ssa", "srt", "vtt", "smi", "zip", "7z", "rar", "ttml", "sub") && !link.contains("attach", true)) return@mapNotNull null
            SubtitleAsset("Anissia · " + maker + " · " + name.ifBlank { link.substringAfterLast('/') },
                if (driveId != null) "https://drive.usercontent.google.com/download?id=" + driveId + "&export=download&confirm=t" else link, "anissia",
                if (SubtitleEpisodeMatcher.matches(name, episode)) 1.0 else 0.6)
        }
        val numbered = assets.filter { SubtitleEpisodeMatcher.parse(it.name)?.episode != null }
        return if (numbered.isEmpty()) assets else assets.filter { it !in numbered || SubtitleEpisodeMatcher.matches(it.name, episode) }
    }
}
