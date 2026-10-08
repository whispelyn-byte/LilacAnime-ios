package com.lilac.anime.shared
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*

class DesktopHomeTest {
    @Test fun linkaniPopularityUsesItsRankingPage() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/label/topday/page/2/", request.url.encodedPath)
            respond("<html></html>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        try { repository.browse("linkani", page = 2, filter = BrowseFilter(sort = "popular")) } finally { repository.close() }
    }
    @Test fun reanimeSeasonExcludesShowsWithoutEpisodes() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("100", request.url.parameters["limit"])
            assertNotNull(request.url.parameters["season"])
            respond("""{"results":[{"anime_id":"aired","title":{"english":"Aired"},"subbed":2},{"anime_id":"future","title":{"english":"Future"},"subbed":0,"dubbed":0}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repository = SourceRepository(client)
        try { assertEquals(listOf("reanime:aired"), repository.homeShows("reanime", true).map { it.id }) } finally { repository.close() }
    }
    @Test fun animenosubFiltersUseAnimeEndpointAndExcludeUpcoming() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/anime/", request.url.encodedPath)
            assertEquals("popular", request.url.parameters["order"])
            assertEquals("2", request.url.parameters["page"])
            respond("<html><article class='bs'><span class='ans-status-ribbon'>Upcoming</span><a href='https://animenosub.to/anime/future/'>Future</a></article></html>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        try { assertTrue(repository.browse("animenosub", page = 2, filter = BrowseFilter(status = "RELEASING", sort = "popular")).isEmpty()) } finally { repository.close() }
    }
}
