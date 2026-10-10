package com.lilac.anime.shared
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*

class DesktopCatalogFiltersTest {
    @Test fun updatedAnimenosubAndLinkaniFilterBeforePagination() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.toString()
            if (request.url.host == "animenosub.to") {
                assertEquals("update", request.url.parameters["order"])
                assertEquals("fantasy", request.url.parameters["genre[0]"])
                assertEquals("TV", request.url.parameters["type"])
                assertEquals("2", request.url.parameters["page"])
            } else assertEquals("/list/2/year/2026/page/2/", request.url.encodedPath)
            respond("<html></html>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        try {
            repository.catalog("animenosub", 2, BrowseFilter(genres = listOf("fantasy"), format = "TV", sort = "updated"))
            repository.catalog("linkani", 2, BrowseFilter(year = "2026", sort = "updated"))
            assertEquals(2, requests.size)
        } finally { repository.close() }
    }
    @Test fun taxonomyKeepsSourceIdsAndAvailableYears() {
        val facets = DesktopCatalogTaxonomy.animenosub("""
            <input name="genre[]" value="slice-of-life"><input name="genre[]" value="fantasy">
            <input name="type" value="TV"><input name="season[]" value="winter-2026">
            <input name="season[]" value="fall-2025"><input name="season[]" value="spring-2026">
        """)
        assertEquals(listOf("slice-of-life", "fantasy"), facets.genres)
        assertEquals(listOf("2026", "2025"), facets.years)
        assertTrue(facets.supportsYear); assertTrue(facets.supportsSeason)
        assertFailsWith<IllegalArgumentException> { DesktopCatalogTaxonomy.animenosub("<html>challenge</html>") }
    }
    @Test fun animenosubAppliesGenreFormatAndAllQuartersOfYear() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/anime/", request.url.encodedPath)
            if (request.url.parameters["page"] == null) respond("""<input name="genre[]" value="fantasy"><input name="type" value="TV"><input name="season[]" value="winter-2026"><input name="season[]" value="spring-2026">""", headers = headersOf(HttpHeaders.ContentType, "text/html"))
            else {
                assertEquals("fantasy", request.url.parameters["genre[0]"])
                assertEquals("TV", request.url.parameters["type"])
                assertEquals("winter-2026", request.url.parameters["season[0]"])
                assertEquals("spring-2026", request.url.parameters["season[1]"])
                assertEquals("rating", request.url.parameters["order"])
                respond("<html></html>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
            }
        })
        val repository = SourceRepository(client)
        try { repository.catalog("animenosub", 1, BrowseFilter(genres = listOf("fantasy"), year = "2026", format = "TV", sort = "score")) } finally { repository.close() }
    }
    @Test fun reanimeUsesDescendingApiSortAndCombinedFilters() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("popularity_desc", request.url.parameters["sort"])
            assertEquals("Fantasy", request.url.parameters["genre"])
            assertEquals("2026", request.url.parameters["year"])
            assertEquals("FALL", request.url.parameters["season"])
            assertEquals("MOVIE", request.url.parameters["format"])
            assertEquals("36", request.url.parameters["offset"])
            respond("""{"results":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repository = SourceRepository(client)
        try { assertFalse(repository.catalog("reanime", 2, BrowseFilter(genres = listOf("Fantasy"), year = "2026", season = "FALL", format = "MOVIE", sort = "popular")).hasMore) } finally { repository.close() }
    }
    @Test fun emptyFormatMatchDoesNotEndSourcePagination() = runTest {
        val client = HttpClient(MockEngine {
            respond("""<div class="show-item"><a class="show-item-img-link" href="/123/show.html"><img src="poster.jpg"></a><div class="show-item-title">TV 작품</div></div>""", headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        try {
            val page = repository.catalog("ohli24", 1, BrowseFilter(format = "Movie"))
            assertTrue(page.items.isEmpty()); assertTrue(page.hasMore); assertTrue(page.signature.isNotEmpty())
            val facets = repository.filters("ohli24")
            assertFalse(facets.supportsYear); assertFalse(facets.supportsSeason); assertTrue(facets.genres.isEmpty())
        } finally { repository.close() }
    }
}
