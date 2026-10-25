package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.*

/** Anissia's Japanese/Korean title pairs, including season and remake dates. */
internal object AnissiaCatalogRules {
    private val season = Regex("第\\s*([0-9一二三四五六七八九]+)\\s*(?:期|シーズン|クール)|([0-9]+)\\s*期|([0-9]+)(?:st|nd|rd|th)\\s*season|season\\s*([0-9]+)", RegexOption.IGNORE_CASE)
    fun japaneseSeason(text: String): Int? {
        val value = normalizeDesktopTitle(text)
        season.find(value)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let {
            return it.toIntOrNull() ?: "一二三四五六七八九".indexOf(it).takeIf { n -> n >= 0 }?.plus(1)
        }
        return Regex("(?:^|\\s)(II|III|IV|V)\\s*$").find(value)?.groupValues?.get(1)?.let { mapOf("II" to 2, "III" to 3, "IV" to 4, "V" to 5)[it] }
    }
    fun key(text: String) = season.replace(normalizeDesktopTitle(text).lowercase(), "")
        .replace(Regex("(?:^|\\s)(?:ii|iii|iv|v)\\s*$"), "").filter { it.isLetterOrDigit() }
    private fun day(text: String): Long? = runCatching { Instant.parse(text.take(10) + "T00:00:00Z").toEpochMilliseconds() / 86_400_000 }.getOrNull()
    fun match(items: List<JsonObject>, native: String, title: String, start: String, format: String): String {
        val wanted = key(native); if (wanted.length < 2) return ""
        val movie = format.equals("MOVIE", true) || "劇場版" in native
        val number = japaneseSeason(native) ?: DesktopCommunity.season(title) ?: DesktopTitleRules.season(title)
        val date = day(start)
        fun away(item: JsonObject) = day(item.text("startDate"))?.let { other -> date?.let { kotlin.math.abs(other - it) } } ?: Long.MAX_VALUE
        val pool = items.filter {
            val other = key(it.text("originalSubject"))
            other.isNotEmpty() && (other == wanted || minOf(other.length, wanted.length) >= 5 && (other.startsWith(wanted) || wanted.startsWith(other))) &&
                (Regex("극장판|劇場版").containsMatchIn(it.text("subject") + it.text("originalSubject")) == movie)
        }
        fun nearest(rows: List<JsonObject>) = rows.sortedWith(compareBy<JsonObject> { away(it) }.thenByDescending { it.text("startDate") }).firstOrNull()?.text("subject").orEmpty()
        val same = pool.filter { (DesktopCommunity.season(it.text("subject")) ?: japaneseSeason(it.text("originalSubject")) ?: DesktopCommunity.bareDigitSeason(it.text("subject")) ?: 1) == number }
        if (same.isNotEmpty()) return nearest(same)
        val close = pool.filter { away(it) < 35 }
        return if (close.isNotEmpty()) nearest(close) else if (number == 1) nearest(pool) else ""
    }
}

internal class AnissiaTitleCatalog(private val client: HttpClient) {
    private val lock = Mutex()
    private var items = emptyList<JsonObject>()
    private var updated = 0L
    suspend fun title(native: String, title: String, start: String, format: String): String {
        if (AnissiaCatalogRules.key(native).length < 2) return ""
        val rows = lock.withLock {
            if (items.isEmpty()) runCatching {
                val saved = Json.parseToJsonElement(readCommunityCache("anissia-titles") ?: "{}").jsonObject
                items = saved.list("items").filterIsInstance<JsonObject>()
                updated = saved["updated"]?.jsonPrimitive?.longOrNull ?: 0
            }
            if (Clock.System.now().toEpochMilliseconds() - updated >= 86_400_000 || items.isEmpty()) {
                try {
                    suspend fun page(n: Int): JsonObject {
                        val root = Json.parseToJsonElement(client.get("https://api.anissia.net/anime/list/$n").bodyAsText()).jsonObject
                        check(root.text("code").let { it.isEmpty() || it == "ok" }) { "Anissia 목록 요청 실패" }
                        return root.obj("data")
                    }
                    val first = page(0)
                    val collected = first.list("content").filterIsInstance<JsonObject>().toMutableList()
                    for (batch in (1 until (first.number("totalPages") ?: 1).coerceAtMost(500)).chunked(6)) {
                        collected += coroutineScope { batch.map { n -> async { page(n).list("content").filterIsInstance<JsonObject>() } }.awaitAll().flatten() }
                    }
                    check(collected.isNotEmpty()) { "Anissia 목록이 비어 있습니다." }
                    items = collected.distinctBy { it.number("animeNo") }; updated = Clock.System.now().toEpochMilliseconds()
                    runCatching { writeCommunityCache("anissia-titles", buildJsonObject { put("updated", updated); put("items", JsonArray(items)) }.toString()) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (items.isEmpty()) throw error }
            }
            items
        }
        return AnissiaCatalogRules.match(rows, native, title, start, format)
    }
}
