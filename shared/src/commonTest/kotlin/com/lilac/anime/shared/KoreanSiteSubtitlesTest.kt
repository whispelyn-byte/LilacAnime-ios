package com.lilac.anime.shared

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Desktop d8f7978 tests/korean-site-subtitles.test.cjs. */
class KoreanSiteSubtitlesTest {
    @Test fun koreanTitlesCompareWithoutTheirSeasonWhichIsReadFromTheKoreanOrJapaneseTitle() {
        assertEquals(2, KoreanSiteRules.koreanSeason("약사의 혼잣말 2기")); assertEquals(3, KoreanSiteRules.koreanSeason("스파이 패밀리 시즌 3")); assertEquals(1, KoreanSiteRules.koreanSeason("단다단"))
        assertEquals(1, KoreanSiteRules.koreanSeason("기기괴괴")) // "기" inside a word is no season
        assertEquals(KoreanSiteRules.koreanBase("약사의혼잣말"), KoreanSiteRules.koreanBase("약사의 혼잣말 2기"))
        assertEquals(2, KoreanSiteRules.nativeSeason("薬屋のひとりごと 第2期")); assertNull(KoreanSiteRules.nativeSeason("薬屋のひとりごと"))
    }

    @Test fun searchHitsOfTheSameTitleAndSeasonAreKept() {
        val hits = listOf(KoreanSiteHit("a", "약사의 혼잣말 3기"), KoreanSiteHit("b", "약사의 혼잣말 2기"), KoreanSiteHit("c", "약사의 혼잣말"), KoreanSiteHit("d", "약사의 혼잣말 외전"))
        assertEquals(listOf("b"), KoreanSiteRules.candidates(hits, listOf("약사의 혼잣말 2기"), "").map { it.ref })
        assertEquals(listOf("b", "c"), KoreanSiteRules.candidates(hits, listOf("약사의 혼잣말"), "薬屋のひとりごと 第2期").map { it.ref })
        assertEquals(emptyList(), KoreanSiteRules.candidates(hits, listOf("약"), "").map { it.ref }) // too short to tell
    }

    @Test fun aShowPageIsTheWorkByItsJapaneseTitleFirstItsAnilistEntryOtherwise() {
        assertTrue(KoreanSiteRules.sameWork(KoreanSiteShow(999, "ダンダダン 2期", emptyList()), "ダンダダン 第2期", 5)) // a loose AniList id loses to the title
        assertFalse(KoreanSiteRules.sameWork(KoreanSiteShow(5, "ダンダダン", emptyList()), "ダンダダン 第2期", 5))
        assertTrue(KoreanSiteRules.sameWork(KoreanSiteShow(5, "", emptyList()), "ダンダダン 第2期", 5))
        assertFalse(KoreanSiteRules.sameWork(KoreanSiteShow(6, "", emptyList()), "", 5))
    }

    @Test fun theNumberedEpisodeIsTakenNeverASpecialOrADubOfThatNumber() {
        val list = listOf(KoreanSiteEpisode(1, true, false, "ova"), KoreanSiteEpisode(1, false, true, "dub"), KoreanSiteEpisode(1, false, false, "ep1"))
        assertEquals("ep1", KoreanSiteRules.episodeOf(list, 1)?.url); assertNull(KoreanSiteRules.episodeOf(list, 2))
        // A Korean site's list: "9.5화" and "OVA" are specials, "12화" is episode 12, "더빙" a dub.
        assertTrue(KoreanSiteRules.episode(Episode("x", 9, "9.5화", displayNumber = "9.5화")).special)
        assertTrue(KoreanSiteRules.episode(Episode("x", 1, "OVA", displayNumber = "OVA 1화")).special)
        assertFalse(KoreanSiteRules.episode(Episode("x", 12, "12화", displayNumber = "12화")).special)
        assertTrue(KoreanSiteRules.episode(Episode("x", 3, "3화 더빙", displayNumber = "3")).dub)
    }

    private class Harness(val search: Map<String, List<KoreanSiteHit>> = emptyMap(), val details: Map<String, KoreanSiteShow> = emptyMap(),
                          val files: Map<String, String> = emptyMap(), val catalog: String? = null, val failSubtitle: Boolean = false, val slow: String? = null) : CrossSiteCacheStore {
        val calls = mutableListOf<String>(); val writes = mutableListOf<String>()
        fun site(name: String) = object : KoreanSiteSource {
            override suspend fun byAnilist(anilistId: Int, native: String): String? { if (name != "linkkf") return null; calls += "linkkf id $anilistId"; return catalog }
            override suspend fun search(titles: List<String>): List<KoreanSiteHit> { calls += "$name search ${titles.joinToString("|")}"; return if (name == "linkkf") emptyList() else search[name].orEmpty() }
            override suspend fun detail(ref: String): KoreanSiteShow { calls += "$name detail $ref"; return details[ref] ?: error("no page") }
            override suspend fun subtitle(episode: KoreanSiteEpisode): CrossSiteTrack? {
                calls += "$name file ${episode.url}"
                if (name == slow) delay(60_000)
                if (failSubtitle) error("offline")
                return files[episode.url]?.let { CrossSiteTrack("x", it, "und", "vtt", "x", mapOf("Referer" to name)) }
            }
        }
        val korean = KoreanSiteSubtitles(listOf("ohli24", "linkani", "linkkf").associateWith(::site), { this }, { 1_000_000L }, 200)
        override fun read(): String? = writes.lastOrNull()
        override fun write(value: String) { writes += value }
    }
    private fun page(native: String, numbers: List<Int> = listOf(1, 2, 3)) = KoreanSiteShow(null, native, numbers.map { KoreanSiteEpisode(it, false, false, "$native-$it") })
    private val titles = listOf("약사의 혼잣말 2기")
    private val native = "薬屋のひとりごと 第2期"

    @Test fun aKoreanSitesFileIsFoundThroughItsSearchAndShowPageMarkedWithItsSite() = runTest {
        val state = Harness(search = mapOf("ohli24" to listOf(KoreanSiteHit("o1", "약사의 혼잣말"), KoreanSiteHit("o2", "약사의 혼잣말 2기"))),
            details = mapOf("o2" to page(native)), files = mapOf("$native-3" to "https://ohli.test/sub.srt"))
        val tracks = state.korean.tracks("reanime", 161645, native, titles, 3)
        assertEquals(listOf(listOf("ohli24", "ko", "한국어", "https://ohli.test/sub.srt")), tracks.map { listOf(it.origin, it.language, it.label, it.url) })
        assertEquals("ohli24", tracks[0].headers["Referer"])
        assertTrue(state.writes.last().contains("\"korean:ohli24:161645\"") && state.writes.last().contains("\"o2\""))
        assertFalse("ohli24 detail o1" in state.calls) // "약사의 혼잣말" is the first season by its Korean title
    }

    @Test fun aShowOfAnotherJapaneseTitleIsNotTakenAndAMissIsRemembered() = runTest {
        val state = Harness(search = mapOf("ohli24" to listOf(KoreanSiteHit("o2", "약사의 혼잣말 2기"))), details = mapOf("o2" to page("別の作品")))
        assertEquals(emptyList(), state.korean.tracks("reanime", 161645, native, titles, 1))
        state.korean.tracks("reanime", 161645, native, titles, 2)
        assertEquals(1, state.calls.count { it.startsWith("ohli24 search") })
    }

    @Test fun aCatalogThatNamesTheAnilistEntryIsUsedWithoutASearch() = runTest {
        val state = Harness(catalog = "post9", details = mapOf("post9" to page(native)), files = mapOf("$native-1" to "https://linkkf.test/sub.vtt"))
        assertEquals(listOf("linkkf"), state.korean.tracks("reanime", 161645, native, titles, 1).map { it.origin })
        assertTrue("linkkf id 161645" in state.calls); assertFalse(state.calls.any { it.startsWith("linkkf search") })
    }

    @Test fun thePlayingSiteIsNotAskedAndAFailingOrSlowSiteLeavesTheOthers() = runTest {
        val state = Harness(catalog = "post9", details = mapOf("post9" to page(native)), files = mapOf("$native-1" to "https://linkkf.test/sub.vtt"))
        assertEquals(emptyList(), state.korean.tracks("linkkf", 161645, native, titles, 1))
        assertEquals(emptyList(), Harness(catalog = "post9", details = mapOf("post9" to page(native)), failSubtitle = true).korean.tracks("reanime", 161645, native, titles, 1))
        val slow = Harness(search = mapOf("ohli24" to listOf(KoreanSiteHit("o2", "약사의 혼잣말 2기"))), catalog = "post9",
            details = mapOf("o2" to page(native), "post9" to page(native)), files = mapOf("$native-1" to "https://x.test/sub.vtt"), slow = "ohli24")
        assertEquals(listOf("linkkf"), slow.korean.tracks("reanime", 161645, native, titles, 1).map { it.origin })
    }

    @Test fun theKoreanSitesAreAskedApartFromReanimeAndMiruroForTheNumberedEntryOnly() = runTest {
        val asked = mutableListOf<String>()
        val koreanSite = object : KoreanSiteSource {
            override suspend fun byAnilist(anilistId: Int, native: String): String { asked += "ohli24:$anilistId"; return "show" }
            override suspend fun search(titles: List<String>) = emptyList<KoreanSiteHit>()
            override suspend fun detail(ref: String) = KoreanSiteShow(8, "", listOf(KoreanSiteEpisode(1, false, false, "ep1")))
            override suspend fun subtitle(episode: KoreanSiteEpisode) = CrossSiteTrack("x", "https://k.test/k.vtt", "und", "vtt", "x")
        }
        val sources = object : CrossSiteSources {
            override suspend fun searchAnilist(text: String) = emptyList<AnilistMedia>()
            override suspend fun reanimeTracks(anilistId: Int, number: Int) = listOf(CrossSiteTrack("Korean", "ko", "ko", "vtt", "reanime"))
            override suspend fun miruroId(anilistId: Int, titles: List<String>): String? = null
            override suspend fun miruroTracks(miruroId: String, number: Int) = emptyList<CrossSiteTrack>()
        }
        val lookup = CrossSiteTrackLookup(sources, { null }, { 1_000_000L }, KoreanSiteSubtitles(mapOf("ohli24" to koreanSite), { null }, { 1_000_000L }))
        val work = CrossSiteWork("linkkf", "l", anilistId = 8)
        // RE:Anime's Korean track does not keep them from being asked, nor are they part of its (faster) answer.
        assertEquals(listOf("ko"), lookup.tracks(work, 1).map { it.url })
        assertEquals(listOf("ohli24"), lookup.koreanSiteTracks(work, 1, koreanTitles = listOf("작품")).map { it.origin })
        assertEquals(emptyList(), lookup.koreanSiteTracks(work, 1, special = true, koreanTitles = listOf("작품")))
        assertEquals(listOf("ohli24:8"), asked)
    }
}
