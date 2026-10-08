package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.json.*

object DesktopMetadataFallback {
    fun parse(root: JsonObject): List<Anime> {
        val rows = when (val data = root["data"]) { is JsonArray -> data.filterIsInstance<JsonObject>(); is JsonObject -> listOf(data); else -> emptyList() }
        return rows.mapNotNull { row ->
            val id = row.number("mal_id") ?: return@mapNotNull null
            val poster = row.obj("images").obj("jpg").text("large_image_url")
            Anime(id = id.toString(), source = "jikan", malId = id, title = row.text("title_english").ifBlank { row.text("title") },
                english = row.text("title_english"), native = row.text("title_japanese"), romaji = row.text("title"),
                poster = poster, backdrop = poster, description = Ksoup.parse(row.text("synopsis")).text(),
                year = row.text("year"), format = row.text("type"), score = row.text("score").toDoubleOrNull() ?: 0.0,
                popularity = (100000 - (row.number("popularity") ?: 100000)).coerceAtLeast(0),
                genres = row.list("genres").filterIsInstance<JsonObject>().map { it.text("name") },
                studios = row.list("studios").filterIsInstance<JsonObject>().map { it.text("name") },
                airedDate = row.obj("aired").text("string"), detailUrl = row.text("url"))
        }
    }
}
