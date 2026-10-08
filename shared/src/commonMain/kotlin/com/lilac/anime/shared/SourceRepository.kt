package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
enum class AnimeSource(val key: String) { LINKKF("linkkf"), REANIME("reanime"), ANIMENOSUB("animenosub"), MIRURO("miruro"), OHLI24("ohli24"), LINKANI("linkani") }
data class BrowseFilter(val genres: List<String> = emptyList(), val year: String = "", val season: String = "", val format: String = "", val status: String = "", val studio: String = "")
data class SourceFilters(val genres: List<String> = emptyList(), val years: List<String> = emptyList(),
    val formats: List<String> = emptyList(), val statuses: List<String> = emptyList(),
    val seasons: List<String> = emptyList(), val studios: List<String> = emptyList())

data class SourceDetail(val anime: Anime, val servers: List<EpisodeServer>)
data class PlaybackTrack(val label: String, val url: String, val referer: String = "", val language: String = "", val kind: String = "video")
class SourceRepository(private val client: HttpClient = newSharedClient()) {
    private val linkkf = LinkkfRepository(client)
    private val desktop = DesktopSourceRepository(client)
    suspend fun browse(source: String, query: String = "", page: Int = 1, filter: BrowseFilter = BrowseFilter()): List<Anime> {
        require(page > 0)
        return when (source) {
            "miruro", "ohli24", "linkani" -> desktop.browse(source, query, page, filter)
            "reanime" -> ReAnimeHarParser.parseSearch(getText("https://reanime.to/api/v1/search", buildMap {
                put("limit", "36"); put("offset", ((page - 1) * 36).toString())
                if (query.isNotBlank()) put("q", query.trim())
                if (filter.genres.isNotEmpty()) put("genre", filter.genres.joinToString(","))
                listOf("year" to filter.year, "season" to filter.season, "format" to filter.format, "status" to filter.status, "studio" to filter.studio).forEach { (key, value) -> if (value.isNotBlank()) put(key, value) }
            }))
            "animenosub" -> {
                val base = "https://animenosub.to"
                val path = if (page == 1) "$base/" else "$base/page/$page/"
                AnimenosubParser.parseAnimeList(Ksoup.parse(getText(path, if (query.isBlank()) emptyMap() else mapOf("s" to query)), path))
            }
            else -> if (query.isNotBlank()) linkkf.search(query, page, filter)
                    else if (filter.genres.isNotEmpty() || filter.year.isNotBlank() || filter.format.isNotBlank()) linkkf.filtered(page, filter)
                    else linkkf.home(page)
        }
    }
    suspend fun detail(summary: Anime, source: String): SourceDetail = coroutineScope {
        when (source) {
            "miruro", "ohli24", "linkani" -> desktop.detail(summary, source)
            "reanime" -> {
                val slug = summary.id.removePrefix("reanime:").substringBefore('/')
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
    suspend fun filters(source: String): SourceFilters = when (source) {
        "reanime" -> facets().let { SourceFilters(it.genres, it.years, it.formats, it.statuses, it.seasons, it.studios) }
        "linkkf" -> linkkf.filters()
        else -> SourceFilters()
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
    fun close() = client.close()
}
internal fun newSharedClient() = HttpClient {
    expectSuccess = true
    install(HttpTimeout) { requestTimeoutMillis = 45_000; connectTimeoutMillis = 15_000 }
}
