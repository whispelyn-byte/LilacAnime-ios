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
    @Test fun anissiaTriesRecentlyUpdatedMakerBeforeOlderMaker() = runTest {
        val repository = SourceRepository(HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.contains("/anime/list/") -> """{"data":{"content":[{"animeNo":1,"subject":"장송의 프리렌"}]}}"""
                request.url.encodedPath.contains("/anime/caption/") -> """{"data":[{"name":"이전 제작자","updDt":"2026-09-01","website":"https://old.blogspot.com/"},{"name":"최근 제작자","updDt":"2026-10-10","website":"https://new.blogspot.com/"}]}"""
                else -> ""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        try {
            val found = AnissiaDiscovery(repository) { origin, _ -> listOf(CommunityPost("장송의 프리렌 1화", "$origin/1", "<a href='$origin/1.ass'>자막</a>")) }.search("장송의 프리렌", 1, "1")
            assertEquals(listOf("https://new.blogspot.com/1.ass", "https://old.blogspot.com/1.ass"), found.filter { it.source == "anissia" }.map { it.url })
            assertEquals(listOf("최근 제작자", "이전 제작자"), AnissiaDiscovery(repository).makers("장송의 프리렌").map { it.name })
        } finally { repository.close() }
    }
    @Test fun anissiaLinkedDifferentSeasonNeverBecomesAttachmentOrImageFallback() = runTest {
        val repository = SourceRepository(HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.contains("/anime/list/") -> """{"data":{"content":[{"animeNo":1,"subject":"장송의 프리렌 2기"}]}}"""
                request.url.encodedPath.contains("/anime/caption/") -> """{"data":[{"name":"제작자","website":"https://example.tistory.com/1"}]}"""
                request.url.encodedPath == "/1" -> """<title>장송의 프리렌 1기 1화</title><a href="https://fixture.test/wrong.ass">자막</a>"""
                else -> ""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        try { assertTrue(AnissiaDiscovery(repository).search("장송의 프리렌 2기", 1, "1").isEmpty()) }
        finally { repository.close() }
    }
    @Test fun anissiaKeepsMultipleMakerPostsAndNicknameEpisodeLabels() = runTest {
        val repository = SourceRepository(HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.contains("/anime/list/") -> """{"data":{"content":[{"animeNo":1,"subject":"장송의 프리렌"}]}}"""
                request.url.encodedPath.contains("/anime/caption/") -> """{"data":[{"name":"제작자","website":"https://example.blogspot.com/"}]}"""
                else -> ""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        try {
            val found = AnissiaDiscovery(repository) { _, _ -> listOf(
                CommunityPost("장송의 프리렌 1화", "https://example.blogspot.com/broken", "<a href='https://fixture.test/broken.ass'>자막</a>"),
                CommunityPost("장송의 프리렌 1화", "https://example.blogspot.com/good", "<a href='https://fixture.test/good.ass'>자막</a>"))
            }.search("장송의 프리렌", 1, "1")
            assertEquals(listOf("https://fixture.test/broken.ass", "https://fixture.test/good.ass"), found.filter { it.source == "anissia" }.map { it.url })
            assertEquals(2, found.count { it.source == "post" })
            assertEquals("츠레카노", AnissiaTitleRules.nickname("츠레카노 12화 자막 (完)", "계모의 데려온 딸이 전 여친이었다"))
            assertEquals("계모의 데려온 딸이 전 여친이었다 12화", AnissiaTitleRules.rename("츠레카노 12", "츠레카노", "계모의 데려온 딸이 전 여친이었다"))
        } finally { repository.close() }
    }
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
