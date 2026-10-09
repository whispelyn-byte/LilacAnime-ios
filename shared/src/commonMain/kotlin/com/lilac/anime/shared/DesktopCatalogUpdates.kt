package com.lilac.anime.shared

import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Desktop catalog-updates.cjs: Svelte reference tables and schedule eligibility. */
object DesktopCatalogUpdates {
    val supported = listOf("reanime", "miruro", "animenosub", "ohli24", "linkani")
    fun decodeData(table: JsonArray): JsonElement {
        val active = mutableSetOf<Int>()
        fun resolve(index: Int, depth: Int): JsonElement {
            if (index !in table.indices) return JsonNull
            require(depth < 100 && active.add(index)) { "Invalid Svelte reference table" }
            val value = table[index]
            val result = when (value) {
                is JsonObject -> JsonObject(value.filterKeys { it !in listOf("__proto__", "constructor", "prototype") }.mapValues { (_, ref) -> resolve((ref as? JsonPrimitive)?.intOrNull ?: -1, depth + 1) })
                is JsonArray -> if (value.firstOrNull() == JsonPrimitive("Date")) value.getOrNull(1) ?: JsonNull else JsonArray(value.map { resolve((it as? JsonPrimitive)?.intOrNull ?: -1, depth + 1) })
                else -> value
            }
            active.remove(index)
            return result
        }
        return resolve(0, 0)
    }
    fun reanimeRows(root: JsonObject): JsonArray {
        val table = root.list("nodes").filterIsInstance<JsonObject>().mapNotNull { it["data"] as? JsonArray }
            .firstOrNull { (it.firstOrNull() as? JsonObject)?.containsKey("latestAired") == true }
        requireNotNull(table) { "RE:Anime의 회차 업데이트 목록을 불러오지 못했습니다." }
        return (decodeData(table) as? JsonObject)?.get("latestAired") as? JsonArray ?: error("RE:Anime의 회차 업데이트 목록이 없습니다.")
    }
    fun latestMiruro(rows: JsonArray, now: String): Map<String, String> {
        val deadline = Instant.parse(now).toEpochMilliseconds() - 75 * 60 * 1000
        val result = linkedMapOf<String, String>()
        for (row in rows.filterIsInstance<JsonObject>()) {
            val aired = row.text("air_at"); val time = runCatching { Instant.parse(aired).toEpochMilliseconds() }.getOrNull() ?: continue
            if (time > deadline) continue
            val id = row.text("anime_id"); if (id.isBlank()) continue
            if (result[id] == null || time > Instant.parse(result.getValue(id)).toEpochMilliseconds()) result[id] = aired
        }
        return result
    }
    fun filter(items: List<Anime>, value: BrowseFilter): List<Anime> = items.filter {
        (value.genres.isEmpty() || value.genres.any(it.genres::contains)) &&
        (value.format.isBlank() || it.format.equals(value.format, true)) &&
        (value.year.isBlank() || it.year == value.year) && (value.season.isBlank() || it.season.equals(value.season, true))
    }
}
