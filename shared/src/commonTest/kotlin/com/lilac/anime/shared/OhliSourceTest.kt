package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OhliSourceTest {
    @Test fun episodeLabelsKeepSpecialsAndHalfEpisodesApart() {
        assertEquals(DesktopSourceParser.EpisodeLabel("12", 12.0, false), DesktopSourceParser.episodeLabel("12화"))
        assertEquals(DesktopSourceParser.EpisodeLabel("9.5", 9.5, false), DesktopSourceParser.episodeLabel("9.5화"))
        assertEquals(DesktopSourceParser.EpisodeLabel("OVA 1", null, true), DesktopSourceParser.episodeLabel("OVA 1화"))
        assertTrue(DesktopSourceParser.episodeLabel("SP 노벨 히로인즈").special)
        assertTrue(DesktopSourceParser.episodeLabel("특별편").special)
        assertFalse(DesktopSourceParser.episodeLabel("Spy 3화").special)
        assertEquals("1", DesktopSourceParser.episodeLabel("").name)
        assertEquals("12화", DesktopSourceParser.episodeTitle("12"))
        assertEquals("OVA 1화", DesktopSourceParser.episodeTitle("OVA 1"))
        assertEquals("SP 노벨 히로인즈", DesktopSourceParser.episodeTitle("SP 노벨 히로인즈"))
    }

    @Test fun pathsEscapeSlashesInTitles() {
        assertEquals("https://ani.ohli24.com/c/%EB%9E%80%EB%A7%88%201%2F2%20%282026%29", DesktopSourceParser.ohliPath("c", "란마 1/2 (2026)"))
        val anime = DesktopSourceParser.ohliAnime(kotlinx.serialization.json.Json.parseToJsonElement("""{"slug":"란마 1/2 (2026)","title":"란마 1/2 (2026)","rating":null,"episode_total":null,"eps":"2화",
            "metadata":{"info_rows":[["방영일","2026.10.04 00:55 (일)"]]}}""") as kotlinx.serialization.json.JsonObject)
        assertEquals("2026", anime.year); assertEquals(0.0, anime.score); assertEquals(2, anime.availableEpisodes); assertNull(anime.totalEpisodes)
        assertEquals("방영중", anime.status)
    }
    @Test fun publishTimesAreReadAsTheDayInKorea() {
        assertEquals("2025-10-29", DesktopSourceParser.ohliDay("2025-10-29T10:14:25+09:00"))
        assertEquals("2025-10-26", DesktopSourceParser.ohliDay("2025-10-25T20:49:54.000000Z"))
        assertEquals("2026-01-01", DesktopSourceParser.ohliDay("2025-12-31T16:00:00Z"))
        assertEquals("2024-03-01", DesktopSourceParser.ohliDay("2024-02-29T23:30:00+09:00".replace("02-29T23:30", "03-01T00:30")))
        assertEquals("", DesktopSourceParser.ohliDay("not a date"))
    }

    @Test fun seriesPagesListEveryEpisodeWithSpecialsAfterNumberedOnes() = runTest {
        val requested = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requested += request.url.toString()
            if (request.method == HttpMethod.Post) return@MockEngine respond("""{"data":{"Page":{"media":[]}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            val props = if (request.url.parameters["page"] == "2")
                """{"episodes":{"data":[{"title":"2화","slug":"작품 2화","sort":200,"published_at":"2025-10-12T23:00:00Z"}],"last_page":2}}"""
            else """{"comic":{"slug":"작품","title":"작품","original_title":"作品","category":"방영중","rating":4.5,"thumbnail":"a.jpg",
                "metadata":{"info_rows":[["방영일","2025-10-05"],["장르","판타지, 모험"]]},"description":"<p>소개</p>","tags_string":"판타지,모험","episode_total":12},
                "episodes":{"data":[{"title":"OVA 1화","slug":"작품 OVA 1화","sort":10},{"title":"9.5화","slug":"작품 9.5화","sort":950},
                {"title":"1화","slug":"작품 1화","sort":100,"published_at":"2025-10-05T15:30:00Z"}],"last_page":2}}"""
            respond("""<html><script data-page="app" type="application/json">{"component":"Comic","props":$props}</script></html>""",
                headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val summary = Anime(id = "작품", title = "작품", source = "ohli24", detailUrl = DesktopSourceParser.ohliPath("c", "작품"))
        val detail = SourceRepository(client).detail(summary, "ohli24")
        val episodes = detail.servers.single().episodes
        assertEquals(listOf("1", "2", "9.5", "OVA 1"), episodes.map { it.displayNumber })
        assertEquals(listOf(1, 2, 9, 0), episodes.map { it.number })
        assertEquals(listOf("1화", "2화", "9.5화", "OVA 1화"), episodes.map { it.title })
        assertEquals("2025-10-06", episodes[0].airedDate)
        assertEquals(DesktopSourceParser.ohliPath("e", "작품 9.5화"), episodes[2].id)
        assertEquals(2, detail.anime.availableEpisodes)
        assertEquals("作品", detail.anime.native)
        assertEquals("https://ani.ohli24.com/storage/a.jpg", detail.anime.poster)
        assertEquals(listOf("판타지", "모험"), detail.anime.genres)
        assertTrue(requested.any { it.contains("page=2") })
    }

    @Test fun aSingleEpisodeMovieIsItsFirstEpisode() {
        val comic = kotlinx.serialization.json.Json.parseToJsonElement("""{"slug":"극장판 작품","title":"극장판 작품","category":"극장판"}""") as kotlinx.serialization.json.JsonObject
        val row = kotlinx.serialization.json.Json.parseToJsonElement("""{"title":"14기","slug":"극장판 작품 14기"}""") as kotlinx.serialization.json.JsonObject
        val detail = DesktopSourceParser.ohliDetail(comic, listOf(row), Anime(id = "x", title = "x", source = "ohli24"))
        val episode = detail.servers.single().episodes.single()
        assertEquals("1", episode.displayNumber); assertEquals(1, episode.number); assertEquals("MOVIE", detail.anime.format)
    }
}
