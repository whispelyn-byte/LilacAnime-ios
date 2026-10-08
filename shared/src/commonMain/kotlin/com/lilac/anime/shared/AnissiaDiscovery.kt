package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import com.lilac.anime.shared.compat.*
import com.lilac.anime.shared.ported.*
import io.ktor.http.Url
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException

/** Anissia maker discovery and linked/RSS post lookup, following Desktop's community flow. */
internal class AnissiaDiscovery(private val repository: SourceRepository) {
    suspend fun search(title: String, episode: Int, episodeKey: String): List<SubtitleAsset> {
        val query = title.replace(Regex("[!?！？.,:;·'\"“”‘’♡♥☆★]"), " ").replace(Regex("\\s+"), " ").trim()
        val root = api("/anime/list/0", mapOf("q" to query)).optJSONObject("data") ?: return emptyList()
        val entries = root.optJSONArray("content") ?: return emptyList()
        fun season(name: String) = Regex("(\\d+)\\s*기|season\\s*(\\d+)|시즌\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)?.toIntOrNull() ?: 1
        val anime = (0 until entries.length()).mapNotNull(entries::optJSONObject)
            .filter { season(it.optString("subject")) == season(title) }
            .maxByOrNull { HangulSimilarityMatcher.similarity(query, it.optString("subject")) } ?: return emptyList()
        if (HangulSimilarityMatcher.similarity(query, anime.optString("subject")) < 0.52) return emptyList()
        val captions = api("/anime/caption/animeNo/" + anime.optInt("animeNo")).optJSONArray("data") ?: return emptyList()
        val output = mutableListOf<SubtitleAsset>()
        for (index in 0 until captions.length()) {
            val maker = captions.optJSONObject(index) ?: continue
            val website = maker.optString("website")
            if (!website.startsWith("https://")) continue
            try {
                val address = Url(website)
                if (address.host in listOf("kairan03.blogspot.com", "csora556.blogspot.com")) continue
                val origin = address.protocol.name + "://" + address.host
                val subject = anime.optString("subject")
                val linked = repository.getText(website)
                val posts = mutableListOf(KairanPost(subject + " " + Ksoup.parse(linked).selectFirst("title")?.text().orEmpty(), website))
                val pages = mutableMapOf(website to linked)
                if (address.host.endsWith(".tistory.com") || address.host.endsWith(".blogspot.com")) {
                    val rss = attempt { repository.getText(origin + if (address.host.endsWith(".blogspot.com")) "/feeds/posts/default?alt=rss" else "/rss") }.orEmpty()
                    val document = Ksoup.parse(rss, parser = Parser.xmlParser())
                    for (item in document.select("item")) {
                        val url = item.selectFirst("link")?.text().orEmpty()
                        if (url.startsWith("https://")) posts += KairanPost(item.selectFirst("title")?.text().orEmpty(), url)
                    }
                    val html = attempt { repository.getText(origin + "/search/" + subject.encodeURLPathPart()) }.orEmpty()
                    val doc = Ksoup.parse(html, origin)
                    posts += doc.select("a[href]").filter { Regex("/(?:entry/)?\\d+$").containsMatchIn(it.attr("href")) }
                        .map { KairanPost(it.text(), it.absUrl("href")) }
                }
                val match = KairanPostMatcher.findBestMatch(subject, episode, posts.distinctBy { it.url }, episodeKey) ?: continue
                val html = pages[match.post.url] ?: repository.getText(match.post.url)
                output += attachments(html, match.post.url, maker.optString("name"), episode)
                output += SubtitleAsset("Anissia · " + maker.optString("name") + " 원본 게시물", match.post.url, "post", match.similarity)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Try the next maker. */ }
        }
        return output.distinctBy { it.url }.sortedByDescending { it.score }
    }
    private suspend fun api(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val root = JSONObject(repository.getText("https://api.anissia.net" + path, params))
        check(root.optString("code").let { it.isEmpty() || it == "ok" }) { "Anissia 요청이 실패했습니다." }
        return root
    }
    private suspend fun attempt(block: suspend () -> String): String? = try { block() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
    private fun attachments(html: String, page: String, maker: String, episode: Int): List<SubtitleAsset> {
        val doc = Ksoup.parse(html, page)
        val links = doc.select("a[href]").map { it.text() to it.absUrl("href") }.toMutableList()
        Regex("aPostFiles\\[\\d+\\]\\s*=\\s*JSON\\.parse\\('(.*?)'\\.replace").findAll(html).forEach { match ->
            val files = runCatching { JSONArray(match.groupValues[1].replace("\\'", "")) }.getOrNull() ?: return@forEach
            for (i in 0 until files.length()) files.optJSONObject(i)?.let { links += it.optString("encodedAttachFileName") to it.optString("encodedAttachFileUrl") }
        }
        return links.mapNotNull { (name, link) ->
            if (!link.startsWith("https://")) return@mapNotNull null
            val driveId = Regex("/file/d/([^/?]+)").find(link)?.groupValues?.get(1) ?: runCatching { Url(link).parameters["id"] }.getOrNull().takeIf { Url(link).host in listOf("drive.google.com", "docs.google.com") }
            val suffix = Url(link).encodedPath.substringAfterLast('.').lowercase()
            if (driveId == null && suffix !in listOf("ass", "ssa", "srt", "vtt", "smi", "zip", "ttml", "sub") && !link.contains("attach", true)) return@mapNotNull null
            SubtitleAsset("Anissia · " + maker + " · " + name.ifBlank { link.substringAfterLast('/') },
                if (driveId != null) "https://drive.google.com/uc?export=download&id=" + driveId else link, "anissia",
                if (SubtitleEpisodeMatcher.matches(name, episode)) 1.0 else 0.6)
        }
    }
}
