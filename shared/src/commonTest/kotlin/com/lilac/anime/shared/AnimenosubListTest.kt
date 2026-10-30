package com.lilac.anime.shared

import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.AnimenosubParser
import kotlin.test.*

class AnimenosubListTest {
    // The site's menu links its series index (/anime/): it came first in every list as a series with no episodes.
    @Test fun theSeriesIndexLinkIsNotASeries() {
        val html = """<html><body><nav><a href="https://animenosub.to/anime/">Animenosub - Watch Anime Online</a></nav>
            <article class="bs"><a href="https://animenosub.to/anime/overgeared/" title="Overgeared"><img src="https://animenosub.to/o.jpg"><h2>Overgeared</h2></a></article>
            <article class="bs"><a href="https://animenosub.to/romelia-war-chronicle-episode-4/" title="Romelia War Chronicle Episode 4"><img src="https://animenosub.to/r.jpg"><h2>Romelia War Chronicle</h2></a></article>
            </body></html>"""
        val list = AnimenosubParser.parseAnimeList(Ksoup.parse(html, "https://animenosub.to/"))
        assertFalse(list.any { it.detailUrl.trimEnd('/') == "https://animenosub.to/anime" }, list.toString())
        assertTrue(list.any { it.detailUrl == "https://animenosub.to/anime/overgeared/" }, list.toString())
    }
}
