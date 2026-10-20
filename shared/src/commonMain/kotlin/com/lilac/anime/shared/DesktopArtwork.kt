package com.lilac.anime.shared

/**
 * Artwork for the detail page (desktop v0.5.43 animeArtwork): a wide picture for its header (a TMDB backdrop, else
 * AniList's banner) and the work's Korean title logos from TMDB, each with no text but the title. '' / empty when none.
 */
data class TitleArtwork(val banner: String, val logos: List<String>)

object DesktopArtwork {
    const val TMDB_IMAGE = "https://image.tmdb.org/t/p"
    private val siteMarks = Regex("\\((?:자막|더빙|무삭제)\\)|\\[[^\\]]*]|(?<![A-Za-z])ORIGINAL(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val seasonTail = Regex("\\s*(?:\\d+\\s*(?:기|부)|시즌\\s*\\d+)(?![가-힣]).*$")
    private val bareNumber = Regex("(?<=[가-힣A-Za-z])\\s+\\d{1,2}$")
    private val subtitle = Regex("\\s*[:：]\\s*|\\s+-\\s*|\\s*[~〜～]")
    private val spaces = Regex("\\s+")
    /**
     * A Korean title as TMDB is searched for it: without the site's marks ("(자막)", "ORIGINAL"), the season or part
     * ("3기", "시즌 2", "5부: 황금의 바람", a bare "3" after the name), then the part before a subtitle (":", " -", "~").
     */
    fun koreanTmdbQueries(title: String): List<String> {
        val text = normalizeDesktopTitle(title)
        if (!TitleCandidates.isKorean(text)) return emptyList()
        val base = text.replace(siteMarks, " ").replace(seasonTail, "").replace(bareNumber, "").replace(spaces, " ").trim()
        return listOf(base, base.split(subtitle).first().trim()).distinct().filter { it.length >= 2 }
    }
}
