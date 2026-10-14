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
import com.lilac.anime.shared.compat.JSONObject
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
            // Two different keys: the same request asked twice would be shared as one.
            listOf(TmdbTitleResolver(client, gate), TmdbTitleResolver(client, gate)).mapIndexed { index, resolver -> async { resolver.test("key$index") } }.awaitAll()
            assertEquals(3, starts.size); assertTrue(starts[1] - starts[0] >= 3000, starts.toString())
            assertTrue(starts[2] - starts[1] >= 40)
        } finally { client.close() }
    }
    // desktop 0.5.20 tmdb-client.cjs: screen requests first, 40 ms apart; catalog index lookups 150 ms apart behind them.
    @Test fun screenRequestsAreSpacedBy40msAndBackgroundOnesBy150ms() = runTest {
        val gate = TmdbRequestGate({ testScheduler.currentTime }, { delay(it) })
        val starts = mutableListOf<Long>()
        (0 until 12).map { index -> async { gate.shared("screen $index", false) { ticket -> gate.takeTurn(ticket); starts += testScheduler.currentTime; JSONObject() } } }.awaitAll()
        assertEquals(12, starts.size)
        for (index in 1 until starts.size) assertTrue(starts[index] - starts[index - 1] >= 40, starts.toString())
        assertTrue(starts[11] - starts[0] < 11 * 100, starts.toString())
        val back = mutableListOf<Long>()
        (0 until 6).map { index -> async { gate.shared("back $index", true) { ticket -> gate.takeTurn(ticket); back += testScheduler.currentTime; JSONObject() } } }.awaitAll()
        for (index in 1 until back.size) assertTrue(back[index] - back[index - 1] >= 150, back.toString())
    }
    @Test fun screenRequestsGoAheadOfWaitingBackgroundLookups() = runTest {
        val gate = TmdbRequestGate({ testScheduler.currentTime }, { delay(it) })
        val order = mutableListOf<String>()
        fun ask(id: String, background: Boolean) = async { gate.shared(id, background) { ticket -> gate.takeTurn(ticket); order += id; JSONObject() } }
        val waiting = (0 until 5).map { ask("bg$it", true) } + ask("screen", false)
        waiting.awaitAll()
        assertTrue(order.indexOf("screen") <= 1, order.toString())
        // A screen request for a background lookup still waiting moves it ahead instead of asking twice.
        val again = TmdbRequestGate({ testScheduler.currentTime }, { delay(it) })
        val calls = mutableListOf<String>()
        fun later(id: String, background: Boolean) = async { again.shared(id, background) { ticket -> again.takeTurn(ticket); calls += id; JSONObject() } }
        ((0 until 5).map { later("bg$it", true) } + later("bg4", false)).awaitAll()
        assertEquals(1, calls.count { it == "bg4" }); assertTrue(calls.indexOf("bg4") <= 1, calls.toString())
    }
    @Test fun theSameRequestSharesOneCallAndAScreenAnswerIsReused() = runTest {
        val gate = TmdbRequestGate({ testScheduler.currentTime }, { delay(it) })
        var calls = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { calls++; respond("""{"id":$calls}""") }
        } }
        try {
            val resolver = TmdbTitleResolver(client, gate)
            listOf(async { resolver.test("key") }, async { resolver.test("key") }).awaitAll()
            assertEquals(1, calls)
            resolver.test("key"); assertEquals(1, calls)
            resolver.test("other"); assertEquals(2, calls)
            delay(6 * 60 * 60 * 1000L); resolver.test("key"); assertEquals(3, calls)
            gate.shared("back", true) { gate.takeTurn(it); calls++; JSONObject() }; gate.shared("back", true) { gate.takeTurn(it); calls++; JSONObject() }
            assertEquals(5, calls)
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
