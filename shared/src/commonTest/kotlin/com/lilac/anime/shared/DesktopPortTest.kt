package com.lilac.anime.shared
import kotlin.test.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*

class DesktopPortTest {
    @Test fun glossaryPrefersCustomNamesAndLongestPhrase() {
        val hints = TranslationTerminology.hints("先生、よろしくお願いいたします。", "先生=스승님")
        assertTrue(hints.contains("先生 = 스승님"))
        assertFalse(hints.contains("先生 = 선생님"))
        assertEquals("ごちそうさまでした = 잘 먹었습니다", TranslationTerminology.hints("ごちそうさまでした！", ""))
    }
    @Test fun greetingMustBeStandaloneInsteadOfPartOfAWord() {
        assertEquals("", TranslationTerminology.hints("おかえり道", ""))
        assertEquals("おかえり = 어서 와", TranslationTerminology.hints("「おかえり！」", ""))
    }

    @Test fun gzipCatalogDecodesOnEveryTarget() {
        assertEquals("{\"data\":[]}", inflateCatalogGzip(byteArrayOf(31,-117,8,0,0,0,0,0,2,10,-85,86,74,73,44,73,84,-78,-118,-114,-83,5,0,-108,100,-78,94,11,0,0,0)).decodeToString())
        assertFails { inflateCatalogGzip(byteArrayOf(1,2,3)) }
    }
    @Test fun miruroMoviesUseFilmAndExcludeUnairedEpisodes() {
        val root = Json.parseToJsonElement("""{"id":"film-1","format":"MOVIE","status":"RELEASING","episode_count":3,"episode_counts":{"sub":1}}""").jsonObject
        val rows = Json.parseToJsonElement("""[{"kind":"regular","episode_number":1},{"kind":"film","episode_number":1,"title":"Film"},{"kind":"film","episode_number":2,"aired_on":"2099-01-01"},{"kind":"film","episode_number":3,"aired_on":"2020-01-01"},{"kind":"film","episode_number":4,"aired_on":"2020-01-01"}]""").jsonArray
        val episodes = DesktopSourceParser.miruroEpisodes(root, rows, "2026-10-08")
        assertEquals(listOf(1, 3), episodes.map { it.number })
        assertEquals("Film", episodes.first().title)
        assertTrue(episodes.first().videoUrl!!.contains("?ep=1"))
    }
    @Test fun miruroStreamsPreserveHeadersAndPrioritizeKorean() {
        val root = Json.parseToJsonElement("""{"tracks":[{"track":"sub","providers":[{"provider":"one","servers":[{"server":"one","streams":[{"format":"hls","url":"https://cdn.example/one.m3u8"}]}]}]},{"track":"ssub","providers":[{"provider":"two","subtitles":[{"file":"https://cdn.example/ko.vtt","language":"ko","label":"Korean"}],"servers":[{"server":"two","headers":{"Referer":"https://player.example/embed/1"},"streams":[{"format":"hls","url":"https://cdn.example/two.m3u8"}]}]}]}]}""").jsonObject
        val streams = DesktopSourceParser.miruroStreams(root)
        assertEquals("https://cdn.example/two.m3u8", streams.first().url)
        assertEquals("https://player.example", streams.first().headers["Origin"])
        assertEquals("ko", streams.first().subtitles.first().language)
    }
    @Test fun linkaniSeparatesEpisodeTabsAndRejectsUnrelatedSubtitle() {
        val html = """<div class="playlist-tab-box"><div class="tab-item">자막</div><div class="tab-item">더빙</div></div><div class="ewave-playlist-content"><a href="/watch/7/k2/">2화</a><a href="/watch/7/k1/">1화</a></div><div class="ewave-playlist-content"><a href="/watch/7/d1/">1화 더빙</a></div>"""
        val detail = DesktopSourceParser.koreanDetail(html, Anime(id="7",title="작품",detailUrl="https://linkani.tv/ani/7/"),"linkani")
        assertEquals(2, detail.servers.size)
        assertEquals(listOf(1,2),detail.servers.first().episodes.map { it.number })
        assertEquals("더빙", detail.servers[1].name)
        val player = """<script>var player_aaaa={"url":"https://cdn.example/h/aa/bb/aaaaaaaaaaaaaaaa/index.m3u8","subtitle_url":"https://cdn.example/s/aa/bb/bbbbbbbbbbbbbbbb/sub.vtt"};</script>"""
        assertTrue(DesktopSourceParser.linkaniStreams(player).first().subtitles.isEmpty())
    }
    @Test fun koreanSourcesKeepOriginalDetailURLs() {
        val html = """<div class="show-item"><a class="show-item-img-link" href="/31/a.html"><img src="/a.jpg" title="애니 제목"></a><div class="show-item-title">애니 제목</div></div>"""
        val anime = DesktopSourceParser.koreanList(html,"ohli24").single()
        assertEquals("31",anime.id)
        assertEquals("https://www.ohli24.net/31/a.html",anime.detailUrl)
        assertEquals("https://www.ohli24.net/a.jpg",anime.poster)
    }
    @Test fun anissiaRejectsDifferentSeasonAndFindsEpisodeAttachment() = runTest {
        val client = HttpClient(MockEngine { request ->
            val text = when (request.url.encodedPath) {
                "/anime/list/0" -> """{"code":"ok","data":{"content":[{"animeNo":1,"subject":"테스트 1기"},{"animeNo":2,"subject":"테스트 2기"}]}}"""
                "/anime/caption/animeNo/2" -> """{"code":"ok","data":[{"name":"Maker","website":"https://maker.tistory.com/12"}]}"""
                "/12" -> """<title>테스트 2기 3화</title><a href="https://files.example/show.S02E03.ass">show.S02E03.ass</a>"""
                else -> "<rss></rss>"
            }
            respond(text,headers=headersOf(HttpHeaders.ContentType,"text/plain"))
        })
        val discovery = SubtitleDiscovery(SourceRepository(client))
        try {
            val result = discovery.search("anissia","테스트 2기",3,"3",0)
            assertTrue(result.any { it.source=="anissia" && it.url.endsWith("S02E03.ass") })
            assertTrue(result.none { it.url.contains("animeNo/1") })
        } finally { discovery.close() }
    }
}
