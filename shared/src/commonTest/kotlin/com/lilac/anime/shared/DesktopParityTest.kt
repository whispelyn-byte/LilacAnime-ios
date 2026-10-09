package com.lilac.anime.shared

import kotlin.test.*
import kotlin.math.sin
import kotlinx.serialization.json.*
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.io.encoding.Base64

class DesktopParityTest {
    @Test fun localPromptsAndSamplingMatchActualDesktopRequests() {
        for (test in desktopOracle.list("localPrompts").filterIsInstance<JsonObject>()) {
            val prompt = DesktopTranslationPrompt.build(test.text("model"), test.text("source"), test.text("before"), test.text("terms"), test.text("cast"))
            assertEquals(test.text("prompt"), prompt.prompt, test.text("model"))
            assertEquals(test.text("temperature").toDouble(), prompt.temperature)
            assertEquals(test.text("topP").toDouble(), prompt.topP); assertEquals(test.number("topK"), prompt.topK)
            assertEquals(test.text("repetition").toDouble(), prompt.repetition)
        }
        for (test in desktopOracle.list("localKinds").filterIsInstance<JsonObject>())
            assertEquals(test.text("expected"), DesktopTranslationPrompt.kind(test.text("model"), test.text("template")))
    }
    @Test fun localCleanupContextTermsAndCastMatchDesktop() {
        for (test in desktopOracle.list("localClean").filterIsInstance<JsonObject>())
            assertEquals(test.text("expected"), DesktopTranslationPrompt.clean(test.text("output"), test.text("original")))
        for (test in desktopOracle.list("localTerms").filterIsInstance<JsonObject>()) {
            val cast = test.list("characters").filterIsInstance<JsonObject>().map { AnimeCharacter(it.text("name"), it.text("native"), it.text("first"), it.text("last"), it.text("gender")) }
            assertEquals(test.text("expected"), AnimeGlossary.localTerms(test.text("source"), cast, ""))
            assertEquals(test.text("cast"), AnimeGlossary.localCast(cast))
        }
        assertTrue(DesktopTranslationPrompt.needsRetry("先輩です")); assertTrue(DesktopTranslationPrompt.needsRetry(""))
        assertFalse(DesktopTranslationPrompt.needsRetry("선배"))
        assertEquals(54, DesktopTranslationPrompt.maxTokens("はい")); assertEquals(512, DesktopTranslationPrompt.maxTokens("長".repeat(200)))
    }
    @Test fun linkaniBurnedKoreanIsDisabledOnlyForOwnExternalSubtitle() {
        fun stream(sub: String) = DesktopSourceParser.linkaniStreams("<script>var player_aaaa={\"url\":\"https://video.test/h/aa/bb/0123456789abcdef/video.m3u8\",\"subtitle_url\":\"$sub\"};</script>").single()
        assertTrue(stream("").burnedKorean)
        assertTrue(stream("https://video.test/s/aa/bb/fedcba9876543210/ko.ass").burnedKorean)
        val own = stream("https://video.test/s/aa/bb/0123456789abcdef/ko.ass")
        assertFalse(own.burnedKorean); assertEquals("ko", own.subtitles.single().language)
    }
    @Test fun tmdbQueriesNamesAndFranchiseAliasesMatchDesktop() {
        for (test in desktopOracle.list("tmdbQueries").filterIsInstance<JsonObject>()) {
            assertEquals(test.list("expected").map { it.jsonPrimitive.content }, DesktopTmdbRules.queries(test.list("input").map { it.jsonPrimitive.content }))
        }
        for (test in desktopOracle.list("tmdbSiblings").filterIsInstance<JsonObject>()) {
            val input = test.list("input").map { it.jsonPrimitive.content }
            assertEquals(test["expected"]!!.jsonPrimitive.boolean, DesktopTmdbRules.sibling(input[0], input[1]))
        }
        for (test in desktopOracle.list("seasonNames").filterIsInstance<JsonObject>()) {
            val input = test.list("input").map { it.jsonPrimitive.content }
            assertEquals(test.text("expected"), DesktopTitleRules.seasonal(input[0], input[1], input[2]))
        }
    }
    @Test fun titleKeysSeasonNumbersAndCleanedNamesMatchDesktop() {
        for (test in desktopOracle.list("titles").filterIsInstance<JsonObject>()) {
            val title = test.text("input")
            assertEquals(test.text("key"), DesktopTitleRules.key(title), title)
            assertEquals(test.number("season"), DesktopTitleRules.season(title), title)
            assertEquals(test.text("clean"), DesktopTitleRules.clean(title), title)
        }
    }
    @Test fun communityLinksMatchDesktopEpisodeBundlesFontsAndShiftedNumbering() {
        for (test in desktopOracle.list("community").filterIsInstance<JsonObject>()) {
            val input = test.obj("input"); val expected = test.obj("expected")
            val result = DesktopCommunity.links(CommunityPost(input.text("title"), "https://fixture.test/post", input.text("html")), input.number("episode")!!)
            assertEquals(expected.list("links").map { it.jsonPrimitive.content }, result.links, input.toString())
            assertEquals(expected.number("episode"), result.episode)
            assertEquals(expected["strict"]?.jsonPrimitive?.booleanOrNull ?: false, result.strict)
        }
    }
    @Test fun desktopCloudResponsePreservesPartialAndRejectsStrayIds() {
        assertEquals(listOf("가", "", "다"), com.lilac.anime.shared.ported.CloudTranslationText.parseDesktop("""{"lines":[{"i":2,"t":"다"},{"i":0,"t":"가"},{"i":99,"t":"unrelated"}]}""", listOf("a", "b", "c")))
        assertTrue(DesktopCloudPrompt.build("Anime: 작품", true).contains("Anime: 작품"))
        assertTrue(DesktopCloudPrompt.build("", false).contains("experienced Korean subtitle translator"))
    }
    @Test fun bilingualChineseLinesAreBlankedOnlyAfterManyJapanesePairs() {
        fun document(count: Int) = "WEBVTT\n\n" + (0 until count).joinToString("\n\n") { i -> "$i\n00:00:01.000 --> 00:00:03.000\n日本語です\n\n${i}c\n00:00:01.000 --> 00:00:03.000\n中文字符" }
        assertEquals(20, SubtitleTools.translationBlanks(document(20), "vtt").size)
        assertTrue(SubtitleTools.translationBlanks(document(19), "vtt").isEmpty())
    }
    @Test fun releaseDatesAndEpisodeLabelsMatchActualDesktopOutput() {
        for (test in desktopOracle.list("metadata").filterIsInstance<JsonObject>()) {
            val input = test.obj("input")
            val anime = Anime(year = input.text("year"), season = input.text("season"), airedDate = input.text("aired").ifBlank { input.text("startedOn") }, availableEpisodes = input.number("availableEpisodes"), totalEpisodes = input.number("totalEpisodes"))
            assertEquals(test.number("release"), DesktopAnimeMetadata.releaseDate(anime), input.toString())
            assertEquals(test.text("label"), DesktopAnimeMetadata.episodeLabel(anime), input.toString())
        }
    }
    @Test fun svelteTablesMatchActualDesktopDecoder() {
        for (test in desktopOracle.list("svelte").filterIsInstance<JsonObject>()) {
            assertEquals(test["expected"], DesktopCatalogUpdates.decodeData(test.getValue("input").jsonArray))
        }
    }
    private fun noise(frames: Int, seed: Int): FloatArray {
        var x = seed
        return FloatArray(frames * 32) { x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); (x / 2147483648.0).toFloat() }
    }
    @Test fun repeatedRegionMatchesDesktopIncludingBoundaryExtension() {
        for (test in desktopOracle.list("regions").filterIsInstance<JsonObject>()) {
            val a = noise(test.number("frames")!!, 123); val b = noise(test.number("frames")!!, 456)
            val from = test.number("from")!!; val to = test.number("to")!!; val count = test.number("length")!!
            a.copyInto(b, to * 32, from * 32, (from + count) * 32)
            val result = DesktopAudioFingerprint.region(a, b)
            if (test["expected"] == JsonNull) assertNull(result)
            else {
                val expected = test.obj("expected"); assertNotNull(result)
                assertEquals(expected.text("startTime").toDouble(), result.startSeconds, 1e-8)
                assertEquals(expected.text("endTime").toDouble(), result.endSeconds, 1e-8)
                assertEquals(expected.text("score").toDouble(), result.score, 1e-8)
            }
        }
    }
    @Test fun logMelValuesMatchActualDesktopFFT() {
        val expected = desktopOracle.obj("pcm")
        val fingerprint = AudioFingerprint.compute(FloatArray(24000) { (sin(it * .021) + .3 * sin(it * .057)).toFloat() }, 8000)
        assertEquals(expected.number("frames")!! * 32, fingerprint.size)
        expected.list("first").forEachIndexed { index, value -> assertEquals(value.jsonPrimitive.double, fingerprint[index].toDouble(), 1e-5, "bin $index") }
    }
    @Test fun consensusRejectsSingleWeakCandidateButAcceptsTwoNearby() {
        assertNull(DesktopAudioFingerprint.consensus(listOf(AudioMatch(20.0, 80.0, .89))))
        assertNotNull(DesktopAudioFingerprint.consensus(listOf(AudioMatch(20.0, 80.0, .9))))
        val result = DesktopAudioFingerprint.consensus(listOf(AudioMatch(20.0, 80.0, .85), AudioMatch(30.0, 90.0, .83), AudioMatch(100.0, 160.0, .82)), 1000.0)
        assertEquals(1025.0, result?.startSeconds); assertEquals(1085.0, result?.endSeconds)
    }
    @Test fun miruroScheduleExcludesFutureAndLast75Minutes() {
        val rows = Json.parseToJsonElement("""[{"anime_id":"a","air_at":"2026-10-08T00:00:00Z"},{"anime_id":"a","air_at":"2026-10-08T01:00:00Z"},{"anime_id":"a","air_at":"2026-10-08T03:00:00Z"},{"anime_id":"b","air_at":"2026-10-08T05:00:00Z"},{"anime_id":"c","air_at":"bad"}]""").jsonArray
        assertEquals(mapOf("a" to "2026-10-08T01:00:00Z"), DesktopCatalogUpdates.latestMiruro(rows, "2026-10-08T04:00:00Z"))
    }
    @Test fun serversPreserveDesktopHdOrderAndRawLabels() {
        val reanime = DesktopSourceParser.reanimeServers(Json.parseToJsonElement("""{"episode_links":[{"serverName":"HD-1","dataLink":"https://flixcloud.cc/e/1"},{"serverName":"HD-2","dataLink":"https://flixcloud.cc/e/2"},{"serverName":"HD-3","dataLink":"https://ads.test/"}]}""").jsonObject)
        assertEquals(listOf("HD-2", "HD-1"), reanime.map { it.label })
        val iframe = Base64.encode("<iframe src='https://video.test/e/raw'></iframe>".encodeToByteArray())
        assertEquals("raw", DesktopSourceParser.animenosubServers("<option value='$iframe'>RAW - Moon</option>", "https://animenosub.to/episode/").single().kind)
    }
    @Test fun geminiModelListFollowsNextPageAndKeepsTextModels() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("1000", request.url.parameters["pageSize"]); calls++
            respond(if (request.url.parameters["pageToken"] == null) """{"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"next"}"""
                else """{"models":[{"name":"models/gemini-2.5-flash-lite","supportedGenerationMethods":["generateContent"]},{"name":"models/text-embedding","supportedGenerationMethods":["embedContent"]}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val translator = CloudTranslator(client)
        try { assertEquals(setOf("gemini-2.5-flash", "gemini-2.5-flash-lite"), translator.models(TranslationConfig("gemini", "fixture-key")).toSet()); assertEquals(2, calls) } finally { translator.close() }
    }
}
