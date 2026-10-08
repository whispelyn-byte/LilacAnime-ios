package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
enum class AnimeSource(val key: String) { LINKKF("linkkf"), REANIME("reanime"), ANIMENOSUB("animenosub"), MIRURO("miruro"), OHLI24("ohli24"), LINKANI("linkani") }
data class BrowseFilter(val genres: List<String> = emptyList(), val year: String = "", val season: String = "", val format: String = "", val status: String = "", val studio: String = "", val sort: String = "")
data class SourceFilters(val genres: List<String> = emptyList(), val years: List<String> = emptyList(),
    val formats: List<String> = emptyList(), val statuses: List<String> = emptyList(),
    val seasons: List<String> = emptyList(), val studios: List<String> = emptyList(),
    val supportsYear: Boolean = false, val supportsSeason: Boolean = false,
    val sorts: List<String> = listOf("default"), val note: String = "", val seasonValues: List<String> = emptyList())
data class SourceCatalogPage(val items: List<Anime>, val hasMore: Boolean, val signature: String)

data class SourceDetail(val anime: Anime, val servers: List<EpisodeServer>)
data class PlaybackTrack(val label: String, val url: String, val referer: String = "", val language: String = "", val kind: String = "video")
class SourceRepository(private val client: HttpClient = newSharedClient()) {
    private val linkkf = LinkkfRepository(client)
    private val desktop = DesktopSourceRepository(client)
    private val filterCache = mutableMapOf<String, SourceFilters>()
    suspend fun browse(source: String, query: String = "", page: Int = 1, filter: BrowseFilter = BrowseFilter()): List<Anime> {
        require(page > 0)
        return when (source) {
            "miruro", "ohli24", "linkani" -> desktop.browse(source, query, page, filter)
            "reanime" -> ReAnimeHarParser.parseSearch(getText("https://reanime.to/api/v1/search", buildMap {
                put("limit", "36"); put("offset", ((page - 1) * 36).toString())
                if (query.isNotBlank()) put("q", query.trim())
                if (filter.sort.isNotBlank()) put("sort", when (filter.sort) { "popular" -> "popularity_desc"; "year" -> "year_desc"; else -> "score_desc" })
                if (filter.genres.isNotEmpty()) put("genre", filter.genres.joinToString(","))
                listOf("year" to filter.year, "season" to filter.season, "format" to filter.format, "status" to filter.status, "studio" to filter.studio).forEach { (key, value) -> if (value.isNotBlank()) put(key, value) }
            }))
            "animenosub" -> {
                val base = "https://animenosub.to"
                val filtered = query.isBlank() && (filter.sort.isNotBlank() || filter.status.isNotBlank() || filter.season.isNotBlank() || filter.year.isNotBlank() || filter.genres.isNotEmpty() || filter.format.isNotBlank())
                val path = if (filtered) "$base/anime/" else if (page == 1) "$base/" else "$base/page/$page/"
                val seasonValues = if (filter.year.isNotBlank()) filters("animenosub").seasonValues.filter { it.endsWith("-" + filter.year) && (filter.season.isBlank() || it == filter.season.lowercase() + "-" + filter.year) } else emptyList()
                if (filter.year.isNotBlank() && seasonValues.isEmpty()) return emptyList()
                val document = Ksoup.parse(getText(path, buildMap {
                    if (filtered) put("page", "$page")
                    if (filter.sort.isNotBlank()) put("order", when(filter.sort) { "year" -> "latest"; "score" -> "rating"; else -> "popular" })
                    if (query.isNotBlank()) put("s", query)
                    if (filter.status == "RELEASING") put("status", "ongoing")
                    filter.genres.forEachIndexed { index, genre -> put("genre[$index]", genre) }
                    if (filter.format.isNotBlank()) put("type", filter.format)
                    seasonValues.forEachIndexed { index, season -> put("season[$index]", season) }
                }), path)
                if (filter.status.isNotBlank() || filter.season.isNotBlank()) document.select("article.bs").filter { it.select(".ans-status-ribbon").text().contains("upcoming", true) }.forEach { it.remove() }
                AnimenosubParser.parseAnimeList(if(filtered) Ksoup.parse(document.select("article.bs").joinToString("\n") { it.outerHtml() }, path) else document)
            }
            else -> if (query.isNotBlank()) linkkf.search(query, page, filter)
                    else if (filter.genres.isNotEmpty() || filter.year.isNotBlank() || filter.format.isNotBlank()) linkkf.filtered(page, filter)
                    else linkkf.home(page)
        }
    }
    suspend fun detail(summary: Anime, source: String): SourceDetail = coroutineScope {
        when (source) {
            "jikan" -> SourceDetail(DesktopMetadataFallback.parse(kotlinx.serialization.json.Json.parseToJsonElement(getText("https://api.jikan.moe/v4/anime/" + summary.id + "/full")).jsonObject).first(), emptyList())
            "miruro", "ohli24", "linkani" -> desktop.detail(summary, source)
            "reanime" -> {
                val slug = summary.id.removePrefix("reanime:").substringBefore('/')
                try {
                    val root = kotlinx.serialization.json.Json.parseToJsonElement(getText("https://reanime.to/api/v1/anime/" + slug)).jsonObject
                    if (root.text("anime_id").isNotBlank()) {
                        val parsed = DesktopReanimeParser.detail(root, summary)
                        val episodes = DesktopReanimeParser.episodes(kotlinx.serialization.json.Json.parseToJsonElement(getText("https://reanime.to/api/v1/anime/" + slug + "/episodes", mapOf("limit" to "2000"))).jsonObject, parsed)
                        if (episodes.isNotEmpty()) return@coroutineScope SourceDetail(parsed.copy(episodes = episodes), listOf(EpisodeServer(1, "ReAnime", episodes)))
                    }
                } catch (error: CancellationException) { throw error } catch (_: Exception) { /* Preserve the previous Svelte fallback. */ }
                val detail = async { getText("https://reanime.to/anime/$slug/__data.json", mapOf("x-appkit-invalidated" to "001")) }
                val watch = async { getText("https://reanime.to/watch/$slug/__data.json", mapOf("x-appkit-invalidated" to "001")) }
                val detailJson = detail.await()
                val anime = ReAnimeHarParser.parseDetail(detailJson, summary).copy(source = "reanime")
                val episodes = ReAnimeHarParser.parseWatchEpisodes(watch.await(), anime, ReAnimeHarParser.episodeTotal(detailJson))
                SourceDetail(anime, listOf(EpisodeServer(1, "ReAnime", episodes)))
            }
            "animenosub" -> {
                val url = summary.detailUrl.ifBlank { "https://animenosub.to/anime/" + summary.id.removePrefix("animenosub:") + "/" }
                val anime = AnimenosubParser.parseAnimeDetail(Ksoup.parse(getText(url), url), summary)
                SourceDetail(anime, listOf(EpisodeServer(1, "Animenosub", anime.episodes)))
            }
            else -> {
                val anime = async { linkkf.detail(summary.id) }
                val servers = async { linkkf.servers(summary.id) }
                SourceDetail(anime.await(), servers.await())
            }
        }
    }
    suspend fun filters(source: String): SourceFilters = filterCache[source] ?: when (source) {
        "reanime" -> facets().let { SourceFilters(it.genres, it.years, it.formats, it.statuses, listOf("WINTER", "SPRING", "SUMMER", "FALL"), it.studios, supportsYear = true, supportsSeason = true, sorts = listOf("popular", "year", "score")) }
        "linkkf" -> linkkf.filters().let { it.copy(supportsYear = it.years.isNotEmpty(), note = "연도는 Linkkf 분류를 따릅니다. 별도의 분기 정보는 제공하지 않습니다.") }
        "animenosub" -> DesktopCatalogTaxonomy.animenosub(getText("https://animenosub.to/anime/"))
        "miruro" -> SourceFilters(supportsYear = true, supportsSeason = true, seasons = listOf("WINTER", "SPRING", "SUMMER", "FALL"), sorts = listOf("popular", "year", "score"), note = "Miruro는 연도·분기로 모아 볼 수 있습니다.")
        "linkani" -> SourceFilters(formats = listOf("TV", "Movie"), supportsYear = true, note = "링크애니는 작품 형태·연도로 모아 볼 수 있습니다. 장르·분기 정보는 제공하지 않습니다.")
        "ohli24" -> SourceFilters(formats = listOf("TV", "Movie"), note = "애니24는 TV 애니·극장판으로 모아 볼 수 있습니다. 연도·분기 정보는 제공하지 않습니다.")
        else -> SourceFilters()
    }.also { filterCache[source] = it }
    suspend fun catalog(source: String, page: Int, filter: BrowseFilter, query: String = ""): SourceCatalogPage {
        val raw = browse(source, query = query, page = page, filter = filter)
        val items = if (source in listOf("ohli24", "linkani") && filter.format.isNotBlank()) raw.filter { it.format.equals(filter.format, true) } else raw
        return SourceCatalogPage(items, if (source == "miruro") desktop.hasNextCatalogPage(page, filter, query) else raw.isNotEmpty(), raw.joinToString("|") { it.id })
    }
    suspend fun desktopStreams(source: String, animeId: String, number: Int, url: String) = desktop.streams(source, animeId, number, url)
    suspend fun top(period: String) = ReAnimeHarParser.parseTop(getText("https://reanime.to/api/v1/top/anime", mapOf("period" to period, "limit" to "20")))
    suspend fun schedule(week: Int) = ReAnimeHarParser.parseSchedule(getText("https://reanime.to/api/v1/schedule", mapOf("tz" to "Asia/Seoul", "week" to week.toString())))
    suspend fun facets() = ReAnimeHarParser.parseFacets(getText("https://reanime.to/api/v1/search", mapOf("facets" to "true", "limit" to "0")))
    suspend fun getText(url: String, params: Map<String, String> = emptyMap()): String = client.get(url) {
        header("Accept", "application/json,text/html,*/*")
        header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1")
        header("Referer", Url(url).let { it.protocol.name + "://" + it.host + "/" })
        params.forEach { (key, value) -> parameter(key, value) }
    }.bodyAsText()
    suspend fun sourceSections(source: String): List<SourceSection> = try { primarySections(source) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            listOf(SourceSection("영상 소스 응답 없음 · Jikan 이번 시즌 정보", DesktopMetadataFallback.parse(kotlinx.serialization.json.Json.parseToJsonElement(getText("https://api.jikan.moe/v4/seasons/now", mapOf("limit" to "20", "sfw" to "true"))).jsonObject)),
                SourceSection("Jikan 인기 작품 정보", DesktopMetadataFallback.parse(kotlinx.serialization.json.Json.parseToJsonElement(getText("https://api.jikan.moe/v4/top/anime", mapOf("limit" to "20"))).jsonObject)))
        }
    private suspend fun primarySections(source: String): List<SourceSection> {
        if (source == "linkkf") return listOf("PV" to "5086", "극장판" to "5061", "16+" to "5085").map { (name, tag) ->
            SourceSection(name, linkkf.filtered(1, BrowseFilter(format = tag)))
        }
        return listOf(SourceSection("이번 시즌", homeShows(source, true)),
            SourceSection("인기 작품", browse(source, filter = BrowseFilter(sort = "popular"))))
    }
    suspend fun homeShows(source: String, seasonOnly: Boolean): List<Anime> {
        if (source in listOf("miruro", "ohli24", "linkani")) return desktop.homeShows(source, seasonOnly)
        val date = currentCatalogDate(); val year = date.take(4)
        val season = listOf("WINTER", "SPRING", "SUMMER", "FALL")[(date.substring(5,7).toInt() - 1) / 3]
        if (source == "reanime") {
            val root = Json.parseToJsonElement(getText("https://reanime.to/api/v1/search", buildMap {
                put("limit", "100"); put("offset", "0")
                if (seasonOnly) { put("season", season); put("year", year) } else { put("status", "RELEASING"); put("sort", "popularity") }
            })).jsonObject
            val playable = root.list("results").ifEmpty { root.list("data") }.filterIsInstance<JsonObject>().map { it["anime"] as? JsonObject ?: it }.filter {
                (it.number("subbed") ?: 0) > 0 || (it.number("dubbed") ?: 0) > 0 || (it.number("subbed_count") ?: 0) > 0 || (it.number("dubbed_count") ?: 0) > 0
            }
            return ReAnimeHarParser.parseSearch(JsonObject(mapOf("results" to JsonArray(playable))).toString())
        }
        return (1..3).flatMap { page -> browse(source, page = page, filter = if(seasonOnly) BrowseFilter(year = year, season = season, sort = "popular") else BrowseFilter(status = "RELEASING", sort = "popular")) }.distinctBy { it.id }
    }
    suspend fun sourceSchedule(source: String, day: Int): List<Anime> {
        if (source == "linkkf") return parseCatalog(kotlinx.serialization.json.Json.parseToJsonElement(getText("https://linkkf1.5imgdarr.top/api/singlefilter.php", mapOf("categorytagid" to (21189 + day.coerceIn(0,6)).toString(), "limit" to "50"))))
        return homeShows(source, false)
    }
    suspend fun recordView(anime: Anime): SourceExtras {
        if (anime.source != "linkkf" || !Regex("^\\d+$").matches(anime.id)) return SourceExtras("", emptyList())
        try { client.submitForm("https://linkkf1.5imgdarr.top/api/view.php",
            parametersOf("action" to listOf("record"), "id" to listOf(anime.id))) { header("Referer", "https://linkkf.app/up/" + anime.id + "/") } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
        return extras(anime)
    }
    suspend fun extras(anime: Anime): SourceExtras {
        if (anime.source != "linkkf" && anime.seriesTagIds.isEmpty()) return SourceExtras("", emptyList())
        val rows = anime.seriesTagIds.flatMap { tag ->
            parseCatalog(kotlinx.serialization.json.Json.parseToJsonElement(getText("https://linkkf1.5imgdarr.top/api/singlefilter.php", mapOf("postanisstagid" to tag.toString(), "limit" to "25"))))
        }.filter { it.id != anime.id }.distinctBy { it.id }
        val stats = try { kotlinx.serialization.json.Json.parseToJsonElement(getText("https://linkkf1.5imgdarr.top/api/view.php", mapOf("action" to "get", "id" to anime.id))).jsonObject.obj("data").text("total_views") } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
        return SourceExtras(stats, rows)
    }
    fun close() = client.close()
}
internal fun newSharedClient() = HttpClient {
    expectSuccess = true
    install(HttpTimeout) { requestTimeoutMillis = 45_000; connectTimeoutMillis = 15_000 }
}
