package com.lilac.anime.shared

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
}
