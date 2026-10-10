package com.lilac.anime.shared

import com.lilac.anime.shared.compat.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class JimakuDesktopTest {
    @Test fun desktopEpisodeScoresMatchAll56Cases() {
        val cases = JSONObject(JimakuOracleData.json).getJSONArray("scores")
        for (i in 0 until cases.length()) {
            val c = cases.optJSONObject(i)!!
            assertEquals(c.optInt("expected"), JimakuRules.episodeScore(c.optString("name"), c.optInt("episode")), c.toString())
        }
    }
    @Test fun desktopRankingsMatchAll8Cases() {
        val cases = JSONObject(JimakuOracleData.json).getJSONArray("ranks")
        for (i in 0 until cases.length()) {
            val c = cases.optJSONObject(i)!!; val files = c.getJSONArray("files")
            val assets = (0 until files.length()).map { index -> files.optJSONObject(index)!!.let { SubtitleAsset(it.optString("name"), it.optString("url"), "jimaku", size = it.optLong("size")) } }
            val expected = c.getJSONArray("expected").let { list -> (0 until list.length()).map { list.getString(it) } }
            assertEquals(expected, JimakuRules.rank(assets, c.optString("title"), c.optInt("episode"), c.optString("preferred")).map { it.name }, "case $i")
        }
    }
    @Test fun concurrentIndexRequestsShareDownloadAndFirstEntryWins() = runTest {
        var indexRequests = 0
        val discovery = SubtitleDiscovery(SourceRepository(HttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath == "/") {
                indexRequests++; delay(20)
                """<div class="entry" data-extra='{"anilist_id":1}'><a href="/entry/123">first</a></div><div class="entry" data-extra='{"anilist_id":1}'><a href="/entry/999">second</a></div>"""
            } else {
                assertEquals("/entry/123", request.url.encodedPath)
                """<div class="entry" data-extra='{"name":"Anime 03.ass","url":"/entry/123/download/1","size":1234}'></div><div class="entry" data-extra='{"name":"Anime 03.ass","url":"/entry/123/download/2"}'></div><div class="entry" data-extra='{"name":"Anime 03.zip","url":"/entry/123/download/3"}'></div><div class="entry" data-extra='{"name":"Anime 03.sami"}'><a class="file-name" href="123/download/4">file</a></div>"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })))
        try {
            val lists = coroutineScope { List(2) { async { discovery.search("jimaku", "Anime", 3, "3", 1) } }.awaitAll() }
            assertEquals(1, indexRequests)
            assertEquals(listOf("Anime 03.ass", "Anime 03.sami"), lists[0].map { it.name })
            assertEquals(1234L, lists[0][0].size)
            assertEquals("https://jimaku.cc/entry/123/download/4", lists[0][1].url)
        } finally { discovery.close() }
    }
}
