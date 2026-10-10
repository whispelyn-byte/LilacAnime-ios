package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class CommunityIndexTest {
    private fun feed(start: Int, count: Int, total: Int): String =
        """{"feed":{"openSearch${'$'}totalResults":{"${'$'}t":"$total"},"entry":[""" +
            (start until start + count).joinToString(",") { n ->
                """{"title":{"${'$'}t":"작품 ${n}화"},"link":[{"rel":"alternate","href":"https://fixture.test/$n"}],"content":{"${'$'}t":""}}"""
            } + "]}}"

    @Test fun simultaneousLookupsShareCompleteIndexAndThreeAdditionalPageTransfers() = runTest {
        var calls = 0; var active = 0; var peak = 0
        val client = HttpClient(MockEngine { request ->
            calls++; active++; peak = maxOf(peak, active)
            delay(10); active--
            val start = request.url.parameters["start-index"]!!.toInt()
            respond(feed(start, minOf(150, 786 - start), 785), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val discovery = SubtitleDiscovery(SourceRepository(client))
        val key = "complete-index-test"
        writeCommunityCache(key, "")
        try {
            val results = awaitAll(async { discovery.communityPosts(key) }, async { discovery.communityPosts(key) })
            assertEquals(6, calls); assertEquals(3, peak)
            assertEquals(785, results[0].size); assertEquals(results[0], results[1])
            assertEquals("https://fixture.test/151", results[0][150].url)
        } finally { discovery.close(); writeCommunityCache(key, "") }
    }

    @Test fun incompleteRefreshPreservesStaleIndexAndRecoversAfterRetryDelay() = runTest {
        var now = 1800000000000L; var calls = 0; var complete = false
        val client = HttpClient(MockEngine { request ->
            calls++
            val start = request.url.parameters["start-index"]!!.toInt()
            respond(feed(start, if (start == 1) 150 else if (complete) 1 else 0, 151), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val key = "stale-index-test"
        val stale = CommunityCache(1, listOf(CommunityPost("이전 작품 1화", "https://fixture.test/old", "")))
        val saved = Json.encodeToString(stale); writeCommunityCache(key, saved)
        val discovery = SubtitleDiscovery(SourceRepository(client)) { now }
        try {
            assertEquals(stale.posts, discovery.communityPosts(key)); assertEquals(2, calls)
            assertEquals(saved, readCommunityCache(key))
            assertEquals(stale.posts, discovery.communityPosts(key, force = true)); assertEquals(2, calls)
            now += 60001; complete = true
            assertEquals(151, discovery.communityPosts(key).size); assertEquals(4, calls)
            assertNotEquals(saved, readCommunityCache(key))
        } finally { discovery.close(); writeCommunityCache(key, "") }
    }

    @Test fun failedFirstLookupNeverCachesEmptyIndex() = runTest {
        var fail = true; var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            if (fail) throw IllegalStateException("offline")
            respond(feed(1, 1, 1), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val key = "failed-index-test"; writeCommunityCache(key, "")
        val discovery = SubtitleDiscovery(SourceRepository(client))
        try {
            assertFailsWith<IllegalStateException> { discovery.communityPosts(key) }
            fail = false
            assertEquals(1, discovery.communityPosts(key).size); assertEquals(2, calls)
        } finally { discovery.close(); writeCommunityCache(key, "") }
    }
}
