package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlinx.serialization.json.*

data class AnimeCharacter(val name: String, val native: String, val first: String, val last: String, val gender: String)
data class DesktopMetadata(val korean: String, val english: String, val overview: String, val aliases: List<String>, val characters: List<AnimeCharacter>, val anilistId: Int, val malId: Int, val titleLookupFailure: String = "", val titleFailureCode: String = "", val titleRetryAfterMs: Long = 0)
internal expect fun normalizeDesktopTitle(value: String): String
object DesktopTitleRules {
    fun compareKey(title: String): String = normalizeDesktopTitle(title).lowercase().replace("…", "...")
        .replace(Regex("[\\s:：'’\"“”!！?？.,·・\\-–—~〜()（）]"), "")
    fun explicitSeason(title: String): Int? = Regex("(?:season|시즌)\\s*(\\d+)|(\\d+)\\s*기(?![가-힣])|(\\d+)(?:st|nd|rd|th)(?:\\s*season)?\\b|[가-힣](\\d)(?=\\s|$)", RegexOption.IGNORE_CASE)
        .find(normalizeDesktopTitle(title))?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)?.toIntOrNull()
    fun season(title: String): Int {
        val value = normalizeDesktopTitle(title).trim()
        explicitSeason(value)?.let { return it }
        val digit = Regex("([a-z]+)[!?'’)]*\\s*([2-9])$", RegexOption.IGNORE_CASE).find(value)
        if (digit != null && digit.groupValues[1].lowercase() !in listOf("no", "vol", "part", "cour", "lv", "level", "ep", "episode", "chapter", "chapters", "act", "phase", "movie", "film", "special", "specials", "ova", "oad", "recap", "arc")) return digit.groupValues[2].toInt()
        if ('◎' in value) return 2
        return Regex("\\s(II|III|IV)$").find(value)?.groupValues?.get(1)?.let { mapOf("II" to 2, "III" to 3, "IV" to 4)[it] } ?: 1
    }
    fun clean(title: String): String {
        if (Regex("\\([^()]*(?:게임|드라마|실사|소설|만화|웹툰|영화 시리즈|음반|노래)[^()]*\\)").containsMatchIn(title)) return ""
        return title.replace(Regex("\\s*\\([^()]*(?:애니메이션|애니|TV|\\d{4}년)[^()]*\\)"), "")
            .replace(Regex("\\s*애니메이션(?:\\s*1\\s*기)?\\s*$|\\s+1\\s*기\\s*$|\\s+\\d+\\s*화\\s*$"), "").replace(Regex("\\s+"), " ").trim()
    }
    fun seasonal(title: String, original: String, format: String = ""): String {
        val number = season(original)
        val cleaned = clean(title)
        if (cleaned.isEmpty()) return ""
        if (TitleCandidates.isKorean(original)) return title
        val marked = explicitSeason(cleaned) != null || Regex("[ⅡⅢⅣⅤⅥ]|(?:^|[\\s~:])(?:II|III|IV|V|VI)(?=$|[\\s~:!])|\\d\\s*$").containsMatchIn(cleaned)
        var result = if (number > 1 && !marked) "$cleaned ${number}기" else cleaned
        if (format.uppercase() in listOf("OVA", "OAD", "SPECIAL") && !Regex("OVA|OAD|스페셜|특별", RegexOption.IGNORE_CASE).containsMatchIn(result)) {
            result += if (format.equals("SPECIAL", true)) " 스페셜" else " OVA"
        }
        return result
    }
    fun simple(title: String) = normalizeDesktopTitle(title).lowercase().replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"), " ")
        .replace(Regex("\\b(?:subtitle|sub)\\b|(?:한글|한국어)?\\s*자막", RegexOption.IGNORE_CASE), " ").replace(Regex("[^a-z0-9가-힣]+"), " ").trim()
    fun key(title: String): String {
        val cleaned = simple(title); val hangul = cleaned.filter { it in '가'..'힣' }
        return if (hangul.length >= 2) hangul else cleaned.replace(Regex("\\s+"), "")
    }
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
    private val prequels = linkedMapOf<Int, Pair<Long, List<Int>>>()
    suspend fun previousEpisodeOffsets(anilist: Int, title: String): List<Int> {
        if (anilist <= 0 || DesktopTitleRules.season(title) < 2) return emptyList()
        prequels[anilist]?.takeIf { Clock.System.now().toEpochMilliseconds() - it.first < 600000 }?.let { return it.second }
        var current = anilist
        val visited = mutableSetOf<Int>()
        val counts = mutableListOf<Int>()
        while (visited.add(current) && visited.size <= 8) {
            val query = "query(" + "$" + "id:Int){Media(id:" + "$" + "id,type:ANIME){relations{edges{relationType node{id format episodes}}}}}"
            val root = Json.parseToJsonElement(client.post("https://graphql.anilist.co") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("query", query); put("variables", buildJsonObject { put("id", current) }) }.toString())
            }.bodyAsText()).jsonObject
            check(root.list("errors").isEmpty() && root.obj("data").obj("Media").obj("relations")["edges"] is JsonArray) { "이전 시즌 회차 정보를 읽지 못했습니다." }
            val prequel = root.obj("data").obj("Media").obj("relations").list("edges").filterIsInstance<JsonObject>().firstOrNull {
                it.text("relationType") == "PREQUEL" && it.obj("node").text("format") in listOf("TV", "TV_SHORT", "ONA") && (it.obj("node").number("episodes") ?: 0) > 0
            }?.obj("node") ?: break
            counts += prequel.number("episodes") ?: break
            current = prequel.number("id") ?: break
        }
        return listOfNotNull(counts.firstOrNull(), counts.sum().takeIf { it > 0 }).distinct().also {
            prequels[anilist] = Clock.System.now().toEpochMilliseconds() to it
            if (prequels.size > 128) prequels.remove(prequels.keys.first())
        }
    }
    private val tmdb = TmdbTitleResolver(client)
    private suspend fun media(anime: Anime, includeCast: Boolean): JsonObject {
        val query = "query(" + "$" + "id:Int," + "$" + "search:String){Page(perPage:5){media(id:" + "$" + "id,search:" + "$" + "search,type:ANIME,sort:SEARCH_MATCH){id idMal format synonyms title{native romaji english}" + (if (includeCast) " characters(perPage:25,sort:[ROLE,RELEVANCE]){nodes{name{full native first last} gender}}" else "") + "}}}"
        val variables = buildJsonObject { if (anime.anilistId != null) put("id", anime.anilistId) else put("search", anime.native.ifBlank { anime.romaji.ifBlank { anime.title } }) }
        val root = Json.parseToJsonElement(client.post("https://graphql.anilist.co") {
            contentType(ContentType.Application.Json); setBody(buildJsonObject { put("query", query); put("variables", variables) }.toString())
        }.bodyAsText()).jsonObject
        val rows = root.obj("data").obj("Page").list("media").filterIsInstance<JsonObject>()
        val keys = listOf(anime.title, anime.english, anime.native, anime.romaji).filter(String::isNotBlank).map(DesktopTitleRules::key).filter(String::isNotBlank)
        return rows.firstOrNull { row -> row.obj("title").values.any { it is JsonPrimitive && DesktopTitleRules.key(it.content).let { key -> key.isNotBlank() && key in keys } } }
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
        var titleFailure = ""
        var tmdbFailure: TmdbFailure? = null
        if (aliases.isEmpty() && credential.isNotBlank()) {
            try { aliases += tmdb.desktopTitles(names, credential, light = !includeCast) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { titleFailure = error.message ?: "TMDB 요청 실패"; tmdbFailure = error as? TmdbFailure }
        }
        val media = if (includeCast || aliases.isEmpty() || anime.english.isBlank()) attempt { media(anime, includeCast) } ?: JsonObject(emptyMap()) else JsonObject(emptyMap())
        if (aliases.isEmpty()) aliases += media.list("synonyms").mapNotNull { (it as? JsonPrimitive)?.content }.filter { TitleCandidates.isKorean(it) }
        val anilist = anime.anilistId ?: media.number("id") ?: 0
        val mal = anime.malId ?: media.number("idMal") ?: 0
        if (aliases.isEmpty()) aliases += attempt { wikidata(anilist, mal) }.orEmpty()
        val cast = media.obj("characters").list("nodes").filterIsInstance<JsonObject>().map {
            val name = it.obj("name"); AnimeCharacter(name.text("full"), name.text("native"), name.text("first"), name.text("last"), it.text("gender"))
        }
        return DesktopMetadata(aliases.firstOrNull()?.let { DesktopTitleRules.seasonal(it, anime.title, anime.format) }.orEmpty(),
            anime.english.ifBlank { media.obj("title").text("english").ifBlank { anime.romaji.ifBlank { media.obj("title").text("romaji") } } },
            if (credential.isBlank() || !includeCast) "" else attempt { tmdb.desktopOverview(listOf(anime.english) + names, credential, anime.format) }.orEmpty(),
            (aliases.map { DesktopTitleRules.seasonal(it, anime.title, anime.format) } + names).distinct(), cast, anilist, mal, titleFailure, tmdbFailure?.code.orEmpty(), tmdbFailure?.retryAfterMs ?: 0)
    }
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    fun close() = client.close()
}
