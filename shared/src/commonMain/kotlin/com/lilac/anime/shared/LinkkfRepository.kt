package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*

/** API contract ported from Android LinkkfApiClient; hosts are configurable. */
class LinkkfRepository(
    private val client: HttpClient = HttpClient {
        expectSuccess = true
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 12_000 }
    },
    private val apiBase: String = "https://linkkf1.5imgdarr.top/api",
    private val episodeBase: String = "https://linkkfep1.5imgdarr.top"
) {
    private val json = Json { ignoreUnknownKeys = true }
    private suspend fun request(url: String, parameters: Map<String, String> = emptyMap()): JsonElement {
        val response = client.get(url) {
            header("Referer", "https://linkkf.app/")
            header("Accept", "application/json")
            parameters.forEach { (key, value) -> parameter(key, value) }
        }
        return json.parseToJsonElement(response.bodyAsText())
    }

    suspend fun home(page: Int = 1): List<Anime> =
        parseCatalog(request("$apiBase/filter.php", mapOf("page" to page.toString(), "limit" to "24")))

    private var catalogCache: List<Anime>? = null

    // Linkkf has no verified text-search endpoint. Search its paginated catalog locally.
    suspend fun search(query: String, page: Int = 1, filter: BrowseFilter = BrowseFilter()): List<Anime> {
        require(page > 0)
        val catalog = catalogCache ?: run {
            val collected = linkedMapOf<String, Anime>()
            var next = 1
            while (true) {
                val root = request("$apiBase/filter.php", mapOf("page" to next.toString(), "limit" to "100"))
                val batch = parseCatalog(root)
                val fresh = batch.filterNot { it.id in collected }
                if (fresh.isEmpty()) break
                fresh.forEach { collected[it.id] = it }
                val pages = ((root as? JsonObject)?.get("pagination") as? JsonObject)?.text("total_pages")?.toIntOrNull()
                if (pages != null && next >= pages) break
                check(next < 500) { "전체 목록이 너무 큽니다. 검색을 완료할 수 없습니다." }
                next++
            }
            collected.values.toList().also { catalogCache = it }
        }
        return catalog.filter { anime ->
            listOf(anime.title, anime.romaji, anime.english, anime.native, anime.synonyms)
                .any { it.contains(query.trim(), ignoreCase = true) }
        }.filter { matchesFilter(it, filter) }.drop((page - 1) * 24).take(24)
    }

    private val tags = mutableMapOf<String, Map<String, Int>>()
    suspend fun filterTags(taxonomy: String): Map<String, Int> = tags[taxonomy] ?: run {
        val root = request("$apiBase/link/api.php", mapOf("taxonomy" to taxonomy, "limit" to "200", "orderby" to "name", "order" to "ASC")) as? JsonObject
        (root?.get("terms") as? JsonArray).orEmpty().mapNotNull { term ->
            val item = term as? JsonObject ?: return@mapNotNull null
            val id = item.text("tag_ID").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            item.text("name") to id
        }.toMap().also { tags[taxonomy] = it }
    }
    suspend fun filters(): SourceFilters = SourceFilters(
        genres = filterTags("anigenres").keys.toList(),
        years = filterTags("anime-seasonys").keys.toList().sortedDescending(),
        formats = filterTags("anime-seasontype").keys.toList())
    suspend fun filtered(page: Int, filter: BrowseFilter): List<Anime> {
        suspend fun ids(values: List<String>, taxonomy: String): String {
            if (values.isEmpty()) return ""
            val known = filterTags(taxonomy)
            return values.map { value ->
                value.toIntOrNull() ?: known.entries.firstOrNull { it.key.equals(value, true) }?.value
                    ?: error("지원하지 않는 필터입니다: $value")
            }.joinToString(",")
        }
        val genres = ids(filter.genres, "anigenres")
        val years = ids(listOf(filter.year).filter(String::isNotBlank), "anime-seasonys")
        val formats = ids(listOf(filter.format).filter(String::isNotBlank), "anime-seasontype")
        return parseCatalog(request("$apiBase/singlefilter.php", buildMap {
            put("page", page.toString()); put("limit", "24")
            if (genres.isNotBlank()) put("postanigenrestagid", genres)
            if (years.isNotBlank()) put("postyeartagid", years)
            if (formats.isNotBlank()) put("postseasontypetagid", formats)
        }))
    }
    private fun matchesFilter(anime: Anime, filter: BrowseFilter): Boolean =
        (filter.genres.isEmpty() || anime.genres.any { genre -> filter.genres.any { it.equals(genre, true) } }) &&
        (filter.year.isBlank() || anime.year == filter.year) &&
        (filter.format.isBlank() || anime.format.equals(filter.format, true))

    suspend fun detail(id: String): Anime {
        val item = (request("$apiBase/single.php", mapOf("postid" to id)) as? JsonObject)?.get("data")
        return parseAnime(item as? JsonObject) ?: error("작품 정보를 찾을 수 없습니다.")
    }

    suspend fun servers(id: String): List<EpisodeServer> =
        parseServers(request("$episodeBase/api2.php", mapOf("epid" to id)), id)

    fun close() = client.close()
}

data class EpisodeServer(val id: Int, val name: String, val episodes: List<Episode>)

internal fun JsonObject.text(vararg keys: String): String =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

internal fun parseCatalog(root: JsonElement): List<Anime> =
    ((root as? JsonObject)?.get("data") as? JsonArray).orEmpty()
        .mapNotNull { parseAnime(it as? JsonObject) }.distinctBy { it.id }

internal fun parseAnime(item: JsonObject?): Anime? {
    item ?: return null
    val id = item.text("postid")
    if (id.isEmpty()) return null
    val poster = item.text("postthum", "thumb").let { if (it.startsWith("//")) "https:$it" else it }
    return Anime(
        id = id, title = item.text("postname", "name"),
        poster = poster, backdrop = poster,
        description = DisplayText.plain(item.text("postcontent", "description", "synopsis")),
        genres = item.text("postanigenres", "genres").split(',', '|', '/').map(String::trim).filter(String::isNotEmpty),
        year = item.text("postyear"), format = item.text("postseasontype"),
        airedDate = item.text("postdate", "datepub"), studios = item.text("poststudios").split(',', '|', '/').map(String::trim).filter(String::isNotBlank),
        source = "linkkf", romaji = item.text("romaji"), english = item.text("english"), native = item.text("native"),
        synonyms = item.text("anisynonyms"), note = item.text("postnote", "postnoti"),
        seasonTypeTagIds = item.text("postseasontypetagid").split(',').mapNotNull(String::toIntOrNull),
        studioTagIds = item.text("studiostagid").split(',').mapNotNull(String::toIntOrNull),
        sourceTagIds = item.text("anisourceid", "postsourceid").split(',').mapNotNull(String::toIntOrNull),
        yearTagId = item.text("postyeartagid").toIntOrNull(), seriesTagIds = item.text("postanisstagid").split(',').mapNotNull(String::toIntOrNull),
        anilistId = item.text("anilistid", "anilist_id", "postanilistid", "anilistId", "postanilist", "anilist").toIntOrNull(),
        detailUrl = "https://linkkf.app/up/$id/"
    )
}

internal fun parseServers(root: JsonElement, postId: String): List<EpisodeServer> =
    (root as? JsonArray).orEmpty().mapNotNull { element ->
        val server = element as? JsonObject ?: return@mapNotNull null
        val id = server.text("id").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val episodes = (server["server_data"] as? JsonArray).orEmpty().mapNotNull episode@{ value ->
            val item = value as? JsonObject ?: return@episode null
            val display = item.text("name", "slug")
            val number = Regex("^([0-9]+)").find(display)?.value?.toIntOrNull() ?: return@episode null
            val slug = item.text("slug")
            val url = io.ktor.http.URLBuilder("https://linkkf.app/up/$postId/watch/").apply {
                parameters.append("server", id.toString()); parameters.append("slug", slug)
            }.buildString()
            Episode(id = item.text("link").ifBlank { "${postId}v${id}_$slug" },
                number = number, title = "${display}화", displayNumber = display, videoUrl = url)
        }.distinctBy { it.id }.sortedWith(compareBy<Episode> { it.number }.thenBy { it.displayNumber })
        EpisodeServer(id, server.text("server_name").ifBlank { "Server $id" }, episodes)
    }
