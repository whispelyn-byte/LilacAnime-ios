package com.lilac.anime.shared
import kotlin.test.*
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
}
