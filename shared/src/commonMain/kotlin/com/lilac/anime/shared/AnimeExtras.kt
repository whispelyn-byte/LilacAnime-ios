package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** A character of the work with its Japanese voice (role: MAIN, SUPPORTING, BACKGROUND). */
data class ExtraCharacter(val name: String, val native: String, val image: String, val role: String, val voice: String, val voiceNative: String)
/** Another AniList entry: a related work (relationType: SEQUEL, PREQUEL, …) or one recommended with this one. */
data class ExtraWork(val anilistId: Int, val malId: Int, val title: String, val native: String, val image: String, val year: String, val format: String, val score: Double, val relationType: String)
data class ExtraLink(val site: String, val url: String)
/**
 * 작품 정보 더 보기 (desktop 19110b2 animeExtras): what AniList and MyAnimeList know of a work beyond the source's own page,
 * from one AniList request and one to Jikan (MAL): when the next episode airs ([nextEpisode] at [nextAiringAt], seconds
 * since 1970; 0 when none is announced), its trailer, main characters with their Japanese voices, related works (the
 * sources other than RE:Anime, Miruro and Linkkf list none), works recommended with it, tags without spoilers, official
 * and streaming sites, and the names of its opening and ending songs.
 */
data class AnimeExtras(
    val anilistId: Int, val malId: Int, val url: String, val nextEpisode: Int, val nextAiringAt: Long, val trailer: String,
    val characters: List<ExtraCharacter>, val relations: List<ExtraWork>, val recommendations: List<ExtraWork>,
    val tags: List<String>, val links: List<ExtraLink>, val openings: List<String>, val endings: List<String>
) {
    /** 전편, 후속편 … as desktop's RELATION_NAMES. */
    fun relationName(type: String): String = RELATION_NAMES[type] ?: "관련 작품"
    companion object {
        val RELATION_NAMES = mapOf("PREQUEL" to "전편", "SEQUEL" to "후속편", "PARENT" to "본편", "SIDE_STORY" to "외전", "SPIN_OFF" to "스핀오프",
            "ALTERNATIVE" to "다른 버전", "SUMMARY" to "총집편", "COMPILATION" to "총집편", "CONTAINS" to "포함 작품", "CHARACTER" to "같은 캐릭터", "OTHER" to "기타")
        /** Sources whose AniList id is their own (Re:Anime's and Miruro's catalogs, Linkkf's field), as CrossSiteTracks' TRUSTED_IDS. */
        val TRUSTED = setOf("reanime", "miruro", "linkkf")
    }
}

class AnimeExtrasRepository(private val client: HttpClient = newSharedClient(), private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }) {
    private val kept = mutableMapOf<String, Pair<Long, AnimeExtras?>>()
    /** The work's extras, or null when it has no AniList entry. Kept for half an hour; a lookup that failed (AniList's
     *  limit, no connection) throws and is not kept, so the page asks again next time. */
    suspend fun extras(anime: Anime, source: String = anime.source): AnimeExtras? {
        val key = "$source:${anime.id}:${anime.anilistId ?: 0}:${anime.native}"
        kept[key]?.let { (time, value) -> if (now() - time < 30 * 60_000L) return value }
        val value = load(anime, source)
        // None found by the title lookup, which answers null also when AniList did not answer: not kept, asked again.
        if (value != null || !looked) kept[key] = now() to value
        if (kept.size > 200) kept.remove(kept.keys.first())
        return value
    }
    private var looked = false
    private suspend fun load(anime: Anime, source: String): AnimeExtras? {
        looked = false
        var anilistId = anime.anilistId?.takeIf { it > 0 && source in AnimeExtras.TRUSTED }
        val malId = anime.malId?.takeIf { it > 0 }
        // 애니24's and 링크애니's own id comes from a looser lookup: the entry is found by the original title, strictly.
        if (anilistId == null && malId == null && anime.native.isNotBlank() && !TitleCandidates.isKorean(anime.native)) {
            val year = Regex("(?:19|20)\\d{2}").find(anime.year.ifBlank { anime.airedDate })?.value.orEmpty()
            looked = true
            anilistId = DesktopSourceRepository(client).lookupNativeIDs(anime.native, year)?.first
        }
        if (anilistId == null && malId == null) return null
        val media = anilist(anilistId, malId)
        if (media.isEmpty()) return null
        val mal = media.number("idMal") ?: malId ?: 0
        // The songs are waited for 5 s at most: a slow MAL does not hold back what AniList gave.
        val themes = if (mal > 0) withTimeoutOrNull(5_000) { attempt { malThemes(mal) } } else null
        val airing = media.obj("nextAiringEpisode")
        val trailer = media.obj("trailer").let { if (it.text("site").equals("youtube", true)) it.text("id").trim() else "" }
        fun work(node: JsonObject, relation: String = ""): ExtraWork? {
            val id = node.number("id") ?: return null
            if (node.text("type") != "ANIME") return null
            val title = node.obj("title").let { listOf(it.text("english"), it.text("romaji"), it.text("native")).firstOrNull(String::isNotBlank) } ?: return null
            val image = node.obj("coverImage").text("large").takeIf { it.startsWith("https://") }.orEmpty()
            return ExtraWork(id, node.number("idMal") ?: 0, title.trim(), node.obj("title").text("native"), image, node.number("seasonYear")?.toString().orEmpty(),
                node.text("format"), (node.number("averageScore") ?: 0) / 10.0, relation)
        }
        val self = media.number("id") ?: 0
        return AnimeExtras(
            anilistId = self, malId = mal,
            url = media.text("siteUrl").takeIf { it.startsWith("https://anilist.co/") }.orEmpty(),
            nextEpisode = airing.number("episode") ?: 0, nextAiringAt = airing.text("airingAt").toLongOrNull() ?: 0L,
            trailer = if (Regex("[\\w-]{6,20}").matches(trailer)) "https://www.youtube.com/watch?v=$trailer" else "",
            characters = media.obj("characters").list("edges").filterIsInstance<JsonObject>().mapNotNull { edge ->
                val node = edge.obj("node"); val voice = edge.list("voiceActors").filterIsInstance<JsonObject>().firstOrNull()?.obj("name") ?: JsonObject(emptyMap())
                val name = node.obj("name"); val image = node.obj("image").text("medium").takeIf { it.startsWith("https://") }.orEmpty()
                ExtraCharacter(name.text("full").trim(), name.text("native").trim(), image, edge.text("role"), voice.text("full").trim(), voice.text("native").trim())
                    .takeIf { it.name.isNotEmpty() || it.native.isNotEmpty() }
            },
            relations = media.obj("relations").list("edges").filterIsInstance<JsonObject>().mapNotNull { work(it.obj("node"), it.text("relationType")) }.distinctBy { it.anilistId }.take(24),
            recommendations = media.obj("recommendations").list("nodes").filterIsInstance<JsonObject>().mapNotNull { (it["mediaRecommendation"] as? JsonObject)?.let(::work) }
                .filter { it.anilistId != self }.distinctBy { it.anilistId },
            tags = media.list("tags").filterIsInstance<JsonObject>()
                .filter { (it["isMediaSpoiler"] as? JsonPrimitive)?.booleanOrNull != true && (it["isGeneralSpoiler"] as? JsonPrimitive)?.booleanOrNull != true && (it.number("rank") ?: 0) >= 60 }
                .map { it.text("name") }.filter(String::isNotBlank).take(12),
            links = media.list("externalLinks").filterIsInstance<JsonObject>()
                .filter { it.text("url").startsWith("https://") && it.text("type") in setOf("INFO", "STREAMING") && it.text("site").isNotBlank() }
                .map { ExtraLink(it.text("site"), it.text("url")) }.take(10),
            openings = themes?.first.orEmpty(), endings = themes?.second.orEmpty())
    }
    private suspend fun anilist(id: Int?, mal: Int?): JsonObject {
        val query = "query(\$id:Int,\$mal:Int){Media(id:\$id,idMal:\$mal,type:ANIME){id idMal siteUrl status episodes title{romaji english native} " +
            "nextAiringEpisode{episode airingAt} trailer{id site} tags{name rank isMediaSpoiler isGeneralSpoiler} " +
            "characters(perPage:12,sort:[ROLE,RELEVANCE,ID]){edges{role node{name{full native} image{medium}} voiceActors(language:JAPANESE,sort:[RELEVANCE,ID]){name{full native}}}} " +
            "relations{edges{relationType node{id idMal type format seasonYear averageScore title{romaji english native} coverImage{large}}}} " +
            "recommendations(perPage:12,sort:[RATING_DESC]){nodes{mediaRecommendation{id idMal type format seasonYear averageScore title{romaji english native} coverImage{large}}}} " +
            "externalLinks{site url type language}}}"
        val body = buildJsonObject { put("query", query); put("variables", buildJsonObject { if (id != null) put("id", id) else if (mal != null) put("mal", mal) }) }.toString()
        suspend fun ask() = client.post("https://graphql.anilist.co") { expectSuccess = false; contentType(ContentType.Application.Json); setBody(body) }
        var response = ask()
        // AniList's per-minute limit: waited out once (as searchAnilist), then a failure, never a "not found".
        if (response.status.value == 429) {
            delay(((response.headers[HttpHeaders.RetryAfter]?.toDoubleOrNull()?.takeIf { it > 0 } ?: 5.0).coerceAtMost(15.0) * 1000).toLong())
            response = ask()
        }
        if (response.status.value == 404) return JsonObject(emptyMap())
        check(response.status.isSuccess()) { "AniList HTTP ${response.status.value}" }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.obj("data").obj("Media")
    }
    /** MAL writes a song as '1: "Guren no Yumiya" by Linked Horizon (eps 1-13)': kept as written, without the number. */
    private suspend fun malThemes(mal: Int): Pair<List<String>, List<String>> {
        val response = client.get("https://api.jikan.moe/v4/anime/$mal/themes") { expectSuccess = false; header("User-Agent", "LilacAnime-iOS") }
        check(response.status.isSuccess()) { "Jikan HTTP ${response.status.value}" }
        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject.obj("data")
        fun songs(key: String) = data.list(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.replace(Regex("^\\s*\\d+\\s*:\\s*"), "")?.trim() }.filter(String::isNotEmpty).take(12)
        return songs("openings") to songs("endings")
    }
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    fun close() = client.close()
}
