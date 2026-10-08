package com.lilac.anime.shared

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlin.experimental.xor

internal expect fun inflateCatalogGzip(data: ByteArray): ByteArray

internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.number(key: String) = text(key).toIntOrNull()
internal fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.list(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())

/** Catalog/episode rules ported from LilacAnime-desktop electron/main.cjs (4cf1104). */
object DesktopSourceParser {
    fun miruroAnime(root: JsonObject): Anime {
        val titles = root.obj("title")
        val ids = root.obj("external_ids")
        return Anime(id = root.text("id"), source = "miruro",
            title = titles.text("english").ifBlank { titles.text("romaji").ifBlank { titles.text("native") } },
            english = titles.text("english"), romaji = titles.text("romaji"), native = titles.text("native"),
            poster = root.text("cover_url"), year = root.text("season_year"), format = root.text("format"),
            description = Ksoup.parse(root.text("description")).text(),
            genres = root.list("genres").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            studios = root.list("studios").mapNotNull { (it as? JsonObject)?.text("name") }.filter(String::isNotBlank),
            airedDate = listOf(root.text("started_on"), root.text("ended_on")).filter(String::isNotBlank).joinToString(" ~ "),
            anilistId = (ids.list("anilist").firstOrNull() as? JsonPrimitive)?.content?.toIntOrNull(),
            malId = (ids.list("mal").firstOrNull() as? JsonPrimitive)?.content?.toIntOrNull(),
            detailUrl = "https://www.miruro.to/watch/" + root.text("id"))
    }
    fun miruroEpisodes(root: JsonObject, rows: JsonArray, today: String): List<Episode> {
        val counts = root.obj("episode_counts")
        val limit = listOfNotNull(root.number("episode_count"), counts.number("raw")).minOrNull()
        val kind = if (root.text("format") == "MOVIE") "film" else "regular"
        val all = rows.mapNotNull { it as? JsonObject }
        val own = all.filter { it.text("kind") == kind }.ifEmpty { all }
        return own.mapNotNull { row ->
            val number = row.number("episode_number") ?: return@mapNotNull null
            if (number <= 0 || (limit != null && number > limit)) return@mapNotNull null
            val available = counts.number("raw") != null || number <= maxOf(counts.number("sub") ?: 0, counts.number("dub") ?: 0)
            val aired = row.text("aired_on").take(10)
            if (!available && ((aired.isNotEmpty() && aired > today) || (aired.isEmpty() && root.text("status") == "NOT_YET_RELEASED"))) return@mapNotNull null
            Episode(id = root.text("id") + ":" + number, number = number,
                title = row.text("title").ifBlank { "Episode " + number },
                videoUrl = "https://www.miruro.to/watch/" + root.text("id") + "?ep=" + number,
                airedDate = row.text("aired_on"), isFiller = row.text("canon_type") == "filler",
                isRecap = row.text("canon_type") == "recap", thumbnailUrl = row.text("thumbnail_url"))
        }.distinctBy { it.id }.sortedBy { it.number }
    }
    fun koreanList(html: String, source: String): List<Anime> {
        val base = if (source == "linkani") "https://linkani.tv" else "https://www.ohli24.net"
        val doc = Ksoup.parse(html, base)
        return doc.select(if (source == "linkani") ".vod-item" else ".show-item").mapNotNull { card ->
            val link = card.selectFirst(if (source == "linkani") "a[href*=/ani/]" else "a.show-item-img-link, a[href*=.html]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            val id = Regex(if (source == "linkani") "/ani/(\\d+)" else "/(\\d+)/[^/]+\\.html").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val image = card.selectFirst("img")
            val title = card.selectFirst(if (source == "linkani") ".vod-item-title" else ".show-item-title")?.text().orEmpty().ifBlank { image?.attr("title").orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val picture = card.selectFirst("[data-original]")?.absUrl("data-original").orEmpty().ifBlank { image?.absUrl("src").orEmpty() }
                .ifBlank { Regex("url\\(\\s*['\"]?([^'\")]+)").find(card.selectFirst("[style*=background-image]")?.attr("style").orEmpty())?.groupValues?.get(1)?.let { (if (it.startsWith("//")) "https:" + it else if (it.startsWith("http")) it else base + "/" + it.trimStart('/')) }.orEmpty() }
            val desc = card.selectFirst(".vod-item-desc")?.text().orEmpty()
            Anime(id = id, title = title, poster = picture, source = source, detailUrl = href,
                format = if (title.contains("극장판") || desc.contains("Movie", true)) "MOVIE" else "TV",
                year = Regex("(19|20)\\d{2}").find(desc)?.value.orEmpty())
        }.distinctBy { it.id }
    }
    fun koreanDetail(html: String, summary: Anime, source: String): SourceDetail {
        val base = if (source == "linkani") "https://linkani.tv" else "https://www.ohli24.net"
        val doc = Ksoup.parse(html, summary.detailUrl.ifBlank { base })
        val fields = doc.select(if (source == "linkani") ".detail-info-desc li" else ".article-box-meta li").associate { row ->
            val label = row.selectFirst("span")?.text().orEmpty().replace(Regex("[:：\\s]"), "")
            label to row.text().removePrefix(row.selectFirst("span")?.text().orEmpty()).trim()
        }
        val servers = mutableListOf<EpisodeServer>()
        val lists = if (source == "linkani") doc.select(".ewave-playlist-content") else doc.select(".eps-list, .eps-box").take(1)
        val tabs = doc.select(".playlist-tab-box .tab-item").map { it.text() }
        val groups = if (lists.isEmpty()) listOf(doc) else lists
        groups.forEachIndexed { index, list ->
            val links = list.select(if (source == "linkani") "a[href*=/watch/]" else ".eps-item a[href]")
            val episodes = links.mapIndexedNotNull { position, link ->
                val url = link.absUrl("href")
                if (url.isBlank()) return@mapIndexedNotNull null
                val label = link.text().replace(link.selectFirst(".eps-date")?.text().orEmpty(), "").trim()
                val number = Regex("\\d+").find(label)?.value?.toIntOrNull() ?: (position + 1)
                Episode(id = url, number = number, title = label.ifBlank { number.toString() + "화" }, videoUrl = url)
            }.distinctBy { it.id }.sortedBy { it.number }
            if (episodes.isNotEmpty()) servers += EpisodeServer(index + 1, tabs.getOrNull(index).orEmpty().ifBlank { if (source == "linkani") "링크애니" else "애니24" }, episodes)
        }
        val title = doc.selectFirst(if (source == "linkani") ".detail-info-title" else "meta[property=og:title]")?.let { if (it.tagName() == "meta") it.attr("content").replace(Regex("\\s*자막\\s*다시보기\\s*$"), "") else it.text() }.orEmpty().ifBlank { summary.title }
        val image = doc.selectFirst(if (source == "linkani") ".detail-img img" else ".article-box-img img")
        val poster = image?.absUrl("data-original").orEmpty().ifBlank { image?.absUrl("src").orEmpty() }.ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }.ifBlank { summary.poster }
        val description = if (source == "linkani") doc.selectFirst("meta[name=description]")?.attr("content").orEmpty().replace(Regex("^.*?다시보기\\.\\s*"), "") else doc.selectFirst(".movie-coment")?.text().orEmpty()
        val year = Regex("(19|20)\\d{2}").find(fields["년"].orEmpty() + fields["방영일"].orEmpty())?.value.orEmpty()
        val native = fields["원제"].orEmpty().ifBlank { Regex("원제\\s*[:：]\\s*(.+?)$").find(doc.selectFirst(".box.tv")?.text().orEmpty())?.groupValues?.get(1).orEmpty() }
        val anime = summary.copy(title = title, poster = poster, source = source, description = description,
            native = native, year = year.ifBlank { summary.year }, airedDate = fields["방영일"].orEmpty(),
            genres = fields["장르"].orEmpty().split(Regex("[,/·]")).map(String::trim).filter(String::isNotBlank),
            episodes = servers.firstOrNull()?.episodes.orEmpty(),
            anilistId = Regex("anilist-(\\d+)\\.").find(poster)?.groupValues?.get(1)?.toIntOrNull() ?: summary.anilistId)
        return SourceDetail(anime, servers)
    }
    fun linkaniStreams(html: String): List<DesktopPlaybackStream> {
        val raw = Regex("var\\s+player_aaaa\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;?\\s*</script>").find(html)?.groupValues?.get(1) ?: return emptyList()
        val player = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val url = player.text("url").ifBlank { player.text("actual_url") }
        if (!url.startsWith("https://")) return emptyList()
        val subtitle = player.text("subtitle_url")
        fun fileID(value: String) = Regex("/[hs]/[0-9a-f]{2}/[0-9a-f]{2}/([0-9a-f]{16,})/", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)
        val own = fileID(url) == null || fileID(subtitle) == null || fileID(url) == fileID(subtitle)
        val tracks = if (subtitle.startsWith("https://") && own) listOf(PlaybackTrack("링크애니 한국어", subtitle, "https://linkani.tv/", "ko", "subtitle")) else emptyList()
        return listOf(DesktopPlaybackStream("링크애니", url, "https://linkani.tv/", mapOf("Referer" to "https://linkani.tv/"), tracks))
    }
    fun miruroStreams(root: JsonObject): List<DesktopPlaybackStream> {
        val streams = mutableListOf<DesktopPlaybackStream>()
        for (track in root.list("tracks").filterIsInstance<JsonObject>()) {
            val kind = when (track.text("track")) { "ssub" -> "SOFT"; "sub" -> "SUB"; "raw" -> "RAW"; "dub" -> "DUB"; else -> continue }
            for (provider in track.list("providers").filterIsInstance<JsonObject>()) {
                val subtitles = provider.list("subtitles").filterIsInstance<JsonObject>()
                for (server in provider.list("servers").filterIsInstance<JsonObject>()) {
                    val stream = server.list("streams").filterIsInstance<JsonObject>().firstOrNull { it.text("format") == "hls" && it.text("url").startsWith("https://") } ?: continue
                    val headers = server.obj("headers").mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() }.toMutableMap()
                    val referer = headers["Referer"].orEmpty()
                    if (referer.startsWith("https://") && "Origin" !in headers) headers["Origin"] = Url(referer).let { it.protocol.name + "://" + it.host }
                    val tracks = subtitles.filter { it.text("file").startsWith("https://") }.map { PlaybackTrack(it.text("label").ifBlank { it.text("language") }, it.text("file"), referer, it.text("language"), "subtitle") }
                    val actualKind = if (kind == "SOFT" && tracks.isEmpty()) "SUB" else kind
                    streams += DesktopPlaybackStream(actualKind + " - " + provider.text("provider") + " " + server.text("server"), stream.text("url"), referer, headers, tracks)
                }
            }
        }
        return streams.distinctBy { it.url }.sortedBy { stream ->
            when { stream.subtitles.any { it.language.startsWith("ko") } -> 0; stream.label.startsWith("SOFT") -> 1; stream.label.startsWith("RAW") -> 2; stream.label.startsWith("SUB") -> 3; else -> 4 }
        }
    }
}

data class DesktopPlaybackStream(val label: String, val url: String, val referer: String, val headers: Map<String, String>, val subtitles: List<PlaybackTrack> = emptyList())

internal class DesktopSourceRepository(private val client: HttpClient) {
    private val cursors = mutableMapOf<String, MutableMap<Int, String>>()
    private suspend fun text(url: String, params: Map<String, String> = emptyMap()) = client.get(url) {
        header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1")
        header("Referer", Url(url).let { it.protocol.name + "://" + it.host + "/" })
        header("Accept-Language", "ko-KR,ko;q=0.9")
        params.forEach { (key, value) -> parameter(key, value) }
    }.body<String>()
    private suspend fun miruro(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        val body = client.get("https://www.miruro.to/api/v1/" + path) {
            header("Referer", "https://www.miruro.to/"); header("Accept", "*/*")
            params.forEach { (key, value) -> parameter(key, value) }
        }.body<ByteArray>()
        require(body.size <= 8 * 1024 * 1024) { "Miruro 응답이 너무 큽니다." }
        val key = "miruro/catalog".encodeToByteArray()
        val decoded = ByteArray(body.size) { body[it] xor key[it % key.size] }
        return Json.parseToJsonElement(inflateCatalogGzip(decoded).decodeToString()).jsonObject
    }
    suspend fun browse(source: String, query: String, page: Int, filter: BrowseFilter): List<Anime> {
        if (source == "miruro") {
            val key = query + filter.toString()
            if (page == 1) cursors[key] = mutableMapOf()
            val pages = cursors.getOrPut(key) { mutableMapOf() }
            if (page > 1 && pages[page] == null) return emptyList()
            val params = mutableMapOf("limit" to "15", "sort" to "-popularity")
            if (query.isNotBlank()) params["q"] = query
            pages[page]?.let { params["cursor"] = it }
            if (filter.year.isNotBlank()) params["season_year"] = filter.year
            if (filter.format.isNotBlank()) params["format"] = filter.format
            val root = miruro("anime", params)
            root.text("next_cursor").ifBlank { root.obj("pagination").text("next_cursor") }.takeIf(String::isNotBlank)?.let { pages[page + 1] = it }
            return root.list("data").filterIsInstance<JsonObject>().map(DesktopSourceParser::miruroAnime)
        }
        if (source == "ohli24") {
            if (query.isNotBlank() && page > 1) return emptyList()
            val url = if (query.isNotBlank()) "https://www.ohli24.net/search/keyword-" + query.encodeURLPathPart() + ".html"
                else if (page == 1) "https://www.ohli24.net/" else "https://www.ohli24.net/finished/" + (page - 1) + "-1.html"
            return DesktopSourceParser.koreanList(text(url), source)
        }
        val base = "https://linkani.tv"
        val suffix = if (page > 1) "page/" + page + "/" else ""
        val path = if (filter.year.isNotBlank()) "/list/2/year/" + filter.year.encodeURLPathPart() + "/" + suffix else "/list/2/" + suffix
        val params = if (query.isBlank()) emptyMap() else mapOf("wd" to query)
        return DesktopSourceParser.koreanList(text(base + if (query.isBlank()) path else "/view/" + suffix, params), source)
    }
    suspend fun detail(summary: Anime, source: String): SourceDetail {
        if (source != "miruro") return DesktopSourceParser.koreanDetail(text(summary.detailUrl), summary, source)
        val root = miruro("anime/" + summary.id)
        val anime = DesktopSourceParser.miruroAnime(root)
        val rows = miruro("anime/" + summary.id + "/episodes", mapOf("limit" to "10000")).list("data")
        val episodes = DesktopSourceParser.miruroEpisodes(root, rows, currentCatalogDate())
        val relationRoot = try { miruro("anime/" + summary.id + "/relations") } catch (error: CancellationException) { throw error } catch (_: Exception) { JsonObject(emptyMap()) }
        val relations = relationRoot.list("data").filterIsInstance<JsonObject>().mapNotNull {
            val related = DesktopSourceParser.miruroAnime(it.obj("anime"))
            if (related.id.isBlank()) null else ReAnimeRelated(related.id, related.title, related.native, related.romaji, related.poster, related.format, it.text("kind"), seasonYear = related.year.toIntOrNull())
        }
        return SourceDetail(anime.copy(episodes = episodes, reAnimeRelated = relations), listOf(EpisodeServer(1, "Miruro", episodes)))
    }
    suspend fun streams(source: String, animeId: String, number: Int, url: String): List<DesktopPlaybackStream> = when (source) {
        "miruro" -> DesktopSourceParser.miruroStreams(miruro("anime/" + animeId + "/episodes/" + number + "/play"))
        "linkani" -> DesktopSourceParser.linkaniStreams(text(url))
        else -> emptyList()
    }
}
internal expect fun currentCatalogDate(): String
