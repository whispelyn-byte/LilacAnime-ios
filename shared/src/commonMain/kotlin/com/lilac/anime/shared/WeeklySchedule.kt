package com.lilac.anime.shared

import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.*
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days

data class WeeklyScheduleEntry(val id: String, val title: String, val originalTitle: String = "", val time: String = "",
    val cancelled: Boolean = false, val genres: List<String> = emptyList(), val startDate: String = "",
    val episode: String = "", val track: String = "", val airingStatus: String = "", val anime: Anime? = null)
data class WeeklyScheduleResult(val items: List<WeeklyScheduleEntry>, val native: Boolean, val timed: Boolean, val note: String)

/** Desktop 11e4f25 provider-schedule: Sunday=0, dates explicitly supplied in Korea's Monday–Sunday week. */
internal class WeeklySchedule(private val repository: SourceRepository, private val desktop: DesktopSourceRepository) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Pair<Long, WeeklyScheduleResult>>()
    private val documents = mutableMapOf<String, Pair<Long, String>>()
    private suspend fun document(key: String, load: suspend () -> String): String {
        val now = Clock.System.now().toEpochMilliseconds()
        documents[key]?.takeIf { now - it.first < 300000 }?.let { return it.second }
        return load().also { documents[key] = now to it }
    }
    suspend fun load(source: String, day: Int, date: String, monday: String): WeeklyScheduleResult = mutex.withLock {
        require(day in 0..6 && source in AnimeSource.entries.map { it.key })
        Instant.parse(date + "T00:00:00Z"); Instant.parse(monday + "T00:00:00Z")
        val key = "$source:$date"; val now = Clock.System.now().toEpochMilliseconds()
        cache.entries.removeAll { now - it.value.first >= 300000 }
        documents.entries.removeAll { now - it.value.first >= 300000 }
        cache[key]?.let { return@withLock it.second }
        try {
            val items = native(source, day, date, monday).sortedWith(compareBy<WeeklyScheduleEntry> { it.time.ifEmpty { "99:99" } }.thenBy { it.title })
            val timed = source in listOf("reanime", "miruro")
            val label = mapOf("reanime" to "RE:Anime", "miruro" to "Miruro", "animenosub" to "Animenosub", "ohli24" to "애니24", "linkani" to "링크애니", "linkkf" to "Linkkf")[source]
            WeeklyScheduleResult(items, true, timed, "$label · " + if (timed) "한국 시간" else if (source == "animenosub") "예상 업데이트" else if (source == "linkani") "요일별 분류" else "방영 목록")
                .also { cache[key] = now to it }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            // Do not cache a provider failure: retry must attempt the source again.
            documents.keys.removeAll { it.startsWith(source + ":") }
            val raw = Json.parseToJsonElement(document("anissia:$day") { repository.getText("https://api.anissia.net/anime/schedule/$day") }).jsonObject
            check(raw.text("code").let { it.isEmpty() || it == "ok" })
            val rows = raw["data"] as? JsonArray ?: error("방영표 응답이 올바르지 않습니다.")
            val items = rows.filterIsInstance<JsonObject>().filter { it.text("subject").isNotEmpty() && it.text("animeNo").isNotEmpty() }
                .associateBy { it.text("animeNo") }.values.map {
                    WeeklyScheduleEntry(it.text("animeNo"), it.text("subject"), it.text("originalSubject"), it.text("time").takeIf { value -> Regex("^[0-9]{2}:[0-9]{2}$").matches(value) }.orEmpty(),
                        it.text("status") == "OFF", it.text("genres").split(',').map(String::trim).filter(String::isNotEmpty), it.text("startDate"))
                }.sortedWith(compareBy<WeeklyScheduleEntry> { it.time.ifEmpty { "99:99" } }.thenBy { it.title })
            WeeklyScheduleResult(items, false, true, "Anissia · 한국 시간")
        }
    }
    private fun entry(anime: Anime) = WeeklyScheduleEntry(anime.id, anime.title, anime.native, genres = anime.genres, anime = anime)
    private suspend fun pages(route: String, params: Map<String, String>): JsonArray {
        val result = mutableListOf<JsonElement>(); val cursors = mutableSetOf<String>(); var cursor = ""
        repeat(30) {
            val root = desktop.miruro(route, params + if (cursor.isEmpty()) emptyMap() else mapOf("cursor" to cursor))
            result.addAll(root["data"] as? JsonArray ?: error("Miruro 방영표 응답 오류"))
            if (root.text("has_more") != "true") return JsonArray(result)
            cursor = root.text("next_cursor"); check(cursor.isNotEmpty() && cursors.add(cursor))
        }
        error("Miruro 방영표 페이지 한도 초과")
    }
    private suspend fun native(source: String, day: Int, date: String, monday: String): List<WeeklyScheduleEntry> {
        if (source == "reanime" || source == "miruro") {
            val rows: List<JsonObject>
            val anime: Map<String, Anime>
            if (source == "reanime") {
                val root = Json.parseToJsonElement(document("reanime:week:$monday") { repository.getText("https://reanime.to/api/v1/schedule", mapOf("tz" to "Asia/Seoul", "week" to "0")) }).jsonObject
                val groups = root["schedule"] as? JsonArray ?: error("RE:Anime 방영표 응답 오류")
                val group = groups.filterIsInstance<JsonObject>().firstOrNull { it.text("date") == date } ?: error("RE:Anime 날짜 누락")
                rows = (group["episodes"] as? JsonArray ?: error("RE:Anime 회차 누락")).filterIsInstance<JsonObject>()
                anime = ReAnimeHarParser.parseSearch(JsonObject(mapOf("results" to JsonArray(rows))).toString()).associateBy { it.id.removePrefix("reanime:") }
            } else {
                val start = Instant.parse(monday + "T00:00:00+09:00")
                rows = Json.parseToJsonElement(document("miruro:week:$monday") { pages("schedule", mapOf("from" to start.toString().take(10), "to" to (start + 7.days).toString().take(10), "track" to "raw,sub,dub", "limit" to "10000")).toString() }).jsonArray.filterIsInstance<JsonObject>()
                    .filter { korea(it.text("air_at"))?.take(10) == date }
                anime = rows.map { it.text("anime_id") }.filter(String::isNotEmpty).distinct().chunked(200).flatMap {
                    pages("anime", mapOf("id_in" to it.joinToString(","), "limit" to "200")).filterIsInstance<JsonObject>().map(DesktopSourceParser::miruroAnime)
                }.associateBy { it.id }
            }
            val unique = linkedMapOf<String, JsonObject>()
            for (row in rows) {
                val time = korea(row.text(if (source == "reanime") "episode_date" else "air_at")) ?: continue
                val id = row.text("anime_id"); if (id.isEmpty()) continue
                val track = row.text(if (source == "reanime") "air_type" else "track")
                val key = id + ":" + row.text("episode_number") + (if (source == "miruro") ":$time" else "") + if (track == "dub") ":dub" else ":sub"
                if (!unique.containsKey(key) || track == "sub") unique[key] = row
            }
            return unique.mapNotNull { (key, row) ->
                val item = anime[row.text("anime_id")] ?: return@mapNotNull null
                entry(item).copy(id = key, time = korea(row.text(if (source == "reanime") "episode_date" else "air_at"))!!.substring(11,16),
                    episode = row.text("episode_number"), track = row.text(if (source == "reanime") "air_type" else "track"), airingStatus = row.text("airing_status"))
            }
        }
        if (source == "animenosub") {
            val doc = Ksoup.parse(document("animenosub:$monday") { repository.getText("https://animenosub.to/schedule/") }, "https://animenosub.to/")
            check(doc.select(".schedulepage").isNotEmpty())
            val weekday = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")[day]
            return doc.select(".schedulepage.sch_$weekday .bs").flatMap { AnimenosubParser.parseAnimeList(Ksoup.parse(it.outerHtml(), "https://animenosub.to/")) }.map(::entry)
        }
        if (source == "ohli24") {
            val weekday = "일월화수목금토"[day] + "요일"
            val root = DesktopSourceParser.ohliProps(repository.getText(DesktopSourceParser.ohliPath("방영중"), mapOf("weekday" to weekday)))
            check(root.text("sca") == weekday && root["items"] is JsonArray)
            return root.list("items").filterIsInstance<JsonObject>().map { entry(DesktopSourceParser.ohliAnime(it)) }
        }
        if (source == "linkani") {
            val base = "https://linkani.tv/list/2/class/" + "일월화수목금토"[day].toString().encodeURLPathPart() + "/"
            val items = mutableListOf<WeeklyScheduleEntry>()
            for (page in 1..30) {
                val url = base + if (page == 1) "" else "page/$page/"
                val html = repository.getText(url); val doc = Ksoup.parse(html, url)
                check(doc.select(".vod-list, .vod-item, .page-container").isNotEmpty())
                items.addAll(DesktopSourceParser.koreanList(html, source).map(::entry))
                if (doc.select("a[href]").none { it.absUrl("href") == base + "page/${page + 1}/" }) return items.distinctBy { it.id }
            }
            error("링크애니 방영표 페이지 한도 초과")
        }
        val raw = Json.parseToJsonElement(repository.getText("https://linkkf1.5imgdarr.top/api/singlefilter.php", mapOf("categorytagid" to (21189 + (day + 6) % 7).toString(), "limit" to "200"))).jsonObject
        check(raw["data"] is JsonArray)
        return parseCatalog(raw).map(::entry)
    }
    companion object {
        internal fun korea(value: String): String? = runCatching { (Instant.parse(value) + 9.hours).toString() }.getOrNull()
    }
}
