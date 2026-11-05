package com.lilac.anime.shared

import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.AnimenosubParser
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Desktop 77e3d7e tests/cross-site-tracks.test.cjs, and the iOS parts (the FlixCloud page list, Animenosub's names). */
class CrossSiteTracksTest {
    private fun media(id: Int, native: String, year: Int?, english: String = "", romaji: String = "", synonyms: List<String> = emptyList(), episodes: Int? = 12) =
        AnilistMedia(id, episodes, year, null, native, english, romaji, synonyms)

    @Test fun titlesCompareWithoutSpacingPunctuationUncensoredOrTheWayASeasonIsWritten() {
        val key = CrossSiteTitles::titleKey
        assertEquals(key("Magic Repo Man: I'll Cash In"), key("Magic Repo Man Ill Cash In"))
        assertEquals(key("Show 2nd Season"), key("Show Season 2"))
        assertEquals(key("Even the Student Council Has Its Holes (Uncensored)"), key("Even The Student Council Has Its Holes"))
        assertEquals(key("マロニエ王国の 七人の騎士"), key("マロニエ王国の七人の騎士"))
        assertEquals(key("ダンダダン 2期"), key("ダンダダン 第2期"))
        assertEquals(key("薬屋のひとりごと 第二期"), key("薬屋のひとりごと 第2期"))
        assertNotEquals(key("ダンダダン 第2期"), key("ダンダダン"))
    }

    @Test fun aJapaneseTitleCountsOnlyInKanaOrKanjiAndTheYearComesFromYearOrAirDate() {
        assertEquals("葬送のフリーレン", CrossSiteTitles.nativeOf("葬送のフリーレン"))
        assertEquals("", CrossSiteTitles.nativeOf("Sousou no Frieren"))
        assertEquals("", CrossSiteTitles.nativeOf("장송의 프리렌"))
        assertEquals(2023, CrossSiteTitles.yearOf("2023", ""))
        assertEquals(2026, CrossSiteTitles.yearOf("", "2026-10-03 ~ 2026-12-19"))
        assertNull(CrossSiteTitles.yearOf("", ""))
    }

    @Test fun theSameJapaneseTitleOfTheAirYearWinsOverARemake() {
        val list = listOf(media(1, "うる星やつら", 1981), media(2, "うる星やつら", 2022), media(3, "うる星やつら 完結編", 1988))
        assertEquals(2, CrossSiteTitles.pickByNative(list, "うる星やつら", 2022)?.id)
        assertEquals(2, CrossSiteTitles.pickByNative(list, "うる星やつら", 2023)?.id) // a year off (late December)
        assertNull(CrossSiteTitles.pickByNative(list, "うる星やつら", 2000)) // two of that title, neither of the year
        assertNull(CrossSiteTitles.pickByNative(listOf(media(4, "別の作品", 2022)), "うる星やつら", 2022))
        assertEquals(1, CrossSiteTitles.pickByNative(list, "うる星やつら", null)?.id)
    }

    @Test fun anEnglishTitleMatchesTheEnglishRomajiOrAnotherNameOfTheEntry() {
        val list = listOf(media(10, "マロニエ王国の七人の騎士", 2026, english = "The Seven Knights of the Marronnier Kingdom"), media(11, "別", 2026, romaji = "Betsu", synonyms = listOf("Other Show")))
        assertEquals(10, CrossSiteTitles.pickByTitle(list, "The Seven Knights Of The Marronnier Kingdom", 2026)?.id)
        assertEquals(11, CrossSiteTitles.pickByTitle(list, "Other Show", null)?.id)
        assertNull(CrossSiteTitles.pickByTitle(list, "The Seven Knights", 2026))
    }

    private class Harness(val results: Map<String, List<AnilistMedia>> = emptyMap(), val fail: Boolean = false, val miruro: Pair<String?, List<CrossSiteTrack>>? = null, val miruroFails: Boolean = false) : CrossSiteSources, CrossSiteCacheStore {
        val searches = mutableListOf<String>(); val asked = mutableListOf<String>(); val writes = mutableListOf<String>(); val miruroAsked = mutableListOf<String>()
        val lookup = CrossSiteTrackLookup(this, { this }, { 1_000_000L })
        override suspend fun searchAnilist(text: String): List<AnilistMedia> { searches += text; if (fail) error("AniList HTTP 429"); return results[text].orEmpty() }
        override suspend fun reanimeTracks(anilistId: Int, number: Int): List<CrossSiteTrack> {
            asked += "$anilistId:$number"
            return listOf(CrossSiteTrack("Japanese", "ja-$anilistId-$number", "ja", "vtt", "reanime", mapOf("Referer" to "https://flixcloud.cc/e/x")))
        }
        override suspend fun miruroId(anilistId: Int, titles: List<String>): String? { miruroAsked += "id $anilistId ${titles.joinToString("|")}"; return miruro?.first }
        override suspend fun miruroTracks(miruroId: String, number: Int): List<CrossSiteTrack> {
            miruroAsked += "tracks $miruroId:$number"; if (miruroFails) error("Miruro HTTP 500"); return miruro?.second.orEmpty()
        }
        override fun read(): String? = writes.lastOrNull()
        override fun write(value: String) { writes += value }
    }
    private fun miruroTrack(url: String, language: String, label: String) = CrossSiteTrack(label, url, language, "vtt", "miruro")

    @Test fun anotherSitesEpisodeGetsReanimeTracksOfItsAnilistEntry() = runTest {
        val state = Harness(mapOf("薬屋のひとりごと" to listOf(media(161645, "薬屋のひとりごと", 2023, episodes = 24))))
        val tracks = state.lookup.tracks(CrossSiteWork("ohli24", "x", "약사의 혼잣말", native = "薬屋のひとりごと", year = "2023"), 3)
        assertEquals(listOf("161645:3"), state.asked)
        assertEquals("reanime", tracks[0].origin); assertEquals("https://flixcloud.cc/e/x", tracks[0].headers["Referer"])
        assertTrue(state.writes.last().contains("\"ohli24:x\"") && state.writes.last().contains("\"161645\""))
    }

    @Test fun miruroAndLinkkfIdsAreUsedAsTheyAreAndOhli24IdsAreCheckedByTheJapaneseTitleFirst() = runTest {
        val state = Harness(mapOf("薬屋のひとりごと" to listOf(media(161645, "薬屋のひとりごと", 2023))))
        state.lookup.tracks(CrossSiteWork("miruro", "m", native = "薬屋のひとりごと", anilistId = 999), 1)
        state.lookup.tracks(CrossSiteWork("linkkf", "l", anilistId = 998), 1)
        state.lookup.tracks(CrossSiteWork("ohli24", "o", native = "薬屋のひとりごと", year = "2023", anilistId = 777), 1)
        assertEquals(listOf("999:1", "998:1", "161645:1"), state.asked)
        assertEquals(listOf("薬屋のひとりごと"), state.searches)
    }

    @Test fun noTracksForSpecialsDubsOrANumberPastTheEntryAndNoneFromTheSitePlaying() = runTest {
        val state = Harness(mapOf("作品" to listOf(media(5, "作品", 2024, episodes = 12))))
        val work = CrossSiteWork("linkani", "a", native = "作品", year = "2024")
        assertTrue(state.lookup.tracks(work.copy(provider = "reanime", anilistId = 5), 1).isEmpty())
        assertTrue(state.lookup.tracks(work, 1, special = true).isEmpty())
        assertTrue(state.lookup.tracks(work, 1, dub = true).isEmpty())
        assertTrue(state.lookup.tracks(work, 0).isEmpty())
        assertTrue(state.lookup.tracks(work, 13).isEmpty())
        assertEquals(emptyList(), state.asked)
    }

    @Test fun miruroTracksAreFoundByTheEntrysTitlesAndAddedForLanguagesReanimeHasNoneOf() = runTest {
        val tracks = listOf(miruroTrack("m-ko", "ko", "Korean"), miruroTrack("m-ja", "ja", "Japanese"), miruroTrack("m-es", "es", "Spanish"))
        val state = Harness(mapOf("作品" to listOf(media(5, "作品", 2024, english = "The Work"))), miruro = "mid" to tracks)
        val result = state.lookup.tracks(CrossSiteWork("animenosub", "w", "The Work", native = "作品", year = "2024"), 2)
        assertEquals(listOf("reanime:ja-5-2", "miruro:m-ko", "miruro:m-es"), result.map { it.origin + ":" + it.url })
        assertEquals(listOf("id 5 作品|The Work", "tracks mid:2"), state.miruroAsked)
        assertTrue(state.writes.last().contains("\"miruro:5\"") && state.writes.last().contains("\"mid\""))
    }

    @Test fun aReanimeEpisodeAsksMiruroOnlyAMiruroEpisodeReanimeOnlyAndOneSiteFailingLeavesTheOther() = runTest {
        val state = Harness(miruro = "mid" to listOf(miruroTrack("m-ko", "ko", "Korean")))
        assertEquals(listOf("m-ko"), state.lookup.tracks(CrossSiteWork("reanime", "r", anilistId = 7), 1).map { it.url }); assertEquals(emptyList(), state.asked)
        assertEquals(listOf("ja-7-1"), state.lookup.tracks(CrossSiteWork("miruro", "mid", anilistId = 7), 1).map { it.url })
        val failing = Harness(miruro = "mid" to emptyList(), miruroFails = true)
        assertEquals(listOf("ja-8-3"), failing.lookup.tracks(CrossSiteWork("linkkf", "l", anilistId = 8), 3).map { it.url })
    }

    @Test fun aShowMiruroDoesNotHaveIsRememberedAsMissing() = runTest {
        val state = Harness(miruro = null to emptyList())
        state.lookup.tracks(CrossSiteWork("linkkf", "l", anilistId = 8), 1)
        state.lookup.tracks(CrossSiteWork("linkkf", "l", anilistId = 8), 2)
        assertEquals(1, state.miruroAsked.count { it.startsWith("id") })
    }

    @Test fun anEnglishTitledShowIsFoundByItsEnglishTitleWhenItHasNoJapaneseOne() = runTest {
        val title = "Frieren: Beyond Journey's End"
        val state = Harness(mapOf(title to listOf(media(154587, "葬送のフリーレン", 2023, english = title))))
        state.lookup.tracks(CrossSiteWork("animenosub", "frieren", title), 2)
        assertEquals(listOf("154587:2"), state.asked)
    }

    @Test fun aMatchAndAMissAreKeptAndAFailedLookupIsAskedAgain() = runTest {
        val work = CrossSiteWork("linkani", "a", native = "作品", year = "2024")
        val found = Harness(mapOf("作品" to listOf(media(5, "作品", 2024))))
        found.lookup.tracks(work, 1); found.lookup.tracks(work, 2)
        assertEquals(1, found.searches.size)
        val missing = Harness()
        missing.lookup.tracks(work.copy(native = "無い作品"), 1); missing.lookup.tracks(work.copy(native = "無い作品"), 1)
        assertEquals(1, missing.searches.size); assertTrue(missing.writes.last().contains("\"linkani:a\""))
        val failing = Harness(fail = true)
        assertFails { failing.lookup.tracks(work, 1) }; assertFails { failing.lookup.tracks(work, 1) }
        assertEquals(2, failing.searches.size); assertEquals(emptyList(), failing.writes)
    }

    @Test fun aMatchKeptByAnotherLookupIsReadFromTheStore() = runTest {
        val first = Harness(mapOf("作品" to listOf(media(5, "作品", 2024))))
        val work = CrossSiteWork("linkani", "a", native = "作品", year = "2024")
        first.lookup.tracks(work, 1)
        val second = Harness()
        second.writes += first.writes.last()
        second.lookup.tracks(work, 2)
        assertEquals(emptyList(), second.searches); assertEquals(listOf("5:2"), second.asked)
    }

    @Test fun theFlixPlayerPageListsItsTracksWithBracketsAndEscapes() {
        val html = """<script>x={sources:[],subtitles:[{url:"https://cdn.example/a_kor_1.vtt",language:"ko",label:"Korean"},""" +
            """{url:"https://cdn.example/b.ass?x=1&y=2",language:"en",label:"English [nedragrevev]"},{url:"https://cdn.example/a_kor_1.vtt",language:"ko",label:"Korean"},""" +
            """{url:"/relative.vtt",language:"ja"}],other:[1]}</script>"""
        val tracks = FlixSubtitleTracks.parse(html)
        assertEquals(listOf("Korean", "English [nedragrevev]"), tracks.map { it.label })
        assertEquals("https://cdn.example/b.ass?x=1&y=2", tracks[1].url); assertEquals("ass", tracks[1].format)
        assertEquals(emptyList(), FlixSubtitleTracks.parse("<html>no list</html>"))
    }

    @Test fun animenosubGivesItsJapaneseTitleAndSeasonYear() {
        val html = """<html><body><h1>The Seven Knights</h1><span class="alter">Siedmiu Rycerzy, The Seven Knights of the Marronnier Kingdom, マロニエ王国の七人の騎士</span>
            <div class="spe"><span><b>Status:</b> Ongoing</span><span><b>Season:</b> <a>Fall 2026</a></span><span><b>Released:</b> Oct 3, 2025</span></div></body></html>"""
        val anime = AnimenosubParser.parseAnimeDetail(Ksoup.parse(html, "https://animenosub.to/anime/x/"), Anime(id = "animenosub:x", source = "animenosub"))
        assertEquals("マロニエ王国の七人の騎士", anime.native); assertEquals("2026", anime.year)
        assertEquals("", AnimenosubParser.nativeName("장송의 프리렌, Frieren"))
    }

    @Test fun languagesOfTwoSitesAreToldApartByCodeOrName() {
        assertEquals("ko", CrossSiteTitles.languageKey("kor", "")); assertEquals("en", CrossSiteTitles.languageKey("", "English (Dialogue)"))
        assertEquals("ja", CrossSiteTitles.languageKey("jpn", "")); assertEquals("es", CrossSiteTitles.languageKey("es-419", "Spanish"))
    }
}
