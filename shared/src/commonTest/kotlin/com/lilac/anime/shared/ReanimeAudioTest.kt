package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ReanimeAudioTest {
    private fun server(id: String, name: String, type: String? = null) = buildJsonObject {
        put("serverName", name); put("dataLink", "https://flixcloud.cc/e/$id")
        if (type != null) put("dataType", type)
    }
    private fun root(vararg links: JsonObject) = buildJsonObject { put("servers", JsonArray(links.toList())) }

    @Test fun originalAudioIsFilteredBeforeHdRankingAndDeduplication() {
        val result = DesktopSourceParser.reanimeServers(root(
            server("same", "HD-2", "dub"), server("sub1", "HD-1", "sub"),
            server("same", "HD-2", "sub"), server("legacy", "HD-1"),
            server("named-dub", "HD-2 DUB"), server("dubbed", "HD-2", "DUBBED")
        ))
        assertEquals(listOf("same", "sub1", "legacy"), result.map { it.url.substringAfterLast('/') })
    }

    @Test fun explicitDubOnlyReturnsDubbedServers() {
        val result = DesktopSourceParser.reanimeServers(root(
            server("sub", "HD-2", "sub"), server("dub", "HD-1", "dub"),
            server("named", "HD-2 Dubbed")
        ), dub = true)
        assertEquals(listOf("named", "dub"), result.map { it.url.substringAfterLast('/') })
    }

    @Test fun wrongAudioWatchFallsBackToFlixAndDubOnlyStaysUnavailable() = runTest {
        for (flixType in listOf("sub", "dub")) {
            val requested = mutableSetOf<String>()
            val client = HttpClient(MockEngine { request ->
                requested += request.url.encodedPath
                val json = if (request.url.encodedPath.startsWith("/api/watch/"))
                    buildJsonObject { put("episode_links", JsonArray(listOf(server("watch-dub", "HD-2", "dub")))) }
                else root(server("flix", "HD-1", flixType))
                respond(json.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            })
            try {
                val result = DesktopSourceRepository(client).serverPages("reanime", "https://reanime.to/watch/test?ep=2", 2, 206774)
                assertEquals(if (flixType == "sub") listOf("https://flixcloud.cc/e/flix") else emptyList(), result.map { it.url })
                assertEquals(setOf("/api/watch/test/2", "/api/flix/206774/2"), requested)
            } finally { client.close() }
        }
    }
}
