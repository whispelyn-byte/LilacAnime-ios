package com.lilac.anime.shared
import com.lilac.anime.shared.compat.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

data class SkipSegment(val type: String, val start: Double, val end: Double)
class AniSkip(private val client: HttpClient = newSharedClient()) {
    suspend fun segments(anilistId: Int, malId: Int, episode: Int, duration: Int): List<SkipSegment> {
        val resolved = if (malId > 0) malId else if (anilistId > 0) {
            val body = JSONObject().put("query", "query (\$id:Int) { Media(id:\$id,type:ANIME) { idMal } }")
                .put("variables", JSONObject().put("id", anilistId))
            JSONObject(client.post("https://graphql.anilist.co") { contentType(ContentType.Application.Json); setBody(body.toString()) }.bodyAsText())
                .optJSONObject("data")?.optJSONObject("Media")?.optInt("idMal") ?: 0
        } else 0
        if (resolved <= 0) return emptyList()
        suspend fun request(length: Int): List<SkipSegment> {
            val root = JSONObject(client.get("https://api.aniskip.com/v2/skip-times/$resolved/$episode") {
                listOf("op", "ed", "mixed-op", "mixed-ed", "recap").forEach { parameter("types[]", it) }
                parameter("episodeLength", length)
            }.bodyAsText())
            val values = root.optJSONArray("results") ?: return emptyList()
            return (0 until values.length()).mapNotNull {
                val item = values.optJSONObject(it) ?: return@mapNotNull null
                val interval = item.optJSONObject("interval") ?: item
                val start = interval.optDouble("startTime")
                val end = interval.optDouble("endTime")
                if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) null
                else SkipSegment(item.optString("skipType"), start, end)
            }.sortedBy { it.start }
        }
        return request(duration).ifEmpty { if (duration > 0) request(0) else emptyList() }
    }
    fun close() = client.close()
}
