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
     * saved file, then — only now — another site's Korean track of this very episode (waited for, 16 s at most, with
     * the blogs searched meanwhile), the blogs (Kairan / Csora / Anissia), then a source to translate.
     * tracks: the stream's own and the other sites' tracks found so far; later: the other sites' tracks still loading.
     * status: what the player says it is doing (desktop #subtitleState).
     */
    suspend fun prepare(item: PlaybackItem, tracks: List<RemoteSubtitle>, preferences: AppPreferences, prefersAI: Boolean = false, skipSaved: Boolean = false,
                        stream: ResolvedStream? = null, later: (suspend () -> List<RemoteSubtitle>)? = null, status: (String?) -> Unit = {}): Found? {
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
        // Another site's Korean track of this very episode, before the blogs (matched only by title); the blogs are
        // searched meanwhile all the same.
        var found = others
        if (later != null) {
            if (searchable) for (provider in DesktopSubtitlePolicy.providers(preferences.subtitleProvider)) communityTask(provider, item, context(item))
            status("다른 사이트의 한국어 자막 트랙을 찾는 중...")
            found = (found + later()).distinctBy { it.url }
        }
        for (track in found.filter(SubtitlePolicy::isKorean)) {
            currentCoroutineContext().ensureActive()
            runCatching { SubtitleFiles.prepare(track.url, track.headers ?: emptyMap()) }.getOrNull()?.let { files -> select(files, item)?.let { return Found(it, "reanime", track) } }
        }
        if (searchable) {
            status("온라인 자막을 찾는 중...")
            community(item, preferences)?.let { rememberKorean(item); return it }
        }
        return translationSource(item, own + found, preferences, later = later)
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
