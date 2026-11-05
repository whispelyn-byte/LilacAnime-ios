package com.lilac.anime.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The Korean subtitle files of the Korean sites (애니24, 링크애니, Linkkf) for an episode played elsewhere, as tracks beside
// RE:Anime's and Miruro's (desktop d8f7978 korean-site-subtitles.cjs). Most of their videos have the Korean subtitle in
// the picture: only an episode whose player hands a subtitle file (애니24's and 링크애니's raw videos, Linkkf's) gives one.
// Their shows are found by the work's Korean title (the sites' searches take nothing else) and checked on their own
// page: the same Japanese title, or the same AniList entry. The files are timed for that site's video: put on, they are
// synced to the one playing like any subtitle.

data class KoreanSiteHit(val ref: String, val title: String)
data class KoreanSiteEpisode(val number: Int, val special: Boolean, val dub: Boolean, val url: String, val id: String = "")
data class KoreanSiteShow(val anilistId: Int?, val native: String, val episodes: List<KoreanSiteEpisode>)

/** One Korean site as the lookup asks it (SourceRepository; fakes in tests). */
interface KoreanSiteSource {
    /** A catalog that names the AniList entry outright (desktop's Linkkf catalog on disk): its show, then no search. */
    suspend fun byAnilist(anilistId: Int, native: String): String? = null
    suspend fun search(titles: List<String>): List<KoreanSiteHit>
    suspend fun detail(ref: String): KoreanSiteShow
    /** The episode's own Korean subtitle file (its address and the headers it wants), or null without one. */
    suspend fun subtitle(episode: KoreanSiteEpisode): CrossSiteTrack?
}

object KoreanSiteRules {
    private val season = Regex("(\\d+)\\s*기(?![가-힣])|시즌\\s*(\\d+)|(\\d+)(?:st|nd|rd|th)\\s*season|season\\s*(\\d+)", RegexOption.IGNORE_CASE)
    /** A Korean title's season ("약사의 혼잣말 2기", "시즌 2", "2nd Season"), 1 without one. */
    fun koreanSeason(title: String): Int = season.find(normalizeDesktopTitle(title))?.groupValues?.drop(1)?.firstOrNull(String::isNotEmpty)?.toIntOrNull() ?: 1
    /** A Korean title without its season, spacing and punctuation ("약사의 혼잣말 2기" → "약사의혼잣말"). */
    fun koreanBase(title: String): String = CrossSiteTitles.stripMarks(normalizeDesktopTitle(title).lowercase().replace(season, " "))
    /** A Japanese title's season ("薬屋のひとりごと 第2期" → 2), null without one. */
    fun nativeSeason(native: String): Int? = Regex("s(\\d+)").find(CrossSiteTitles.titleKey(native))?.groupValues?.get(1)?.toIntOrNull()
    /** The search hits that may be the work: the same title less its season, of the season the Korean title or the
     *  Japanese one names (a Korean title found without "2기" still finds the second season by "第2期"). */
    fun candidates(hits: List<KoreanSiteHit>, titles: List<String>, native: String): List<KoreanSiteHit> {
        val extra = nativeSeason(native)
        val wanted = titles.map { koreanBase(it) to (listOf(koreanSeason(it)) + listOfNotNull(extra)) }.filter { it.first.length >= 2 }
        return hits.filter { hit -> wanted.any { (base, seasons) -> koreanBase(hit.title) == base && koreanSeason(hit.title) in seasons } }
    }
    /** A show page that is the work: the same Japanese title (seasons written either way), else the same AniList entry.
     *  The Japanese title is trusted first: 애니24's and 링크애니's AniList ids come from a looser lookup. */
    fun sameWork(show: KoreanSiteShow?, native: String, anilistId: Int): Boolean {
        val mine = CrossSiteTitles.titleKey(native); val theirs = CrossSiteTitles.titleKey(show?.native.orEmpty())
        if (mine.isNotEmpty() && theirs.isNotEmpty()) return mine == theirs
        return (show?.anilistId ?: 0) > 0 && show?.anilistId == anilistId
    }
    /** The episode of a site's list: the numbered one (no special, no dub) of that number. */
    fun episodeOf(episodes: List<KoreanSiteEpisode>, number: Int): KoreanSiteEpisode? = episodes.firstOrNull { it.number == number && !it.special && !it.dub }
    /** A Korean site's episode as listed: its label tells a half episode or special ("9.5", "OVA"), its title a dub. */
    fun episode(episode: Episode): KoreanSiteEpisode {
        val label = episode.displayNumber.trim()
        val special = episode.number <= 0 || (label.toIntOrNull() == null &&
            Regex("\\d+\\.\\d+|^\\d+[a-z](?![a-z])|ova|ona|\\bsp\\b|special|특별|극장|스페셜", RegexOption.IGNORE_CASE).containsMatchIn(label))
        return KoreanSiteEpisode(episode.number, special, Regex("더빙|\\bdub").containsMatchIn(episode.title + " " + label), episode.videoUrl.orEmpty(), episode.id)
    }
}

class KoreanSiteSubtitles(
    private val sites: Map<String, KoreanSiteSource>,
    private val store: () -> CrossSiteCacheStore? = { CrossSiteCacheRegistry.store },
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val timeoutMillis: Long = 10_000
) {
    private companion object { val ORDER = listOf("ohli24", "linkani", "linkkf") }
    private val matches = CrossSiteMatchCache(store, now)
    private val lock = Mutex()
    private val details = mutableMapOf<String, Pair<Long, KoreanSiteShow>>()
    private val files = mutableMapOf<String, Pair<Long, CrossSiteTrack>>()

    private suspend fun detail(site: String, ref: String): KoreanSiteShow {
        val key = "$site:$ref"
        lock.withLock { details[key]?.let { (at, show) -> if (now() - at < 10 * 60 * 1000) return show } }
        val show = sites.getValue(site).detail(ref)
        lock.withLock { details[key] = now() to show }
        return show
    }
    /** The site's show of the work, kept for 30 days when found and a day when not; a failed lookup is not kept. */
    private suspend fun show(site: String, anilistId: Int, native: String, titles: List<String>): String? =
        matches.cached("korean:$site:$anilistId") {
            val api = sites.getValue(site)
            var ref: String? = api.byAnilist(anilistId, native)
            val hits = if (ref != null) emptyList() else KoreanSiteRules.candidates(api.search(titles), titles, native).take(3)
            for (hit in hits) {
                val page = try { detail(site, hit.ref) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                if (KoreanSiteRules.sameWork(page, native, anilistId)) { ref = hit.ref; break }
            }
            CrossSiteMatch(ref)
        }?.id

    private suspend fun siteTrack(site: String, anilistId: Int, native: String, titles: List<String>, number: Int): CrossSiteTrack? {
        val ref = show(site, anilistId, native, titles) ?: return null
        val episode = KoreanSiteRules.episodeOf(detail(site, ref).episodes, number) ?: return null
        val key = "$site:$ref:$number"
        lock.withLock { files[key]?.let { (at, track) -> if (now() - at < 30 * 60 * 1000) return track } }
        val track = sites.getValue(site).subtitle(episode)?.copy(label = "한국어", language = "ko", origin = site) ?: return null
        lock.withLock { files[key] = now() to track }
        return track
    }
    /** The Korean sites' files of the episode (never the playing site's), in the order 애니24, 링크애니, Linkkf; each site is
     *  given its time (10 s), and one failing leaves the others. */
    suspend fun tracks(provider: String, anilistId: Int, native: String, koreanTitles: List<String>, number: Int): List<CrossSiteTrack> {
        if (anilistId <= 0 || number <= 0) return emptyList()
        val titles = koreanTitles.filter { it.isNotBlank() }.distinct()
        return coroutineScope {
            ORDER.filter { it != provider && it in sites }.map { site ->
                async {
                    withTimeoutOrNull(timeoutMillis) {
                        try { siteTrack(site, anilistId, native, titles, number) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}

/** Linkkf's subtitle comes only with its player page, which iOS opens in a hidden web view of its own (Swift
 *  KoreanSiteBrowser, desktop resolveLinkkfStream in a partition of its own). The answer is {"url", "headers"} or null. */
interface KoreanSiteBrowser {
    fun linkkfSubtitle(id: String, url: String, completion: (String?, String?) -> Unit)
    fun cancel(id: String)
}
object KoreanSiteBrowserRegistry {
    var browser: KoreanSiteBrowser? = null
    private var sequence = 0L
    internal suspend fun linkkfSubtitle(url: String): CrossSiteTrack? {
        if (browser == null || !url.startsWith("http")) return null
        return withContext(Dispatchers.Main) { ask(url) }
    }
    private suspend fun ask(url: String): CrossSiteTrack? {
        val bridge = browser ?: return null
        val id = (++sequence).toString()
        val answer: String? = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { bridge.cancel(id) }
            bridge.linkkfSubtitle(id, url) { body, error ->
                if (continuation.isActive) { if (error == null) continuation.resume(body) else continuation.resumeWithException(IllegalStateException(error)) }
            }
        }
        val root = answer?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val file = (root["url"] as? JsonPrimitive)?.contentOrNull.orEmpty().takeIf { it.startsWith("http") } ?: return null
        val headers = (root["headers"] as? JsonObject)?.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        return CrossSiteTrack("한국어", file, "ko", "vtt", "linkkf", headers)
    }
}
