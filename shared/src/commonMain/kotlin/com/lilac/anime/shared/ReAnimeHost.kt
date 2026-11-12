package com.lilac.anime.shared

/** RE:Anime moved from reanime.to to reanime.cz in November 2026: the old name answers every address, its API's too, with
 *  a redirect to the new home page, so every list, detail and episode came back as that page (desktop 3fa419c).
 *  Requests go to [WEB]. The addresses kept for a work and an episode ([Anime.detailUrl], [Episode.videoUrl]: 내 목록,
 *  시청 기록, downloads, saved subtitles and 기기 동기화 are keyed by them) stay on [ID] as they were saved, so nothing saved
 *  before is lost and the phone and the desktop keep naming an episode the same way; [live] turns one into the site's
 *  address of now when it is fetched or opened. A later move changes [WEB] only. */
object ReAnimeHost {
    const val WEB = "https://reanime.cz"
    const val ID = "https://reanime.to"
    private val known = Regex("""^https?://(?:www\.)?reanime\.(?:to|cz)(?=[/?#]|$)""", RegexOption.IGNORE_CASE)
    /** The address to ask or open for [url]: RE:Anime's at its current name, any other one as it is. */
    fun live(url: String): String = known.replaceFirst(url, WEB)
}
