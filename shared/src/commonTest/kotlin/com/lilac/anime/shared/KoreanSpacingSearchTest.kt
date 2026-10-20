package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Desktop v0.5.40 searchIgnoringSpaces: 애니24 and 링크애니 find a keyword only as it is spaced. */
class KoreanSpacingSearchTest {
    private fun ohliPage(props: String) = """<html><script data-page="app" type="application/json">{"component":"Board","props":$props}</script></html>"""
    private fun ohliItem(title: String) = """{"slug":"$title","title":"$title","metadata":{"info_rows":[]}}"""

    @Test fun ohliSearchesAgainByPiecesWhenTheSpacedQueryFindsNothing() = runTest {
        val keywords = mutableListOf<String>()
        val boards = mapOf(
            "스파이" to listOf("스파이 패밀리", "스파이 교실"),
            "파이패" to emptyList(),
            "패밀리" to listOf("스파이 패밀리", "스파이 패밀리 2기", "패밀리 레스토랑"))
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath.decodeURLPart()
            val props = if (path == "/방영중") """{"items":[${ohliItem("스파이패밀리 극장판 코드 화이트")}]}""" else {
                val keyword = request.url.parameters["keyword"].orEmpty(); keywords += keyword
                val titles = if (path == "/완결") boards[keyword].orEmpty() else emptyList()
                """{"comics":{"data":[${titles.joinToString(",") { ohliItem(it) }}],"next_page_url":null}}"""
            }
            respond(ohliPage(props), headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        try {
            val found = repository.browse("ohli24", "스파이패밀리", 1, BrowseFilter()).map { it.title }
            assertEquals(listOf("스파이패밀리 극장판 코드 화이트", "스파이 패밀리", "스파이 패밀리 2기"), found)
            assertEquals(setOf("스파이패밀리", "스파이", "파이패", "패밀리"), keywords.toSet())
            keywords.clear()
            assertEquals(listOf("스파이패밀리 극장판 코드 화이트", "스파이 패밀리", "스파이 교실"), repository.browse("ohli24", "스파이", 1, BrowseFilter()).map { it.title })
            assertEquals(setOf("스파이"), keywords.toSet(), "a query that finds something is not searched again")
        } finally { repository.close() }
    }

    @Test fun linkaniSearchesAgainByPiecesOnTheFirstPageOnly() = runTest {
        val queries = mutableListOf<String>()
        val results = mapOf("약사의" to listOf(1 to "약사의 혼잣말", 2 to "약사의 혼잣말 2기"), "혼잣말" to listOf(1 to "약사의 혼잣말", 3 to "혼잣말 하는 고양이"))
        val client = HttpClient(MockEngine { request ->
            val wd = request.url.parameters["wd"].orEmpty(); queries += wd
            val items = results[wd].orEmpty().joinToString("") { (id, title) -> "<div class='vod-item'><a href='/ani/$id/'><span class='vod-item-title'>$title</span></a></div>" }
            respond("<div class='vod-list'>$items</div>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        try {
            val found = repository.browse("linkani", "약사의혼잣말", 1, BrowseFilter())
            assertEquals(listOf("약사의 혼잣말", "약사의 혼잣말 2기"), found.map { it.title })
            assertTrue("약사의" in queries && "혼잣말" in queries)
            queries.clear()
            assertTrue(repository.browse("linkani", "약사의혼잣말", 2, BrowseFilter()).isEmpty())
            assertEquals(listOf("약사의혼잣말"), queries, "later pages are not searched by pieces")
            // English and short queries are never split.
            queries.clear(); repository.browse("linkani", "spyfamily", 1, BrowseFilter()); repository.browse("linkani", "약사", 1, BrowseFilter())
            assertEquals(listOf("spyfamily", "약사"), queries)
        } finally { repository.close() }
    }
}
