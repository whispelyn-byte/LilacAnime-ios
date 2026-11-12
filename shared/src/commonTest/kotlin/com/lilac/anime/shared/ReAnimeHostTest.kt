package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** RE:Anime moved to reanime.cz (desktop 3fa419c): requests go there, the saved reanime.to addresses stay as they are. */
class ReAnimeHostTest {
    @Test fun savedAddressesAreAskedAtTheCurrentNameAndOtherSitesAreLeftAlone() {
        assertEquals("https://reanime.cz/watch/x?ep=1", ReAnimeHost.live("https://reanime.to/watch/x?ep=1"))
        assertEquals("https://reanime.cz/anime/y", ReAnimeHost.live("https://www.reanime.to/anime/y"))
        assertEquals("https://reanime.cz", ReAnimeHost.live("https://reanime.to"))
        assertEquals("https://reanime.cz/api/v1/search", ReAnimeHost.live("https://reanime.cz/api/v1/search"))
        for (other in listOf("https://reanime.tools/x", "https://flixcloud.cc/e/a", "https://ani.ohli24.com/c/x")) assertEquals(other, ReAnimeHost.live(other))
    }

    // An episode saved before the move (시청 기록, 내 목록, the phone's sync) still plays: its series route and Flix are asked
    // at reanime.cz, with the new name as the Referer.
    @Test fun anEpisodeSavedOnTheOldNameIsAskedAtTheNewOne() = runTest {
        val asked = mutableListOf<Pair<String, String?>>(); val lock = Mutex()
        val client = HttpClient(MockEngine { request ->
            lock.withLock { asked += (request.url.host + request.url.encodedPath) to request.headers[HttpHeaders.Referrer] }
            val json = if (request.url.encodedPath.startsWith("/api/v1/anime/")) buildJsonObject { put("anilist_id", 14741) }
                else buildJsonObject { put("servers", buildJsonArray { add(buildJsonObject { put("serverName", "HD-2"); put("dataLink", "https://flixcloud.cc/e/sub"); put("dataType", "sub") }) }) }
            respond(json.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val result = DesktopSourceRepository(client).serverPages("reanime", "https://reanime.to/watch/love-chunibyo-other-delusions-y66mwz?ep=1", 1, 0)
            assertEquals(listOf("https://flixcloud.cc/e/sub"), result.map { it.url })
            assertEquals(listOf("reanime.cz/api/v1/anime/love-chunibyo-other-delusions-y66mwz", "reanime.cz/api/flix/14741/1"), asked.map { it.first })
            assertTrue(asked.all { it.second == "https://reanime.cz/" }, asked.toString())
        } finally { client.close() }
    }
}
