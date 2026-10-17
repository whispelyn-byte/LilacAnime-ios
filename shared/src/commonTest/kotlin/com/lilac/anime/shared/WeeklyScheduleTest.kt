package com.lilac.anime.shared
import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*

class WeeklyScheduleTest {
    @Test fun koreaBoundaryAndInvalidTime() {
        assertEquals("2026-10-17T00:00:00Z", WeeklySchedule.korea("2026-10-16T15:00:00Z"))
        assertNull(WeeklySchedule.korea("bad"))
    }
    @Test fun reanimeDeduplicatesRawAndSubKeepsDubAndSharesWeek() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("""{"schedule":[{"date":"2026-10-17","episodes":[
                {"anime_id":"show","title":"Show","episode_number":2,"episode_date":"2026-10-16T15:30:00Z","air_type":"raw"},
                {"anime_id":"show","title":"Show","episode_number":2,"episode_date":"2026-10-16T15:30:00Z","air_type":"sub"},
                {"anime_id":"show","title":"Show","episode_number":2,"episode_date":"2026-10-16T15:30:00Z","air_type":"dub"}
            ]},{"date":"2026-10-12","episodes":[]}]}""")
        })
        val repository = SourceRepository(client)
        try {
            val first = async { repository.weeklySchedule("reanime", 6, "2026-10-17", "2026-10-12") }
            val second = async { repository.weeklySchedule("reanime", 6, "2026-10-17", "2026-10-12") }
            val result = first.await(); assertTrue(result.native); assertTrue(result.timed)
            assertEquals(listOf("sub", "dub"), result.items.map { it.track })
            assertEquals(listOf("00:30", "00:30"), result.items.map { it.time })
            assertEquals(result, second.await())
            assertTrue(repository.weeklySchedule("reanime", 1, "2026-10-12", "2026-10-12").items.isEmpty())
            assertEquals(1, calls)
        } finally { repository.close() }
    }
    @Test fun failureFallsBackButRetryRecoversAndSundayUsesLastLinkkfTag() = runTest {
        var fail = true; var calls = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "api.anissia.net") respond("""{"code":"ok","data":[{"animeNo":1,"subject":"Fallback","time":"01:00","status":"OFF"}]}""")
            else {
                calls++; assertEquals("21195", request.url.parameters["categorytagid"]); assertEquals("200", request.url.parameters["limit"])
                respond(if (fail) "{}" else """{"data":[]}""")
            }
        })
        val repository = SourceRepository(client)
        try {
            val fallback = repository.weeklySchedule("linkkf", 0, "2026-10-18", "2026-10-12")
            assertFalse(fallback.native); assertTrue(fallback.items.single().cancelled)
            fail = false
            val result = repository.weeklySchedule("linkkf", 0, "2026-10-18", "2026-10-12")
            assertTrue(result.native); assertFalse(result.timed); assertTrue(result.items.isEmpty())
            repository.weeklySchedule("linkkf", 0, "2026-10-18", "2026-10-12")
            assertEquals(2, calls)
            assertFailsWith<IllegalArgumentException> { repository.weeklySchedule("unknown", 0, "2026-10-18", "2026-10-12") }
        } finally { repository.close() }
    }
    @Test fun animenosubKeepsSourceWeekdaysWithoutInventingTimes() = runTest {
        val client = HttpClient(MockEngine { respond("""<div class="schedulepage sch_monday"></div><div class="schedulepage sch_sunday"></div>""") })
        val repository = SourceRepository(client)
        try {
            val result = repository.weeklySchedule("animenosub", 1, "2026-10-12", "2026-10-12")
            assertTrue(result.native); assertFalse(result.timed); assertTrue(result.items.isEmpty())
        } finally { repository.close() }
    }

    @Test fun miruroPaginatesAndJoinsOnlyTheKoreanDay() = runTest {
        var schedules = 0
        val client = HttpClient(MockEngine { request ->
            val encoded = if (request.url.encodedPath.endsWith("/anime")) {
                assertEquals("show", request.url.parameters["id_in"])
                "cuJ6dXJvL2Nhi3ymJG3tSWYzk8JznRCQvQFb62WgOW8kzZhk6Q9u9/GIeTFRc+ipLQzAfbjxYF3vEXt5P2pTz6ATthwHP1arAKvuiQTmN+kpoDVOE6RsdoWtdrcegDowPsCXaKmL/ydIY2F0"
            } else {
                schedules++
                assertEquals("2026-10-11", request.url.parameters["from"])
                assertEquals("2026-10-18", request.url.parameters["to"])
                if (request.url.parameters["cursor"] == null) "cuJ6dXJvL2Nhi3SnLm2uSWIzk8QjnLv05zqH+87C9lUxTIJodFLnaC2JMzXMeqzqevnhHd9MSoJ1u3hTbjXkdOdUKO/bKc50j5Wej9O4XIyYDgRAseJTiRZklgllJ7JRdSXkDnnqL9iFHoz9mmOaeujiHnVybw==" else "cuJ6dXJvL2Nhi3ygVG3tWWIwo8IjzDfsSTajXcF9e9Z7d+tsUBZAgjLT0HWceNUmDre8pFRAsWFqSG6mBE4MC+kisjtlSYe1AhuYqXuhTbiMS5N546QLzuykz0Dlvu38zemelktyTpLcYX5rlpkIZ21p"
            }
            respond(Base64.decode(encoded))
        })
        val repository = SourceRepository(client)
        try {
            val result = repository.weeklySchedule("miruro", 6, "2026-10-17", "2026-10-12")
            assertTrue(result.native); assertEquals(2, schedules)
            assertEquals("00:00", result.items.single().time)
            assertEquals("show", result.items.single().anime?.id)
            assertEquals("https://example.test/cover.jpg", result.items.single().anime?.poster)
        } finally { repository.close() }
    }
    @Test fun ohliPreservesWeekdayAndLinkaniFollowsNextPage() = runTest {
        var pages = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.host.contains("ohli")) {
                assertEquals("토요일", request.url.parameters["weekday"])
                respond("""<script data-page="app" type="application/json">{"props":{"sca":"토요일","items":[{"slug":"sat","title":"Saturday"}]}}</script>""")
            } else {
                pages++
                val id = if (request.url.encodedPath.contains("page/2")) "2" else "1"
                val next = if (id == "1") "<a href='page/2/'>Next</a>" else ""
                respond("""<div class="vod-list"><div class="vod-item"><a href="/ani/$id/"><div class="vod-item-title">Show $id</div></a></div></div>$next""")
            }
        })
        val repository = SourceRepository(client)
        try {
            val ohli = repository.weeklySchedule("ohli24", 6, "2026-10-17", "2026-10-12")
            assertTrue(ohli.native); assertFalse(ohli.timed); assertEquals("sat", ohli.items.single().anime?.id)
            val linkani = repository.weeklySchedule("linkani", 6, "2026-10-17", "2026-10-12")
            assertTrue(linkani.native); assertEquals(2, pages); assertEquals(2, linkani.items.size)
        } finally { repository.close() }
    }
}
