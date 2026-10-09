package com.lilac.anime.shared

import com.lilac.anime.shared.ported.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.math.*

class PortedFeaturesTest {
    @Test fun referenceTableResolvesMetadataAndEpisodeCount() {
        val detail = """{"nodes":[null,{"data":[{"anime":1},{"anime_id":2,"title":3,"anilist_id":5,"episodes_total":6},"sample",{"english":4},"Sample",1234,12]}]}"""
        val anime = ReAnimeHarParser.parseDetail(detail, Anime(title = "fallback"))
        assertEquals("reanime:sample", anime.id)
        assertEquals(1234, anime.anilistId)
        assertEquals(12, ReAnimeHarParser.episodeTotal(detail))
        val watch = """{"nodes":[{"data":[{"episodes":1},[2],{"number":3,"title":4},5,"Episode Five"]}]}"""
        val episodes = ReAnimeHarParser.parseWatchEpisodes(watch, anime.copy(source = "reanime"), 12)
        assertEquals(12, episodes.size)
        assertEquals("Episode Five", episodes[4].title)
        assertTrue(episodes.last().videoUrl!!.endsWith("?ep=12"))
    }
    @Test fun snapshotPreservesSuffixesRelationsAndMetadata() {
        val original = Anime(id = "test", native = "原題", genres = listOf("Action"), studios = listOf("Studio"),
            episodes = listOf(Episode("4a", 4, "4a", displayNumber = "4a")),
            reAnimeRelated = listOf(ReAnimeRelated("reanime:sequel", "Sequel")))
        assertEquals(original, AnimeSnapshot.decode(AnimeSnapshot.encode(original)))
    }
    @Test fun subtitleTranslationPreservesTimingStylesAndHeaders() {
        val ass = "[Script Info]\nTitle: Fixture\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\i1}こんにちは{\\i0}\nComment: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,unchanged"
        val translated = SubtitleTools.replace(ass, "ass", listOf("안녕하세요"))
        assertTrue(translated.contains("0:00:01.00,0:00:03.00,Default"))
        assertTrue(translated.contains("{\\i1}안녕하세요{\\i0}"))
        assertTrue(translated.endsWith("unchanged"))
        val srt = "1\n00:00:01,123 --> 00:00:02,456\n<b>こんにちは</b>\n\n2\n00:00:03,000 --> 00:00:04,000\nさようなら"
        val output = SubtitleTools.replace(srt, "srt", listOf("안녕", "잘 가"))
        assertTrue(output.contains("00:00:01,123 --> 00:00:02,456"))
        assertTrue(output.contains("<b>안녕</b>"))
        assertEquals(listOf("안녕", "잘 가"), SubtitleTools.lines(output, "srt"))
    }
    @Test fun microDvdUsesFramesAndKeepsFpsHeader() {
        val original = "{1}{1}25.000\n{25}{75}こんにちは|世界"
        val cues = SubtitleTools.cues(original, "sub")
        assertEquals(1, cues.size)
        assertEquals(1.0, cues[0].startSeconds)
        assertEquals(3.0, cues[0].endSeconds)
        val translated = SubtitleTools.replace(original, "sub", listOf("안녕\n세계"))
        assertTrue(translated.startsWith("{1}{1}25.000\n{25}{75}"))
        assertTrue(translated.endsWith("안녕|세계"))
    }
    @Test fun episodeMatchingRejectsWrongSeasonAndEpisode() {
        assertFalse(SubtitleEpisodeMatcher.matches("[Group] Anime S01E05.ass", 1))
        assertTrue(SubtitleEpisodeMatcher.matches("[Group] Anime S01E05.ass", 5))
        val posts = listOf(KairanPost("작품 1기 05화", "https://example.test/1"), KairanPost("작품 2기 05화", "https://example.test/2"))
        assertEquals("https://example.test/2", KairanPostMatcher.findBestMatch("작품 2기", 5, posts)?.post?.url)
    }
    @Test fun structuredCloudLinesAreOrderedAndMissingLinesFail() {
        val originals = listOf("a", "b")
        assertEquals(listOf("가", "나"), CloudTranslationText.parseMarked("""{"lines":[{"i":2,"t":"나"},{"i":1,"t":"가"}]}""", originals))
        assertFails { CloudTranslationText.parseMarked("<LILAC_1> 가", originals) }
    }
    @Test fun openaiUsesResponsesSchemaAndCachesIdenticalInput() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            if (request.url.encodedPath == "/v1/models") return@MockEngine respond("""{"data":[{"id":"gpt-4.1-mini"},{"id":"gpt-4.1"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            assertEquals("/v1/responses", request.url.encodedPath)
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
            assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
            assertEquals("json_schema", body["text"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(0, Json.parseToJsonElement(body.getValue("input").jsonPrimitive.content).jsonObject.getValue("lines").jsonArray.first().jsonObject.getValue("i").jsonPrimitive.int)
            respond("""{"output":[{"content":[{"type":"output_text","text":"{\"lines\":[{\"i\":0,\"t\":\"안녕\"}]}"}]}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val translator = CloudTranslator(HttpClient(engine) { expectSuccess = true })
        try {
            val config = TranslationConfig("openai", "test-key")
            assertEquals(listOf("안녕"), translator.translate(listOf("こんにちは"), config))
            assertEquals(listOf("안녕"), translator.translate(listOf("こんにちは"), config))
            assertEquals(2, calls)
        } finally { translator.close() }
    }
    @Test fun geminiQuotaSwitchesToFlashButNeverPro() = runTest {
        val called = mutableListOf<String>()
        val engine = MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.url.encodedPath.endsWith("/models")) return@MockEngine respond("""{"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-2.0-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]}]}""", HttpStatusCode.OK, json)
            called += request.url.encodedPath
            if (request.url.encodedPath.contains("2.5-flash")) respond("""{"error":{"message":"quota exhausted"}}""", HttpStatusCode.TooManyRequests, json)
            else respond("""{"candidates":[{"content":{"parts":[{"text":"[{\"i\":0,\"t\":\"안녕\"}]"}]}}]}""", HttpStatusCode.OK, json)
        }
        val translator = CloudTranslator(HttpClient(engine) { expectSuccess = true })
        try {
            assertEquals(listOf("안녕"), translator.translate(listOf("Hello"), TranslationConfig("gemini", "key", "gemini-2.5-flash")))
            assertEquals(2, called.size)
            assertTrue(called.last().contains("2.0-flash"))
            assertTrue(called.none { it.contains("pro") })
        } finally { translator.close() }
    }
    @Test fun openaiBillingQuotaDoesNotSwitchPaidModels() = runTest {
        var posts = 0
        val engine = MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.url.encodedPath == "/v1/models") return@MockEngine respond("""{"data":[{"id":"gpt-4.1-mini"},{"id":"gpt-4.1-nano"}]}""", HttpStatusCode.OK, json)
            posts++
            respond("""{"error":{"code":"insufficient_quota","message":"billing quota exceeded"}}""", HttpStatusCode.TooManyRequests, json)
        }
        val translator = CloudTranslator(HttpClient(engine) { expectSuccess = true })
        try {
            assertFailsWith<io.ktor.client.plugins.ClientRequestException> {
                translator.translate(listOf("Hello"), TranslationConfig("openai", "key", "gpt-4.1-mini"))
            }
            assertEquals(1, posts)
        } finally { translator.close() }
    }
    @Test fun driveConfirmationUsesOnlyGoogleHttpsAndPreservesHiddenValues() {
        val html = """<form action="https://drive.usercontent.google.com/download"><input name="id" value="abc"><input name="confirm" value="token &amp; value"></form>"""
        val url = Url(SubtitleTools.driveConfirmation(html, "https://drive.google.com/uc"))
        assertEquals("abc", url.parameters["id"])
        assertEquals("token & value", url.parameters["confirm"])
        assertEquals("", SubtitleTools.driveConfirmation("""<form action="https://example.test/download"></form>""", "https://drive.google.com/"))
    }
    @Test fun audioFingerprintRejectsSilenceAndProducesFiniteBins() {
        val silence = AudioFingerprint.compute(FloatArray(16000), 8000)
        assertTrue(AudioFingerprint.repeated(silence, silence).isEmpty())
        val tone = AudioFingerprint.compute(FloatArray(16000) { sin(2 * PI * 440 * it / 8000).toFloat() }, 8000)
        assertTrue(tone.isNotEmpty())
        assertEquals(0, tone.size % 32)
        assertTrue(tone.all { it.isFinite() })
    }
}
