package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class FullParityRegressionTest {
    @Test fun catalogSortExcludesUnreleasedAndRetainsSourceOrderOnTies() {
        val first = Anime(id = "1", title = "Z", year = "2025", score = 8.0, popularity = 10)
        val second = first.copy(id = "2", title = "A")
        val popular = first.copy(id = "3", popularity = 20)
        val unreleased = first.copy(id = "4", status = "Not yet released", availableEpisodes = 0)
        val future = first.copy(id = "5", year = "2027", availableEpisodes = 1)
        val values = listOf(first, second, popular, unreleased, future)
        assertEquals(listOf("3", "1", "2"), DesktopAnimeMetadata.sortedCatalog(values, "year", 20261010).map { it.id })
        assertEquals(listOf("3", "1", "2", "4", "5"), DesktopAnimeMetadata.sortedCatalog(values, "score", 20261010).map { it.id })
    }
    @Test fun nativeIDsChooseExactTitleAndYearAndRetryFailedLookup() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            if (calls == 1) respond("{}", HttpStatusCode.ServiceUnavailable)
            else respond("""{"data":{"Page":{"media":[{"id":1,"idMal":11,"seasonYear":2024,"title":{"native":"日本語！"}},{"id":2,"idMal":22,"seasonYear":2025,"title":{"native":"日本語!"}}]}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = true }
        try {
            val repository = DesktopSourceRepository(client)
            assertNull(repository.lookupNativeIDs("日本語！", "2025"))
            assertEquals(2 to 22, repository.lookupNativeIDs("日本語！", "2025"))
            assertEquals(2 to 22, repository.lookupNativeIDs("日本語！", "2025"))
            assertEquals(2, calls)
            assertNotEquals(DesktopTitleRules.compareKey("作品 Part 1"), DesktopTitleRules.compareKey("作品 Part 2"))
        } finally { client.close() }
    }
    @Test fun koreanMetadataRetainsEpisodeDatesCountsAndMovieFallback() {
        val anime = Anime(id = "1", title = "작품", detailUrl = "https://www.ohli24.net/1/series.html")
        val detail = DesktopSourceParser.koreanDetail("""<ul class="article-box-meta"><li><span>원제:</span><span>日本語</span></li><li><span>방영일:</span><span>2025-01-01</span></li><li><span>총화수:</span><span>12화</span></li></ul><div class="eps-list"><div class="eps-item"><a href="/1/episode.html">2화<span class="eps-date">2025-01-08</span></a></div></div>""", anime, "ohli24")
        assertEquals("2025-01-08", detail.anime.episodes.single().airedDate)
        assertEquals(12, detail.anime.totalEpisodes)
        assertEquals(2, detail.anime.availableEpisodes)
        assertEquals(anime.detailUrl, DesktopSourceParser.koreanDetail("", anime, "ohli24").anime.episodes.single().videoUrl)
    }
    @Test fun geminiQuotaUsesRetryInfoAndNeverWaitsOnDailyOrLongLimits() {
        assertEquals(2.5, GeminiRetryPolicy.waitSeconds("{}", 0))
        assertEquals(10.0, GeminiRetryPolicy.waitSeconds("{}", 2))
        assertEquals(12.5, GeminiRetryPolicy.waitSeconds("""{"error":{"details":[{"retryDelay":"12.5s"}]}}""", 0))
        assertEquals(5.0, GeminiRetryPolicy.waitSeconds("""{"error":{"message":"Please retry in 5 s"}}""", 0))
        assertNull(GeminiRetryPolicy.waitSeconds("""{"error":{"details":[{"violations":[{"quotaId":"GenerateRequestsPerDay"}]}]}}""", 0))
        assertNull(GeminiRetryPolicy.waitSeconds("""{"error":{"details":[{"retryDelay":"121s"}]}}""", 0))
    }
    @Test fun qwenAliasesPrecedeDatedModelsAndModelDefaultsMatchDesktop() {
        assertEquals(listOf("qwen-flash", "qwen-plus", "qwen-plus-2026"), CloudModelRules.sorted("qwen", listOf("qwen-plus-2026", "qwen-plus", "qwen-flash")))
        assertEquals(listOf("picked", "qwen-plus", "qwen-plus-2026", "qwen-flash"), CloudModelRules.chain("qwen", "picked", listOf("qwen-plus-2026", "qwen-flash", "qwen-plus")))
        assertEquals("gpt-6-mini", CloudModelRules.default("openai", listOf("gpt-6-mini-preview", "gpt-6-mini")))
        assertEquals(listOf("gemini-3-flash"), CloudModelRules.sorted("gemini", listOf("gemini-flash-latest", "gemini-3-flash")))
    }
    @Test fun geminiRemembersSimplerShapeAndDoesNotSendThoughtsToSubtitleParser() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Get) return@MockEngine respond("""{"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            requests++
            val body = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
            val generation = body.obj("generationConfig")
            assertEquals("ARRAY", generation.obj("responseSchema").text("type"))
            if (requests == 1) {
                assertEquals("0", generation.obj("thinkingConfig").text("thinkingBudget"))
                respond("{}", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                assertNull(generation["thinkingConfig"])
                respond("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"thinking"},{"text":"[{\"i\":0,\"t\":\"번역\"}]"}]}}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) { expectSuccess = true }
        val translator = CloudTranslator(client)
        try {
            val config = TranslationConfig("gemini", "fixture", "gemini-2.5-flash")
            assertEquals(listOf("번역"), translator.translate(listOf("一行"), config))
            assertEquals(listOf("번역"), translator.translate(listOf("二行"), config))
            assertEquals(3, requests)
        } finally { translator.close() }
    }
}
