package com.lilac.anime.shared

import com.lilac.anime.shared.ported.CloudTranslationText
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class TesterRegressionTest {
    @Test fun descriptionsRemoveExecutableContentAndNestedEncodedMarkup() {
        val input = "<style>.ad { display: block }</style><script>window.ad()</script><p>작품 &amp; 소개<br>다음 줄</p>"
        assertEquals("작품 & 소개\n다음 줄", DisplayText.plain(input))
        assertEquals("소개", DisplayText.plain("&amp;lt;p&amp;gt;소개&amp;lt;/p&amp;gt;"))
        assertEquals("작품 이야기", DisplayText.plain("&lt;script&gt;alert(1)&lt;/script&gt;작품 이야기"))
        assertEquals("A & B", DisplayText.plain("A &amp; B"))
    }
    @Test fun missingIndexedLinesPreserveIdsWithoutShiftingDialogue() {
        assertEquals(listOf("", "둘", ""), CloudTranslationText.parseDesktop("""[{"i":1,"t":"둘"},{"i":99,"t":"잘못된 줄"}]""", listOf("one", "two", "three")))
    }
    @Test fun malformedBatchIsSplitAndSingleAnswersKeepTheirPositions() = runTest {
        val sizes = mutableListOf<Int>()
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Get) return@MockEngine respond("""{"data":[{"id":"gpt-4.1-mini"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            val body = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
            val input = Json.parseToJsonElement(body.getValue("input").jsonPrimitive.content).jsonObject.getValue("lines").jsonArray
            sizes += input.size
            val answer = if (input.size > 1) "{broken json" else """[{"i":0,"t":"번역 ${input[0].jsonObject.getValue("t").jsonPrimitive.content}"}]"""
            respond(buildJsonObject { put("output_text", answer) }.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val translator = CloudTranslator(client)
        try {
            assertEquals(listOf("번역 a", "번역 b"), translator.translate(listOf("a", "b"), TranslationConfig("openai", "test", "gpt-4.1-mini")))
            assertEquals(listOf(2, 1, 1), sizes)
        } finally { translator.close() }
    }
    @Test fun shortDeeplArrayNeverAssignsTranslationToWrongCue() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            val input = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject.getValue("text").jsonArray
            val answer = if (input.size > 1) """{"translations":[{"text":"잘못 묶인 답"}]}""" else """{"translations":[{"text":"번역 ${input[0].jsonPrimitive.content}"}]}"""
            respond(answer, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val translator = CloudTranslator(client)
        try {
            assertEquals(listOf("번역 a", "번역 b"), translator.translate(listOf("a", "b"), TranslationConfig("deepl", "test")))
            assertEquals(3, requests)
        } finally { translator.close() }
    }
    @Test fun malformedResponsesHaveBoundedRetriesAndRemainRetryable() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond("""{"translations":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val translator = CloudTranslator(client)
        try {
            val config = TranslationConfig("deepl", "test")
            assertFails { translator.translate(List(8) { "line $it" }, config) }
            assertEquals(7, requests)
            assertFails { translator.translate(listOf("line 0"), config) }
            assertEquals(8, requests) // No completed cache entry for missing translations.
        } finally { translator.close() }
    }
}
