package com.lilac.anime.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// Subtitle tracks for an episode played on another site (애니24, 링크애니, Linkkf, Animenosub, a Miruro episode without
// tracks of its own; desktop 77e3d7e cross-site-tracks.cjs): RE:Anime serves an episode's tracks by its AniList entry and
// number alone (/api/flix/<anilistId>/<n>), so the work is found on AniList by the site's Japanese title and air year
// (else its English title) and the tracks are asked for by that; Miruro's by a title search matched on that entry.
// They are timed for that site's video, not the one playing: the player syncs them to the episode's audio like any
// subtitle put on, and never takes them as the timing reference for another subtitle.

/** A subtitle track of another site's same episode. Some providers return the already verified file. */
data class CrossSiteTrack(
    val label: String,
    val url: String,
    val language: String,
    val format: String,
    val origin: String,
    val headers: Map<String, String> = emptyMap(),
    val contentBase64: String? = null
)

/** What the cross-site lookup needs of the work playing: the site, its own id, its titles, air year and AniList id. */
data class CrossSiteWork(
    val provider: String,
    val id: String,
    val title: String = "",
    val english: String = "",
    val native: String = "",
    val year: String = "",
    val aired: String = "",
    val anilistId: Int? = null
) {
    companion object {
        fun of(anime: Anime, provider: String = anime.source) = CrossSiteWork(provider, anime.id, anime.title, anime.english, anime.native, anime.year, anime.airedDate, anime.anilistId)
    }
}

/** An AniList entry as the lookup compares them. */
data class AnilistMedia(
    val id: Int,
    val episodes: Int? = null,
    val seasonYear: Int? = null,
    val startYear: Int? = null,
    val native: String = "",
    val english: String = "",
    val romaji: String = "",
    val synonyms: List<String> = emptyList()
) { val year: Int? get() = seasonYear ?: startYear }

object CrossSiteTitles {
    private val kanaKanji = Regex("[぀-ヿ㐀-鿿]")
    private val hangul = Regex("[가-힣]")
    private val englishSeason = Regex("\\b(\\d+)(?:st|nd|rd|th)\\s+season\\b|\\bseason\\s+(\\d+)\\b")
    // Japanese seasons: 애니24 writes "ダンダダン 2期" where AniList has "ダンダダン 第2期" ("第二期", "2シーズン" too).
    private val japaneseSeason = Regex("第?\\s*([0-9]+|[一二三四五六七八九])\\s*(?:期|シーズン)")
    private val uncensored = Regex("\\(?\\buncensored\\b\\)?")
    private val year = Regex("(?:19|20)\\d{2}")
    private val ignored = setOf(
        CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION, CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION, CharCategory.OTHER_PUNCTUATION,
        CharCategory.MATH_SYMBOL, CharCategory.CURRENCY_SYMBOL, CharCategory.MODIFIER_SYMBOL, CharCategory.OTHER_SYMBOL
    )

    fun hasHangul(value: String) = hangul.containsMatchIn(value)

    /** A title as compared: NFKC, lower case, seasons written one way (s2), "uncensored", spaces, punctuation and symbols left out. */
    fun titleKey(value: String): String {
        val kanji = "一二三四五六七八九"
        return normalizeDesktopTitle(value).lowercase()
            .replace(englishSeason) { " s" + it.groupValues[1].ifEmpty { it.groupValues[2] } + " " }
            .replace(japaneseSeason) { match ->
                val n = match.groupValues[1]
                " s" + (n.toIntOrNull() ?: (kanji.indexOf(n) + 1)) + " "
            }
            .replace(uncensored, " ")
            .let(::stripMarks)
    }
    /** Spaces, punctuation and symbols left out (desktop /[\s\p{P}\p{S}]/gu). */
    internal fun stripMarks(value: String): String = value.filterNot { it.isWhitespace() || it.category in ignored }
    /** The site's Japanese title, when written in kana or kanji (never one in Hangul). */
    fun nativeOf(value: String): String = value.trim().takeIf { it.isNotEmpty() && kanaKanji.containsMatchIn(it) && !hasHangul(it) }.orEmpty()
    fun yearOf(year: String, aired: String): Int? = (this.year.find(year) ?: this.year.find(aired))?.value?.toIntOrNull()

    /** Of the entries with the same title, the one of the air year (a remake shares its title), else one a year off (a
     *  show of late December), else the only one; any of them when the year is not known. */
    fun byYear(same: List<AnilistMedia>, year: Int?): AnilistMedia? {
        if (year == null) return same.firstOrNull()
        return same.firstOrNull { it.year == year } ?: same.firstOrNull { kotlin.math.abs((it.year ?: 0) - year) <= 1 } ?: same.singleOrNull()
    }
    fun pickByNative(list: List<AnilistMedia>, native: String, year: Int?): AnilistMedia? {
        val key = titleKey(native); if (key.isEmpty()) return null
        return byYear(list.filter { titleKey(it.native) == key }, year)
    }
    fun pickByTitle(list: List<AnilistMedia>, title: String, year: Int?): AnilistMedia? {
        val key = titleKey(title); if (key.isEmpty()) return null
        return byYear(list.filter { media -> (listOf(media.english, media.romaji) + media.synonyms).filter(String::isNotBlank).map(::titleKey).contains(key) }, year)
    }
    /** A track's language for telling the same one of two sites apart: "ko", "en", "ja", else the first word of its language. */
    fun languageKey(language: String, label: String): String {
        val text = "$language $label".lowercase()
        if (Regex("(?:^|[^a-z])(?:ko|kor)(?:[^a-z]|$)|korean|한국").containsMatchIn(text)) return "ko"
        if (Regex("(?:^|[^a-z])(?:en|eng)(?:[^a-z]|$)|english").containsMatchIn(text)) return "en"
        if (Regex("(?:^|[^a-z])(?:ja|jpn)(?:[^a-z]|$)|japanese|日本").containsMatchIn(text)) return "ja"
        return language.ifBlank { label }.lowercase().split(Regex("[\\s(_-]")).firstOrNull()?.ifBlank { null } ?: "und"
    }
    /** The tracks of both sites in one list: RE:Anime's, then Miruro's of a language RE:Anime has none of. */
    fun mergeTracks(reanime: List<CrossSiteTrack>, miruro: List<CrossSiteTrack>): List<CrossSiteTrack> {
        val have = reanime.map { languageKey(it.language, it.label) }.toSet()
        return reanime + miruro.filter { languageKey(it.language, it.label) !in have }
    }
}

/** A Re:Anime / FlixCloud player page's subtitle list (desktop parseFlixSubtitleTracks, flix-discovery.js). */
object FlixSubtitleTracks {
    private val entry = Regex("\\{([^{}]*?url:\"[^\"]+\"[^{}]*?)\\}")
    private val field = Regex("(?:^|,)\\s*(url|language|label|format):\"((?:\\\\.|[^\"])*)\"")
    fun parse(html: String): List<CrossSiteTrack> {
        // Track names can contain brackets ("English [nedragrevev]"), so the closing ']' is found outside strings.
        val start = Regex("subtitles:\\[").find(html)?.range?.first ?: return emptyList()
        val open = html.indexOf('[', start)
        var end = -1; var depth = 0; var quote = 0.toChar(); var i = open
        while (i < html.length) {
            val c = html[i]
            if (quote != 0.toChar()) { if (c == '\\') i++ else if (c == quote) quote = 0.toChar() }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '[') depth++
            else if (c == ']' && --depth == 0) { end = i; break }
            i++
        }
        if (end <= 0) return emptyList()
        val block = html.substring(open + 1, end)
        val tracks = mutableListOf<CrossSiteTrack>()
        for (match in entry.findAll(block)) {
            val fields = field.findAll(match.groupValues[1]).associate { it.groupValues[1] to decode(it.groupValues[2]) }
            val url = fields["url"].orEmpty().replace("\\u0026", "&")
            if (!Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(url)) continue
            val language = fields["language"].orEmpty().ifEmpty { "und" }
            val format = fields["format"].orEmpty().ifEmpty {
                if (Regex("\\.srt(?:$|\\?)", RegexOption.IGNORE_CASE).containsMatchIn(url)) "srt" else if (Regex("\\.ass(?:$|\\?)", RegexOption.IGNORE_CASE).containsMatchIn(url)) "ass" else "vtt"
            }.lowercase()
            tracks += CrossSiteTrack(fields["label"].orEmpty().ifEmpty { language }, url, language, format, "reanime")
        }
        return tracks.distinctBy { it.url }
    }
    private fun decode(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching { Json.decodeFromString<String>("\"$value\"").trim() }
            .getOrElse { value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/").trim() }
    }
}

/** Where the AniList and Miruro matches are kept between launches (iOS: a file beside the app's caches); the lookup
 *  keeps them in memory without one. */
interface CrossSiteCacheStore {
    fun read(): String?
    fun write(value: String)
}
object CrossSiteCacheRegistry { var store: CrossSiteCacheStore? = null }

/** What the lookup asks of the sites (DesktopSourceRepository; fakes in tests). */
interface CrossSiteSources {
    suspend fun searchAnilist(text: String): List<AnilistMedia>
    /** RE:Anime's tracks of an AniList entry's episode, each with the Referer its file wants. */
    suspend fun reanimeTracks(anilistId: Int, number: Int): List<CrossSiteTrack>
    /** Miruro's own id of an AniList entry, found by its titles, or null. */
    suspend fun miruroId(anilistId: Int, titles: List<String>): String?
    suspend fun miruroTracks(miruroId: String, number: Int): List<CrossSiteTrack>
}

/** A kept match: the AniList entry (id), Miruro's id, or a Korean site's show (id); null id when there is none. native:
 *  the entry's original title as written (Latin ones too), what the Korean sites' show pages are checked by. */
@Serializable
internal data class CrossSiteMatch(val id: String? = null, val episodes: Int? = null, val titles: List<String> = emptyList(), val at: Long = 0, val native: String = "")

/** The matches kept in the store: 30 days when found, a day when not; a lookup that failed (unreachable, a rate limit) is
 *  not kept. One lookup per key at a time: the others wait for its answer. */
internal class CrossSiteMatchCache(private val store: () -> CrossSiteCacheStore?, private val now: () -> Long) {
    private companion object { const val DAY = 24L * 60 * 60 * 1000 }
    private val lock = Mutex()
    private var disk: MutableMap<String, CrossSiteMatch>? = null
    private val pending = mutableMapOf<String, Deferred<CrossSiteMatch>>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun loaded(): MutableMap<String, CrossSiteMatch> = disk ?: (read() ?: mutableMapOf()).also { disk = it }
    private fun read(): MutableMap<String, CrossSiteMatch>? =
        runCatching { store()?.read()?.let { json.decodeFromString(MapSerializer(String.serializer(), CrossSiteMatch.serializer()), it) } }.getOrNull()?.toMutableMap()
    /** Several lookups (one per player, download, next episode) share the store: what another kept meanwhile is merged,
     *  the newer of two answers winning. */
    private fun save(key: String, match: CrossSiteMatch) {
        val all = read() ?: mutableMapOf()
        for ((name, entry) in loaded()) if ((all[name]?.at ?: -1) < entry.at) all[name] = entry
        all[key] = match; disk = all
        runCatching { store()?.write(json.encodeToString(MapSerializer(String.serializer(), CrossSiteMatch.serializer()), all)) }
    }
    suspend fun cached(key: String, load: suspend () -> CrossSiteMatch): CrossSiteMatch? {
        var owned: CompletableDeferred<CrossSiteMatch>? = null
        val task = lock.withLock {
            loaded()[key]?.let { known -> if (now() - known.at < (if (known.id != null) 30 * DAY else DAY)) return known.takeIf { it.id != null } }
            pending[key] ?: CompletableDeferred<CrossSiteMatch>().also { pending[key] = it; owned = it }
        }
        owned?.let { deferred ->
            try {
                val entry = load().copy(at = now())
                lock.withLock { save(key, entry); pending.remove(key) }
                deferred.complete(entry)
            } catch (error: Throwable) {
                lock.withLock { pending.remove(key) }
                deferred.completeExceptionally(error); throw error
            }
        }
        return task.await().takeIf { it.id != null }
    }
}

class CrossSiteTrackLookup(
    private val sources: CrossSiteSources,
    private val store: () -> CrossSiteCacheStore? = { CrossSiteCacheRegistry.store },
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val koreanSites: KoreanSiteSubtitles? = null
) {
    private companion object {
        // Sites whose AniList id is the site's own (Re:Anime's and Miruro's catalogs, Linkkf's field). 애니24's and 링크애니's
        // come from a looser lookup (any show of the same year when the title does not match), so theirs is checked again
        // by the Japanese title.
        val TRUSTED_IDS = setOf("reanime", "miruro", "linkkf")
    }
    private val lock = Mutex()
    private val matches = CrossSiteMatchCache(store, now)
    private val trackCache = mutableMapOf<String, Pair<Long, List<CrossSiteTrack>>>()
    private suspend fun cached(key: String, load: suspend () -> CrossSiteMatch) = matches.cached(key, load)
    /** The original title as written, Latin letters too ("NEW GAME!"), never a Korean one. */
    private fun originalTitle(value: String) = value.trim().takeIf { it.isNotEmpty() && !CrossSiteTitles.hasHangul(it) }.orEmpty()
    private fun titles(work: CrossSiteWork): List<String> =
        (listOf(CrossSiteTitles.nativeOf(work.native)) + listOf(work.english, work.title).filter { it.isNotBlank() && !CrossSiteTitles.hasHangul(it) }).filter(String::isNotBlank)

    private suspend fun lookup(work: CrossSiteWork): AnilistMedia? {
        val native = CrossSiteTitles.nativeOf(work.native); val year = CrossSiteTitles.yearOf(work.year, work.aired)
        if (native.isNotEmpty()) CrossSiteTitles.pickByNative(sources.searchAnilist(native), native, year)?.let { return it }
        work.anilistId?.takeIf { it > 0 }?.let { return AnilistMedia(it) }
        for (title in titles(work).filter { it != native }.distinct()) {
            CrossSiteTitles.pickByTitle(sources.searchAnilist(title), title, year)?.let { return it }
        }
        return null
    }
    /** The AniList entry of a site's show, or null; its titles are what Miruro is searched by. */
    internal suspend fun anilistEntry(work: CrossSiteWork): CrossSiteMatch? {
        if (work.provider in TRUSTED_IDS && (work.anilistId ?: 0) > 0) return CrossSiteMatch(work.anilistId.toString(), null, titles(work), native = originalTitle(work.native))
        return cached(work.provider + ":" + work.id.ifBlank { work.title }) {
            val media = lookup(work)
            val names = (listOf(media?.native.orEmpty(), media?.english.orEmpty(), media?.romaji.orEmpty()) + titles(work)).filter(String::isNotBlank).distinct()
            CrossSiteMatch(media?.id?.toString(), media?.episodes, names, native = originalTitle(media?.native?.ifBlank { null } ?: work.native))
        }
    }
    /** The episode's numbered AniList entry, or null (a special or dub, a number past the entry's episodes). */
    private suspend fun episodeEntry(work: CrossSiteWork, number: Int, special: Boolean, dub: Boolean): CrossSiteMatch? {
        if (special || dub || number <= 0) return null
        val entry = anilistEntry(work) ?: return null
        if (entry.id?.toIntOrNull() == null || (entry.episodes != null && number > entry.episodes)) return null
        return entry
    }
    /** The Korean sites' files of the episode (애니24, 링크애니, Linkkf; never the one playing; desktop d8f7978), asked apart
     *  from tracks() because they come slower. koreanTitles: the work's Korean titles, what those sites are searched by. */
    suspend fun koreanSiteTracks(work: CrossSiteWork, number: Int, special: Boolean = false, dub: Boolean = false, koreanTitles: List<String>): List<CrossSiteTrack> {
        val sites = koreanSites ?: return emptyList()
        val entry = episodeEntry(work, number, special, dub) ?: return emptyList()
        val native = entry.native.ifBlank { entry.titles.firstOrNull { CrossSiteTitles.nativeOf(it).isNotEmpty() }.orEmpty() }
        return try { sites.tracks(work.provider, entry.id!!.toInt(), native, koreanTitles, number) }
            catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
    }
    private suspend fun miruroId(entry: CrossSiteMatch): String? =
        cached("miruro:" + entry.id) { CrossSiteMatch(sources.miruroId(entry.id!!.toInt(), entry.titles)) }?.id

    /** The episode's tracks on the other sites (RE:Anime, Miruro; never the one playing). None for a special or dub (no
     *  numbered episode of the entry) or a number past the entry's episodes (a site numbering a later season on from the
     *  first). Kept for half an hour; an empty answer is asked again, and one site failing leaves the other's tracks. */
    suspend fun tracks(work: CrossSiteWork, number: Int, special: Boolean = false, dub: Boolean = false): List<CrossSiteTrack> {
        val entry = episodeEntry(work, number, special, dub) ?: return emptyList()
        val anilist = entry.id!!.toInt()
        val key = work.provider + ">" + anilist + ":" + number
        lock.withLock { trackCache[key]?.let { (at, tracks) -> if (now() - at < 30 * 60 * 1000) return tracks } }
        val list = coroutineScope {
            val reanime = async { if (work.provider == "reanime") emptyList() else safely { sources.reanimeTracks(anilist, number) } }
            val miruro = async { if (work.provider == "miruro") emptyList() else safely { miruroId(entry)?.let { sources.miruroTracks(it, number) } ?: emptyList() } }
            CrossSiteTitles.mergeTracks(reanime.await(), miruro.await())
        }
        lock.withLock { if (list.isEmpty()) trackCache.remove(key) else trackCache[key] = now() to list }
        return list
    }
    private suspend fun safely(load: suspend () -> List<CrossSiteTrack>): List<CrossSiteTrack> =
        try { load() } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
}
