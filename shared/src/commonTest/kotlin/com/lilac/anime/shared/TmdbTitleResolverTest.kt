package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TmdbTitleResolverTest {
    @Test fun titleAliasesAndBearerStayOnTmdb() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("api.themoviedb.org", request.url.host)
            assertEquals(URLProtocol.HTTPS, request.url.protocol)
            assertEquals("Bearer example.read.token", request.headers["Authorization"])
            assertNull(request.url.parameters["api_key"])
            assertEquals("ko-KR", request.url.parameters["language"])
            val query = request.url.parameters["query"].orEmpty(); requests.add(query)
            respond(if (query == "Known Title") """{"results":[{"media_type":"tv","name":"관련 없는 인기 작품","original_name":"Other Title","popularity":9999},{"media_type":"tv","name":"알려진 작품","original_name":"Known Title","genre_ids":[16],"original_language":"ja"}]}""" else """{"results":[]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            assertEquals("알려진 작품", TmdbTitleResolver(client).resolve(listOf("Unknown", "Known Title"), "example.read.token"))
            assertEquals(listOf("Unknown", "Known Title"), requests)
        } finally { client.close() }
    }
    @Test fun apiKeyErrorsDoNotExposeCredentialAndNoKeyDoesNotRequest() = runTest {
        var count = 0
        val client = HttpClient(MockEngine { request ->
            count++; assertEquals("private-test-key", request.url.parameters["api_key"])
            respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = true }
        try {
            val resolver = TmdbTitleResolver(client)
            assertNull(resolver.resolve(listOf("Title"), "")); assertEquals(0, count)
            val error = assertFailsWith<IllegalStateException> { resolver.test("private-test-key") }
            assertEquals("TMDB HTTP 401", error.message)
            assertFalse(error.message.orEmpty().contains("private-test-key"))
        } finally { client.close() }
    }
    @Test fun castSubtitleIncludesOffsetAndEscapesText() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n한글 & 자막\n"
        val vtt = SubtitleTools.castVtt(srt, "srt", 0.5)
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:02.500"))
        assertTrue(vtt.contains("한글 &amp; 자막"))
        assertEquals("WEBVTT\n\n", SubtitleTools.castVtt(srt, "srt", -3.0))
    }
}
