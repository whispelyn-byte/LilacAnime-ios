package com.lilac.anime.shared

import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*

class SharedTest {
    @Test fun catalogHandlesNullsNumericIdsAndDuplicates() {
        val root = Json.parseToJsonElement("""{"data":[{"postid":15,"postname":null,"name":"작품","postthum":"//example.com/a.jpg"},{"postid":"15"},{"postname":"invalid"}]}""")
        val list = parseCatalog(root)
        assertEquals(1, list.size)
        assertEquals("15", list[0].id)
        assertEquals("작품", list[0].title)
        assertEquals("https://example.com/a.jpg", list[0].poster)
    }
    @Test fun episodeSuffixAndSlugArePreserved() {
        val root = Json.parseToJsonElement("""[{"id":12,"server_name":"NR","server_data":[{"name":"4a","slug":"4 a","link":"token-a"},{"name":"4","slug":"4","link":"token"}]}]""")
        val episodes = parseServers(root, "42").single().episodes
        assertEquals(listOf("4", "4a"), episodes.map { it.displayNumber })
        assertEquals("token-a", episodes.last().id)
        assertTrue(episodes.last().videoUrl!!.contains("slug=4+a") || episodes.last().videoUrl!!.contains("slug=4%20a"))
    }
    @Test fun subtitlesHaveExclusiveEndsAndOverlap() {
        val cues = Subtitles.parse("\uFEFFWEBVTT\r\n\r\n00:01.000 --> 00:03.000 align:center\r\n<b>안녕</b>\r\n\r\n2\r\n00:00:02,000 --> 00:00:04,000\r\n세계")
        assertEquals(2, cues.size)
        assertEquals("안녕\n세계", Subtitles.textAt(cues, 2.0))
        assertEquals("세계", Subtitles.textAt(cues, 3.0))
        assertEquals("", Subtitles.textAt(cues, 4.0))
        assertTrue(Subtitles.parse("1\n00:99:00 --> 00:00:01\ninvalid").isEmpty())
    }
    @Test fun repositorySearchesCatalogAndPropagatesHttpErrors() = runTest {
        val engine = MockEngine { request ->
            assertEquals("1", request.url.parameters["page"])
            assertNull(request.url.parameters["s"])
            respond("""{"data":[{"postid":"1","name":"a & b result"}],"pagination":{"total_pages":1}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repository = LinkkfRepository(HttpClient(engine) { expectSuccess = true })
        try { assertEquals("a & b result", repository.search("a & b").single().title) }
        finally { repository.close() }
        val failure = LinkkfRepository(HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }) { expectSuccess = true })
        try { assertFails { failure.home() } } finally { failure.close() }
    }
}
