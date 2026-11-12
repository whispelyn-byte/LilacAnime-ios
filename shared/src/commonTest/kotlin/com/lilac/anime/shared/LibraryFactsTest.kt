package com.lilac.anime.shared

import kotlin.test.*

/** 내 목록 kept up to date (desktop 19110b2, GitHub issue #28). */
class LibraryFactsTest {
    private val now = kotlin.time.Instant.parse("2026-11-10T12:00:00Z").toEpochMilliseconds()
    private fun episode(n: Int, aired: String, playable: Boolean = true) = Episode(id = "$n", number = n, title = "", airedDate = aired, playable = playable)

    @Test fun theLatestEpisodeThatIsOutDatesTheWork() {
        val detail = SourceDetail(Anime(id = "a", availableEpisodes = 7, totalEpisodes = 12, status = "Releasing"), listOf(EpisodeServer(1, "", listOf(
            episode(6, "2026-11-03"), episode(7, "2026-11-10T00:00:00Z"), episode(8, "2026-11-17"), episode(9, "2026-11-09", playable = false), episode(1, "")))))
        assertEquals(LibraryFact(7, 12, -1, "Releasing", "2026-11-10"), LibraryFacts.of(detail, now))
        assertEquals("", LibraryFacts.of(SourceDetail(Anime(id = "b"), emptyList()), now).updatedAt)
    }

    @Test fun theCardShowsTheCountsOfNowAndKeepsWhatTheFactsDoNotGive() {
        val saved = Anime(id = "a", availableEpisodes = 3, totalEpisodes = 12, status = "Releasing")
        val shown = LibraryFacts.shown(saved, LibraryFact(7, -1, -1, "", "2026-11-10"))
        assertEquals(7, shown.availableEpisodes); assertEquals(12, shown.totalEpisodes); assertEquals("Releasing", shown.status); assertEquals("2026-11-10", shown.updatedAt)
        assertSame(saved, LibraryFacts.shown(saved, null))
    }

    @Test fun aFinishedWorkIsLookedAtWeeklyAndOneStillAiringEveryFewHours() {
        assertEquals(3 * 60 * 60 * 1000L, LibraryFacts.dueAfterMillis(Anime(availableEpisodes = 7, totalEpisodes = 12)))
        assertEquals(7 * 24 * 60 * 60 * 1000L, LibraryFacts.dueAfterMillis(Anime(availableEpisodes = 12, totalEpisodes = 12)))
        assertEquals(7 * 24 * 60 * 60 * 1000L, LibraryFacts.dueAfterMillis(Anime(status = "완결")))
        assertEquals("2026-11-10", LibraryFacts.day("2026-11-10T15:30:00Z")); assertEquals("", LibraryFacts.day("어제"))
    }
}
