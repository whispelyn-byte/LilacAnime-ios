package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AnimenosubSearchTest {
    @Test fun searchUsesDesktopPaginationAndExcludesNavigation() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/page/2/", request.url.encodedPath)
            assertEquals("Test Hero", request.url.parameters["s"])
            assertNull(request.url.parameters["order"])
            respond("""
                <header><a href="/anime/header/">Header</a></header>
                <nav><a href="/anime/navigation/">Navigation</a></nav>
                <main><article><h2>Test Hero</h2><a href="/anime/test-hero/"><img src="/hero.jpg" alt="Test Hero"></a></article></main>
                <aside id="sidebar"><a href="/anime/recommendation/">Recommendation</a></aside>
                <footer><a href="/anime/footer/">Footer</a></footer>
            """.trimIndent(), headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        try {
            val result = repository.browse("animenosub", "Test Hero", 2, BrowseFilter(sort = "popular"))
            assertEquals(listOf("animenosub:test-hero"), result.map { it.id })
            assertEquals("https://animenosub.to/anime/test-hero/", result.single().detailUrl)
        } finally { repository.close() }
    }

    @Test fun emptySearchDoesNotReturnSidebarRecommendations() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/", request.url.encodedPath)
            respond("<main>No results</main><aside id='sidebar'><a href='/anime/popular/'>Popular</a></aside>",
                headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        try { assertTrue(repository.browse("animenosub", "missing").isEmpty()) }
        finally { repository.close() }
    }
}
