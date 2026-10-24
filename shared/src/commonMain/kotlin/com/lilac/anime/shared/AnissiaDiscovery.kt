package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import com.lilac.anime.shared.compat.*
import com.lilac.anime.shared.ported.*
import io.ktor.http.Url
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Anissia maker discovery and linked/RSS post lookup, following Desktop's community flow. */
data class SubtitleMaker(val name: String, val website: String, val status: String)
internal class AnissiaDiscovery(private val repository: SourceRepository, private val blogPosts: (suspend (String, Boolean) -> List<CommunityPost>)? = null) {
    suspend fun makers(title: String): List<SubtitleMaker> {
        val anime = anime(title) ?: return emptyList()
        val captions = api("/anime/caption/animeNo/" + anime.optInt("animeNo")).optJSONArray("data") ?: return emptyList()
        return (0 until captions.length()).mapNotNull(captions::optJSONObject).sortedByDescending { it.optString("updDt") }.mapNotNull { row ->
            val website = row.optString("website")
            if (!website.startsWith("https://")) null else SubtitleMaker(row.optString("name"), website, row.optString("status"))
        }.distinctBy { it.website }
    }
    /** main.cjs/anissiaAnime: the Anissia entry for a Korean title, with the season required to match ("2기", "Season 3"). */
    internal suspend fun anime(title: String): JSONObject? {
        val season = DesktopCommunity.season(title) ?: DesktopTitleRules.season(title)
        val wanted = DesktopCommunity.title(title)
        // Subtitles (~…~, -Link Click-) and season marks go: Anissia keeps "Ⅱ" as typed, and the title read here has it
        // as "II", which its search does not find. The season is checked on the results instead.
        val bare = wanted.replace(Regex("[~〜～][^~〜～]*[~〜～]"), " ").replace(Regex("(?:^|\\s)-[^-]+-(?=\\s|$)"), " ").replace(Regex("(?<=[가-힣\\s])(?:II|III|IV|VI|V)(?=\\s|$)"), " ")
            .replace(Regex("\\s*(?:\\d+\\s*기|season\\s*\\d+|시즌\\s*\\d+)\\s*$", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim()
        // Anissia's search misses titles typed with their punctuation ("명탐정 프리큐어!").
        val plain = bare.replace(Regex("[!?！？.,:;·'\"“”‘’♡♥☆★]+"), " ").replace(Regex("\\s+"), " ").trim()
        val words = plain.split(' ')
        val queries = listOf(bare, plain, words.take(2).joinToString(" "), words.first()).distinct().filter { it.length >= 2 }
        val seen = linkedMapOf<Int, JSONObject>()
        var best: Pair<JSONObject, Double>? = null
        var lastError: Exception? = null
        var answered = false
        for (query in queries) for (page in 0 until 3) {
            val result = try { api("/anime/list/$page", mapOf("q" to query)).optJSONObject("data")?.takeIf { it.optJSONArray("content") != null } ?: error("Anissia 검색 응답을 읽지 못했습니다.") }
                catch (error: CancellationException) { throw error } catch (error: Exception) { lastError = error; break }
            answered = true
            val content = result.optJSONArray("content")!!
            for (row in (0 until content.length()).mapNotNull(content::optJSONObject)) seen[row.optInt("animeNo")] = row
            // Anissia also names a later season with a bare digit ("이세계 유유자적 농가 2").
            best = seen.values.filter { DesktopCommunity.anissiaSeason(it.optString("subject")) == season }
                .map { it to DesktopCommunity.score(wanted, it.optString("subject")) }.filter { it.second >= .52 }.maxByOrNull { it.second }
            if ((best?.second ?: 0.0) >= .98) return best!!.first
            if (result.optBoolean("last") || page + 1 >= result.optInt("totalPages", 1) || content.length() == 0) break
        }
        if (best == null && !answered) lastError?.let { throw it }
        return best?.first
    }
    suspend fun search(title: String, episode: Int, episodeKey: String, makerWebsite: String = "", offsets: List<Int> = emptyList(), fresh: Boolean = false): List<SubtitleAsset> {
        val anime = anime(title) ?: return emptyList()
        val captions = api("/anime/caption/animeNo/" + anime.optInt("animeNo")).optJSONArray("data") ?: return emptyList()
        // The season asked for, from the title or Anissia's own ("이세계 유유자적 농가 2"): posts are ranked under either
        // name, and the Anissia one may not show it the way a post title would.
        val subjectTitle = anime.optString("subject")
        val askedSeason = DesktopCommunity.season(title) ?: DesktopTitleRules.season(title).takeIf { it > 1 }
            ?: DesktopCommunity.season(subjectTitle) ?: DesktopCommunity.bareDigitSeason(subjectTitle)
        val downloads = CommunityAssets(repository)
        val output = mutableListOf<SubtitleAsset>()
        for (maker in (0 until captions.length()).mapNotNull(captions::optJSONObject).sortedByDescending { it.optString("updDt") }) {
            val website = maker.optString("website")
            if (!website.startsWith("https://") || makerWebsite.isNotBlank() && website != makerWebsite) continue
            try {
                if (CommunityAssets.folderId(website) != null) {
                    val number = episodeKey.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: episode.toDouble()
                    val match = CommunityMatch(CommunityPost(subjectTitle, website, ""), listOf(website), episode, false,
                        bundle = true, episodeNumber = number, fileSeason = askedSeason)
                    output += downloads.assets(match, "Anissia · " + maker.optString("name"), "anissia", subjectTitle)
                    continue
                }
                val address = Url(website)
                if (address.host in listOf("kairan03.blogspot.com", "csora556.blogspot.com")) continue
                val origin = address.protocol.name + "://" + address.host
                val subject = anime.optString("subject")
                val naver = address.host in listOf("blog.naver.com", "m.blog.naver.com")
                if (!naver && !address.host.endsWith(".blogspot.com") && !address.host.endsWith(".tistory.com")) continue
                val parts = address.encodedPath.split('/').filter(String::isNotBlank)
                val blogId = address.parameters["blogId"] ?: parts.firstOrNull()?.takeUnless { it.endsWith(".naver") }.orEmpty()
                val logNo = address.parameters["logNo"] ?: parts.getOrNull(1)?.takeIf { it.toLongOrNull() != null }.orEmpty()
                val postUrl = if (naver && logNo.isNotBlank()) "https://blog.naver.com/PostView.naver?blogId=" + blogId.encodeURLPathPart() + "&logNo=" + logNo else website
                val linked = attempt { repository.getText(postUrl) }.orEmpty()
                val document = Ksoup.parse(linked)
                val pageTitle = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty().ifBlank { document.selectFirst("title")?.text().orEmpty() }
                    .replace(Regex("\\s*[-|:]\\s*[^-|:]*$"), "").trim()
                val linkedTitle = DesktopCommunity.postTitle(pageTitle)
                val wrongSeason = DesktopCommunity.season(linkedTitle) != null && (askedSeason ?: 1) !in DesktopCommunity.seasons(linkedTitle)
                val posts = mutableListOf<KairanPost>()
                val pages = mutableMapOf<String, String>()
                if (!wrongSeason && linked.isNotBlank()) { posts += KairanPost("$subject $pageTitle", postUrl); pages[postUrl] = linked }
                val nickname = if (wrongSeason) "" else AnissiaTitleRules.nickname(pageTitle, subject)
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
                    for (query in listOf(subject, series, nickname).filter(String::isNotBlank).distinct()) for (page in 1..4) {
                        val result = attempt { JSONObject(repository.getText(base + "/search/post", mapOf("query" to query, "page" to page.toString()))).optJSONObject("result") } ?: break
                        val list = result.optJSONArray("list") ?: break
                        addRows(list)
                        if (list.length() == 0 || page * list.length() >= result.optInt("totalCount")) break
                    }
                }
                if (address.host.endsWith(".blogspot.com")) {
                    val indexed = attempt { blogPosts?.invoke(origin, fresh) }
                    if (indexed != null) for (post in indexed) { posts += KairanPost(post.title, post.url); pages[post.url] = post.html }
                    else {
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
                }
                if (address.host.endsWith(".tistory.com") || address.host.endsWith(".blogspot.com")) {
                    val rss = attempt { repository.getText(origin + if (address.host.endsWith(".blogspot.com")) "/feeds/posts/default?alt=rss" else "/rss") }.orEmpty()
                    val document = Ksoup.parse(rss, parser = Parser.xmlParser())
                    for (item in document.select("item")) {
                        val url = item.selectFirst("link")?.text().orEmpty()
                        if (url.startsWith("https://")) posts += KairanPost(item.selectFirst("title")?.text().orEmpty(), url)
                    }
                    for (query in listOf(subject, nickname).filter(String::isNotBlank).distinct()) {
                        val html = attempt { repository.getText(origin + "/search/" + query.encodeURLPathPart()) }.orEmpty()
                        val doc = Ksoup.parse(html, origin)
                        posts += doc.select("a[href]").filter { Regex("/(?:entry/)?\\d+$").containsMatchIn(it.attr("href")) }
                            .map { KairanPost(it.text(), it.absUrl("href")) }
                    }
                }
                val candidates = posts.distinctBy { it.url }.map { it.copy(title = AnissiaTitleRules.rename(it.title, nickname, subject)) }
                val number = episodeKey.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: episode.toDouble()
                val wantedSeason = askedSeason ?: 1
                val likely = candidates.filter { post ->
                    val own = DesktopCommunity.episodes(post.title)
                    // The season's own number (a post may keep several seasons: "1기/2기/3기"), or a continued one on
                    // posts without a season ("15화" for 2기 3화).
                    (wantedSeason in DesktopCommunity.seasons(DesktopCommunity.postTitle(post.title)) && own.has(number) ||
                        wantedSeason > 1 && DesktopCommunity.season(DesktopCommunity.postTitle(post.title)) == null && offsets.any { own.has(number + it) }) &&
                        maxOf(DesktopCommunity.score(subject, post.title), DesktopCommunity.score(title, post.title)) >= .52
                }.take(4)
                // The likely posts are fetched together, not one after another.
                coroutineScope { likely.filter { pages[it.url] == null }.map { post -> async { post.url to attempt { repository.getText(post.url) } } }.awaitAll() }
                    .forEach { (url, html) -> if (html != null) pages[url] = html }
                val hydrated = candidates.mapNotNull { post -> pages[post.url]?.let { CommunityPost(post.title, post.url, it) } }
                val direct = DesktopCommunity.rank(hydrated, subject, episode, offsets, number, askedSeason).ifEmpty { DesktopCommunity.rank(hydrated, title, episode, offsets, number, askedSeason) }.take(5)
                if (direct.isNotEmpty()) {
                    for (match in direct) {
                        output += downloads.assets(match, "Anissia · " + maker.optString("name"), "anissia", title)
                        output += SubtitleAsset("Anissia · " + maker.optString("name") + " 원본 게시물", match.post.url, "post", match.score, match.episode, match.strict, match.bundle, match.post.url, match.episodeNumber)
                    }
                    continue
                }
                val match = DesktopEpisodeRules.findPost(subject, episode, candidates, episodeKey, offsets) ?: continue
                val html = pages[match.post.url] ?: repository.getText(match.post.url)
                output += attachments(html, match.post.url, maker.optString("name"), episode)
                output += SubtitleAsset("Anissia · " + maker.optString("name") + " 원본 게시물", match.post.url, "post", match.similarity)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Try the next maker. */ }
        }
        return output.distinctBy { it.url }
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
                if (SubtitleEpisodeMatcher.matches(name, episode)) 1.0 else 0.6, postURL = page)
        }
        val numbered = assets.filter { SubtitleEpisodeMatcher.parse(it.name)?.episode != null }
        return if (numbered.isEmpty()) assets else assets.filter { it !in numbered || SubtitleEpisodeMatcher.matches(it.name, episode) }
    }
}
internal object AnissiaTitleRules {
    fun nickname(pageTitle: String, subject: String): String {
        val head = Regex("^(.*?\\S)\\s*\\d+\\s*(?:화|회|편)").find(pageTitle)?.groupValues?.get(1) ?: pageTitle
        val name = head.replace(Regex("\\d+\\s*(?:화|회|편)?|\\((?:끝|완|完)\\)|完|자막|-끝-"), " ").replace(Regex("\\s+"), " ").trim()
        return name.takeIf { it.length >= 2 && DesktopCommunity.score(subject, it) < .52 }.orEmpty()
    }
    fun rename(title: String, nickname: String, subject: String): String {
        if (nickname.isBlank() || !title.contains(nickname) || DesktopCommunity.score(subject, title) >= .52) return title
        val number = Regex("^\\s*(\\d{1,3})(?!\\d)").find(title.substringAfter(nickname))?.groupValues?.get(1)?.toIntOrNull()
        return if (number == null) title else "$subject ${number}화"
    }
}
