package com.lilac.anime.shared

/** 애니24 moved from ani.ohli24.com to a101.ohli24.com in November 2026 (desktop 0.5.67): the old name answers every
 *  address with HTTP 451, so lists, search, episodes and playback all failed. As with [ReAnimeHost], requests go to [WEB]
 *  while the addresses kept for a work and an episode (내 목록, 시청 기록, downloads, 기기 동기화) stay on [ID] as they were
 *  saved, so nothing saved before is lost and the phone and the desktop keep naming an episode the same way. [live] turns
 *  one into the site's address of now when it is fetched, shown or opened. A later move changes [WEB] only. */
object Ohli24Host {
    const val WEB = "https://a101.ohli24.com"
    const val ID = "https://ani.ohli24.com"
    private val known = Regex("""^https?://(?:(?:ani|www|a\d+)\.)?ohli24\.com(?=[/?#]|$)""", RegexOption.IGNORE_CASE)
    /** The address to ask or open for [url]: 애니24's at its current name, any other one as it is. */
    fun live(url: String): String = known.replaceFirst(url, WEB)
    /** An address of 애니24's own site, at any of its names (not the old www.ohli24.net copy). */
    fun owns(url: String): Boolean = known.containsMatchIn(url)
}

/** The address to ask, show or open for [url], for every site that moved ([ReAnimeHost], [Ohli24Host]). */
object SiteHost {
    fun live(url: String): String = Ohli24Host.live(ReAnimeHost.live(url))
}
