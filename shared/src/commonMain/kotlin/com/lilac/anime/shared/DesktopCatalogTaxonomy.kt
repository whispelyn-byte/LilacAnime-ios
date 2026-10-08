package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup

object DesktopCatalogTaxonomy {
    fun animenosub(html: String): SourceFilters {
        val doc = Ksoup.parse(html)
        fun values(name: String) = doc.select("input").filter { it.attr("name") == name }.map { it.attr("value") }.filter(String::isNotBlank).distinct()
        val seasons = values("season[]")
        val genres = values("genre[]")
        require(genres.isNotEmpty() || seasons.isNotEmpty()) { "Animenosub 분류를 불러오지 못했습니다." }
        val years = seasons.mapNotNull { Regex("-(\\d{4})$").find(it)?.groupValues?.get(1) }.distinct().sortedDescending()
        return SourceFilters(genres = genres, years = years, formats = values("type"), seasons = if (seasons.isEmpty()) emptyList() else listOf("WINTER", "SPRING", "SUMMER", "FALL"),
            supportsYear = years.isNotEmpty(), supportsSeason = seasons.isNotEmpty(), sorts = listOf("popular", "year", "score"), seasonValues = seasons)
    }
}
