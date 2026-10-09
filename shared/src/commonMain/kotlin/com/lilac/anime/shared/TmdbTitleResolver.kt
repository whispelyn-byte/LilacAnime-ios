package com.lilac.anime.shared

import com.lilac.anime.shared.compat.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Credentials are used only on TMDB's fixed HTTPS API, never written to disk. */
class TmdbTitleResolver(private val client: HttpClient = HttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 20_000 }
}, private val gate: TmdbRequestGate = TmdbRequestGate.shared) {
    private suspend fun request(path: String, credential: String, query: String? = null, language: String = "ko-KR"): JSONObject {
        if (credential.isBlank()) throw TmdbFailure("auth")
        for (attempt in 0..2) {
        gate.takeTurn()
        val failure = try {
        val response = try { client.get("https://api.themoviedb.org/3/" + path) {
            timeout { requestTimeoutMillis = 20_000; connectTimeoutMillis = 20_000 }
            header("Accept", "application/json")
            parameter("language", language)
            if (credential.startsWith("Bearer ", true) || credential.contains('.')) {
                header("Authorization", if (credential.startsWith("Bearer ", true)) credential else "Bearer " + credential)
            } else parameter("api_key", credential)
            if (query != null) {
                parameter("query", query)
                parameter("include_adult", "false"); parameter("page", 1)
            }
        } } catch (error: ResponseException) { error.response }
        if (response.status.value in 200..299) {
            try { return JSONObject(response.bodyAsText()) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { TmdbFailure("response") }
        } else {
            val status = response.status.value
            TmdbFailure(if (status == 401 || status == 403) "auth" else if (status == 429) "rate-limit" else "http", status, gate.retryAfter(response.headers["Retry-After"]))
        }
        } catch (error: CancellationException) { throw error }
        catch (_: HttpRequestTimeoutException) { TmdbFailure("timeout") }
        catch (_: SocketTimeoutException) { TmdbFailure("timeout") }
        catch (_: Exception) { TmdbFailure("network") }
        if (!gate.retry(failure, attempt)) throw failure
        }
        error("Unreachable TMDB retry state")
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
    private fun rows(root: JSONObject, name: String): List<JSONObject> = root.optJSONArray(name)?.let { list -> (0 until list.length()).mapNotNull(list::optJSONObject) }.orEmpty()
    private suspend fun desktopPick(query: String, kind: String, credential: String): JSONObject? = coroutineScope {
        val en = async { request("search/$kind", credential, query, "en-US") }
        val ko = async { request("search/$kind", credential, query) }
        val animation = rows(en.await(), "results").filter { item -> item.optJSONArray("genre_ids")?.let { (0 until it.length()).any { index -> it.optInt(index) == 16 } } == true }
        val pick = animation.firstOrNull { DesktopTmdbRules.key(it.optString("name").ifBlank { it.optString("title") }) == DesktopTmdbRules.key(query) }
            ?: animation.sortedByDescending { item -> item.optString("original_language") == "ja" || item.optJSONArray("origin_country")?.let { (0 until it.length()).any { index -> it.optString(index) == "JP" } } == true }.firstOrNull()
        val korean = rows(ko.await(), "results")
        pick?.let { chosen -> korean.firstOrNull { it.optInt("id") == chosen.optInt("id") } ?: chosen }
    }
    private fun namedSeason(root: JSONObject, original: String): JSONObject? = rows(root, "seasons").filter {
        val name = it.optString("name")
        !Regex("^(?:season\\s*\\d+|specials)$", RegexOption.IGNORE_CASE).matches(name) &&
            DesktopTmdbRules.key(name).length >= 6 && DesktopTmdbRules.key(original).contains(DesktopTmdbRules.key(name))
    }.maxByOrNull { DesktopTmdbRules.key(it.optString("name")).length }
    private suspend fun desktopSeasonTitle(id: Int, original: String, credential: String): String = coroutineScope {
        val en = async { request("tv/$id", credential, language = "en-US") }
        val ko = async { request("tv/$id", credential) }
        val season = namedSeason(en.await(), original)
        val korean = ko.await()
        val name = rows(korean, "seasons").firstOrNull { season != null && it.optInt("season_number") == season.optInt("season_number") }
            ?.optString("name").orEmpty().replace(Regex("^시즌\\s*\\d+\\s*[:：]?\\s*"), "").trim()
        if (!Regex("[가-힣]{2}").containsMatchIn(name)) return@coroutineScope ""
        val series = korean.optString("name").trim()
        val first = DesktopTitleRules.simple(series).split(' ').firstOrNull().orEmpty()
        if (first.isNotBlank() && DesktopTitleRules.simple(name).contains(first)) name
        else series.replace(Regex("\\s*[~〜～][^~〜～]*[~〜～]\\s*"), " ").trim() + " " + name
    }
    suspend fun desktopTitles(titles: List<String>, credential: String, light: Boolean): List<String> {
        if (credential.isBlank()) return emptyList()
        val found = mutableListOf<String>()
        fun add(value: String) {
            val name = value.trim()
            if (Regex("[가-힣]{2}").containsMatchIn(name) && name !in found && !DesktopTmdbRules.sibling(found.firstOrNull().orEmpty(), name)) found += name
        }
        for (query in DesktopTmdbRules.queries(titles)) for (kind in listOf("tv", "movie")) {
            val pick = desktopPick(query, kind, credential) ?: continue
            if (kind == "tv") attempt { desktopSeasonTitle(pick.optInt("id"), titles.firstOrNull().orEmpty(), credential) }?.let(::add)
            add(pick.optString("name").ifBlank { pick.optString("title") })
            if (!light) attempt { request("$kind/${pick.optInt("id")}/alternative_titles", credential) }?.let { root ->
                (rows(root, "results") + rows(root, "titles")).filter { it.optString("iso_3166_1") == "KR" }.forEach { add(it.optString("title")) }
            }
            if (found.isNotEmpty()) return found
        }
        return found
    }
    suspend fun desktopOverview(titles: List<String>, credential: String, format: String): String {
        if (credential.isBlank()) return ""
        val kinds = if (Regex("movie|film|극장", RegexOption.IGNORE_CASE).containsMatchIn(format)) listOf("movie", "tv") else listOf("tv", "movie")
        for (query in DesktopTmdbRules.queries(titles)) for (kind in kinds) {
            val pick = attempt { desktopPick(query, kind, credential) } ?: continue
            val id = pick.optInt("id")
            val ko = request("$kind/$id", credential)
            var text = ko.optString("overview").trim()
            if (kind == "tv") {
                val named = attempt { request("tv/$id", credential, language = "en-US") }?.let { namedSeason(it, titles.firstOrNull().orEmpty()) }
                val number = named?.optInt("season_number")?.takeIf { it > 0 } ?: DesktopTitleRules.season(titles.firstOrNull().orEmpty())
                if (number > 1) attempt { request("tv/$id/season/$number", credential).optString("overview") }?.takeIf(TitleCandidates::isKorean)?.let { text = it.trim() }
            }
            if (TitleCandidates.isKorean(text)) return text
        }
        return ""
    }
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
    private fun similarity(a: String, b: String): Double {
        fun normalize(value: String) = value.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("").split(' ').filter { it.isNotEmpty() }
        val x = normalize(a); val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val sx = x.toSet(); val sy = y.toSet()
        return sx.intersect(sy).size.toDouble() / sx.union(sy).size
    }
    suspend fun overview(titles: List<String>, credential: String, format: String): String {
        if (credential.isBlank()) return ""
        for (query in titles.filter(String::isNotBlank).distinct().take(6)) {
            val rows = request("search/multi", credential, query).optJSONArray("results") ?: continue
            val picks = (0 until rows.length()).mapNotNull(rows::optJSONObject).filter { row ->
                row.optString("media_type") in listOf("tv", "movie") &&
                    row.optJSONArray("genre_ids")?.let { genres -> (0 until genres.length()).any { genres.optInt(it) == 16 } } == true &&
                    similarity(query, row.optString("original_name").ifBlank { row.optString("original_title") }) >= 0.2
            }.sortedByDescending { row ->
                similarity(query, row.optString("original_name").ifBlank { row.optString("original_title") }) +
                    if (row.optString("media_type") == if (format == "MOVIE") "movie" else "tv") 0.2 else 0.0
            }
            for (pick in picks.take(2)) {
                val type = pick.optString("media_type"); val id = pick.optInt("id")
                val detail = request(type + "/" + id, credential)
                var text = detail.optString("overview")
                val season = DesktopTitleRules.season(titles.firstOrNull().orEmpty())
                if (type == "tv" && season > 1) {
                    val later = try { request("tv/" + id + "/season/" + season, credential).optString("overview") }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
                    if (TitleCandidates.isKorean(later)) text = later
                }
                if (TitleCandidates.isKorean(text)) return text
            }
        }
        return ""
    }
    suspend fun variants(query: String, credential: String): List<String> {
        if (credential.isBlank()) return emptyList()
        val rows = request("search/multi", credential, query).optJSONArray("results") ?: return emptyList()
        return (0 until rows.length()).mapNotNull(rows::optJSONObject).filter { row ->
            val genres = row.optJSONArray("genre_ids")
            genres != null && (0 until genres.length()).any { genres.optInt(it) == 16 } &&
                similarity(query, row.optString("name").ifBlank { row.optString("title") }) >= 0.2
        }.take(4).flatMap { row ->
            listOf(row.optString("original_name"), row.optString("original_title"), row.optString("name"), row.optString("title"))
        }.filter(String::isNotBlank).distinct()
    }
    fun close() { client.close() }
}
