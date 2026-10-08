package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.*
object DesktopReanimeParser {
    fun detail(root: JsonObject, old: Anime): Anime {
        fun date(key: String) = root.obj(key).let { row -> listOf("year", "month", "day").map { row.text(it).padStart(2, '0') }.joinToString("-").takeIf { row.number("year") != null }.orEmpty() }
        val titles = root.obj("title")
        val related = root.list("relations").filterIsInstance<JsonObject>().mapNotNull {
            val id = it.text("anime_id")
            if (id.isBlank() || id == old.id) null else ReAnimeRelated(id, it.obj("title").text("english").ifBlank { it.obj("title").text("romaji") },
                it.obj("title").text("native"), it.obj("title").text("romaji"), it.obj("cover_image").text("extra_large"), it.text("format"), it.text("relation_type"), it.text("season"), it.number("season_year"))
        }
        return old.copy(score = root.text("average_score").toDoubleOrNull() ?: old.score, popularity = root.number("popularity") ?: old.popularity, source = "reanime", native = titles.text("native"), romaji = titles.text("romaji"), english = titles.text("english"),
            description = Ksoup.parse(root.text("description")).text().ifBlank { old.description },
            poster = root.obj("cover_image").text("extra_large").ifBlank { old.poster },
            genres = root.list("genres").mapNotNull { (it as? JsonPrimitive)?.content },
            studios = root.list("studios").filterIsInstance<JsonObject>().map { it.text("name") },
            synonyms = root.list("synonyms").mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", "),
            year = root.text("season_year"), format = root.text("format"), airedDate = listOf(date("start_date"), date("end_date")).filter(String::isNotBlank).joinToString(" ~ "),
            anilistId = root.number("anilist_id") ?: old.anilistId, malId = root.number("mal_id") ?: old.malId, reAnimeRelated = related)
    }
    fun episodes(root: JsonObject, anime: Anime): List<Episode> = root.list("data").filterIsInstance<JsonObject>().mapNotNull { row ->
        val number = row.number("episode_number")?.takeIf { it > 0 } ?: return@mapNotNull null
        fun flag(key: String) = row.text(key) == "true"
        Episode(anime.id + ":" + number, number, row.text("title").ifBlank { number.toString() + "화" },
            videoUrl = "https://reanime.to/watch/" + anime.id.encodeURLPathPart() + "?ep=" + number,
            nativeTitle = row.text("title_japanese"), airedDate = row.text("aired"), isFiller = flag("is_filler"), isRecap = flag("is_recap"),
            playable = row.text("playable") != "false", subbed = flag("subbed"), dubbed = flag("dubbed"), thumbnailUrl = row.text("thumbnail"))
    }.distinctBy { it.number }.sortedBy { it.number }
}
data class SourceSection(val name: String, val items: List<Anime>)
data class SourceExtras(val views: String, val related: List<Anime>)
