package com.lilac.anime.shared

/** src/anime-metadata.js; unknown counts stay unknown, including an explicit zero. */
object DesktopAnimeMetadata {
    fun releaseDate(anime: Anime): Int {
        val date = Regex("^(\\d{4})-(\\d{2})(?:-(\\d{2}))?").find(anime.airedDate)
        if (date != null) return date.groupValues[1].toInt() * 10000 + date.groupValues[2].toInt() * 100 + (date.groupValues[3].toIntOrNull() ?: 1)
        val month = mapOf("WINTER" to 1, "SPRING" to 4, "SUMMER" to 7, "FALL" to 10)[anime.season.uppercase()] ?: 0
        return (anime.year.toIntOrNull() ?: 0) * 10000 + month * 100 + 1
    }

    fun episodeLabel(anime: Anime): String {
        val available = anime.availableEpisodes?.coerceAtLeast(0)
        val total = anime.totalEpisodes?.takeIf { it > 0 }
        return if (available != null) "업로드 ${available}화" + (total?.let { " / 총 ${it}화" } ?: "")
        else total?.let { "총 ${it}화" } ?: ""
    }
}
