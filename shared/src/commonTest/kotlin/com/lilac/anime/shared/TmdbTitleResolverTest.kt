package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TmdbTitleResolverTest {
    @Test fun desktopUsesEnglishAnimationMatchAndKoreanIdRatherThanSpinoffRanking() = runTest {
        val client = HttpClient(MockEngine { request ->
            val text = when (request.url.encodedPath) {
                "/3/search/tv" -> if (request.url.parameters["language"] == "en-US") """{"results":[{"id":1,"name":"Attack on Titan: Junior High","genre_ids":[16],"original_language":"ja"},{"id":2,"name":"Attack on Titan","genre_ids":[16],"original_language":"ja"}]}""" else """{"results":[{"id":1,"name":"진격! 거인중학교"},{"id":2,"name":"진격의 거인"}]}"""
                "/3/tv/2" -> """{"name":"진격의 거인","seasons":[]}"""
                "/3/tv/2/alternative_titles" -> """{"results":[{"iso_3166_1":"KR","title":"진격 거인"}]}"""
                else -> error("Unexpected request: ${request.url.encodedPath}")
            }
            respond(text, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { assertEquals(listOf("진격의 거인", "진격 거인"), TmdbTitleResolver(client).desktopTitles(listOf("Attack on Titan Season 2"), "fixture", false)) }
        finally { client.close() }
    }
    @Test fun namedSeasonKoreanTitleWinsAndOtherSeasonAliasesAreExcluded() = runTest {
        val client = HttpClient(MockEngine { request ->
            val english = request.url.parameters["language"] == "en-US"
            val text = when (request.url.encodedPath) {
                "/3/search/tv" -> if (english) """{"results":[{"id":2,"name":"Rascal Does Not Dream of Bunny Girl Senpai","genre_ids":[16],"original_language":"ja"}]}""" else """{"results":[{"id":2,"name":"청춘 돼지는 바니걸 선배의 꿈을 꾸지 않는다"}]}"""
                "/3/tv/2" -> if (english) """{"seasons":[{"season_number":2,"name":"Rascal Does Not Dream of Santa Claus"}]}""" else """{"name":"청춘 돼지는 바니걸 선배의 꿈을 꾸지 않는다","seasons":[{"season_number":2,"name":"청춘 돼지는 산타클로스의 꿈을 꾸지 않는다"}]}"""
                "/3/tv/2/alternative_titles" -> """{"results":[{"iso_3166_1":"KR","title":"청춘 돼지는 란도셀 소녀의 꿈을 꾸지 않는다"}]}"""
                else -> error("Unexpected request")
            }
            respond(text, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { assertEquals(listOf("청춘 돼지는 산타클로스의 꿈을 꾸지 않는다"), TmdbTitleResolver(client).desktopTitles(listOf("Rascal Does Not Dream of Santa Claus"), "fixture", false)) }
        finally { client.close() }
    }
    @Test fun metadataReportsTmdbFailureSeparatelyFromSuccessfulMiss() = runTest {
        for (failure in listOf(true, false)) {
            val client = HttpClient(MockEngine { request ->
                when (request.url.host) {
                    "api.themoviedb.org" -> respond(if (failure) "{}" else """{"results":[]}""", if (failure) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    "graphql.anilist.co" -> respond("""{"data":{"Page":{"media":[]}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    else -> error("Unexpected host")
                }
            })
            try {
                val result = DesktopMetadataRepository(client).resolve(Anime(title = "Unknown Title"), "fixture", false)
                assertEquals("", result.korean)
                assertEquals(failure, result.titleLookupFailure.isNotEmpty())
            } finally { client.close() }
        }
    }
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
