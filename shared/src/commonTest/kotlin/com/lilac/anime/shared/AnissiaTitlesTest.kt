package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlinx.serialization.json.*

class AnissiaTitlesTest {
    private val rows = Json.parseToJsonElement("""[
      {"animeNo":1,"subject":"약사의 혼잣말","originalSubject":"薬屋のひとりごと","startDate":"2023-10-22"},
      {"animeNo":2,"subject":"약사의 혼잣말 2기","originalSubject":"薬屋のひとりごと 第2期","startDate":"2025-01-10"},
      {"animeNo":3,"subject":"약사의 혼잣말 3기","originalSubject":"薬屋のひとりごと","startDate":"2026-10-02"},
      {"animeNo":4,"subject":"극장판 약사의 혼잣말","originalSubject":"劇場版 薬屋のひとりごと","startDate":"2026-12-11"},
      {"animeNo":5,"subject":"반딧불이의 혼례","originalSubject":"ホタルの嫁入り","startDate":"2026-10-03"},
      {"animeNo":6,"subject":"란마 1/2 (2024) 3기","originalSubject":"らんま1/2 第3期","startDate":"2026-10-04"}
    ]""").jsonArray.map { it.jsonObject }
    @Test fun nativeTitleSeasonAndDateMatchDesktopCases() {
        fun name(native: String, date: String, format: String = "TV") = AnissiaCatalogRules.match(rows, native, "", date, format)
        assertEquals("약사의 혼잣말 3기", name("薬屋のひとりごと 第3期", "2026-10-02"))
        assertEquals("약사의 혼잣말 2기", name("薬屋のひとりごと 第2期", "2025-01-10"))
        assertEquals("약사의 혼잣말", name("薬屋のひとりごと", "2023-10-22"))
        assertEquals("극장판 약사의 혼잣말", name("劇場版 薬屋のひとりごと", "2026-12-11", "MOVIE"))
        assertEquals("반딧불이의 혼례", name("ホタルの嫁入り", "2026-10-03"))
        assertEquals("란마 1/2 (2024) 3기", name("らんま1/2 第3期", "2026-10-04"))
        assertEquals("", name("進撃の巨人", "2013-04-07"))
        assertEquals("", name("薬屋のひとりごと 第4期", "2028-01-01"))
    }
    @Test fun unicodeAndRomanSeasonsRemainDistinct() {
        assertEquals(3, AnissiaCatalogRules.japaneseSeason("作品 第三期"))
        assertEquals(2, AnissiaCatalogRules.japaneseSeason("作品 Ⅱ"))
        assertEquals(AnissiaCatalogRules.key("作品 第3期"), AnissiaCatalogRules.key("作品 III"))
    }
    // Desktop ae060bc: Anissia is searched by the Japanese title less its season; the daily catalog only when that finds none.
    @Test fun anissiaIsSearchedByTheJapaneseTitleBeforeItsCatalogIsRead() = runTest {
        writeCommunityCache("anissia-titles", "{}")
        val asked = mutableListOf<String>()
        val found = """[{"animeNo":2588,"subject":"환생했는데 제7왕자라 내맘대로 마술을 연마합니다","originalSubject":"転生したら第七王子だったので、気ままに魔術を極めます","startDate":"2024-04-02"},
            {"animeNo":2986,"subject":"환생했는데 제7왕자라 내맘대로 마술을 연마합니다 2기","originalSubject":"転生したら第七王子だったので、気ままに魔術を極めます","startDate":"2025-07-10"}]"""
        val client = HttpClient(MockEngine { request ->
            val query = request.url.parameters["q"]
            asked += request.url.encodedPath + (query?.let { "?q=$it" } ?: "")
            val content = when {
                query == null -> """[{"animeNo":9,"subject":"실패작","originalSubject":"失敗作","startDate":"2020-01-01"}]"""
                "第七王子" in query -> found
                else -> "[]"
            }
            respond("""{"code":"ok","data":{"content":$content,"totalPages":1}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val catalog = AnissiaTitleCatalog(client)
        assertEquals("환생했는데 제7왕자라 내맘대로 마술을 연마합니다 2기", catalog.title("転生したら第七王子だったので、気ままに魔術を極めます 第2期", "", "2025-07-10", "TV"))
        assertEquals(listOf("/anime/list/0?q=転生したら第七王子だったので、気ままに魔術を極めます"), asked)
        assertEquals("실패작", catalog.title("失敗作", "", "2020-01-01", "TV"))
        assertEquals("/anime/list/0", asked.last(), "a search that finds none reads the catalog")
    }
}
