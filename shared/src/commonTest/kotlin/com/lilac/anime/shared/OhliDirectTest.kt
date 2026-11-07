package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
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

    @Test fun crossSiteSubtitleKeepsTheVerifiedFileEvenWithAnHtmlContentType() = runTest {
        val sami = "<SAMI><BODY><SYNC Start=0><P>".encodeToByteArray() +
            byteArrayOf(0xc7.toByte(), 0xd1.toByte()) + "</BODY></SAMI>".encodeToByteArray()
        val htmlHeaders = headersOf(HttpHeaders.ContentType, "text/html")
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/episode" -> respond("""<script data-page="app" type="application/json">{"props":{"playing":{"body":{"url":"https://player.test/video"}}}}</script>""")
                "/video" -> {
                    assertEquals("${DesktopSourceParser.OHLI24_WEB}/", request.headers["Referer"])
                    respond("""<script>var playerjsSubtitle = "[1]https://sub.test/blocked,[2]https://sub.test/caption";</script>""")
                }
                "/blocked" -> respond("<html><!-- try again --></html>", headers = htmlHeaders)
                "/caption" -> {
                    assertNull(request.headers["Referer"])
                    assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().contains("Chrome/"))
                    respond(sami, headers = htmlHeaders)
                }
                else -> error("Unexpected request: ${request.url}")
            }
        })
        val track = assertNotNull(DesktopSourceRepository(client).ohliEpisodeSubtitle("https://episode.test/episode"))
        assertEquals("https://sub.test/caption", track.url)
        assertEquals("smi", track.format)
        assertContentEquals(sami, Base64.decode(assertNotNull(track.contentBase64)))
    }

    @Test fun linkaniKeepsTheVerifiedSubtitleAndLinkkfSkipsAnHtmlPage() = runTest {
        val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n자막\n"
        val htmlHeaders = headersOf(HttpHeaders.ContentType, "text/html")
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/episode" -> respond("""<script>var player_aaaa = {"url":"https://video.test/1.m3u8","subtitle_url":"https://sub.test/caption"};</script>""")
                "/caption" -> {
                    assertEquals("https://linkani.tv/", request.headers["Referer"])
                    assertTrue(request.headers["User-Agent"].orEmpty().contains("Chrome/"))
                    respond(vtt, headers = htmlHeaders)
                }
                "/blocked" -> {
                    assertEquals("https://player.test/video", request.headers["Referer"])
                    respond("<!-- blocked --><html>Log in</html>")
                }
                else -> error("Unexpected request: ${request.url}")
            }
        })
        val repository = DesktopSourceRepository(client)
        val track = assertNotNull(repository.linkaniEpisodeSubtitle("https://linkani.tv/episode"))
        assertEquals("vtt", track.format)
        assertEquals(vtt, Base64.decode(assertNotNull(track.contentBase64)).decodeToString())
        assertNull(repository.verifiedProviderSubtitle("https://sub.test/blocked", mapOf("Referer" to "https://player.test/video"), "linkkf"))
    }

    @Test fun remoteSubtitleDownloadsRecognizeTheBodyInsteadOfRelyingOnMimeType() {
        assertFalse(SubtitleTools.isHtmlResponse("<SAMI><BODY><SYNC Start=0><P>자막", "text/html"))
        assertFalse(SubtitleTools.isHtmlResponse("\uFEFF1\n00:00:00,000 --> 00:00:01,000\n자막", "text/html"))
        assertTrue(SubtitleTools.isHtmlResponse("<!-- redirect --><!DOCTYPE html><html>", "application/octet-stream"))
        assertTrue(SubtitleTools.isHtmlResponse("Forbidden", "text/html"))
    }

    @Test fun thePlayerIsReadWithoutOpeningIt() = runTest {
        val asked = mutableListOf<String>()
        val askedMutex = Mutex()
        val client = HttpClient(MockEngine { request ->
            askedMutex.withLock { asked += request.method.value + " " + request.url.encodedPath }
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
