package com.lilac.anime.shared
import com.lilac.anime.shared.compat.JSONObject
object AnimeSnapshot {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(anime: Anime): String = json.encodeToString(Anime.serializer(), anime)
    fun decode(content: String): Anime = runCatching { json.decodeFromString(Anime.serializer(), content) }.getOrElse { Anime(title = "저장된 작품 정보를 읽을 수 없습니다.") }
    fun withEpisodes(anime: Anime, episodes: List<Episode>): Anime = anime.copy(episodes = episodes)
    fun localVideo(name: String, url: String): Anime = Anime(id = url, title = name, detailUrl = url, source = "local")
    fun related(relation: ReAnimeRelated): Anime = Anime(id = relation.id, title = relation.title, poster = relation.poster,
        native = relation.nativeTitle, romaji = relation.romaji, format = relation.format, source = "reanime",
        detailUrl = "https://reanime.to/anime/" + relation.id.removePrefix("reanime:"))
    fun fullFilter(genre: String, year: String, season: String, format: String, status: String, studio: String): BrowseFilter = BrowseFilter(genre.split(',').map(String::trim).filter(String::isNotBlank), year, season, format, status, studio)
    fun sortedFilter(genre: String, year: String, season: String, format: String, status: String, studio: String, sort: String): BrowseFilter = fullFilter(genre, year, season, format, status, studio).copy(sort = sort)
    fun filter(genre: String, year: String, format: String, status: String): BrowseFilter = BrowseFilter(
        genres = genre.split(',').map(String::trim).filter(String::isNotBlank), year = year, format = format, status = status)
}
