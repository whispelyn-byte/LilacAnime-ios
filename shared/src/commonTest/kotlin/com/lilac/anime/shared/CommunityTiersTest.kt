package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Desktop v0.5.41-0.5.43 community matching: link tiers, release-style file names, seasons, Drive folders, Anissia titles. */
class CommunityTiersTest {
    private fun JsonElement.numbers() = jsonArray.map { it.jsonPrimitive.double }

    @Test fun linkPlansMatchDesktopTiersAndSeasons() {
        for (test in desktopOracle.list("community").filterIsInstance<JsonObject>()) {
            val input = test.obj("input"); val expected = test.obj("expected")
            val number = input.text("episode").toDouble()
            val result = DesktopCommunity.links(CommunityPost(input.text("title"), "https://fixture.test/post", input.text("html")), number.toInt(),
                input["offsets"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty(), input["allowOffset"]?.jsonPrimitive?.booleanOrNull ?: false, number, input.number("season"))
            assertEquals(expected.list("links").map { it.jsonPrimitive.content }, result.links, input.toString())
            assertEquals(expected.text("episode").toDouble(), result.episodeNumber, input.toString())
            val info = expected["linkInfo"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            assertEquals(info.map { it.text("href") }, result.linkInfo.map { it.href }, input.toString())
            assertEquals(info.map { it["font"]?.jsonPrimitive?.booleanOrNull ?: false }, result.linkInfo.map { it.font }, input.toString())
            assertEquals(info.map { it["tier"]?.jsonPrimitive?.intOrNull ?: 0 }, result.linkInfo.map { it.tier }, input.toString())
            assertEquals(info.map { it["exact"]?.jsonPrimitive?.booleanOrNull ?: false }, result.linkInfo.map { it.exact }, input.toString())
            assertEquals(expected["strict"]?.jsonPrimitive?.booleanOrNull ?: false, result.strict, input.toString())
            assertEquals(expected["bundle"]?.jsonPrimitive?.booleanOrNull ?: false, result.bundle, input.toString())
        }
    }

    @Test fun linkTextsAndSeasonsMatchDesktop() {
        val links = desktopOracle.list("communityLinkEpisodes").filterIsInstance<JsonObject>()
        assertTrue(links.size >= 16)
        for (test in links) {
            val found = DesktopCommunity.linkEpisodes(test.text("input"))
            assertEquals(test["list"]!!.numbers(), found.list, test.text("input"))
            assertEquals(test["ranges"]!!.jsonArray.map { it.numbers() }, found.ranges.map { listOf(it.start, it.endInclusive) }, test.text("input"))
        }
        for (test in desktopOracle.list("communitySeasons").filterIsInstance<JsonObject>()) {
            assertEquals(test.number("season"), DesktopCommunity.season(test.text("input")), test.text("input"))
            assertEquals(test["seasons"]!!.jsonArray.map { it.jsonPrimitive.int }.toSet(), DesktopCommunity.seasons(test.text("input")), test.text("input"))
        }
    }

    @Test fun exactLinksGiveTheirOneEpisodeAsDesktopPicksIt() {
        val fixtures = desktopOracle.list("communityPick").filterIsInstance<JsonObject>()
        assertEquals(8, fixtures.size)
        for (fixture in fixtures) {
            val input = fixture.obj("input")
            val names = input.list("names").map { it.jsonPrimitive.content }
            fun flag(name: String, fallback: Boolean) = input[name]?.jsonPrimitive?.booleanOrNull ?: fallback
            val picked = DesktopCommunityFiles.pick(names, names.indices.map { (100 + it).toLong() }, input.text("episode").toDouble(), flag("exact", false), flag("strict", true), flag("bundle", false), input.number("season") ?: 0)
            assertEquals(fixture["expected"]?.jsonPrimitive?.contentOrNull, names.getOrNull(picked), input.toString())
        }
    }

    @Test fun releaseNamesAndNumberedSetsKeepTheirEpisode() {
        val cases = listOf("Yasei no Last Boss ga Arawareta - 01SubsPlease.smi" to 1.0, "Show - 2nd.ass" to null,
            "[SubsPlease] Black Torch - 07 (1080p) [6D89AB2F].srt" to 7.0, "[Ohys-Raws] Kikansha no Mahou wa Tokubetsu Desu - 01 (BS11 1280x720 x264 AAC).smi" to 1.0,
            "Hotaru Yome 01_SubsPlease.srt" to 1.0, "슈뒤담 - 12_ToonsHub.srt" to 12.0, "약사의 혼잣말03.ass" to 3.0, "꽃피우는 그대들에게2.smi" to null,
            "[SubsPlease] Iseleve - OVA (1080p) [FEE28781].smi" to null)
        for ((name, episode) in cases) assertEquals(episode, DesktopCommunityFiles.parse(name).episode, name)
        assertEquals(DesktopCommunityFiles.Parsed(4.0, 2), DesktopCommunityFiles.parse("Clevatess S2 - 04SubsPlease.smi"))
        // An Anissia archive whose names the parser alone did not read: episode 1 got episode 8's subtitle.
        val files = (1..9).map { "Yasei no Last Boss ga Arawareta - 0${it}SubsPlease.smi" } + "Yasei no Last Boss ga Arawareta - 08SubsPlease.vtt"
        val sizes = files.map { 0L }
        assertEquals(0, DesktopCommunityFiles.select(files, sizes, 1.0, strict = false, bundle = false, season = 0))
        assertEquals(9, DesktopCommunityFiles.select(files, sizes, 8.0, strict = true, bundle = true, season = 0))
        val odd = listOf("ab01cd.smi", "ab02cd.smi", "ab03cd.smi")
        assertEquals(1, DesktopCommunityFiles.select(odd, odd.map { 0L }, 2.0, false, false, 0))
        assertEquals(-1, DesktopCommunityFiles.select(odd, odd.map { 0L }, 4.0, false, false, 0))
    }

    @Test fun aSeriesPostKeepingSeveralSeasonsServesEachItsOwnArchive() {
        assertEquals(setOf(1, 2, 3), DesktopCommunity.seasons("자판기 1기/2기/3기")); assertEquals(setOf(2), DesktopCommunity.seasons("작품 2기"))
        assertEquals(2, DesktopCommunity.season("클레바테스Ⅱ ~마수왕과 가짜 용사 전승~"))
        val name = "자동판매기로 다시 태어난 나는 미궁을 방랑한다"
        val html = listOf("$name 3기 1-2 SubsPlease", "$name 2기 1-12 SubsPlease", "$name 1-12 Ohys").withIndex()
            .joinToString("") { (i, label) -> "<a href=\"https://drive.google.com/file/d/seasons_$i/view\">$label</a>" }
        val post = CommunityPost("$name 1기/2기/3기", "https://example.blogspot.com/seasons", html)
        fun links(title: String, episode: Int) = DesktopCommunity.rank(listOf(post), title, episode, emptyList()).map { it.links.joinToString() }
        assertEquals(listOf("https://drive.google.com/file/d/seasons_0/view"), links("$name 3기", 1))
        assertEquals(listOf("https://drive.google.com/file/d/seasons_1/view"), links("$name 2기", 5))
        assertEquals(listOf("https://drive.google.com/file/d/seasons_2/view"), links(name, 5))
        assertNull(DesktopCommunity.rank(listOf(post), name, 5, emptyList()).single().fileSeason, "an unmarked title does not filter its files by season")
    }

    @Test fun driveFolderArchiveIsSearchedForTheTitleNewestYearsFirst() = runTest {
        val tree = mapOf("root" to listOf(Triple("완결", "done", true), Triple("극장판", "movies", true)), "movies" to listOf(Triple("어떤 극장판", "m1", true)),
            "done" to listOf(Triple("2025", "y25", true), Triple("2026", "y26", true)), "y25" to listOf(Triple("4분기", "q254", true)),
            "y26" to listOf(Triple("1분기", "q261", true), Triple("2분기", "q262", true)), "q262" to listOf(Triple("다른 작품", "other", true)),
            "q261" to listOf(Triple("마술사 쿠논은 보인다", "kunon", true)),
            "kunon" to listOf(Triple("마술사 쿠논은 보인다 01.zip", "f1", false), Triple("마술사 쿠논은 보인다 02.zip", "f2", false)),
            "flat" to listOf(Triple("푸드코트에서 1화 자막.ass", "a1", false)))
        val listed = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("/embeddedfolderview", request.url.encodedPath)
            val id = request.url.parameters["id"].orEmpty(); listed += id
            val body = tree[id].orEmpty().joinToString("") { (name, entry, folder) ->
                "<div class=\"flip-entry\" id=\"entry-$entry\"><a href=\"https://drive.google.com/${if (folder) "drive/folders/$entry" else "file/d/$entry/view"}\"><div class=\"flip-entry-title\">$name</div></a></div>"
            }
            respond("<html><body>$body</body></html>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val repository = SourceRepository(client)
        try {
            val assets = CommunityAssets(repository)
            assertEquals("root", CommunityAssets.folderId("https://drive.google.com/drive/folders/root?usp=sharing"))
            assertNull(CommunityAssets.folderId("https://drive.google.com/file/d/abc/view"))
            assertEquals(listOf("f1", "f2"), assets.folderFiles("root", "마술사 쿠논은 보인다").map { it.id })
            assertTrue("q261" in listed && ("q254" !in listed || listed.indexOf("q262") < listed.indexOf("q254")))
            assertEquals(listOf("a1"), assets.folderFiles("flat", "푸드코트에서").map { it.id })
            // A folder link becomes the episode's own file in it.
            val post = CommunityPost("마술사 쿠논은 보인다", "https://example.tistory.com/1", "<a href=\"https://drive.google.com/drive/folders/root\">자막 폴더</a>")
            val match = DesktopCommunity.links(post, 2)
            val downloads = assets.assets(match, "Anissia", "anissia", "마술사 쿠논은 보인다")
            assertEquals(listOf("https://drive.usercontent.google.com/download?id=f2&export=download&confirm=t"), downloads.map { it.url })
            assertEquals("마술사 쿠논은 보인다 02.zip", downloads.single().label)
        } finally { repository.close() }
    }

    @Test fun anissiaFindsRomanSeasonsHyphenSubtitlesAndBareDigitSeasons() = runTest {
        val entries = mapOf(
            "클레바테스" to listOf(1 to "클레바테스 ~마수왕과 아기와 시체 용사~", 2 to "클레바테스Ⅱ ~마수왕과 가짜 용사 전승~"),
            "이세계 유유자적 농가" to listOf(6 to "이세계 유유자적 농가 2", 7 to "이세계 유유자적 농가"),
            "시광대리인" to listOf(3 to "시광대리인 -Link Click- 영도편", 4 to "시광대리인 Ⅱ -Link Click Ⅱ-", 5 to "시광대리인 -Link Click-"))
        val client = HttpClient(MockEngine { request ->
            val rows = entries[request.url.parameters["q"].orEmpty()].orEmpty().joinToString(",") { (no, subject) -> """{"animeNo":$no,"subject":"$subject"}""" }
            respond("""{"code":"ok","data":{"content":[$rows],"totalPages":1,"last":true}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repository = SourceRepository(client)
        try {
            val anissia = AnissiaDiscovery(repository)
            suspend fun found(title: String) = anissia.anime(title)?.optInt("animeNo")
            assertEquals(2, found("클레바테스Ⅱ ~마수왕과 가짜 용사 전승~"))
            assertEquals(1, found("클레바테스 ~마수왕과 아기와 시체 용사~"))
            assertEquals(3, found("시광대리인 -Link Click- 영도편"))
            assertEquals(4, found("시광대리인 Ⅱ -Link Click Ⅱ-"))
            assertEquals(6, found("이세계 유유자적 농가 2기"))
            assertEquals(7, found("이세계 유유자적 농가"))
        } finally { repository.close() }
    }

    @Test fun koreanSitesAreSearchedForTmdbArtworkByTheirBareName() {
        for (test in desktopOracle.list("koreanTmdbQueries").filterIsInstance<JsonObject>())
            assertEquals(test.list("expected").map { it.jsonPrimitive.content }, DesktopArtwork.koreanTmdbQueries(test.text("input")), test.text("input"))
    }
}
