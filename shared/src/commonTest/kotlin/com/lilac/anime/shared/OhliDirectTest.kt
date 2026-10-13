package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OhliDirectTest {
    @Test fun theBestVariantIsChosenAndReadAgainstItsPlaylist() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\n480/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1920x1080\n/v/1080/index.m3u8\n"
        assertEquals("https://cdn.test/v/1080/index.m3u8", DesktopSourceParser.ohliVariant(master, "https://cdn.test/hls/master.m3u8?md5=x"))
        assertEquals("https://cdn.test/hls/480/index.m3u8", DesktopSourceParser.resolveUrl("480/index.m3u8", "https://cdn.test/hls/master.m3u8?md5=x"))
        assertEquals("https://other.test/a.m3u8", DesktopSourceParser.resolveUrl("//other.test/a.m3u8", "https://cdn.test/hls/master.m3u8"))
        assertFailsWith<IllegalStateException> { DesktopSourceParser.ohliVariant("<html>", "https://cdn.test/") }
        assertFailsWith<IllegalStateException> { DesktopSourceParser.ohliVariant("#EXTM3U\n#EXTINF:4,\na.ts\n", "https://cdn.test/") }
    }

    @Test fun thePlayerPageNamesItsSubtitleFiles() {
        val html = """<script>var playerjsSubtitle = "[뉴게임01]https://sub.test/뉴게임01.srt,[KO]https://sub.test/b.ass";</script>"""
        assertEquals(listOf("https://sub.test/뉴게임01.srt", "https://sub.test/b.ass"), DesktopSourceParser.ohliSubtitleFiles(html))
        assertEquals(emptyList(), DesktopSourceParser.ohliSubtitleFiles("<html></html>"))
    }

    @Test fun thePlayerIsReadWithoutOpeningIt() = runTest {
        val asked = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            asked += request.method.value + " " + request.url.encodedPath
            when {
                request.url.encodedPath == "/player/index.php" -> {
                    assertEquals("getVideo", request.url.parameters["do"]); assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
                    respond("""{"hls":true,"securedLink":"https://cdn.test/hls/master.m3u8?md5=a&expires=1"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                request.url.encodedPath == "/hls/master.m3u8" -> respond("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\n720/index.m3u8\n")
                request.url.encodedPath == "/hls/720/index.m3u8" -> respond("#EXTM3U\n#EXTINF:4,\nseg0.ts\n#EXT-X-ENDLIST\n")
                else -> respond("""<script>var playerjsSubtitle = "[1화]https://sub.test/1.srt";</script>""")
            }
        })
        // Real time: the 20 s limit must not pass at once in the test's virtual time.
        val stream = withContext(Dispatchers.Default) { DesktopSourceRepository(client).ohliStream("https://player.test/video/abc123", "UA") }
        assertEquals("https://cdn.test/hls/720/index.m3u8", stream.url)
        assertTrue(stream.manifest.contains("seg0.ts"))
        assertEquals(listOf("https://sub.test/1.srt"), stream.subtitles)
        assertTrue("POST /player/index.php" in asked)
    }

    @Test fun aSeriesAskedTwiceAtOnceIsFetchedOnce() = runTest {
        var loads = 0
        val memory = DetailMemory()
        val answers = (1..3).map { async { memory.get("ohli24|x") { loads++; listed("x") } } }.awaitAll()
        assertEquals(1, loads); assertTrue(answers.all { it.anime.id == "x" })
        assertFailsWith<IllegalStateException> { memory.get("ohli24|y") { error("down") } }
        assertEquals("y", memory.get("ohli24|y") { SourceDetail(Anime(id = "y", title = "y"), emptyList()) }.anime.id, "a failed answer is not kept")
    }
    private fun listed(id: String) = SourceDetail(Anime(id = id, title = id), listOf(EpisodeServer(1, "애니24", listOf(Episode(id = "1", number = 1, title = "1화")))))
}
