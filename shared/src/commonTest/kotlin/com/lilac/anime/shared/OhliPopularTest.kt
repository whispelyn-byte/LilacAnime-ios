package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Desktop a28d0b6, bf76f91: 애니24's 인기 순위 is its most rated shows; its detail page does not wait on AniList. */
class OhliPopularTest {
    private fun page(vararg shows: Triple<String, Int, Double>) = """<html><script data-page="app" type="application/json">{"component":"Home","props":{"comics":{"data":[""" +
        shows.joinToString(",") { (slug, count, rating) -> """{"slug":"$slug","title":"$slug","rating_count":$count,"rating":$rating}""" } +
        """]}}}</script></html>"""

    @Test fun mostRatedShowsComeFirstEachOnce() {
        val items = listOf("""{"slug":"a","title":"A","rating_count":3,"rating":4.9}""", """{"slug":"b","title":"B","rating_count":120,"rating":4.1}""",
            """{"slug":"c","title":"C","rating_count":120,"rating":4.6}""", """{"slug":"b","title":"B","rating_count":120,"rating":4.1}""", """{"slug":"d","title":"D"}""")
            .map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("c", "b", "a", "d"), DesktopSourceParser.ohliPopular(items).map { it.id })
    }

    @Test fun popularRailReadsTheRatingPagesTogetherAndKeepsThemAnHour() = runTest {
        val asked = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            asked += request.url.toString()
            val body = when (request.url.parameters["page"]) {
                "1" -> page(Triple("one", 10, 4.0), Triple("two", 50, 4.0))
                "2" -> page(Triple("three", 30, 4.5), Triple("one", 10, 4.0))
                else -> page()
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        val first = repository.browse("ohli24", filter = BrowseFilter(sort = "popular"))
        assertEquals(listOf("two", "three", "one"), first.map { it.id })
        assertTrue(asked.all { it.contains("sort=rating-desc") }, asked.toString())
        assertEquals(5, asked.size)
        repository.browse("ohli24", filter = BrowseFilter(sort = "popular"))
        assertEquals(5, asked.size, "kept, not asked again")
    }

    @Test fun detailPageDoesNotWaitForAniList() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Post) { delay(20_000); return@MockEngine respond("""{"data":{"Page":{"media":[]}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
            val props = """{"comic":{"slug":"작품","title":"작품","original_title":"作品","category":"방영중","metadata":{"info_rows":[]}},
                "episodes":{"data":[{"title":"1화","slug":"작품 1화","sort":100}],"last_page":1}}"""
            respond("""<html><script data-page="app" type="application/json">{"component":"Comic","props":$props}</script></html>""",
                headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val summary = Anime(id = "작품", title = "작품", source = "ohli24", detailUrl = DesktopSourceParser.ohliPath("c", "작품"))
        val detail = SourceRepository(client).detail(summary, "ohli24")
        assertEquals(1, detail.servers.single().episodes.size)
        assertNull(detail.anime.anilistId)
        assertTrue(testScheduler.currentTime <= 1_500, "waited ${testScheduler.currentTime} ms")
    }
}
