package com.lilac.anime.shared

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class DesktopNewFiltersTest {
    @Test fun linkaniCombinesTaxonomyRoutesAndKeepsServerFormat() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/list/2/class/Slice%20of%20Life/year/2025/lang/OVA/page/2/", request.url.encodedPath)
            respond("<div class='vod-item'><a href='/view/example/'><span class='vod-item-title'>Example</span></a></div>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        try { DesktopSourceRepository(client).browse("linkani", "", 2, BrowseFilter(genres = listOf("Slice of Life"), year = "2025", format = "OVA")) } finally { client.close() }
    }
    @Test fun sourceTaxonomiesExposeRealOptionsAndDecades() {
        val link = DesktopCatalogTaxonomy.linkani("""<a href="/list/2/class/Slice+of+Life/">일상</a><a href="/list/2/class/월/">월</a><a href="/list/2/year/2025/">2025</a><a href="/list/2/lang/OVA/">OVA</a>""")
        assertEquals(listOf("Slice of Life"), link.genres); assertEquals(listOf("OVA"), link.formats)
        val ohli = DesktopCatalogTaxonomy.ohli(Json.parseToJsonElement("""{"filterOptions":{"genres":[{"items":[{"value":"액션","label":"액션"}]}],"years":[{"value":"1990s","label":"1990년대"}]}}""").jsonObject)
        assertEquals(listOf("1990s"), ohli.years); assertEquals(listOf("액션"), ohli.genres)
        assertEquals(3, ohli.extraFilters.size)
    }
    @Test fun incompatibleAndInvalidFiltersAreRejected() {
        val facets = DesktopCatalogTaxonomy.miruro()
        for (extras in listOf(mapOf("scoreMin" to "101"), mapOf("scoreMin" to "NaN"), mapOf("scoreMin" to "80", "scoreMax" to "20"), mapOf("status" to "NOT_YET_RELEASED", "scoreMin" to "0"), mapOf("unsupported" to "yes"))) {
            assertFails { DesktopCatalogTaxonomy.validate("miruro", BrowseFilter(extras = extras), facets) }
        }
        DesktopCatalogTaxonomy.validate("miruro", BrowseFilter(extras = mapOf("scoreMin" to "0", "scoreMax" to "100", "adult" to "false")), facets)
        val re = Json.parseToJsonElement("""{"facets":{"tags":{"Magic":1}}}""").jsonObject
        assertFails { DesktopCatalogTaxonomy.validate("reanime", BrowseFilter(sort = "updated", extras = mapOf("tag" to "Magic")), SourceFilters(extraFilters = DesktopCatalogTaxonomy.reanime(re))) }
    }
    @Test fun reanimeSendsExtraFiltersWithYearSort() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.parameters["facets"] != null) respond("""{"facets":{"genres":{"Action":2},"format":{"TV":2},"tags":{"Magic":1}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else {
                assertEquals("year_desc", request.url.parameters["sort"])
                assertEquals("Magic", request.url.parameters["tag"]); assertEquals("JP", request.url.parameters["country"])
                assertEquals("Kyoto Animation", request.url.parameters["studio"])
                respond("""{"results":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val repository = SourceRepository(client)
        try { repository.catalog("reanime", 1, BrowseFilter(sort = "year", extras = mapOf("tag" to "Magic", "country" to "JP", "studio" to "Kyoto Animation"))) } finally { repository.close() }
    }
    @Test fun animenosubSendsStudioStatusSubAndExplicitOrder() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.parameters["page"] == null) respond("""<input name="genre[]" value="fantasy"><input name="studio[]" value="kyoto"><input name="status" value="upcoming"><input name="sub" value="raw"><input name="order" value="title">""", headers = headersOf(HttpHeaders.ContentType, "text/html"))
            else {
                assertEquals("kyoto", request.url.parameters["studio[0]"]); assertEquals("upcoming", request.url.parameters["status"])
                assertEquals("raw", request.url.parameters["sub"]); assertEquals("title", request.url.parameters["order"])
                respond("<html></html>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
            }
        })
        val repository = SourceRepository(client)
        try { repository.catalog("animenosub", 1, BrowseFilter(sort = "popular", extras = mapOf("studio" to "kyoto", "status" to "upcoming", "sub" to "raw", "order" to "title"))) } finally { repository.close() }
    }
    @Test fun ohliFiltersUseRootSearchAndNextPageMetadata() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/", request.url.encodedPath)
            assertEquals("극장판", request.url.parameters["types[0]"]); assertEquals("1990s", request.url.parameters["years[0]"])
            assertEquals("액션", request.url.parameters["genres[0]"]); assertEquals("2", request.url.parameters["page"])
            respond("""<script data-page="app" type="application/json">{"props":{"comics":{"data":[{"slug":"movie","title":"Movie","category":"극장판","metadata":{"info_rows":[["상영일","1995-01-01"]]}}],"next_page_url":"/?page=3"}}}</script>""", headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        try {
            val page = repository.catalog("ohli24", 2, BrowseFilter(genres = listOf("액션"), year = "1990s", format = "Movie"))
            assertEquals("1995", page.items.single().year); assertTrue(page.hasMore)
        } finally { repository.close() }
    }
}
