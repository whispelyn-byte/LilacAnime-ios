package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TmdbRequestTest {
    @Test fun concurrentResolversShareSpacingAnd429Cooldown() = runTest {
        val gate = TmdbRequestGate({ testScheduler.currentTime }, { delay(it) })
        val starts = mutableListOf<Long>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
            starts += testScheduler.currentTime
            if (starts.size == 1) respond("{}", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "3"))
            else respond("{}")
            }
        } }
        try {
            listOf(TmdbTitleResolver(client, gate), TmdbTitleResolver(client, gate)).map { resolver -> async { resolver.test("key") } }.awaitAll()
            assertEquals(3, starts.size); assertTrue(starts[1] - starts[0] >= 3000, starts.toString())
            assertTrue(starts[2] - starts[1] >= 150)
        } finally { client.close() }
    }
    @Test fun transientErrorsRetryTwiceWithSanitizedDiagnostics() = runTest {
        for (kind in listOf("network", "json", "http")) {
            val gate = TmdbRequestGate({ testScheduler.currentTime }, { delay(it) })
            val starts = mutableListOf<Long>()
            val client = HttpClient(MockEngine {
                starts += testScheduler.currentTime
                when(kind) {
                    "network" -> error("https://secret.test/?api_key=private-key")
                    "json" -> respond("not JSON")
                    else -> respond("{}", HttpStatusCode.ServiceUnavailable)
                }
            })
            try {
                val failure = assertFailsWith<TmdbFailure> { TmdbTitleResolver(client, gate).test("private-key") }
                assertFalse(failure.message.orEmpty().contains("private-key")); assertEquals(3, starts.size)
                assertTrue(starts[1] - starts[0] >= 1000); assertTrue(starts[2] - starts[1] >= 2000)
            } finally { client.close() }
        }
    }
    @Test fun authenticationDoesNotRetryAndLongCooldownIsReturnedToScheduler() = runTest {
        for (status in listOf(401, 403, 404, 429)) {
            val gate = TmdbRequestGate({ testScheduler.currentTime }, { delay(it) })
            var count = 0
            val client = HttpClient(MockEngine { count++; respond("{}", HttpStatusCode.fromValue(status), headersOf("Retry-After", "120")) })
            try {
                val resolver = TmdbTitleResolver(client, gate)
                val failure = assertFailsWith<TmdbFailure> { resolver.test("key") }
                assertEquals(status, failure.status); assertEquals(1, count)
                if (status == 429) {
                    assertEquals(120000L, failure.retryAfterMs)
                    assertFailsWith<TmdbFailure> { TmdbTitleResolver(client, gate).test("other-key") }
                    assertEquals(1, count)
                }
            } finally { client.close() }
        }
    }
    @Test fun retryAfterUnderstandsHttpDatesAndTransientRecovery() = runTest {
        val gate = TmdbRequestGate({ 1700000000000 + testScheduler.currentTime }, { delay(it) })
        assertEquals(5000L, gate.retryAfter("Tue, 14 Nov 2023 22:13:25 GMT"))
        assertEquals(2500L, gate.retryAfter("2.5")); assertEquals(0L, gate.retryAfter("bad"))
        var count = 0
        val client = HttpClient(MockEngine { if (++count == 1) respond("{}", HttpStatusCode.RequestTimeout) else respond("{}") })
        try { assertEquals("TMDB 연결 성공", TmdbTitleResolver(client, gate).test("key")); assertEquals(2, count) }
        finally { client.close() }
    }
}
