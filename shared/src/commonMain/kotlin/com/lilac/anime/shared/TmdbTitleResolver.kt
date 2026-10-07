package com.lilac.anime.shared

import com.lilac.anime.shared.compat.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText

/** Credentials are used only on TMDB's fixed HTTPS API, never written to disk. */
class TmdbTitleResolver(private val client: HttpClient = HttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 12_000; connectTimeoutMillis = 8_000 }
}) {
    private suspend fun request(path: String, credential: String, query: String? = null): JSONObject {
        require(credential.isNotBlank()) { "TMDB 키를 설정하세요." }
        val response = try { client.get("https://api.themoviedb.org/3/" + path) {
            timeout { requestTimeoutMillis = 12_000; connectTimeoutMillis = 8_000 }
            header("Accept", "application/json")
            if (credential.startsWith("Bearer ", true) || credential.contains('.')) {
                header("Authorization", if (credential.startsWith("Bearer ", true)) credential else "Bearer " + credential)
            } else parameter("api_key", credential)
            if (query != null) {
                parameter("query", query); parameter("language", "ko-KR")
                parameter("include_adult", "false"); parameter("page", 1)
            }
        } } catch (error: CancellationException) { throw error }
        catch (error: ResponseException) { throw IllegalStateException("TMDB HTTP " + error.response.status.value) }
        catch (_: Exception) { throw IllegalStateException("TMDB에 연결할 수 없습니다.") }
        require(response.status.value in 200..299) { "TMDB HTTP " + response.status.value }
        return JSONObject(response.bodyAsText())
    }
    suspend fun test(credential: String): String {
        request("configuration", credential.trim())
        return "TMDB 연결 성공"
    }
    suspend fun resolve(titles: List<String>, credential: String): String? {
        if (credential.isBlank()) return null
        for (query in titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6)) {
            val results = request("search/multi", credential.trim(), query).optJSONArray("results") ?: continue
            val best = (0 until results.length()).mapNotNull { index ->
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                val type = item.optString("media_type")
                if (type !in listOf("tv", "movie")) return@mapNotNull null
                val title = item.optString("name").ifBlank { item.optString("title") }.trim()
                if (!TitleCandidates.isKorean(title)) return@mapNotNull null
                val original = item.optString("original_name").ifBlank { item.optString("original_title") }
                val similarity = maxOf(similarity(query, original), similarity(query, title))
                // Popular but unrelated results must not turn into a subtitle search title.
                if (similarity < 0.2) return@mapNotNull null
                val genres = item.optJSONArray("genre_ids")
                val animation = genres != null && (0 until genres.length()).any { genres.optInt(it) == 16 }
                val score = similarity + (if (animation) 0.2 else 0.0) +
                    (if (item.optString("original_language") == "ja") 0.1 else 0.0)
                title to score
            }.maxByOrNull { it.second }
            if (best != null) return best.first
        }
        return null
    }
    private fun similarity(a: String, b: String): Double {
        fun normalize(value: String) = value.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("").split(' ').filter { it.isNotEmpty() }
        val x = normalize(a); val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val sx = x.toSet(); val sy = y.toSet()
        return sx.intersect(sy).size.toDouble() / sx.union(sy).size
    }
    fun close() { client.close() }
}
