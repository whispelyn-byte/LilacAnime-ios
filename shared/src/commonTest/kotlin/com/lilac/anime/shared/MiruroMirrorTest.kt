package com.lilac.anime.shared
import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*

class MiruroMirrorTest {
    @Test fun connectionAndServerErrorsRetryCompatibleMirrorWithSameQuery() = runTest {
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            calls.add(request.url.host)
            assertEquals("15", request.url.parameters["limit"])
            assertEquals("https://" + request.url.host + "/", request.headers["Referer"])
            if (calls.size == 1) respond("offline", HttpStatusCode.ServiceUnavailable)
            else respond(Base64.decode("cuJ6dXJvL2Nhi8o6JS5BICbH+OGCZmHgBd4xbG1pcg=="))
        })
        try {
            val result = DesktopSourceRepository(client).miruro("anime", mapOf("limit" to "15"))
            assertTrue(result.list("data").isEmpty())
            assertEquals(listOf("www.miruro.to", "www.miruro.tv"), calls)
        } finally { client.close() }
    }
    @Test fun forbiddenResponsesRetryCompatibleMirror() = runTest {
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            calls.add(request.url.host)
            if (calls.size == 1) respond("Just a moment /cdn-cgi/challenge-platform/", HttpStatusCode.Forbidden)
            else respond(Base64.decode("cuJ6dXJvL2Nhi8o6JS5BICbH+OGCZmHgBd4xbG1pcg=="))
        })
        try {
            val result = DesktopSourceRepository(client).miruro("anime")
            assertTrue(result.list("data").isEmpty())
            assertEquals(listOf("www.miruro.to", "www.miruro.tv"), calls)
        } finally { client.close() }
    }
    @Test fun invalidQueriesDoNotRetryOtherMirrors() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("bad query", HttpStatusCode.BadRequest) })
        try {
            assertFailsWith<IllegalStateException> { DesktopSourceRepository(client).miruro("anime") }
            assertEquals(1, calls)
        } finally { client.close() }
    }
}
