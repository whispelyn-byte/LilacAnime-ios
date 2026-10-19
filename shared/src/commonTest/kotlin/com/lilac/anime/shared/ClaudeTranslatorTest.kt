package com.lilac.anime.shared

import com.lilac.anime.shared.compat.JSONObject
import com.lilac.anime.shared.ported.CloudTranslationText
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.http.content.*

// Desktop 0.5.38: Claude (the Anthropic API) as a translation API, as anthropic-api.cjs and its tests ask it.
class ClaudeTranslatorTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun models(vararg ids: String, more: Boolean = false) =
        """{"data":[${ids.joinToString(",") { """{"type":"model","id":"$it","display_name":"$it"}""" }}],"has_more":$more,"first_id":"${ids.first()}","last_id":"${ids.last()}"}"""
    private fun answer(text: String, stop: String = "end_turn", details: String = "null") =
        JSONObject().put("content", com.lilac.anime.shared.compat.JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", ""))
            .put(JSONObject().put("type", "text").put("text", text))).put("stop_reason", stop).toString().replace("\"stop_reason\"", "\"stop_details\":$details,\"stop_reason\"")
    private fun body(request: io.ktor.client.request.HttpRequestData) = JSONObject((request.body as TextContent).text)
    private fun translator(handler: MockRequestHandler) = CloudTranslator(HttpClient(MockEngine(handler)) {
        expectSuccess = true
        install(HttpTimeout) { requestTimeoutMillis = 180_000 }
    })

    @Test fun aKeyListsEveryModelItCanUseAndTheLatestOpusIsPicked() = runTest {
        val pages = mutableListOf<String>()
        val translator = translator { request ->
            assertEquals("https://api.anthropic.com/v1/models", request.url.toString().substringBefore('?'))
            assertEquals("fake-key", request.headers["x-api-key"]); assertEquals("2023-06-01", request.headers["anthropic-version"])
            pages += request.url.parameters["after_id"].orEmpty()
            if (request.url.parameters["after_id"] == null) respond(models("claude-haiku-5-5", "claude-opus-5-5", more = true), headers = json)
            else respond(models("claude-sonnet-5-5"), headers = json)
        }
        try {
            val names = translator.models(TranslationConfig("claude", "fake-key"))
            assertEquals(listOf("claude-haiku-5-5", "claude-opus-5-5", "claude-sonnet-5-5"), names)
            assertEquals(listOf("", "claude-opus-5-5"), pages)
            assertEquals("claude-opus-5-5", CloudModelRules.default("claude", names))
            assertEquals("claude-haiku-5-5", CloudModelRules.default("claude", listOf("claude-haiku-5-5")))
            assertEquals(CloudModelRules.CLAUDE_DEFAULT, CloudModelRules.default("claude", emptyList()))
        } finally { translator.close() }
    }

    @Test fun structuredJsonAtLowEffortWithACachedSystemPromptAndTheServerSideFallback() = runTest {
        var posts = 0
        val translator = translator { request ->
            if (request.method == HttpMethod.Get) return@translator respond(models("claude-opus-5-5"), headers = json)
            posts++
            assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
            assertEquals("fake-key", request.headers["x-api-key"]); assertEquals("2023-06-01", request.headers["anthropic-version"])
            assertEquals("server-side-fallback-2026-07-01", request.headers["anthropic-beta"])
            val sent = body(request)
            assertEquals("claude-opus-5-5", sent.optString("model")); assertEquals("default", sent.optString("fallbacks"))
            val output = sent.getJSONObject("output_config")
            assertEquals("low", output.optString("effort")); assertEquals("json_schema", output.getJSONObject("format").optString("type"))
            assertEquals("false", output.getJSONObject("format").getJSONObject("schema").optString("additionalProperties"))
            val system = sent.getJSONArray("system").optJSONObject(0)!!
            assertEquals("ephemeral", system.getJSONObject("cache_control").optString("type"))
            assertTrue(system.optString("text").contains("experienced Korean subtitle translator"))
            assertEquals(CloudTranslationText.jsonInput(listOf("Hello"), true), sent.getJSONArray("messages").optJSONObject(0)!!.optString("content"))
            respond(answer("""{"lines":[{"i":0,"t":"안녕"}]}"""), headers = json)
        }
        try {
            assertEquals(listOf("안녕"), translator.translate(listOf("Hello"), TranslationConfig("claude", "fake-key", "claude-opus-5-5")))
            assertEquals(1, posts)
        } finally { translator.close() }
    }

    @Test fun aModelWithoutEffortDropsItAndTheWorkingRequestIsRemembered() = runTest {
        val shapes = mutableListOf<String>()
        val translator = translator { request ->
            if (request.method == HttpMethod.Get) return@translator respond(models("claude-haiku-4-5"), headers = json)
            assertNull(request.headers["anthropic-beta"])
            val sent = body(request); assertFalse(sent.has("fallbacks"))
            val output = sent.optJSONObject("output_config")
            shapes += if (output?.has("effort") == true) "effort" else if (output != null) "schema" else "plain"
            if (output?.has("effort") == true) respond("""{"type":"error","error":{"type":"invalid_request_error","message":"effort is not supported"}}""", HttpStatusCode.BadRequest, json)
            else respond(answer("""{"lines":[{"i":0,"t":"안녕"}]}"""), headers = json)
        }
        try {
            translator.translate(listOf("Hello"), TranslationConfig("claude", "fake-key", "claude-haiku-4-5"))
            translator.translate(listOf("Bye"), TranslationConfig("claude", "fake-key", "claude-haiku-4-5"))
            assertEquals(listOf("effort", "schema", "schema"), shapes)
        } finally { translator.close() }
    }

    @Test fun aKeyOutOfCreditIsNotAskedAgainInOtherShapes() = runTest {
        var posts = 0
        val translator = translator { request ->
            if (request.method == HttpMethod.Get) return@translator respond(models("claude-opus-5-5"), headers = json)
            posts++
            respond("""{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}""", HttpStatusCode.BadRequest, json)
        }
        try {
            assertFailsWith<ClientRequestException> { translator.translate(listOf("Hello"), TranslationConfig("claude", "fake-key", "claude-opus-5-5")) }
            assertEquals(1, posts)
        } finally { translator.close() }
    }

    @Test fun aDeclinedBatchIsAnErrorNotAnEmptyTranslation() = runTest {
        val translator = translator { request ->
            if (request.method == HttpMethod.Get) return@translator respond(models("claude-opus-5-5"), headers = json)
            respond(answer("", "refusal", """{"type":"refusal","category":"cyber","explanation":null}"""), headers = json)
        }
        try {
            val failure = assertFailsWith<IllegalStateException> { translator.translate(listOf("Hello"), TranslationConfig("claude", "fake-key", "claude-opus-5-5")) }
            assertTrue(failure.message.orEmpty().contains("거절") && failure.message.orEmpty().contains("cyber"), failure.message)
        } finally { translator.close() }
    }

    @Test fun aRateLimitedModelHandsOverToAnotherOfItsFamilyOnly() = runTest {
        val asked = mutableListOf<String>()
        val translator = translator { request ->
            if (request.method == HttpMethod.Get) return@translator respond(models("claude-haiku-5-5", "claude-opus-5-5", "claude-opus-5"), headers = json)
            val model = body(request).optString("model"); asked += model
            if (model == "claude-opus-5-5") respond("""{"type":"error","error":{"type":"rate_limit_error","message":"rate limited"}}""", HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "Retry-After" to listOf("0")))
            else respond(answer("""{"lines":[{"i":0,"t":"안녕"}]}"""), headers = json)
        }
        try {
            assertEquals(listOf("안녕"), translator.translate(listOf("Hello"), TranslationConfig("claude", "fake-key", "claude-opus-5-5")))
            assertEquals("claude-opus-5", asked.last()); assertFalse("claude-haiku-5-5" in asked)
            assertEquals(300, CloudCooldown.seconds("claude", 429, "rate limited", 1000))
            assertEquals(listOf("claude-sonnet-5-5"), CloudModelRules.chain("claude", "claude-sonnet-5-5", listOf("claude-opus-5-5", "claude-haiku-5-5")))
            assertEquals("opus", CloudModelRules.claudeFamily("claude-opus-5-5")); assertEquals("sonnet", CloudModelRules.claudeFamily("claude-3-5-sonnet-20241022"))
        } finally { translator.close() }
    }
}
