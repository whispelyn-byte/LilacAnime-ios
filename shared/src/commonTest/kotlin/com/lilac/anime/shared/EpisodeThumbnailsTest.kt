package com.lilac.anime.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

// desktop 0.5.55 main.cjs episodeThumbnails: AniList's streaming episodes over Kitsu's pictures, by the work's ids.
class EpisodeThumbnailsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    @Test fun aniListStreamingEpisodesWinOverKitsuAndKitsuFillsTheRest() = runTest {
        val asked = mutableListOf<String>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            val url = request.url.toString(); asked += url
            when {
                request.url.host == "graphql.anilist.co" -> respond("""{"data":{"Media":{"id":154587,"idMal":52991,"streamingEpisodes":[
                    {"title":"Episode 2 - The Hero's Party","thumbnail":"https://crunchyroll/2.jpg"},{"title":"Recap","thumbnail":"https://crunchyroll/x.jpg"},
                    {"title":"Episode 3 - Killing Magic","thumbnail":"http://insecure/3.jpg"}]}}}""", headers = json)
                "mappings" in url -> respond("""{"data":[],"included":[{"type":"anime","id":"46474"}]}""", headers = json)
                "/anime/46474/episodes" in url -> respond("""{"meta":{"count":3},"data":[
                    {"attributes":{"number":1,"thumbnail":{"original":"https://kitsu/1.jpg"}}},
                    {"attributes":{"number":2,"thumbnail":{"original":"https://kitsu/2.jpg"}}},
                    {"attributes":{"number":3,"thumbnail":{"large":"https://kitsu/3.jpg"}}}]}""", headers = json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        } } }
        val thumbs = EpisodeThumbnailRepository(client).thumbnails(Anime(id = "frieren", title = "장송의 프리렌", english = "Frieren", malId = 52991))
        assertEquals(mapOf(1 to "https://kitsu/1.jpg", 2 to "https://crunchyroll/2.jpg", 3 to "https://kitsu/3.jpg"), thumbs)
        assertTrue(asked.any { "myanimelist" in it.decodeURLQueryComponent() && "52991" in it }, asked.toString())
        client.close()
    }
    @Test fun aLongSeriesIsReadPageByPageUpTo600Episodes() = runTest {
        val offsets = mutableListOf<Int>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            val url = request.url.toString().decodeURLQueryComponent()
            when {
                request.url.host == "graphql.anilist.co" -> respond("""{"data":{"Media":null}}""", headers = json)
                "mappings" in url -> respond("""{"included":[{"type":"anime","id":"12"}]}""", headers = json)
                "/episodes" in url -> {
                    val offset = Regex("page\\[offset]=(\\d+)").find(url)!!.groupValues[1].toInt(); offsets += offset
                    respond("""{"meta":{"count":1100},"data":[{"attributes":{"number":${offset + 1},"thumbnail":{"original":"https://kitsu/${offset + 1}.jpg"}}}]}""", headers = json)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        } } }
        val thumbs = EpisodeThumbnailRepository(client).thumbnails(Anime(id = "op", title = "One Piece", anilistId = 21))
        assertEquals(30, offsets.size); assertEquals(580, offsets.max())
        assertEquals("https://kitsu/581.jpg", thumbs[581])
        client.close()
    }
    @Test fun aKoreanOnlyWorkWithoutIdsAsksNothing() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine) { engine { addHandler { calls++; respond("", HttpStatusCode.NotFound) } } }
        assertTrue(EpisodeThumbnailRepository(client).thumbnails(Anime(id = "x", title = "한국어 제목")).isEmpty())
        assertEquals(0, calls)
        client.close()
    }
}
