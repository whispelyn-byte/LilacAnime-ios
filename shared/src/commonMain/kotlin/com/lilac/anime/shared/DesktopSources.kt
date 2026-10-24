package com.lilac.anime.shared
import io.ktor.util.decodeBase64Bytes

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlin.experimental.xor
import kotlin.io.encoding.Base64

internal expect fun inflateCatalogGzip(data: ByteArray): ByteArray

internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.number(key: String) = text(key).toIntOrNull()
internal fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.list(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())

/** Catalog/episode rules ported from LilacAnime-desktop electron/main.cjs (4cf1104). */
object DesktopSourceParser {
    fun animenosubServers(html: String, pageUrl: String): List<DesktopPlaybackServer> {
        val doc = Ksoup.parse(html, pageUrl)
        val servers = doc.select("option[value]").mapNotNull { option ->
            val decoded = runCatching { Base64.decode(option.attr("value")).decodeToString() }.getOrNull() ?: return@mapNotNull null
            val frame = Ksoup.parse(decoded, pageUrl).selectFirst("iframe[src]") ?: return@mapNotNull null
            val label = option.text().trim(); val url = frame.absUrl("src")
            if (label.isEmpty() || !url.startsWith("https://")) return@mapNotNull null
            DesktopPlaybackServer(label, if (Regex("^raw\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) "raw" else if (Regex("^dub\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) "dub" else "sub", url)
        }.distinctBy { it.label }
        if (servers.isNotEmpty()) return servers
        val frame = doc.selectFirst("iframe[src], iframe[data-src]") ?: return emptyList()
        val url = frame.absUrl(if (frame.hasAttr("src")) "src" else "data-src")
        return if (url.startsWith("https://")) listOf(DesktopPlaybackServer("기본", "sub", url)) else emptyList()
    }

    fun reanimeServers(root: JsonObject, dub: Boolean = false): List<DesktopPlaybackServer> {
        val links = root.list("episode_links").ifEmpty { root.list("servers") }
        return links.filterIsInstance<JsonObject>().mapNotNull {
            // Desktop 0ac2590: preserve requested audio before HD ranking and URL deduplication.
            val dubbed = Regex("^dub(?:bed)?$", RegexOption.IGNORE_CASE).matches(it.text("dataType")) ||
                Regex("\\bdub(?:bed)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.text("serverName"))
            if (dubbed != dub) return@mapNotNull null
            val url = it.text("dataLink").ifBlank { it.text("link") }
            if (!Regex("^https://flixcloud\\.cc/e/", RegexOption.IGNORE_CASE).containsMatchIn(url)) null
            else DesktopPlaybackServer(it.text("serverName"), "soft", url)
        }.sortedBy { if (Regex("HD-?2", RegexOption.IGNORE_CASE).containsMatchIn(it.label)) 0 else if (Regex("HD-?1", RegexOption.IGNORE_CASE).containsMatchIn(it.label)) 1 else 2 }.distinctBy { it.url }
    }
    fun miruroAnime(root: JsonObject): Anime {
        val titles = root.obj("title")
        val ids = root.obj("external_ids")
        return Anime(id = root.text("id"), source = "miruro", score = (root.text("average_score").toDoubleOrNull() ?: 0.0) / 10, popularity = root.number("popularity") ?: 0,
            title = titles.text("english").ifBlank { titles.text("romaji").ifBlank { titles.text("native") } },
            english = titles.text("english"), romaji = titles.text("romaji"), native = titles.text("native"),
            poster = root.text("cover_url"), year = root.text("season_year"), format = root.text("format"),
            season = root.text("season"), status = root.text("status").lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, totalEpisodes = root.number("episode_count"), availableEpisodes = root.obj("episode_counts").values.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.maxOrNull(),
            description = Ksoup.parse(root.text("description")).text(),
            genres = root.list("genres").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            studios = root.list("studios").filterIsInstance<JsonObject>().let { all -> all.filter { it.text("is_animation_studio") == "true" }.ifEmpty { all.take(2) } }.map { it.text("name") }.filter(String::isNotBlank),
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
            val counts = Regex("(\\d+)\\s*/\\s*(\\d+)").find(desc)
            Anime(id = id, title = title, poster = picture, source = source, detailUrl = href,
                format = if (title.contains("극장판") || desc.contains("Movie", true)) "MOVIE" else "TV",
                year = Regex("(19|20)\\d{2}").find(desc)?.value.orEmpty(),
                score = card.selectFirst(".vod-item-score")?.text()?.toDoubleOrNull() ?: 0.0,
                availableEpisodes = if (source == "linkani") counts?.groupValues?.get(1)?.toIntOrNull() else Regex("\\d+").find(card.selectFirst(".show-item-eps")?.text().orEmpty())?.value?.toIntOrNull(),
                totalEpisodes = counts?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 },
                status = card.selectFirst(".cat-tag")?.text().orEmpty(),
                genres = card.select(".top-list-body-genre a").map { it.text() }.filter(String::isNotBlank))
        }.distinctBy { it.id }
    }
    // --- 애니24 (ani.ohli24.com), as desktop main.cjs reads it -------------------------------------------------------
    // A Laravel + Inertia app: each page carries what it shows as JSON (<script data-page="app">). Boards: /방영중 (every
    // airing show on one page), /완결 and /극장판 (24 a page, ?page=N, ?keyword=). A series is /c/<title> (its info rows,
    // story and episodes, 100 a page); an episode is /e/<title N화>, its player in playing.body.url. It took the place of
    // www.ohli24.net, a copy no longer kept up (episodes missing, dates off).
    const val OHLI24_WEB = "https://ani.ohli24.com"
    /** An address in a playlist or page, as the browser reads it against where it came from. */
    fun resolveUrl(value: String, base: String): String {
        val trimmed = value.trim()
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return trimmed
        val url = Url(base)
        val origin = url.protocol.name + "://" + url.hostWithPortIfSpecified
        if (trimmed.startsWith("//")) return url.protocol.name + ":" + trimmed
        if (trimmed.startsWith("/")) return origin + trimmed
        return origin + url.encodedPath.substringBeforeLast('/') + "/" + trimmed
    }
    /** The best variant of an 애니24 master playlist (desktop ohliVariant). */
    fun ohliVariant(text: String, base: String): String {
        check(text.startsWith("#EXTM3U")) { "애니24 영상 재생목록을 받지 못했습니다." }
        val variants = Regex("#EXT-X-STREAM-INF:([^\\r\\n]*)\\r?\\n\\s*([^\\r\\n#][^\\r\\n]*)").findAll(text).map {
            (Regex("BANDWIDTH=(\\d+)").find(it.groupValues[1])?.groupValues?.get(1)?.toLongOrNull() ?: 0L) to resolveUrl(it.groupValues[2], base)
        }.toList()
        check(variants.isNotEmpty()) { "애니24 영상 화질 목록이 없습니다." }
        return variants.maxBy { it.first }.second
    }
    /** The subtitle files a player page names (var playerjsSubtitle = "[뉴게임01]https://…/뉴게임01.srt,…"). */
    fun ohliSubtitleFiles(html: String): List<String> {
        val listed = Regex("playerjsSubtitle\\s*=\\s*\"([^\"]*)\"").find(html)?.groupValues?.get(1).orEmpty()
        return Regex("(?:\\[[^\\]]*\\])?\\s*(https?://[^,\\s]+)").findAll(listed).map { it.groupValues[1] }.distinct().toList()
    }
    fun ohliProps(html: String): JsonObject {
        val json = Regex("<script data-page=\"app\" type=\"application/json\">([\\s\\S]*?)</script>").find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("애니24 페이지를 읽지 못했습니다.")
        return Json.parseToJsonElement(json).jsonObject.obj("props")
    }
    /** Each part escaped whole, "/" too (a title like "란마 1/2 (2026)"), as encodeURIComponent does. */
    fun ohliPath(vararg parts: String): String = OHLI24_WEB + "/" + parts.joinToString("/") { it.encodeURLParameter() }
    fun ohliInfo(item: JsonObject): Map<String, String> = item.obj("metadata").list("info_rows").mapNotNull { row ->
        val pair = row as? JsonArray ?: return@mapNotNull null
        val label = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        label to (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty()
    }.toMap()
    /** "2025-10-29T10:14:25+09:00" / "2025-10-25T08:49:54.000000Z" -> the day in Korea. */
    fun ohliDay(value: String): String {
        val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::\\d{2})?(?:\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?)?").find(value.trim()) ?: return ""
        val (y, m, d, h, mi, zone) = match.destructured
        if (h.isEmpty()) return "$y-$m-$d"
        val offset = when {
            zone.isEmpty() -> 540
            zone == "Z" -> 0
            else -> zone.filter(Char::isDigit).let { (if (zone.startsWith("-")) -1 else 1) * (it.take(2).toInt() * 60 + it.drop(2).toInt()) }
        }
        val minutes = h.toInt() * 60 + mi.toInt() - offset + 540
        val days = civilDays(y.toInt(), m.toInt(), d.toInt()) + floorDivide(minutes.toLong(), 1440L)
        return civilDate(days)
    }
    private fun floorDivide(a: Long, b: Long): Long = a / b - if (a % b != 0L && (a < 0) != (b < 0)) 1 else 0
    private fun civilDays(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = floorDivide(y, 400L)
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
    private fun civilDate(days: Long): String {
        val z = days + 719468
        val era = floorDivide(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return year.toString().padStart(4, '0') + "-" + month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
    }
    /** A series as the app lists it, from a board's card (comics.data) or the airing list's shorter one. */
    fun ohliAnime(item: JsonObject): Anime {
        val slug = item.text("slug").ifBlank { item.text("title") }
        val info = ohliInfo(item)
        val count = item.number("episodes_count") ?: Regex("\\d+").find(item.text("eps"))?.value?.toIntOrNull()
        val thumbnail = item.text("thumbnail")
        return Anime(id = slug, title = item.text("title").ifBlank { slug }, source = "ohli24", detailUrl = ohliPath("c", slug),
            native = item.text("original_title").ifBlank { info["원제"].orEmpty() },
            poster = if (thumbnail.isBlank()) "" else "$OHLI24_WEB/storage/$thumbnail",
            score = item.text("rating").toDoubleOrNull() ?: 0.0,
            year = Regex("\\d{4}").find(info["방영일"].orEmpty().ifBlank { info["상영일"].orEmpty() }.ifBlank { item.text("published_at") })?.value.orEmpty(),
            format = if (item.text("category") == "극장판") "MOVIE" else "TV",
            totalEpisodes = item.number("episode_total")?.takeIf { it > 0 }, availableEpisodes = count,
            status = item.text("category").ifBlank { if (item.text("eps").isNotBlank()) "방영중" else "" },
            description = DisplayText.plain(item.text("description")),
            genres = item.text("tags_string").split(",").map(String::trim).filter(String::isNotBlank))
    }
    /** A series and every episode: numbered ones first by number, specials (OVA, SP) after them; a movie is its one 1화. */
    fun ohliDetail(comic: JsonObject, rows: List<JsonObject>, summary: Anime): SourceDetail {
        data class Row(val label: EpisodeLabel, val url: String, val order: Int, val date: String)
        // An episode titled by name alone ("80000대장경") takes its number from its address (/e/<title N화>), or else from
        // its place in the site's order.
        val listed = rows.map {
            val label = episodeLabel(it.text("title")).let { label ->
                val fromSlug = if (label.number == null && !label.special) Regex("(\\d+(?:\\.\\d+)?)\\s*화\\s*$").find(it.text("slug"))?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { value -> value != 0.0 } else null
                if (fromSlug != null) label.copy(number = fromSlug) else label
            }
            Row(label, ohliPath("e", it.text("slug")), it.number("sort") ?: 0, ohliDay(it.text("published_at")))
        }.sortedBy { it.order }
        var items = listed.zip(numberTitled(listed.map { it.label })) { row, label -> row.copy(label = label) }
            .sortedWith(compareBy<Row>({ it.label.special }, { it.label.number ?: 0.0 }, { it.order }))
        if (comic.text("category") == "극장판" && items.size == 1) items = listOf(items[0].copy(label = EpisodeLabel("1", 1.0, false)))
        val episodes = items.mapIndexed { index, row -> koreanEpisode(row.label, row.url, index, row.date) }.distinctBy { it.id }
        val info = ohliInfo(comic)
        val card = ohliAnime(JsonObject(comic + ("episodes_count" to JsonPrimitive(episodes.size))))
        val anime = summary.copy(id = summary.id.ifBlank { card.id },
            title = card.title, native = card.native.ifBlank { summary.native }, poster = card.poster.ifBlank { summary.poster },
            description = card.description.ifBlank { summary.description },
            genres = card.genres.ifEmpty { info["장르"].orEmpty().split(",").map(String::trim).filter(String::isNotBlank) },
            year = card.year.ifBlank { summary.year }, airedDate = info["방영일"].orEmpty(), format = card.format, status = card.status,
            detailUrl = card.detailUrl, totalEpisodes = card.totalEpisodes, source = "ohli24", episodes = episodes,
            availableEpisodes = items.filter { !it.label.special }.mapNotNull { it.label.number?.takeIf { value -> value % 1.0 == 0.0 }?.toInt() }.maxOrNull())
        return SourceDetail(anime, if (episodes.isEmpty()) emptyList() else listOf(EpisodeServer(1, "애니24", episodes)))
    }
    // An episode's label on the Korean sites (애니24, 링크애니): "12화", "9.5화", or a special ("OVA 1화", "SP 노벨 히로인즈",
    // "특별편"). A special has no number of its own (read as one, "OVA 1화" was 1화 a second time): it keeps its label.
    data class EpisodeLabel(val name: String, val number: Double?, val special: Boolean)
    private val specialEpisode = Regex("^\\s*(?:OVA|OAD|ONA|SP|SPECIAL|스페셜|특별편?|번외편?|극장판)(?![a-z])", RegexOption.IGNORE_CASE)
    fun episodeLabel(label: String): EpisodeLabel {
        val text = label.trim()
        if (specialEpisode.containsMatchIn(text)) return EpisodeLabel(text.replace(Regex("\\s*화\\s*$"), ""), null, true)
        val number = episodeLabelNumber(text)
        return EpisodeLabel(number?.let(::numberName) ?: text.ifBlank { "1" }, number, false)
    }
    // The number a label gives: one marked as an episode's ("12화", "5회", "제3화", "EP 3", "#3") or the label itself
    // ("12", "12-13", "12 END", "12 (完)"). A number inside an episode's own title ("80000대장경") is part of the title,
    // not its number: read as one, the 5th episode was 80000화.
    fun episodeLabelNumber(label: String): Double? {
        val text = label.trim()
        val marked = Regex("(\\d+(?:\\.\\d+)?)\\s*[화회話]").find(text) ?: Regex("^(?:제|ep(?:isode)?\\.?|#)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(text)
        if (marked != null) return marked.groupValues[1].toDoubleOrNull()?.takeIf { it != 0.0 }
        val lead = Regex("^(\\d+(?:\\.\\d+)?)([\\s\\S]*)$").find(text) ?: return null
        val rest = lead.groupValues[2].replace(Regex("[(\\[{【][^)\\]}】]*[)\\]}】]"), "").replace(Regex("\\b(?:end|final|fin)\\b|完|끝|最終", RegexOption.IGNORE_CASE), "")
        return if (Regex("\\p{L}").containsMatchIn(rest)) null else lead.groupValues[1].toDoubleOrNull()?.takeIf { it != 0.0 }
    }
    // An episode listed by its title alone ("팔만대장경") is numbered by its place in the site's order: the one after the
    // numbered episode before it (1 at the start). Specials keep no number.
    fun numberTitled(labels: List<EpisodeLabel>): List<EpisodeLabel> {
        var last = 0.0
        return labels.map { label ->
            if (label.special) label else (if (label.number == null) label.copy(number = kotlin.math.floor(last) + 1) else label).also { last = it.number ?: last }
        }
    }
    private fun numberName(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    /** "12화", or a special's own label ("OVA 1", "SP 노벨 히로인즈"). */
    fun episodeTitle(name: String) = if (Regex("\\d$").containsMatchIn(name)) name + "화" else name
    private fun koreanEpisode(label: EpisodeLabel, url: String, index: Int, airedDate: String) =
        Episode(id = url, number = label.number?.toInt() ?: if (label.special) 0 else index + 1, title = episodeTitle(label.name), videoUrl = url,
            displayNumber = label.name, airedDate = airedDate)
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
            // 9.5화 keeps its own number and an OVA its label, so neither is listed, played or saved as another episode.
            // An episode named by its title alone ("80000대장경") is numbered by its place in the list.
            val found = links.mapNotNull { link ->
                val url = link.absUrl("href")
                if (url.isBlank()) return@mapNotNull null
                var label = episodeLabel(link.text().replace(link.selectFirst(".eps-date")?.text().orEmpty(), "").trim())
                if (label.number == null && !label.special) {
                    val fromUrl = Regex("/k(\\d+)/?$").find(url)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it != 0.0 }
                    if (fromUrl != null) label = EpisodeLabel(numberName(fromUrl), fromUrl, false)
                }
                Triple(label, url, link.selectFirst(".eps-date")?.text()?.takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }.orEmpty())
            }.distinctBy { it.second }
            val labelled = found.zip(numberTitled(found.map { it.first })).mapIndexed { position, (row, label) ->
                label to koreanEpisode(label, row.second, position, row.third)
            }.sortedWith(compareBy<Pair<EpisodeLabel, Episode>>({ it.first.special }, { it.first.number ?: 0.0 }))
            val episodes = labelled.map { it.second }
            if (episodes.isNotEmpty()) servers += EpisodeServer(index + 1, tabs.getOrNull(index).orEmpty().ifBlank { if (source == "linkani") "링크애니" else "애니24" }, episodes)
        }
        if (source == "ohli24" && servers.isEmpty()) servers += EpisodeServer(1, "애니24", listOf(Episode(summary.detailUrl, 1, "1화", videoUrl = summary.detailUrl)))
        val title = doc.selectFirst(if (source == "linkani") ".detail-info-title" else "meta[property=og:title]")?.let { if (it.tagName() == "meta") it.attr("content").replace(Regex("\\s*자막\\s*다시보기\\s*$"), "") else it.text() }.orEmpty().ifBlank { summary.title }
        val image = doc.selectFirst(if (source == "linkani") ".detail-img img" else ".article-box-img img")
        val poster = image?.absUrl("data-original").orEmpty().ifBlank { image?.absUrl("src").orEmpty() }.ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }.ifBlank { summary.poster }
        val description = DisplayText.plain(if (source == "linkani") doc.selectFirst("meta[name=description]")?.attr("content").orEmpty().replace(Regex("^.*?다시보기\\.\\s*"), "") else doc.selectFirst(".movie-coment")?.html().orEmpty())
        val year = Regex("(19|20)\\d{2}").find(fields["년"].orEmpty() + fields["방영일"].orEmpty())?.value.orEmpty()
        val native = fields["원제"].orEmpty().ifBlank { Regex("원제\\s*[:：]\\s*(.+?)$").find(doc.selectFirst(".box.tv")?.text().orEmpty())?.groupValues?.get(1).orEmpty() }
        val anime = summary.copy(title = title, poster = poster, source = source, description = description,
            native = native, year = year.ifBlank { summary.year }, airedDate = fields["방영일"].orEmpty(),
            genres = if (source == "linkani") doc.select(".detail-info-desc li").firstOrNull { it.selectFirst("span")?.text()?.contains("장르") == true }?.select("a")?.map { it.text() }.orEmpty()
                else fields["장르"].orEmpty().split(Regex("[,/·]")).map(String::trim).filter(String::isNotBlank),
            studios = if (source == "linkani") doc.select(".detail-info-desc li").firstOrNull { it.selectFirst("span")?.text()?.contains("제작사") == true }?.select("a")?.map { it.text() }.orEmpty() else summary.studios,
            score = doc.selectFirst(".ewave-star")?.attr("score")?.toDoubleOrNull() ?: summary.score,
            totalEpisodes = Regex("\\d+").find(fields["총화수"].orEmpty())?.value?.toIntOrNull() ?: summary.totalEpisodes,
            availableEpisodes = servers.flatMap { it.episodes }.filter { it.displayNumber.toDoubleOrNull()?.let { value -> value % 1.0 == 0.0 } == true }.maxOfOrNull { it.number },
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
        return listOf(DesktopPlaybackStream("링크애니", url, "https://linkani.tv/", mapOf("Referer" to "https://linkani.tv/"), tracks, burnedKorean = tracks.isEmpty()))
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

data class DesktopPlaybackStream(val label: String, val url: String, val referer: String, val headers: Map<String, String>, val subtitles: List<PlaybackTrack> = emptyList(), val burnedKorean: Boolean = false)
data class DesktopPlaybackServer(val label: String, val kind: String, val url: String)
/** An 애니24 episode read from its player directly: the best variant, its playlist, and the subtitle files named. */
data class OhliDirectStream(val url: String, val manifest: String, val subtitles: List<String>)

internal class DesktopSourceRepository(private val client: HttpClient) {
    private val nativeIDs = mutableMapOf<String, Pair<Int?, Int?>?>()
    internal suspend fun lookupNativeIDs(native: String, year: String): Pair<Int?, Int?>? {
        if (native.isBlank() || Regex("[가-힣]").containsMatchIn(native)) return null
        val key = "$native|$year"
        if (nativeIDs.containsKey(key)) return nativeIDs[key]
        return try {
            suspend fun ask() = client.post("https://graphql.anilist.co") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("query", "query(\$search:String){Page(perPage:10){media(search:\$search,type:ANIME){id idMal seasonYear startDate{year} title{native}}}}")
                    put("variables", buildJsonObject { put("search", native) })
                }.toString())
            }
            var response = ask()
            if (response.status.value == 429) {
                delay(((response.headers[HttpHeaders.RetryAfter]?.toDoubleOrNull()?.takeIf { it > 0 } ?: 5.0).coerceAtMost(15.0) * 1000).toLong())
                response = ask()
            }
            check(response.status.isSuccess()) { "AniList HTTP ${response.status.value}" }
            val rows = Json.parseToJsonElement(response.body<String>()).jsonObject.obj("data").obj("Page").list("media").filterIsInstance<JsonObject>()
            fun same(row: JsonObject) = DesktopTitleRules.compareKey(row.obj("title").text("native")) == DesktopTitleRules.compareKey(native)
            fun inYear(row: JsonObject) = year.toIntOrNull()?.takeIf { it > 0 }?.let { it == (row.number("seasonYear") ?: row.obj("startDate").number("year")) } == true
            val row = rows.firstOrNull { same(it) && inYear(it) } ?: rows.firstOrNull(::same) ?: rows.firstOrNull(::inYear)
            val result = row?.let { it.number("id") to it.number("idMal") }
            nativeIDs[key] = result
            result
        } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
    }
    suspend fun serverPages(source: String, url: String, number: Int, anilist: Int): List<DesktopPlaybackServer> {
        if (source == "animenosub") return DesktopSourceParser.animenosubServers(text(url), url)
        if (source != "reanime") return emptyList()
        val slug = Url(url).encodedPath.trimEnd('/').substringAfterLast('/')
        // The current API often serves streams only from /api/flix: both are asked at once (an empty /api/watch cost a
        // whole request first). Only links matching the requested audio count toward the watch result.
        return coroutineScope {
            val flix = if (anilist > 0) async { runCatching { DesktopSourceParser.reanimeServers(Json.parseToJsonElement(text("https://reanime.to/api/flix/$anilist/$number")).jsonObject) } } else null
            val links = try { DesktopSourceParser.reanimeServers(Json.parseToJsonElement(text("https://reanime.to/api/watch/$slug/$number")).jsonObject) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            if (links.isNotEmpty() || flix == null) { flix?.cancel(); links } else flix.await().getOrThrow()
        }
    }
    private val cursors = mutableMapOf<String, MutableMap<Int, String>>()
    private suspend fun text(url: String, params: Map<String, String> = emptyMap()) = client.get(url) {
        header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1")
        header("Referer", Url(url).let { it.protocol.name + "://" + it.host + "/" })
        header("Accept-Language", "ko-KR,ko;q=0.9")
        params.forEach { (key, value) -> parameter(key, value) }
    }.body<String>()
    private class MiruroQueryFailure(message: String) : IllegalStateException(message)
    internal suspend fun miruro(path: String, params: Map<String, String> = emptyMap()): JsonObject = withContext(kotlinWork) {
        var failure: Exception? = null
        for (origin in listOf("https://www.miruro.to", "https://www.miruro.tv", "https://www.miruro.cx", "https://www.miruro.bz")) {
            try {
                if (MiruroTransportRegistry.transport != null) {
                    val address = io.ktor.http.URLBuilder("$origin/api/v1/$path").apply {
                        params.forEach { (key, value) -> if (value.isNotEmpty()) parameters.append(key, value) }
                    }.buildString()
                    val native = MiruroTransportRegistry.fetch(address)
                    if (native != null) {
                        val packet = Json.parseToJsonElement(native).jsonObject
                        val status = packet.number("status") ?: 0
                        if (status !in 200..299) {
                            if (status in 400..499 && status !in listOf(403,408,429)) throw MiruroQueryFailure("Miruro HTTP $status")
                            error("Miruro HTTP $status")
                        }
                        val body = packet.text("body").decodeBase64Bytes()
                        require(body.size <= 8 * 1024 * 1024) { "Miruro 응답이 너무 큽니다." }
                        val key = "miruro/catalog".encodeToByteArray()
                        return@withContext Json.parseToJsonElement(inflateCatalogGzip(ByteArray(body.size) { body[it] xor key[it % key.size] }).decodeToString()).jsonObject
                    }
                }
                val result = withTimeoutOrNull(8000) {
                    val response = client.get("$origin/api/v1/$path") {
                        expectSuccess = false
                        header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1")
                        header("Referer", "$origin/"); header("Accept", "*/*")
                        params.forEach { (key, value) -> if (value.isNotEmpty()) parameter(key, value) }
                    }
                    val status = response.status.value
                    if (status !in 200..299) {
                        if (status in 400..499 && status !in listOf(403,408,429)) throw MiruroQueryFailure("Miruro HTTP $status")
                        error("Miruro HTTP $status")
                    }
                    val body = response.body<ByteArray>()
                    require(body.size <= 8 * 1024 * 1024) { "Miruro 응답이 너무 큽니다." }
                    val key = "miruro/catalog".encodeToByteArray()
                    val decoded = ByteArray(body.size) { body[it] xor key[it % key.size] }
                    Json.parseToJsonElement(inflateCatalogGzip(decoded).decodeToString()).jsonObject
                }
                if (result != null) return@withContext result
                failure = IllegalStateException("Miruro 응답 시간 초과")
            } catch (error: CancellationException) { throw error }
            catch (error: MiruroQueryFailure) { throw error }
            catch (error: Exception) { if (error.message?.startsWith("MIRURO_STOP:") == true) throw error; failure = error }
        }
        throw failure ?: IllegalStateException("Miruro 연결 실패")
    }
    private val linkaniNext = mutableMapOf<String, Boolean>()
    fun hasNextLinkaniPage(page: Int, filter: BrowseFilter, query: String): Boolean = linkaniNext[query + filter.toString() + ":" + page] == true
    suspend fun browse(source: String, query: String, page: Int, filter: BrowseFilter, catalogSearch: Boolean = false): List<Anime> {
        if (source == "miruro") {
            val key = query + filter.toString()
            if (page == 1) cursors[key] = mutableMapOf()
            val pages = cursors.getOrPut(key) { mutableMapOf() }
            if (page > 1 && pages[page] == null) return emptyList()
            val params = mutableMapOf("limit" to "15", "sort" to "-popularity")
            params["sort"] = when(filter.sort) { "score" -> "-score"; "year" -> "-started_on"; "updated" -> "-updated_at"; else -> "-popularity" }
            if (query.isNotBlank()) params["q"] = query
            pages[page]?.let { params["cursor"] = it }
            if (filter.year.isNotBlank()) params["season_year"] = filter.year
            if (filter.format.isNotBlank()) params["format"] = filter.format
            if (filter.season.isNotBlank()) params["season"] = filter.season
            if (filter.status.isNotBlank()) params["status"] = filter.status
            if (filter.genres.isNotEmpty()) params["genre_all"] = filter.genres.joinToString(",")
            mapOf("status" to "status", "country" to "country_of_origin", "source" to "source", "adult" to "is_adult", "scoreMin" to "score_gte", "scoreMax" to "score_lte").forEach { (key, api) -> filter.extras[key]?.takeIf(String::isNotBlank)?.let { params[api] = it } }
            filter.extras["tag"]?.takeIf(String::isNotBlank)?.let { params["tag_all"] = it.split(',').map(String::trim).filter(String::isNotBlank).distinct().joinToString(",") }
            val values = mutableListOf<JsonObject>()
            repeat(if(filter.sort == "year" && !catalogSearch) 20 else 1) {
                val root = miruro("anime", params)
                values += root.list("data").filterIsInstance<JsonObject>().filter { raw -> catalogSearch || filter.sort != "year" || filter.extras.isNotEmpty() ||
                    (raw.text("status") != "NOT_YET_RELEASED" && (raw.number("season_year") ?: 0) in 1..currentCatalogDate().take(4).toInt()) }
                val next = root.text("next_cursor").ifBlank { root.obj("pagination").text("next_cursor") }
                if (root.text("has_more") != "true" || next.isBlank() || next == params["cursor"]) { pages.remove(page + 1); return values.map(DesktopSourceParser::miruroAnime) }
                pages[page + 1] = next; params["cursor"] = next
                if (values.size >= 15) return values.map(DesktopSourceParser::miruroAnime)
            }
            return values.map(DesktopSourceParser::miruroAnime)
        }
        if (source == "ohli24") {
            // The airing shows first, then the finished ones a page at a time; movies from their own board.
            if (query.isNotBlank()) return if (page > 1) emptyList() else ohliSearch(query)
            if (filter.format.equals("Movie", true)) return ohliBoard("극장판", page).first
            return if (page <= 1) ohliAiring() else ohliBoard("완결", page - 1).first
        }
        val base = "https://linkani.tv"
        val suffix = if (page > 1) "page/" + page + "/" else ""
        val category = filter.genres.firstOrNull().orEmpty().ifBlank { filter.extras["weekday"].orEmpty() }.ifBlank { if (filter.status == "RELEASING") "월화수목금토일"[((catalogWeekday() + 6) % 7)].toString() else "" }
        val route = (if (category.isNotBlank()) "class/" + category.encodeURLPathPart() + "/" else "") + (if (filter.year.isNotBlank()) "year/" + filter.year.encodeURLPathPart() + "/" else "") + (if (filter.format.isNotBlank()) "lang/" + filter.format.encodeURLPathPart() + "/" else "") + suffix
        val path = if (filter.sort == "popular" && route == suffix) "/label/topday/" + suffix else "/list/2/" + route
        val params = if (query.isBlank()) emptyMap() else mapOf("wd" to query)
        val html = text(base + if (query.isBlank()) path else "/view/" + suffix, params)
        val items = DesktopSourceParser.koreanList(html, source).map { if (query.isBlank() && filter.format.isNotBlank()) it.copy(format = filter.format) else it }
        // A search that found nothing as spaced is tried again by pieces (one page of them).
        if (query.isNotBlank() && page == 1 && items.isEmpty()) {
            linkaniNext[query + filter.toString() + ":" + page] = false
            return searchIgnoringSpaces(query) { piece -> DesktopSourceParser.koreanList(text("$base/view/", mapOf("wd" to piece)), source) }
        }
        val next = Ksoup.parse(html).select("a[href]").firstOrNull { it.text().trim() == "→" }?.attr("href")
        linkaniNext[query + filter.toString() + ":" + page] = items.isNotEmpty() && (next == null || Regex("^(https?://|/)").containsMatchIn(next))
        return items
    }
    internal suspend fun ohliPage(params: Map<String, String>) = DesktopSourceParser.ohliProps(text(DesktopSourceParser.OHLI24_WEB + "/", params))
    private val ohliWeekdays = mutableMapOf<String, Pair<Long, Set<String>>>()
    internal suspend fun ohliCatalog(page: Int, filter: BrowseFilter): SourceCatalogPage = coroutineScope {
        val e = filter.extras
        val weekday = e["weekday"].orEmpty()
        val types = when {
            filter.format.equals("Movie", true) -> listOf("극장판")
            weekday.isNotBlank() -> listOf("방영중")
            !e["status"].isNullOrBlank() -> listOf(e.getValue("status"))
            filter.format == "TV" -> listOf("방영중", "종영")
            else -> emptyList()
        }
        val result = async { ohliPage(buildMap {
            types.forEachIndexed { index, value -> put("types[$index]", value) }
            filter.genres.forEachIndexed { index, value -> put("genres[$index]", value) }
            if (filter.year.isNotBlank()) put("years[0]", filter.year)
            if (weekday.isNotBlank()) put("weekday", weekday)
            put("sort", e["order"].orEmpty().ifBlank { "update-desc" }); put("page", page.toString())
        }) }
        val dayIds = if (weekday.isBlank()) null else async {
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            ohliWeekdays[weekday]?.takeIf { now - it.first < 300000 }?.second ?: run {
                val day = DesktopSourceParser.ohliProps(text(DesktopSourceParser.ohliPath("방영중"), mapOf("weekday" to weekday)))
                require(day["items"] is JsonArray && day.text("sca") == weekday) { "애니24 요일 분류를 읽지 못했습니다." }
                day.list("items").filterIsInstance<JsonObject>().map { DesktopSourceParser.ohliAnime(it).id }.toSet().also { ohliWeekdays[weekday] = now to it }
            }
        }
        val comics = result.await().obj("comics")
        require(comics["data"] is JsonArray) { "애니24 필터 결과를 읽지 못했습니다." }
        val raw = comics.list("data").filterIsInstance<JsonObject>().map(DesktopSourceParser::ohliAnime)
        val ids = dayIds?.await()
        SourceCatalogPage(raw.filter { ids == null || it.id in ids }, comics.text("next_page_url").isNotBlank(), raw.joinToString("|") { it.id })
    }
    suspend fun ohliAiring(): List<Anime> =
        DesktopSourceParser.ohliProps(text(DesktopSourceParser.ohliPath("방영중"))).list("items").filterIsInstance<JsonObject>().map(DesktopSourceParser::ohliAnime)
    private suspend fun ohliBoard(board: String, page: Int, keyword: String = ""): Pair<List<Anime>, Boolean> {
        val comics = DesktopSourceParser.ohliProps(text(DesktopSourceParser.ohliPath(board), buildMap {
            if (page > 1) put("page", page.toString()); if (keyword.isNotBlank()) put("keyword", keyword)
        })).obj("comics")
        return comics.list("data").filterIsInstance<JsonObject>().map(DesktopSourceParser::ohliAnime) to comics.text("next_page_url").isBlank()
    }
    /**
     * The finished and movie boards search themselves (spaced as typed: searched again by pieces when nothing is found);
     * the airing board lists every airing show, matched here.
     */
    private suspend fun ohliSearch(query: String): List<Anime> = coroutineScope {
        suspend fun safe(block: suspend () -> List<Anime>) = try { block() } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
        suspend fun boards(keyword: String) = coroutineScope { listOf("완결", "극장판").map { board -> async { safe { ohliBoard(board, 1, keyword).first } } }.awaitAll().flatten() }
        val airing = async { safe { ohliAiring() } }
        val listed = boards(query)
        val searched = listed.ifEmpty { searchIgnoringSpaces(query) { boards(it) } }
        val wanted = spaceKey(query)
        (airing.await().filter { spaceKey(it.title).contains(wanted) } + searched).distinctBy { it.id }
    }
    private fun spaceKey(value: String) = normalizeDesktopTitle(value).lowercase().replace(Regex("\\s+"), "")
    /**
     * The Korean sites (애니24, 링크애니) find a keyword only as it is spaced: "스파이패밀리" finds nothing of "스파이 패밀리".
     * A Korean query that found nothing is searched again by three pieces of it (its start, middle and end: three letters
     * each, two for a short query), and of what they find, the titles holding the whole query, spaces aside, are kept.
     */
    internal suspend fun searchIgnoringSpaces(query: String, search: suspend (String) -> List<Anime>): List<Anime> = coroutineScope {
        val wanted = spaceKey(query)
        if (!TitleCandidates.isKorean(query) || wanted.length < 3) return@coroutineScope emptyList()
        val size = if (wanted.length >= 5) 3 else 2
        val pieces = listOf(0, (wanted.length - size) / 2, wanted.length - size).map { wanted.substring(it, it + size) }.distinct().filter { it != query.trim() }
        pieces.map { piece -> async { try { search(piece) } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() } } }.awaitAll()
            .flatten().filter { spaceKey(it.title).contains(wanted) }.distinctBy { it.id }
    }
    /** A series by its page; one saved from www.ohli24.net is opened by its title, or found by a search for it. */
    private suspend fun ohliDetail(summary: Anime): SourceDetail {
        val own = summary.detailUrl.startsWith(DesktopSourceParser.OHLI24_WEB + "/c/")
        val slug = if (own) Url(summary.detailUrl).encodedPath.removePrefix("/c/").decodeURLPart() else summary.title.ifBlank { summary.id }
        var props = try { DesktopSourceParser.ohliProps(text(DesktopSourceParser.ohliPath("c", slug))) }
            catch (error: CancellationException) { throw error } catch (error: Exception) { if (own) throw error; JsonObject(emptyMap()) }
        if (props["comic"] !is JsonObject) {
            if (own) throw IllegalStateException("애니24에서 이 작품을 찾지 못했습니다.")
            val hit = ohliSearch(slug).firstOrNull { DesktopTitleRules.compareKey(it.title) == DesktopTitleRules.compareKey(slug) }
                ?: throw IllegalStateException("애니24에서 이 작품을 찾지 못했습니다.")
            props = DesktopSourceParser.ohliProps(text(hit.detailUrl))
        }
        val comic = props.obj("comic")
        val rows = props.obj("episodes").list("data").filterIsInstance<JsonObject>().toMutableList()
        // The rest of a long series' episode pages (원피스: 13) and the AniList lookup go out together, not one by one.
        val head = DesktopSourceParser.ohliDetail(comic, rows, summary).anime
        return coroutineScope {
            val lookup = async { if (head.anilistId == null) lookupNativeIDs(head.native, head.year) else null }
            val more = (2..(props.obj("episodes").number("last_page") ?: 1)).map { page ->
                async { DesktopSourceParser.ohliProps(text(DesktopSourceParser.ohliPath("c", comic.text("slug")), mapOf("page" to page.toString()))).obj("episodes").list("data").filterIsInstance<JsonObject>() }
            }.awaitAll()
            more.forEach { rows += it }
            val detail = DesktopSourceParser.ohliDetail(comic, rows, summary)
            val ids = if (detail.anime.anilistId == null) lookup.await() else { lookup.cancel(); null }
            if (ids == null) detail else detail.copy(anime = detail.anime.copy(anilistId = ids.first, malId = ids.second))
        }
    }
    /** The player (cdndania / michealcdn, FirePlayer) read without opening it (desktop ohliStreamDirect): its page names
     *  the episode's subtitle files, and its getVideo answer has a signed master playlist that opens without the page's
     *  cookie. Two requests at once instead of the page loading in a hidden web view. */
    suspend fun ohliStream(playerUrl: String, userAgent: String): OhliDirectStream = withTimeout(20_000) { coroutineScope {
        val player = Url(playerUrl)
        val origin = player.protocol.name + "://" + player.hostWithPortIfSpecified
        val hash = player.encodedPath.trimEnd('/').substringAfterLast('/')
        val page = async {
            try { client.get(playerUrl) { header("User-Agent", userAgent); header("Referer", DesktopSourceParser.OHLI24_WEB + "/") }.body<String>() }
            catch (error: CancellationException) { throw error } catch (_: Exception) { "" }
        }
        val answer = client.post("$origin/player/index.php") {
            parameter("data", hash); parameter("do", "getVideo")
            header("User-Agent", userAgent); header("X-Requested-With", "XMLHttpRequest"); header("Referer", playerUrl); header("Origin", origin)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("hash=" + hash.encodeURLParameter() + "&r=" + (DesktopSourceParser.OHLI24_WEB + "/").encodeURLParameter())
        }.body<String>()
        val info = Json.parseToJsonElement(answer).jsonObject
        val hls = (info["hls"] as? JsonPrimitive)?.content.orEmpty()
        val secured = info.text("securedLink")
        check(hls !in listOf("", "false", "0", "null") && secured.startsWith("http")) { "애니24 플레이어가 재생목록을 주지 않았습니다." }
        val variant = DesktopSourceParser.ohliVariant(client.get(secured) { header("User-Agent", userAgent) }.body<String>(), secured)
        val manifest = client.get(variant) { header("User-Agent", userAgent) }.body<String>()
        OhliDirectStream(variant, manifest, DesktopSourceParser.ohliSubtitleFiles(page.await()))
    } }
    /** An episode's player page, from its page data (desktop resolveOhliEpisode). */
    suspend fun ohliPlayer(url: String): String =
        DesktopSourceParser.ohliProps(text(url)).obj("playing").obj("body").text("url").takeIf { it.startsWith("http") }
            ?: throw IllegalStateException("이 회차의 영상 플레이어를 찾지 못했습니다.")
    suspend fun detail(summary: Anime, source: String): SourceDetail {
        if (source == "ohli24") return ohliDetail(summary)
        if (source != "miruro") {
            val detail = DesktopSourceParser.koreanDetail(text(summary.detailUrl), summary, source)
            val ids = if (detail.anime.anilistId == null) lookupNativeIDs(detail.anime.native, detail.anime.year) else null
            return if (ids == null) detail else detail.copy(anime = detail.anime.copy(anilistId = ids.first, malId = ids.second))
        }
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
    fun hasNextCatalogPage(page: Int, filter: BrowseFilter, query: String): Boolean = cursors[query + filter.toString()]?.containsKey(page + 1) == true
    suspend fun homeShows(source: String, season: Boolean): List<Anime> {
        val date = currentCatalogDate(); val year = date.take(4)
        val quarter = listOf("WINTER", "SPRING", "SUMMER", "FALL")[(date.substring(5, 7).toInt() - 1) / 3]
        if (source == "ohli24") return if (season) ohliAiring() else emptyList()
        if (source == "linkani") return coroutineScope { (1..if (season) 2 else 1).map { async { browse(source, "", it, if (season) BrowseFilter(year = year) else BrowseFilter(status = "RELEASING")) } }.awaitAll() }.flatten().distinctBy { it.id }
        val candidates = mutableListOf<JsonObject>(); var cursor = ""
        repeat(6) {
            if (candidates.size >= 40) return@repeat
            val root = miruro("anime", buildMap {
                put("limit", "15"); put("sort", "-popularity"); if (cursor.isNotBlank()) put("cursor", cursor)
                if (season) { put("season", quarter); put("season_year", year) } else put("status", "RELEASING")
            })
            candidates += root.list("data").filterIsInstance<JsonObject>().filter { raw -> raw.text("status") != "NOT_YET_RELEASED" && raw.obj("episode_counts").values.any { (it as? JsonPrimitive)?.content?.toIntOrNull()?.let { n -> n > 0 } == true } }
            cursor = root.text("next_cursor")
            if (cursor.isBlank()) return candidates.map(DesktopSourceParser::miruroAnime).let { if (season) playableMiruro(it) else it }
        }
        return candidates.map(DesktopSourceParser::miruroAnime).let { if (season) playableMiruro(it) else it }
    }
    private suspend fun playableMiruro(items: List<Anime>): List<Anime> = coroutineScope {
        items.chunked(6).flatMap { group -> group.map { item -> async { try { item.takeIf { detail(it, "miruro").anime.episodes.isNotEmpty() } } catch (e: CancellationException) { throw e } catch (_: Exception) { null } } }.awaitAll().filterNotNull() }
    }
    suspend fun streams(source: String, animeId: String, number: Int, url: String): List<DesktopPlaybackStream> = when (source) {
        "miruro" -> DesktopSourceParser.miruroStreams(miruro("anime/" + animeId + "/episodes/" + number + "/play"))
        "linkani" -> DesktopSourceParser.linkaniStreams(text(url))
        else -> emptyList()
    }
}
internal expect fun currentCatalogDate(): String
internal fun catalogWeekday(): Int {
    // Gregorian weekday (Sunday = 0), independent of platform calendars.
    val parts = currentCatalogDate().take(10).split('-').map(String::toInt)
    var year = parts[0]; val month = parts[1]; if (month < 3) year--
    return (year + year / 4 - year / 100 + year / 400 + listOf(0,3,2,5,0,3,5,1,4,6,2,4)[month - 1] + parts[2]) % 7
}
