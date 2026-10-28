package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

/**
 * 회차 썸네일 for the episode lists (the player's 회차 panel, 이어서 보기's sheet) where the source has none (Animenosub,
 * Linkkf, 애니24, 링크애니, some Re:Anime episodes), as desktop 0.5.55 main.cjs episodeThumbnails: AniList's streaming
 * episodes ("Episode 3 - …", most recent series) and Kitsu's episode pictures (older ones too), found through Kitsu's
 * mapping of the work's MAL or AniList id. AniList is asked by the id it knows, else by the English or Japanese title.
 * Episode number -> picture; Kitsu up to 600 episodes (20 a page, five pages at a time).
 */
class EpisodeThumbnailRepository(private val client: HttpClient = newSharedClient()) {
    suspend fun thumbnails(anime: Anime): Map<Int, String> {
        val search = listOf(anime.english, anime.romaji, anime.native, anime.title).map(String::trim).firstOrNull { it.isNotEmpty() && !TitleCandidates.isKorean(it) }.orEmpty()
        val anilistId = anime.anilistId?.takeIf { it > 0 }
        val malId = anime.malId?.takeIf { it > 0 }
        val media = if (anilistId != null || malId != null || search.isNotEmpty()) attempt { anilist(anilistId, malId, search) } ?: JsonObject(emptyMap()) else JsonObject(emptyMap())
        val thumbs = (attempt { kitsu(malId ?: media.number("idMal"), anilistId ?: media.number("id")) } ?: emptyMap()).toMutableMap()
        for (item in media.list("streamingEpisodes").filterIsInstance<JsonObject>()) {
            val number = streamingNumber.find(item.text("title"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val url = item.text("thumbnail")
            if (number > 0 && url.startsWith("https:")) thumbs[number] = url
        }
        return thumbs
    }
    private suspend fun anilist(id: Int?, mal: Int?, search: String): JsonObject {
        val query = "query(" + "$" + "id:Int," + "$" + "mal:Int," + "$" + "search:String){Media(id:" + "$" + "id,idMal:" + "$" + "mal,search:" + "$" + "search,type:ANIME){id idMal streamingEpisodes{title thumbnail}}}"
        val variables = buildJsonObject { if (id != null) put("id", id) else if (mal != null) put("mal", mal) else put("search", search) }
        val root = Json.parseToJsonElement(client.post("https://graphql.anilist.co") {
            contentType(ContentType.Application.Json); setBody(buildJsonObject { put("query", query); put("variables", variables) }.toString())
        }.bodyAsText()).jsonObject
        return root.obj("data").obj("Media")
    }
    private suspend fun kitsuJson(url: String): JsonObject {
        val response = client.get(url) { header("Accept", "application/vnd.api+json"); header("User-Agent", "LilacAnime-iOS") }
        check(response.status.isSuccess()) { "Kitsu HTTP ${response.status.value}" }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }
    private suspend fun kitsu(malId: Int?, anilistId: Int?): Map<Int, String> = coroutineScope {
        var kitsuId: String? = null
        for ((site, id) in listOf("myanimelist/anime" to malId, "anilist/anime" to anilistId)) {
            if (id == null || id <= 0 || kitsuId != null) continue
            val root = attempt { kitsuJson("https://kitsu.io/api/edge/mappings?filter[externalSite]=$site&filter[externalId]=$id&include=item") }
            kitsuId = root?.list("included")?.filterIsInstance<JsonObject>()?.firstOrNull { it.text("type") == "anime" }?.text("id")?.ifBlank { null }
        }
        val anime = kitsuId ?: return@coroutineScope emptyMap()
        suspend fun page(offset: Int) = kitsuJson("https://kitsu.io/api/edge/anime/$anime/episodes?page[limit]=20&page[offset]=$offset&sort=number&fields[episodes]=number,thumbnail")
        val first = page(0)
        val count = minOf(600, first.obj("meta").number("count") ?: 0)
        val pages = mutableListOf(first)
        var offset = 20
        while (offset < count) {
            val batch = minOf(5, (count - offset + 19) / 20)
            val start = offset
            pages += (0 until batch).map { index -> async { attempt { page(start + index * 20) } ?: JsonObject(emptyMap()) } }.awaitAll()
            offset += 100
        }
        buildMap {
            for (item in pages.flatMap { it.list("data").filterIsInstance<JsonObject>() }) {
                val attributes = item.obj("attributes")
                val number = attributes.text("number").toDoubleOrNull()?.toInt() ?: continue
                val thumbnail = attributes.obj("thumbnail")
                val url = thumbnail.text("original").ifBlank { thumbnail.text("large") }
                if (number > 0 && url.startsWith("https:")) put(number, url)
            }
        }
    }
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    fun close() = client.close()
    private companion object { val streamingNumber = Regex("^Episode\\s+(\\d+)", RegexOption.IGNORE_CASE) }
}
