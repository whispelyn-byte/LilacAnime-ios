package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            // /api/watch and /api/flix are asked at once, answered on MockEngine's threads: the set is guarded.
            val requested = mutableSetOf<String>(); val lock = Mutex()
            val client = HttpClient(MockEngine { request ->
                lock.withLock { requested += request.url.encodedPath }
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

    // desktop 466e6cb: an episode synced from the phone has no AniList id; it is read from the series route by the slug.
    @Test fun anEpisodeWithoutItsAniListIdAsksFlixByTheOneTheSeriesRouteGives() = runTest {
        val requested = mutableListOf<String>(); val lock = Mutex()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            lock.withLock { requested += path }
            val json = when {
                path.startsWith("/api/watch/") -> buildJsonObject { put("episode_links", JsonArray(emptyList())) }
                path.startsWith("/api/v1/anime/") -> buildJsonObject { put("anilist_id", 14741) }
                else -> root(server("sub", "HD-2", "sub"))
            }
            respond(json.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val result = DesktopSourceRepository(client).serverPages("reanime", "https://reanime.to/watch/love-chunibyo-other-delusions-y66mwz?ep=1", 1, 0)
            assertEquals(listOf("https://flixcloud.cc/e/sub"), result.map { it.url })
            assertTrue("/api/v1/anime/love-chunibyo-other-delusions-y66mwz" in requested, requested.toString())
            assertTrue("/api/flix/14741/1" in requested, requested.toString())
        } finally { client.close() }
    }
}
