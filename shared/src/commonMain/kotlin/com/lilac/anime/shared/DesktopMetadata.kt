package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

data class AnimeCharacter(val name: String, val native: String, val first: String, val last: String, val gender: String)
data class DesktopMetadata(val korean: String, val english: String, val overview: String, val aliases: List<String>, val characters: List<AnimeCharacter>, val anilistId: Int, val malId: Int)
object DesktopTitleRules {
    fun season(title: String): Int = Regex("(\\d+)\\s*기|(?:season|시즌)\\s*(\\d+)|(\\d+)(?:st|nd|rd|th)\\s*season", RegexOption.IGNORE_CASE)
        .find(title)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)?.toIntOrNull() ?: 1
    fun clean(title: String): String = title.replace(Regex("\\((?:애니메이션|TV|애니|\\d{4}년)[^)]*\\)"), "")
        .replace(Regex("\\s+"), " ").trim()
    fun seasonal(title: String, original: String): String {
        val number = season(original)
        return if (number > 1 && season(title) == 1) clean(title) + " " + number + "기" else clean(title)
    }
    fun key(title: String) = title.lowercase().filter { it.isLetterOrDigit() }
}
class DesktopMetadataRepository(private val client: HttpClient = newSharedClient()) {
    suspend fun wikidataIndex(): String {
        val query = "SELECT ?anilist ?ko WHERE {?item wdt:P8729 ?anilist; rdfs:label ?ko. FILTER(lang(?ko)=\"ko\")}"
        val root = Json.parseToJsonElement(client.get("https://query.wikidata.org/sparql") {
            parameter("format", "json"); parameter("query", query); header("Accept", "application/sparql-results+json")
            header("User-Agent", "LilacAnime-iOS (https://github.com/whispelyn-byte/LilacAnime-ios)")
        }.bodyAsText()).jsonObject
        return buildJsonObject { root.obj("results").list("bindings").filterIsInstance<JsonObject>().forEach {
            val id = it.obj("anilist").text("value"); val title = DesktopTitleRules.clean(it.obj("ko").text("value"))
            if (id.toIntOrNull() != null && TitleCandidates.isKorean(title)) put(id, title)
        } }.toString()
    }
    private val prequels = mutableMapOf<Int, List<Int>>()
    suspend fun previousEpisodeOffsets(anilist: Int, title: String): List<Int> {
        if (anilist <= 0 || DesktopTitleRules.season(title) < 2) return emptyList()
        prequels[anilist]?.let { return it }
        var current = anilist
        val visited = mutableSetOf<Int>()
        val counts = mutableListOf<Int>()
        while (visited.add(current) && visited.size <= 8) {
            val query = "query(" + "$" + "id:Int){Media(id:" + "$" + "id,type:ANIME){relations{edges{relationType node{id format episodes}}}}}"
            val root = Json.parseToJsonElement(client.post("https://graphql.anilist.co") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("query", query); put("variables", buildJsonObject { put("id", current) }) }.toString())
            }.bodyAsText()).jsonObject
            val prequel = root.obj("data").obj("Media").obj("relations").list("edges").filterIsInstance<JsonObject>().firstOrNull {
                it.text("relationType") == "PREQUEL" && it.obj("node").text("format") in listOf("TV", "TV_SHORT", "ONA") && (it.obj("node").number("episodes") ?: 0) > 0
            }?.obj("node") ?: break
            counts += prequel.number("episodes") ?: break
            current = prequel.number("id") ?: break
        }
        return listOfNotNull(counts.firstOrNull(), counts.sum().takeIf { it > 0 }).distinct().also { prequels[anilist] = it }
    }
    private val tmdb = TmdbTitleResolver(client)
    private suspend fun media(anime: Anime, includeCast: Boolean): JsonObject {
        val query = "query(" + "$" + "id:Int," + "$" + "search:String){Page(perPage:5){media(id:" + "$" + "id,search:" + "$" + "search,type:ANIME,sort:SEARCH_MATCH){id idMal format synonyms title{native romaji english}" + (if (includeCast) " characters(perPage:25,sort:[ROLE,RELEVANCE]){nodes{name{full native first last} gender}}" else "") + "}}}"
        val variables = buildJsonObject { if (anime.anilistId != null) put("id", anime.anilistId) else put("search", anime.native.ifBlank { anime.romaji.ifBlank { anime.title } }) }
        val root = Json.parseToJsonElement(client.post("https://graphql.anilist.co") {
            contentType(ContentType.Application.Json); setBody(buildJsonObject { put("query", query); put("variables", variables) }.toString())
        }.bodyAsText()).jsonObject
        val rows = root.obj("data").obj("Page").list("media").filterIsInstance<JsonObject>()
        val keys = listOf(anime.title, anime.english, anime.native, anime.romaji).filter(String::isNotBlank).map(DesktopTitleRules::key)
        return rows.firstOrNull { row -> row.obj("title").values.any { it is JsonPrimitive && DesktopTitleRules.key(it.content) in keys } }
            ?: rows.firstOrNull { it.text("format") == "TV" } ?: rows.firstOrNull() ?: JsonObject(emptyMap())
    }
    suspend fun wikidata(anilist: Int, mal: Int): List<String> {
        val where = buildList { if (anilist > 0) add("{?item wdt:P8729 \"" + anilist + "\"}"); if (mal > 0) add("{?item wdt:P4086 \"" + mal + "\"}") }.joinToString(" UNION ")
        if (where.isBlank()) return emptyList()
        val query = "SELECT ?ko ?seriesKo WHERE { " + where + " OPTIONAL{?item rdfs:label ?ko FILTER(lang(?ko)=\"ko\")} OPTIONAL{?item wdt:P179 ?series. ?series rdfs:label ?seriesKo FILTER(lang(?seriesKo)=\"ko\")} } LIMIT 5"
        val root = Json.parseToJsonElement(client.get("https://query.wikidata.org/sparql") {
            parameter("format", "json"); parameter("query", query); header("Accept", "application/sparql-results+json")
            header("User-Agent", "LilacAnime-iOS (https://github.com/whispelyn-byte/LilacAnime-ios)")
        }.bodyAsText()).jsonObject
        return root.obj("results").list("bindings").filterIsInstance<JsonObject>().flatMap { listOf(it.obj("ko").text("value"), it.obj("seriesKo").text("value")) }
            .map(DesktopTitleRules::clean).filter { TitleCandidates.isKorean(it) }.distinct()
    }
    suspend fun resolve(anime: Anime, credential: String, includeCast: Boolean = true): DesktopMetadata {
        val names = listOf(anime.title, anime.native, anime.romaji, anime.english).filter(String::isNotBlank).distinct()
        val aliases = names.filter { TitleCandidates.isKorean(it) }.toMutableList()
        if (aliases.isEmpty() && credential.isNotBlank()) {
            attempt { tmdb.resolve(names, credential) }?.let { aliases.add(it) }
        }
        val media = if (includeCast || aliases.isEmpty() || anime.english.isBlank()) attempt { media(anime, includeCast) } ?: JsonObject(emptyMap()) else JsonObject(emptyMap())
        if (aliases.isEmpty()) aliases += media.list("synonyms").mapNotNull { (it as? JsonPrimitive)?.content }.filter { TitleCandidates.isKorean(it) }
        val anilist = anime.anilistId ?: media.number("id") ?: 0
        val mal = anime.malId ?: media.number("idMal") ?: 0
        if (aliases.isEmpty()) aliases += attempt { wikidata(anilist, mal) }.orEmpty()
        val cast = media.obj("characters").list("nodes").filterIsInstance<JsonObject>().map {
            val name = it.obj("name"); AnimeCharacter(name.text("full"), name.text("native"), name.text("first"), name.text("last"), it.text("gender"))
        }
        return DesktopMetadata(aliases.firstOrNull()?.let { DesktopTitleRules.seasonal(it, anime.title) }.orEmpty(),
            anime.english.ifBlank { media.obj("title").text("english").ifBlank { anime.romaji.ifBlank { media.obj("title").text("romaji") } } },
            if (credential.isBlank() || !includeCast) "" else attempt { tmdb.overview(names, credential, anime.format) }.orEmpty(),
            (aliases.map { DesktopTitleRules.seasonal(it, anime.title) } + names).distinct(), cast, anilist, mal)
    }
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    fun close() = client.close()
}
