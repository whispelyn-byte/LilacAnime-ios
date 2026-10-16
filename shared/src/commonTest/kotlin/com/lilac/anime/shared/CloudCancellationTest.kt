package com.lilac.anime.shared

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*

@OptIn(ExperimentalCoroutinesApi::class)
class CloudCancellationTest {
    @Test fun cancellingEachProviderInterruptsRetryDelay() = runTest {
        for ((provider, model) in listOf("gemini" to "gemini-2.5-flash", "openai" to "gpt-5-mini", "qwen" to "qwen-plus", "deepl" to "")) {
            var posts = 0
            val requested = CompletableDeferred<Unit>()
            val client = HttpClient(MockEngine { request ->
                if (request.method == HttpMethod.Get) respond(if (provider == "gemini") """{"models":[{"name":"models/$model","supportedGenerationMethods":["generateContent"]}]}""" else """{"data":[{"id":"$model"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                else { posts++; requested.complete(Unit); respond("""{"error":{"message":"Rate limit exceeded","details":[{"retryDelay":"60s"}]}}""", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType to listOf("application/json"), "Retry-After" to listOf("60"))) }
            }) { expectSuccess = true }
            val translator = CloudTranslator(client)
            try {
                val job = launch { translator.translate(listOf("Hello"), TranslationConfig(provider, "fake-key", model)) }
                requested.await(); runCurrent(); assertEquals(1, posts, provider)
                job.cancelAndJoin(); advanceUntilIdle()
                assertTrue(job.isCancelled); assertEquals(1, posts, provider)
            } finally { translator.close() }
        }
    }
    @Test fun emptyOrIneligibleQwenModelListFailsValidation() = runTest {
        for (body in listOf("""{"data":[]}""", """{"data":[{"id":"qwen-vl-plus"}]}""")) {
            val translator = CloudTranslator(HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }))
            try { assertFailsWith<IllegalArgumentException> { translator.models(TranslationConfig("qwen", "fake-key")) } } finally { translator.close() }
        }
    }
}
