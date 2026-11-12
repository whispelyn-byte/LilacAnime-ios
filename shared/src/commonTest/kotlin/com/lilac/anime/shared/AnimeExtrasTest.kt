package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** 작품 정보 더 보기 (desktop 19110b2): AniList's and MAL's extras of a work. */
class AnimeExtrasTest {
    private val media = """{"data":{"Media":{"id":10,"idMal":20,"siteUrl":"https://anilist.co/anime/10","title":{"romaji":"Sho","english":"Show","native":"ショー"},
        "nextAiringEpisode":{"episode":8,"airingAt":1800000000},"trailer":{"id":"abcDEF12345","site":"youtube"},
        "tags":[{"name":"School","rank":90},{"name":"Twist","rank":95,"isMediaSpoiler":true},{"name":"Minor","rank":20}],
        "characters":{"edges":[{"role":"MAIN","node":{"name":{"full":"Aoi Sora","native":"空あおい"},"image":{"medium":"https://img/a.jpg"}},"voiceActors":[{"name":{"full":"Voice One","native":"声一"}}]}]},
        "relations":{"edges":[{"relationType":"SEQUEL","node":{"id":11,"type":"ANIME","format":"TV","seasonYear":2027,"title":{"english":"Show 2"},"coverImage":{"large":"https://img/2.jpg"}}},
            {"relationType":"ADAPTATION","node":{"id":12,"type":"MANGA","title":{"english":"Show (manga)"}}}]},
        "recommendations":{"nodes":[{"mediaRecommendation":{"id":30,"type":"ANIME","averageScore":81,"title":{"romaji":"Other"},"coverImage":{"large":"javascript:alert(1)"}}},{"mediaRecommendation":null}]},
        "externalLinks":[{"site":"Official Site","url":"https://show.example/","type":"INFO"},{"site":"Twitter","url":"https://x.example/","type":"SOCIAL"},{"site":"Bad","url":"http://plain.example/","type":"INFO"}]}}}"""
    private val themes = """{"data":{"openings":["1: \"Song\" by Band (eps 1-12)"],"endings":["\"Bye\" by Duo"]}}"""
    private fun MockRequestHandleScope.json(text: String) = respond(text, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    // On a real dispatcher: runTest's virtual time would let the 5 s wait for MAL's songs run out at once while the mock
    // engine answers on a thread of its own.
    @Test fun theEntryByItsIdShapedForThePageWithMalSongNames() = runTest { withContext(Dispatchers.Default) {
        val asked = mutableListOf<String>(); val lock = Mutex()
        val client = HttpClient(MockEngine { request ->
            lock.withLock { asked += request.url.host }
            if (request.url.host == "api.jikan.moe") { assertEquals("/v4/anime/20/themes", request.url.encodedPath); json(themes) } else json(media)
        })
        try {
            val extras = assertNotNull(AnimeExtrasRepository(client).extras(Anime(id = "show", anilistId = 10, source = "reanime"), "reanime"))
            assertEquals(listOf("graphql.anilist.co", "api.jikan.moe"), asked)
            assertEquals(8, extras.nextEpisode); assertEquals(1800000000L, extras.nextAiringAt)
            assertEquals("https://www.youtube.com/watch?v=abcDEF12345", extras.trailer)
            assertEquals(listOf("School"), extras.tags)
            assertEquals("声一", extras.characters.single().voiceNative)
            // Only anime among the relations; a picture that is not https is dropped; links are official / streaming https ones.
            assertEquals(listOf(11 to "SEQUEL"), extras.relations.map { it.anilistId to it.relationType })
            assertEquals("후속편", extras.relationName("SEQUEL"))
            assertEquals(listOf(Triple(30, "Other", "")), extras.recommendations.map { Triple(it.anilistId, it.title, it.image) })
            assertEquals(8.1, extras.recommendations.single().score)
            assertEquals(listOf("Official Site"), extras.links.map { it.site })
            assertEquals(listOf("\"Song\" by Band (eps 1-12)"), extras.openings); assertEquals(listOf("\"Bye\" by Duo"), extras.endings)
        } finally { client.close() }
    } }

    // A 애니24 work's own id is not trusted as it is: its entry is found by the original title (strictly). A lookup AniList
    // did not answer is asked again next time; an answer is kept for half an hour.
    @Test fun aKoreanSiteWorkIsFoundByItsOriginalTitleAndAFailureIsNotKept() = runTest {
        var fail = true; var calls = 0; val lock = Mutex()
        val client = HttpClient(MockEngine { request ->
            lock.withLock { calls++ }
            val body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            when {
                fail -> respond("{}", HttpStatusCode.ServiceUnavailable)
                request.url.host == "api.jikan.moe" -> json(themes)
                body.contains("\$search") -> json("""{"data":{"Page":{"media":[{"id":10,"idMal":20,"seasonYear":2025,"title":{"native":"ショー"}}]}}}""")
                else -> json(media)
            }
        })
        try {
            val repository = AnimeExtrasRepository(client)
            val anime = Anime(id = "쇼", anilistId = 999, native = "ショー", year = "2025", source = "ohli24")
            assertNull(repository.extras(anime, "ohli24"))
            fail = false
            assertEquals(10, repository.extras(anime, "ohli24")?.anilistId)
            val before = calls
            assertEquals(10, repository.extras(anime, "ohli24")?.anilistId)
            assertEquals(before, calls, "kept for half an hour")
            assertNull(repository.extras(Anime(id = "5", title = "쇼", source = "linkani"), "linkani"))
        } finally { client.close() }
    }
}
