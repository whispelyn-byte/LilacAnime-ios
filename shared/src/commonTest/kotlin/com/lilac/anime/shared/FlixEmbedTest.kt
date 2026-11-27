package com.lilac.anime.shared

import kotlin.test.*

/** FlixCloud plays only inside a frame since November 2026 (desktop 0.5.67): the apps frame it on the episode site's page. */
class FlixEmbedTest {
    @Test fun playersAreKnownByTheirHost() {
        assertTrue(FlixEmbed.isPlayer("https://flixcloud.cc/e/abc"))
        assertTrue(FlixEmbed.isPlayer("https://www.flixcloud.cc/e/abc?x=1"))
        assertFalse(FlixEmbed.isPlayer("https://flixcloud.cc.example/e/abc"))
        assertFalse(FlixEmbed.isPlayer("https://reanime.cz/watch/x"))
    }

    @Test fun thePlayerIsFramedOnTheEpisodeSitesCurrentAddress() {
        assertEquals("https://reanime.cz/", FlixEmbed.embedder("https://reanime.to/watch/x?ep=1"))
        assertEquals("https://reanime.cz/", FlixEmbed.embedder(""))
        assertEquals("https://animenosub.to/", FlixEmbed.embedder("https://animenosub.to/watch/y"))
    }

    @Test fun thePageFramesThePlayerWithItsAddressEscapedAndKeepsWhatItPassesUp() {
        val page = FlixEmbed.page("https://flixcloud.cc/e/a?b=1&c=\"2\"")
        assertTrue("""<iframe src="https://flixcloud.cc/e/a?b=1&amp;c=&quot;2&quot;" allow="autoplay; fullscreen; encrypted-media" allowfullscreen>""" in page, page)
        assertTrue("""<meta name="referrer" content="origin">""" in page)
        for (name in listOf("lilacFlixState", "'__pk'", "'__lilacReAnimeFlixResult'", "__lilacFlixDiscovery", "lilacStopDiscovery")) assertTrue(name in page, name)
    }
}
