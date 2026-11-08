package com.lilac.anime.android.subtitle

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.android.data.AppPreferences
import com.lilac.anime.android.data.SecureKeys
import com.lilac.anime.android.data.await
import com.lilac.anime.android.player.PlaybackItem
import com.lilac.anime.android.player.RemoteSubtitle
import com.lilac.anime.android.player.ResolvedStream
import com.lilac.anime.android.player.SubtitlePolicy
import com.lilac.anime.shared.*
import com.lilac.anime.shared.ported.SubtitleEpisodeMatcher
import kotlinx.coroutines.*
import java.io.File

/** The episode the subtitle sites are searched for: its label ("12", "9.5", "4a"), or its number when the label is a title. */
val PlaybackItem.subtitleEpisodeKey: String
    get() = if (Regex("^\\d+(?:\\.\\d+)?[a-z]?$", RegexOption.IGNORE_CASE).matches(displayNumber)) displayNumber else number.toString()

/** A subtitle found: its file and where it came from (kairan, reanime, jimaku, …). */
/** The sites whose Korean subtitle file is read from their pages (slow): a work known to have one there waits for it. */
val KOREAN_SLOW_SITES = setOf("ohli24", "linkani", "linkkf")

/** track: another site's track it came from (put on, it is synced to this video's audio). */
data class Found(val file: File, val provider: String, val track: RemoteSubtitle? = null)

/** Selection rules from desktop app.js ensureSubtitle (iOS DesktopSubtitlePolicy). */
object DesktopSubtitlePolicy {
    fun source(value: String?) = when (value) { null, "auto", "한국어 트랙" -> "reanime"; "선택" -> "user"; else -> value }
    fun providers(preferred: String?): List<String> {
        val all = listOf("kairan", "csora", "anissia"); val wanted = source(preferred)
        return if (wanted in all) listOf(wanted) + all.filter { it != wanted } else all
    }
    fun translationTrack(tracks: List<RemoteSubtitle>, language: String? = null): RemoteSubtitle? {
        fun pick(lang: String): RemoteSubtitle? {
            val code = Regex(if (lang == "ja") "^(?:ja|jpn)(?:[-_]|$)" else "^(?:en|eng)(?:[-_]|$)", RegexOption.IGNORE_CASE)
            val name = Regex(if (lang == "ja") "japanese|日本|일본" else "english|영어", RegexOption.IGNORE_CASE)
            fun rank(item: RemoteSubtitle) = when {
                Regex("dubtitle|\\(ai\\)|\\bai\\b", RegexOption.IGNORE_CASE).containsMatchIn(item.label) -> 2
                Regex("dialogue|full", RegexOption.IGNORE_CASE).containsMatchIn(item.label) -> 0
                else -> 1
            }
            return tracks.withIndex().filter { (_, item) ->
                !Regex("signs|songs|forced", RegexOption.IGNORE_CASE).containsMatchIn(item.label) && (code.containsMatchIn(item.language) || name.containsMatchIn(item.label + " " + item.language))
            }.minWithOrNull(compareBy({ rank(it.value) }, { it.index }))?.value
        }
        return if (language != null) pick(language) else pick("ja") ?: pick("en")
    }
    fun preferredSaved(saved: List<SavedSubtitle>, preferred: String?): SavedSubtitle? {
        val wanted = source(preferred)
        if (wanted in listOf("reanime", "jimaku")) saved.firstOrNull()?.takeIf { it.translated }?.let { return it }
        return saved.firstOrNull { source(it.provider) == wanted && !it.translated }
    }
    /** A stream said to have Korean in the picture has; 애니24's does unless its player puts one over it from a file. */
    fun burnedKorean(source: String, stream: ResolvedStream? = null): Boolean {
        if (stream?.burnedKorean == true) return true
        if (source == "ohli24") return stream?.subtitles?.any(SubtitlePolicy::isKorean) != true
        stream?.burnedKorean?.let { return it }
        return source == "linkani" && stream?.subtitles?.any(SubtitlePolicy::isKorean) != true
    }
    fun siteKorean(source: String, tracks: List<RemoteSubtitle>, stream: ResolvedStream? = null) =
        burnedKorean(source, stream) || source == "linkkf" || tracks.any(SubtitlePolicy::isKorean)
}

/** app.js loadJimakuList (iOS DesktopJimakuCatalog): one listing per episode, an empty one too; a failed one is retried
 *  when opened. */
class JimakuCatalog(private val service: IosServices, private val context: Context) {
    var revision by mutableIntStateOf(0); private set
    private class Listing { var files: List<SubtitleAsset>? = null; var error: String? = null; var task: Deferred<List<SubtitleAsset>>? = null }
    private val listings = mutableMapOf<String, Listing>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val defaults = context.getSharedPreferences("lilac", Context.MODE_PRIVATE)
    private fun key(item: PlaybackItem) = item.anime.id + "#" + item.episodeID
    fun files(item: PlaybackItem) = listings[key(item)]?.files
    fun error(item: PlaybackItem) = listings[key(item)]?.error

    suspend fun load(item: PlaybackItem, retryFailure: Boolean = false): List<SubtitleAsset> {
        val entry = listings.getOrPut(key(item)) { Listing() }
        if (retryFailure && entry.error != null) { entry.task = null; entry.error = null }
        entry.task?.let { return it.await() }
        val task = scope.async {
            try {
                val anilist = item.anime.anime.anilistId ?: 0
                if (anilist <= 0) throw IllegalStateException("AniList 작품을 찾지 못해 Jimaku를 검색할 수 없습니다.")
                val files = service.await<List<SubtitleAsset>> { findSubtitles("jimaku", item.anime.title, item.number, item.subtitleEpisodeKey, anilist, it) }.getOrThrow()
                val previous = defaults.getString("jimaku-pick:" + item.anime.id, null)
                    ?: EpisodeSubtitleStore.records.filter { it.episodeKey.startsWith(item.anime.id + "#") && it.provider == "jimaku" && !it.translated }.maxByOrNull { it.date }?.name ?: ""
                JimakuRules.prefer(files, previous).also { entry.files = it; entry.error = null; revision++ }
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) {
                entry.files = null; entry.error = failure.message; revision++; emptyList()
            }
        }
        entry.task = task; revision++
        return task.await()
    }
    fun remember(name: String, item: PlaybackItem) { defaults.edit().putString("jimaku-pick:" + item.anime.id, name).apply() }
    fun cancel() { listings.values.forEach { it.task?.cancel() }; listings.clear(); revision++ }
}

/** iOS DesktopSubtitlePreparer: the subtitle an episode opens with — saved, the site's Korean track, the Korean blogs
 *  (Kairan · Csora · Anissia), else a source to translate (the site's Japanese track, Jimaku, an English track). */
class SubtitlePreparer(private val context: Context) {
    private val service = IosServices()
    val jimaku = JimakuCatalog(service, context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val searches = mutableMapOf<String, Deferred<Found?>>()
    private val contexts = mutableMapOf<String, Triple<String, Int, List<Int>>>()
    private val defaults = context.getSharedPreferences("lilac", Context.MODE_PRIVATE)

    /**
     * desktop app.js ensureSubtitle, step for step: the preferred source's saved file, the stream's own subtitle, any
     * saved file, then the other site's Korean track and the blogs' (Kairan / Csora / Anissia) asked at once, then a
     * source to translate.
     * tracks: the stream's own and the other sites' tracks found so far. korean: the player's search for the episode
     * (KoreanSearch), whose Korean subtitle can still come after this returns; without it (a download, the next episode)
     * the other sites' tracks found so far and the blogs are taken in turn.
     * status: what the player says it is doing (desktop #subtitleState).
     */
    suspend fun prepare(item: PlaybackItem, tracks: List<RemoteSubtitle>, preferences: AppPreferences, prefersAI: Boolean = false, skipSaved: Boolean = false,
                        stream: ResolvedStream? = null, korean: KoreanSearch? = null, status: (String?) -> Unit = {}): Found? {
        val later: (suspend () -> List<RemoteSubtitle>)? = korean?.let { search -> suspend { search.othersAfter() } }
        if (preferences.subtitleProvider == "manual" || DesktopSubtitlePolicy.burnedKorean(item.anime.source, stream)) return null
        val source = item.anime.source
        val saved = if (skipSaved) emptyList() else EpisodeSubtitleStore.list(item)
        // The site's own tracks (애니24's file is marked with its own origin); the rest are other sites' of the episode.
        val own = tracks.filter { it.origin == null || it.origin == source }
        val others = tracks.filter { it.origin != null && it.origin != source }
        if (prefersAI) {
            saved.firstOrNull { it.translated }?.file?.let { return Found(it, saved.first { s -> s.translated }.provider) }
            translationSource(item, tracks, preferences, later = later)?.let { return it }
        }
        DesktopSubtitlePolicy.preferredSaved(saved, preferences.subtitleProvider)?.let { record -> record.file?.let { return Found(it, record.provider) } }
        // The stream's own subtitle: a Korean track; Linkkf's, 링크애니's and 애니24's file is the Korean subtitle itself.
        val ownKorean = if (source in setOf("linkkf", "linkani", "ohli24")) own else own.filter(SubtitlePolicy::isKorean)
        for (track in ownKorean) {
            currentCoroutineContext().ensureActive()
            runCatching { SubtitleFiles.prepare(track.url, track.headers ?: emptyMap()) }.getOrNull()?.let { files -> select(files, item)?.let { return Found(it, if (source == "linkkf") "linkkf" else "reanime") } }
        }
        // A downloaded episode's subtitle was picked for it when it was saved (desktop: the stream's own subtitle).
        val local = item.localSubtitles.map(::File).filter { it.isFile }
        local.firstOrNull { SubtitleFiles.isKorean(it) }?.let { return Found(it, "download") }
        local.firstOrNull()?.let { return Found(it, "download") }
        saved.firstOrNull()?.let { record -> record.file?.let { return Found(it, record.provider) } }
        val searchable = source != "local"
        if (korean == null) {
            // Another site's Korean track of this very episode, before the blogs (matched only by title).
            for (track in others.filter(SubtitlePolicy::isKorean)) {
                currentCoroutineContext().ensureActive()
                runCatching { SubtitleFiles.prepare(track.url, track.headers ?: emptyMap()) }.getOrNull()?.let { files -> select(files, item)?.let { return Found(it, "reanime", track) } }
            }
            if (searchable) community(item, preferences)?.let { rememberKorean(item); return it }
            return translationSource(item, tracks, preferences)
        }
        // desktop: the other site's Korean track and the blogs' are asked at once, and whatever Korean one is there after
        // a short wait (2.5 s) goes on, the other site's first. A work whose Korean subtitle came last time from a slow
        // site (애니24, 링크애니, Linkkf: their pages are read) waits for it 16 s. The cloud translation costs a request,
        // so it waits until both have answered (16 s at most); the local AI and Whisper start at once. A Korean subtitle
        // coming after this returns takes the place of the translation, the Whisper subtitle or none (the player's
        // upgradeToLateKorean).
        status("다른 사이트·블로그에서 한국어 자막을 찾는 중...")
        var found = korean.wait(if (koreanSourceOf(item) in KOREAN_SLOW_SITES) 16_000 else 2_500)
        if (found == null && !korean.settled() && !prefersAI && preferences.translationPreferences(automatic = true)?.translationProvider.let { it != null && it != "local" }) {
            status("한국어 자막을 조금 더 찾아본 뒤 번역해요...")
            found = korean.wait(16_000 - korean.elapsed())
        }
        if (found != null) { rememberKoreanSource(item, found); return found }
        return translationSource(item, tracks, preferences, later = later)
    }

    /** The other site's Korean track and the blogs' Korean subtitle of an episode, asked at once (desktop koreanSearchFor):
     *  what is there now (the other site's track first), whether both have answered, and a wait for it.
     *  others: the other sites' tracks as they come; othersDone: done once they are all in. */
    inner class KoreanSearch(private val item: PlaybackItem, private val preferences: AppPreferences,
                             private val others: () -> List<RemoteSubtitle>, private val othersDone: Deferred<*>?) {
        private val started = System.currentTimeMillis()
        private var blogs: Deferred<Found?>? = null
        private val read = mutableMapOf<String, Found?>()
        /** The blogs are asked once the search is waited for (a saved or own subtitle needs none). */
        private fun blogs(): Deferred<Found?> = blogs ?: scope.async {
            if (item.anime.source == "local") null else try { community(item, preferences) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        }.also { blogs = it }
        /** Waited for at least once (the blogs asked). */
        val asked: Boolean get() = blogs != null
        /** The other site's Korean track, one whose file could not be read passed over. */
        private fun track() = others().firstOrNull { it.origin != null && it.origin != item.anime.source && SubtitlePolicy.isKorean(it) && !(read.containsKey(it.url) && read[it.url] == null) }
        private fun blog(): Found? = blogs?.takeIf { it.isCompleted && !it.isCancelled }?.let { runCatching { it.getCompleted() }.getOrNull() }
        private val othersFinished get() = othersDone == null || othersDone.isCompleted
        fun settled() = blogs?.isCompleted == true && othersFinished
        // The wait ends on the other site's Korean track, or the blogs' once the other sites have answered (theirs comes first).
        private fun best() = track() != null || (blog() != null && othersFinished) || settled()
        fun elapsed() = System.currentTimeMillis() - started
        suspend fun wait(ms: Long): Found? {
            blogs()
            if (ms > 0) withTimeoutOrNull(ms) { while (!best()) delay(200) }
            return now()
        }
        /** What is there now, as a file: the other site's Korean track (read once), else the blogs' subtitle. */
        private suspend fun now(): Found? {
            while (true) {
                val track = track() ?: break
                read[track.url]?.let { return it }
                val file = runCatching { SubtitleFiles.prepare(track.url, track.headers ?: emptyMap()) }.getOrNull()?.let { select(it, item) }
                read[track.url] = file?.let { Found(it, "reanime", track) }
                read[track.url]?.let { return it }
            }
            return blog()
        }
        /** The other sites' tracks once they are all in (16 s from the start at most), for a translation's source. */
        suspend fun othersAfter(): List<RemoteSubtitle> {
            withTimeoutOrNull(maxOf(0L, 16_000 - elapsed())) { runCatching { othersDone?.await() } }
            return others()
        }
    }

    /**
     * A download's subtitle (desktop findDownloadSubtitle, iOS downloadSubtitle): a Korean one first — the preferred
     * source's saved one when it is Korean, the stream's own subtitle, a saved Korean one, another site's Korean track
     * (all of them waited for), the blogs — then a saved one in another language, else a source to translate.
     */
    suspend fun prepareDownload(item: PlaybackItem, tracks: List<RemoteSubtitle>, preferences: AppPreferences, stream: ResolvedStream? = null): Found? {
        if (preferences.subtitleProvider == "manual" || DesktopSubtitlePolicy.burnedKorean(item.anime.source, stream)) return null
        val source = item.anime.source
        val saved = EpisodeSubtitleStore.list(item)
        fun korean(record: SavedSubtitle) = record.translated || record.provider in setOf("kairan", "csora", "anissia", "linkkf", "user")
        val preferred = DesktopSubtitlePolicy.preferredSaved(saved, preferences.subtitleProvider)
        if (preferred != null && korean(preferred)) preferred.file?.let { return Found(it, preferred.provider) }
        val own = tracks.filter { it.origin == null || it.origin == source }
        for (track in if (source in KOREAN_SLOW_SITES) own else own.filter(SubtitlePolicy::isKorean)) {
            currentCoroutineContext().ensureActive()
            runCatching { SubtitleFiles.prepare(track.url, track.headers ?: emptyMap()) }.getOrNull()?.let { files -> select(files, item)?.let { return Found(it, if (source == "linkkf") "linkkf" else "reanime") } }
        }
        saved.firstOrNull(::korean)?.let { record -> record.file?.let { return Found(it, record.provider) } }
        for (track in tracks.filter { it.origin != null && it.origin != source && SubtitlePolicy.isKorean(it) }) {
            currentCoroutineContext().ensureActive()
            runCatching { SubtitleFiles.prepare(track.url, track.headers ?: emptyMap()) }.getOrNull()?.let { files -> select(files, item)?.let { return Found(it, "reanime", track) } }
        }
        if (source != "local") community(item, preferences)?.let { rememberKorean(item); return it }
        (preferred ?: saved.firstOrNull())?.let { record -> record.file?.let { return Found(it, record.provider) } }
        return translationSource(item, tracks, preferences)
    }

    /** The other sites' tracks of an episode, all of them (RE:Anime's and Miruro's, and the Korean sites' files), for a
     *  download, which waits for them (desktop withOtherSiteTracks; 다른 사이트 자막 트랙도 찾기). */
    suspend fun otherSiteTracks(item: PlaybackItem, preferences: AppPreferences): List<RemoteSubtitle> {
        val label = item.displayNumber.trim()
        val number = label.toIntOrNull() ?: item.number
        val special = Regex("^\\d+(?:\\.\\d+|[a-z])$", RegexOption.IGNORE_CASE).matches(label) ||
            Regex("^(?:ova|ona|sp|special|특별|극장)", RegexOption.IGNORE_CASE).containsMatchIn(label)
        val dubbed = Regex("(?:^|[^a-z])dub(?:bed)?(?:[^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(item.watchURL + " " + item.episodeID)
        if (!preferences.otherSiteTracks || item.anime.source == "local" || special || dubbed || number <= 0) return emptyList()
        return coroutineScope {
            val korean = async {
                com.lilac.anime.android.data.DesktopCatalog.enrich(item.anime.anime, item.anime.source)
                val record = com.lilac.anime.android.data.DesktopCatalog.record(item.anime)
                val titles = (listOf(record?.korean.orEmpty(), item.anime.title) + record?.aliases.orEmpty()).filter { TitleCandidates.isKorean(it) }.distinct().take(3)
                if (titles.isEmpty()) emptyList()
                else service.await<List<CrossSiteTrack>> { koreanSiteTracks(item.anime.anime, item.anime.source, number, false, false, titles, it) }.getOrDefault(emptyList())
            }
            val sites = service.await<List<CrossSiteTrack>> { crossSiteTracks(item.anime.anime, item.anime.source, number, false, false, it) }.getOrDefault(emptyList())
            (sites + korean.await()).mapNotNull { SubtitleFiles.providerTrack(it) }.distinctBy { it.url }
        }
    }

    /** Which Korean source the series had last time (a site's origin, or "blog"): a slow site's file is waited for longer. */
    private fun koreanSourceOf(item: PlaybackItem) = defaults.getString("korean-source." + item.anime.id, "").orEmpty()
    fun rememberKoreanSource(item: PlaybackItem, found: Found) {
        val source = found.track?.origin ?: if (found.provider in listOf("kairan", "csora", "anissia")) "blog" else return
        defaults.edit().putString("korean-source." + item.anime.id, source).apply()
        if (source == "blog") rememberKorean(item)
    }

    /** A work known to have Korean subtitles: Animenosub's and Miruro's clean video is chosen for it next time. */
    private fun rememberKorean(item: PlaybackItem) {
        val known = (defaults.getStringSet("desktop.korean-series", emptySet()) ?: emptySet()).toMutableSet()
        if (known.add(item.anime.id)) defaults.edit().putStringSet("desktop.korean-series", known.toList().takeLast(300).toSet()).apply()
    }

    /** All the blogs searched together, used in the preferred order. */
    suspend fun community(item: PlaybackItem, preferences: AppPreferences): Found? {
        val context = context(item)
        val pending = DesktopSubtitlePolicy.providers(preferences.subtitleProvider).map { communityTask(it, item, context) }
        for (task in pending) { task.await()?.let { return it }; currentCoroutineContext().ensureActive() }
        return null
    }
    suspend fun community(item: PlaybackItem, provider: String): Found? = communityTask(provider, item, context(item)).await()

    /** An Anissia maker's subtitle (player maker list). */
    suspend fun maker(item: PlaybackItem, website: String): Found? {
        val context = context(item)
        val assets = service.await<List<SubtitleAsset>> { makerSubtitles(context.first, item.number, item.subtitleEpisodeKey, website, context.second, it) }.getOrDefault(emptyList())
        return apply(assets, "anissia", item, mutableSetOf())
    }
    suspend fun makers(item: PlaybackItem): Result<List<SubtitleMaker>> {
        val title = context(item).first
        return service.await { subtitleMakers(title, it) }
    }
    suspend fun search(provider: String, item: PlaybackItem): Result<List<SubtitleAsset>> {
        val context = context(item)
        return service.await { findSubtitles(provider, context.first, item.number, item.subtitleEpisodeKey, context.second, it) }
    }

    private fun communityTask(provider: String, item: PlaybackItem, context: Triple<String, Int, List<Int>>): Deferred<Found?> {
        val key = item.anime.id + "#" + item.episodeID + "#" + context.first + "#" + provider
        searches[key]?.let { return it }
        val task = scope.async {
            val tried = mutableSetOf<String>()
            for (round in 0 until 2) {
                val assets = find(provider, item, context, fresh = round > 0)
                apply(assets, provider, item, tried)?.let { return@async it }
                if (!isActive) return@async null
            }
            null
        }
        searches[key] = task
        task.invokeOnCompletion { if (task.isCancelled || runCatching { task.getCompleted() }.getOrNull() == null) searches.remove(key) }
        return task
    }

    /** Downloads a search's posts in rank order and picks the episode's file (desktop downloadCommunityMatch). */
    private suspend fun apply(assets: List<SubtitleAsset>, provider: String, item: PlaybackItem, tried: MutableSet<String>): Found? {
        val downloads = assets.filter { it.source != "post" }
        for (post in downloads.map { it.postURL }.distinct()) {
            val group = downloads.filter { it.postURL == post }
            if (!tried.add(post + "\u0000" + group.map { it.url }.sorted().joinToString("\u0000"))) continue
            val first = group.first()
            val subtitles = group.filter { !it.font }
            var found: File? = null; val fonts = mutableListOf<File>()
            for (tier in subtitles.map { it.tier }.distinct().sorted()) {
                val links = subtitles.filter { it.tier == tier }
                val candidates = mutableListOf<File>()
                for (asset in links) {
                    currentCoroutineContext().ensureActive()
                    runCatching { SubtitleFiles.prepare(asset.url, label = asset.label) }.getOrNull()?.let { candidates += it; fonts += SubtitleFiles.preparedFonts(asset.url) }
                }
                val planned = tier >= 0; val exact = planned && links.all { it.exact }
                communitySelect(candidates, item, first.matchedEpisode ?: first.episode?.toDouble(), if (!planned || exact) first.strict else true, first.bundle, exact, first.fileSeason)
                    ?.let { found = it }
                if (found != null) break
            }
            val file = found ?: continue
            for (asset in group.filter { it.font }) {
                currentCoroutineContext().ensureActive()
                if (runCatching { SubtitleFiles.prepare(asset.url, label = asset.label) }.isSuccess) fonts += SubtitleFiles.preparedFonts(asset.url)
            }
            runCatching { SubtitleFiles.associateFonts(SubtitleFiles.fonts(file) + fonts, file) }
            return Found(file, provider)
        }
        if (provider == "anissia") {
            for (asset in assets.filter { it.source == "post" }) {
                currentCoroutineContext().ensureActive()
                if (!tried.add(asset.url + "\u0000WinPNG")) continue
                val episode = asset.matchedEpisode ?: item.displayNumber.toDoubleOrNull() ?: item.number.toDouble()
                try {
                    WinPngReader.subtitle(context, asset.url, episode)?.let { return Found(it, provider) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Try the next ranked post when the embedded converter fails.
                }
            }
        }
        return null
    }

    /** The subtitle to translate from (desktop subtitle-source.js): the site's Japanese track, Jimaku's Japanese file, then
     *  the site's English track. */
    suspend fun translationSource(item: PlaybackItem, tracks: List<RemoteSubtitle>, preferences: AppPreferences, requireAutomatic: Boolean = true,
                                  later: (suspend () -> List<RemoteSubtitle>)? = null): Found? {
        if (requireAutomatic && preferences.translationPreferences(automatic = true) == null) return null
        val tried = mutableSetOf<String>()
        DesktopSubtitlePolicy.translationTrack(tracks, "ja")?.let { japanese ->
            tried += japanese.url
            runCatching { SubtitleFiles.prepare(japanese.url, japanese.headers ?: emptyMap()) }.getOrNull()?.let { files -> select(files, item)?.let { return Found(it, "reanime") } }
        }
        EpisodeSubtitleStore.list(item).firstOrNull { !it.translated && it.provider == "jimaku" }?.file?.let { return Found(it, "jimaku") }
        if (item.anime.source != "local") {
            for (asset in jimaku.load(item)) {
                if (asset.source == "post" || asset.score <= 0 || !JimakuRules.validDownload(asset.url) ||
                    Regex("(?:^|[. _-])(?:en|eng|english)(?:[. _-]|$)", RegexOption.IGNORE_CASE).containsMatchIn(asset.name)) continue
                currentCoroutineContext().ensureActive()
                runCatching { SubtitleFiles.prepare(asset.url, japanese = true, filename = asset.name) }.getOrNull()?.firstOrNull()?.let { return Found(it, "jimaku") }
            }
        }
        // The tracks still loading (another site's) are waited for after Jimaku, so they can be the source (desktop autoTranslate).
        val all = if (later == null) tracks else (tracks + later()).distinctBy { it.url }
        DesktopSubtitlePolicy.translationTrack(all, "ja")?.takeIf { tried.add(it.url) }?.let { japanese ->
            runCatching { SubtitleFiles.prepare(japanese.url, japanese.headers ?: emptyMap()) }.getOrNull()?.let { files -> select(files, item)?.let { return Found(it, "reanime") } }
        }
        val ordered = listOfNotNull(DesktopSubtitlePolicy.translationTrack(all, "en")) + all
        for (track in ordered) {
            if (!tried.add(track.url) || SubtitlePolicy.isKorean(track)) continue
            currentCoroutineContext().ensureActive()
            runCatching { SubtitleFiles.prepare(track.url, track.headers ?: emptyMap()) }.getOrNull()?.let { files -> select(files, item)?.let { return Found(it, "reanime") } }
        }
        return null
    }

    /** The Korean title the blogs are searched with: the work's own when Korean, else TMDB's (with its key), and the
     *  episode offsets of earlier seasons. The full Korean-title catalog comes with stage 4. */
    private suspend fun context(item: PlaybackItem): Triple<String, Int, List<Int>> {
        contexts[item.anime.id]?.let { return it }
        val anime = item.anime.anime
        com.lilac.anime.android.data.DesktopCatalog.enrich(anime, item.anime.source)
        val metadata = com.lilac.anime.android.data.DesktopCatalog.record(item.anime)
        var title = metadata?.korean?.takeIf { it.isNotEmpty() }
            ?: listOf(anime.title, anime.native, anime.romaji, anime.english).firstOrNull { TitleCandidates.isKorean(it) } ?: item.anime.title
        if (!TitleCandidates.isKorean(title)) {
            val credential = SecureKeys.load("tmdb")
            if (credential.isNotEmpty()) service.await<String> { koreanTitle(listOf(anime.title, anime.english, anime.romaji, anime.native).filter { t -> t.isNotBlank() }, credential, it) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { title = it }
        }
        val anilist = anime.anilistId ?: metadata?.anilist ?: 0
        val offsets = service.await<List<Int>> { episodeOffsets(anilist, title, it) }.getOrDefault(emptyList())
        val result = Triple(title, anilist, offsets)
        if (offsets.isNotEmpty() || DesktopTitleRules.season(title) < 2) contexts[item.anime.id] = result
        return result
    }
    suspend fun searchTitle(item: PlaybackItem) = context(item).first

    private suspend fun find(provider: String, item: PlaybackItem, context: Triple<String, Int, List<Int>>, fresh: Boolean): List<SubtitleAsset> {
        val aliases = if (provider == "jimaku") emptyList() else com.lilac.anime.android.data.DesktopCatalog.record(item.anime)?.aliases.orEmpty().filter { TitleCandidates.isKorean(it) }
        for (title in (listOf(context.first) + aliases).distinct().take(8)) {
            currentCoroutineContext().ensureActive()
            val answer = service.await<List<SubtitleAsset>> {
                if (fresh) refreshSubtitles(provider, title, item.number, item.subtitleEpisodeKey, context.second, it)
                else findSubtitles(provider, title, item.number, item.subtitleEpisodeKey, context.second, it)
            }.getOrDefault(emptyList())
            if (answer.isNotEmpty()) return answer
        }
        return emptyList()
    }

    fun communitySelect(files: List<File>, item: PlaybackItem, episode: Double?, strict: Boolean, bundle: Boolean, exact: Boolean, fileSeason: Int): File? {
        val titleSeason = DesktopTitleRules.season(item.anime.title)
        val season = if (fileSeason > 0) fileSeason else if (titleSeason > 1) titleSeason else 0
        val index = DesktopCommunityFiles.pick(files.map { it.name }, files.map { it.length() }, episode ?: item.number.toDouble(), exact, strict, bundle, season)
        return files.getOrNull(index)
    }
    fun select(files: List<File>, item: PlaybackItem, strict: Boolean = false): File? {
        val season = DesktopTitleRules.season(item.anime.title)
        val compatible = files.filter { file -> SubtitleEpisodeMatcher.parse(file.name)?.season?.let { it == season } ?: true }
        compatible.firstOrNull { file ->
            val parsed = SubtitleEpisodeMatcher.parse(file.name)
            (parsed?.episode == null && files.size == 1 && !strict) || SubtitleEpisodeMatcher.matches(file.name, item.number, null)
        }?.let { return it }
        if (!strict) return compatible.firstOrNull()
        if (item.number == 1 && compatible.none { SubtitleEpisodeMatcher.matches(it.name, 2, null) }) return compatible.maxByOrNull { it.length() }
        return null
    }
    fun resetSearches() { searches.values.forEach { it.cancel() }; searches.clear() }
    fun cancel() { resetSearches(); jimaku.cancel(); service.cancel() }
    fun close() { cancel(); scope.cancel(); service.close() }
}
