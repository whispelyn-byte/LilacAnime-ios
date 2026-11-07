package com.lilac.anime.android.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.lilac.anime.android.data.LibraryStore
import com.lilac.anime.android.data.SavedAnime
import com.lilac.anime.android.data.await
import com.lilac.anime.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** iOS EpisodePlayerModel, the playing part: an episode resolved, played, saved as it goes, its OP/ED skipped, the next
 *  one played when it ends. Subtitles beyond the site's own tracks come with stage 3 (docs/android-port.md). */
class PlayerModel(private val context: Context, start: PlaybackItem, private val library: LibraryStore) {
    var item by mutableStateOf(start); private set
    var previous by mutableStateOf(start.preceding); private set
    var active by mutableStateOf<ResolvedStream?>(null)
    var error by mutableStateOf<String?>(null)
    var chapters by mutableStateOf(emptyList<Chapter>()); private set
    var toast by mutableStateOf<Pair<String, Long>?>(null); private set

    val engine = MpvEngine(context)
    val resolver = PlaybackResolver(context)
    private val service = IosServices()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var generation = 0L
    private var proxy: HlsProxy? = null
    private var loadedSkip = false
    private var lastSave = 0.0
    private var lastSkipped = ""
    private var skipEntered: Pair<String, SkipCountdown>? = null
    private var preferRaw = false
    private var playRequest = 0L
    private var selectionJob: Job? = null
    private var stallJob: Job? = null
    private var miruroMonitor = MiruroStallMonitor()
    private var miruroFailed = mutableSetOf<String>()
    private val refreshedSeries = mutableSetOf<String>()
    private val defaults = context.getSharedPreferences("lilac", Context.MODE_PRIVATE)
    /** The subtitles: the automatic choice, the 자막 tab and the AI translation (stage 3). */
    val subtitles = com.lilac.anime.android.subtitle.PlayerSubtitles(context, library, engine, { item }, { active },
        { active?.subtitles?.ifEmpty { null } ?: resolver.subtitles }, { generation }, ::notify)

    init {
        scope.launch { snapshotFlow { resolver.streams }.distinctUntilChanged().collect { selectResolved(it) } }
        // 애니24's own subtitle file can be found after the stream: the playing stream takes it and it is put on.
        scope.launch {
            snapshotFlow { resolver.subtitles }.distinctUntilChanged().collect { tracks ->
                val stream = active ?: return@collect
                if (item.anime.source == "ohli24" && stream.subtitles.isEmpty() && tracks.isNotEmpty()) {
                    active = stream.copy(subtitles = tracks, burnedKorean = false)
                    if (subtitles.subtitle == null) subtitles.automatic(active!!)
                }
            }
        }
    }

    fun notify(text: String) { toast = text to System.nanoTime() }
    val seriesItems: List<PlaybackItem> get() = previous + listOf(item) + item.next

    fun begin() {
        active = null; loadedSkip = false; engine.error = null; error = null
        val token = generation
        engine.onEnd = {
            if (token == generation) {
                // desktop video 'ended': said when nothing follows.
                when {
                    item.next.isEmpty() -> notify("마지막 회차입니다.")
                    !library.preferences.autoPlay -> notify("재생이 끝났습니다.")
                    else -> { val remaining = item.next; change(remaining[0].copy(next = remaining.drop(1))) }
                }
            }
        }
        engine.onProgress = { position, duration ->
            if (token == generation && active != null) {
                if (position - lastSave >= 5 || position < lastSave) { lastSave = position; save() }
                if (!loadedSkip && duration > 0) {
                    loadedSkip = true
                    scope.launch {
                        service.await<List<SkipSegment>> { skipSegmentsFor(item.anime.anime, item.number, duration.toInt(), it) }.getOrNull()
                            ?.takeIf { it.isNotEmpty() && token == generation }?.let { segments -> chapters = segments.map { Chapter(it.type, it.start, it.end) } }
                    }
                }
                updateAutoSkip(position)
            }
        }
        stallJob?.cancel(); miruroMonitor = MiruroStallMonitor(); miruroFailed = mutableSetOf()
        stallJob = scope.launch { while (isActive) { delay(1000); if (token == generation) recoverMiruroIfStalled(token) } }
        engine.configure(library.preferences)
        subtitles.begin()
        refreshSeries(token)
        // Animenosub's and Miruro's clean video is chosen for a work known to have Korean subtitles (desktop korean-series).
        preferRaw = item.anime.source in setOf("miruro", "animenosub") &&
            (defaults.getStringSet("desktop.korean-series", emptySet()) ?: emptySet()).contains(item.anime.id)
        resolver.resolve(item, preferRaw, library.preferences.preferredServers[item.anime.source] ?: library.preferences.preferredStream)
    }

    /** An episode opened from 시청기록 carries the list saved back then: the series is asked again for newer episodes. */
    private fun refreshSeries(token: Long) {
        val opening = item
        if (opening.next.isNotEmpty() || opening.directURL != null || opening.anime.source == "local" || !refreshedSeries.add(opening.anime.id + "#" + opening.episodeID)) return
        scope.launch {
            val detail = service.await<SourceDetail> { detail(opening.anime.anime, opening.anime.source, it) }.getOrNull() ?: return@launch
            if (token != generation) return@launch
            val server = detail.servers.firstOrNull { s -> s.episodes.any { it.id == opening.episodeID } } ?: detail.servers.firstOrNull { s -> s.episodes.any { it.number == opening.number } }
            val episodes = server?.episodes ?: return@launch
            val index = episodes.indexOfFirst { it.id == opening.episodeID }.takeIf { it >= 0 } ?: episodes.indexOfFirst { it.number == opening.number }.takeIf { it >= 0 } ?: return@launch
            val saved = SavedAnime.of(AnimeSnapshot.withEpisodes(detail.anime, episodes), opening.anime.source)
            if (saved.id != opening.anime.id || item.next.isNotEmpty()) return@launch
            val next = episodes.drop(index + 1).map { PlaybackItem.of(saved, it) }
            if (next.isNotEmpty()) item = item.copy(next = next)
            if (previous.isEmpty()) previous = episodes.take(index).map { PlaybackItem.of(saved, it) }
        }
    }

    fun selectResolved(streams: List<ResolvedStream>) {
        if (streams.isEmpty()) return
        val token = generation
        val preferred = library.preferences.preferredServers[item.anime.source] ?: library.preferences.preferredStream
        val ordered = if (item.anime.source == "miruro") DesktopStreamPolicy.ordered(streams, preferRaw, preferred, defaults.getString("miruro.working-provider", null))
        else streams.sortedBy { if (it.label == preferred) 0 else 1 }
        if (active != null && ordered.none { it.url == active?.url && it.manifestKey != active?.manifestKey }) return
        selectionJob?.cancel()
        selectionJob = scope.launch {
            if (item.anime.source == "miruro" && ordered.none { isFileURL(it.url) }) {
                try {
                    val chosen = MiruroChooser.choose(ordered, preferRaw, preferred)
                    if (token == generation) play(chosen)
                } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) { if (token == generation) error = failure.message }
                return@launch
            }
            ordered.firstOrNull()?.let { if (token == generation) play(it) } ?: run { error = "모든 영상 서버 연결에 실패했습니다." }
        }
    }

    fun change(next: PlaybackItem, remember: Boolean = true) {
        selectionJob?.cancel()
        if (remember) previous = previous + item
        save(); engine.pause(); proxy?.stop(); proxy = null
        generation++; item = next; active = null; chapters = emptyList(); skipEntered = null
        loadedSkip = false; lastSave = 0.0; lastSkipped = ""; subtitles.reset(); engine.clearViewerSeek()
        begin()
    }
    fun jump(index: Int) {
        val all = seriesItems
        if (index !in all.indices || index == previous.size) return
        change(all[index].copy(next = all.drop(index + 1)), remember = false)
        previous = all.take(index)
    }
    fun previousEpisode() {
        val last = previous.lastOrNull() ?: return
        previous = previous.dropLast(1)
        change(last.copy(next = listOf(item) + item.next), remember = false)
    }
    fun nextEpisode() { val remaining = item.next; if (remaining.isNotEmpty()) change(remaining[0].copy(next = remaining.drop(1))) }

    fun play(stream: ResolvedStream, paused: Boolean = false) {
        miruroFailed = mutableSetOf(); miruroMonitor = MiruroStallMonitor()
        val playing = ++playRequest
        val token = generation
        scope.launch {
            try {
                proxy?.stop(); proxy = null
                var playable = stream
                if (needsProxy(stream)) { val started = HlsProxy(); proxy = started; playable = started.start(stream, library.preferences.quality) }
                if (token != generation || playing != playRequest) return@launch
                active = stream; error = null
                if (item.anime.source == "miruro") stream.label.split(" - ").getOrNull(1)?.split(" ")?.firstOrNull()?.let { defaults.edit().putString("miruro.working-provider", it).apply() }
                engine.configure(library.preferences)
                engine.load(playable, library.resume(item.anime, item.episodeID), paused)
                if (subtitles.subtitle == null) subtitles.automatic(stream)
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) {
                if (token == generation && playing == playRequest) error = failure.message
            }
        }
    }
    private fun needsProxy(stream: ResolvedStream) = stream.manifestKey != null || stream.hlsManifest != null || (urlExtension(stream.url) == "m3u8" && !isFileURL(stream.url))


    private suspend fun recoverMiruroIfStalled(token: Long) {
        val current = active ?: return
        val eligible = item.anime.source == "miruro" && !isFileURL(current.url) && !engine.paused
        if (!miruroMonitor.update(current.id, engine.position, engine.seekRevision, eligible, engine.buffering, System.currentTimeMillis() / 1000.0)) return
        if (!MiruroChooser.canRecover(resolver.streams, current, miruroFailed)) return
        miruroFailed += current.label
        val position = engine.position; val seek = engine.seekRevision
        notify("영상 서버가 느려 다른 서버로 연결하는 중…")
        try {
            val chosen = MiruroChooser.choose(resolver.streams, preferRaw, current.label, miruroFailed)
            var playable = chosen
            var replacement: HlsProxy? = null
            if (needsProxy(chosen)) { replacement = HlsProxy(); playable = try { replacement.start(chosen, library.preferences.quality) } catch (e: Throwable) { replacement.stop(); throw e } }
            if (token != generation || active?.id != current.id || engine.paused || engine.seekRevision != seek || kotlin.math.abs(engine.position - position) > 0.1 || !engine.buffering) { replacement?.stop(); return }
            proxy?.stop(); proxy = replacement
            active = chosen; error = null
            engine.load(playable, position)
        } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) { if (token == generation) error = failure.message }
    }

    fun reloadForQuality() { active?.let { save(); play(it) } }

    fun updateAutoSkip(position: Double) {
        if (!library.preferences.autoSkip) { skipEntered = null; return }
        val chapter = chapters.firstOrNull { position >= it.start && position < it.end }
        val watched = watchedByChoice(chapter)
        if (chapter == null) { skipEntered = null; lastSkipped = ""; return }
        val entered = skipEntered
        if (entered?.first != chapter.id) skipEntered = chapter.id to SkipCountdown(position, engine.speed, engine.seekRevision)
        else entered.second.update(position, engine.speed, engine.seekRevision)
        if (!engine.paused && !engine.buffering && !watched && chapter.id != lastSkipped && (skipEntered?.second?.elapsed ?: 0.0) >= 2.5) {
            lastSkipped = chapter.id; engine.seek(chapter.end)
        }
    }
    /** desktop updateSkipState: an OP/ED the viewer moved into themselves is watched until they leave it. */
    private fun watchedByChoice(chapter: Chapter?): Boolean {
        val seek = engine.viewerSeek ?: return false
        if (chapter != null && seek.first >= chapter.start && seek.first < chapter.end) return true
        if (System.currentTimeMillis() - seek.second >= 1500) engine.clearViewerSeek()
        return false
    }
    val currentChapter: Chapter? get() = chapters.firstOrNull { engine.position >= it.start && engine.position < it.end }

    fun save() {
        val stream = active ?: return
        if (!engine.canSaveProgress) return
        library.progress(item.anime, item.episodeID, item.title, item.number, item.watchURL, stream.url.takeIf { isFileURL(it) }, engine.position, engine.duration, item.displayNumber)
    }

    fun shutdown() {
        save(); generation++; selectionJob?.cancel(); stallJob?.cancel()
        subtitles.shutdown(); engine.shutdown(); resolver.shutdown(); proxy?.stop(); proxy = null
        service.close(); scope.cancel()
    }
}

/** iOS MiruroPlayback: Miruro's servers are tried for speed, three at a time in each priority, the first fast enough
 *  playing; a stalled one moves to another of the same dub kind. */
object MiruroChooser {
    fun canRecover(streams: List<ResolvedStream>, current: ResolvedStream, failed: Set<String>): Boolean {
        val excluded = failed + current.label
        if (excluded.size > 2) return false
        val dubbed = ServerChoice.kind(current.label) == "dub"
        return streams.any { it.label !in excluded && (ServerChoice.kind(it.label) == "dub") == dubbed }
    }
    private data class Probe(val speed: Double, val required: Double)
    suspend fun choose(streams: List<ResolvedStream>, preferRaw: Boolean, preferred: String?, excluded: Set<String> = emptySet()): ResolvedStream {
        val dubbed = streams.firstOrNull { it.label == preferred }?.let { ServerChoice.kind(it.label) == "dub" } ?: false
        val candidates = streams.filter { it.label !in excluded && (ServerChoice.kind(it.label) == "dub") == dubbed }
        if (candidates.isEmpty()) throw PlaybackFailure("사용 가능한 영상 서버가 없습니다. 다른 서버를 선택해 주세요.")
        candidates.firstOrNull { it.label == preferred }?.let { manual -> runCatching { inspect(manual) }.onSuccess { return manual } }
        val slow = mutableListOf<Pair<ResolvedStream, Double>>()
        for (priority in candidates.map { DesktopStreamPolicy.priority(it, preferRaw) }.distinct().sorted()) {
            val group = candidates.filter { it.label != preferred && DesktopStreamPolicy.priority(it, preferRaw) == priority }
            for (batch in group.chunked(3)) {
                val results = coroutineScope { batch.map { stream -> async { runCatching { stream to inspect(stream) }.getOrNull() } }.awaitAll() }
                results.filterNotNull().forEach { (stream, probe) ->
                    if (probe.speed >= maxOf(300.0 * 1024, probe.required)) return stream
                    slow += stream to probe.speed
                }
            }
        }
        slow.maxByOrNull { it.second }?.let { return it.first }
        throw PlaybackFailure("모든 영상 서버 연결에 실패했습니다.")
    }
    private suspend fun inspect(stream: ResolvedStream): Probe = withTimeout(12_000) {
        var url = stream.url; var bandwidth = 0.0
        repeat(5) {
            val fetched = HlsData.fetch(url, stream, timeoutSeconds = 6); url = fetched.url
            val text = HlsData.manifest(fetched.data, stream.manifestKey)
            if (text.contains("#EXT-X-STREAM-INF")) {
                val variants = Regex("#EXT-X-STREAM-INF:([^\\r\\n]*)\\r?\\n\\s*([^#\\r\\n]+)").findAll(text).toList()
                fun number(match: MatchResult, pattern: String) = Regex(pattern).find(match.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                val selected = variants.maxWithOrNull(compareBy({ number(it, "RESOLUTION=\\d+x(\\d+)") }, { number(it, "(?:^|,)BANDWIDTH=(\\d+)") }))
                    ?: throw PlaybackFailure("영상 재생목록 오류")
                bandwidth = number(selected, "(?:^|,)BANDWIDTH=(\\d+)")
                url = resolveURL(url, selected.groupValues[2].trim()) ?: throw PlaybackFailure("영상 재생목록 오류")
                return@repeat
            }
            val samples = Regex("#EXTINF:([\\d.]+)[^\\r\\n]*\\r?\\n(?:#[^\\r\\n]*\\r?\\n)*([^#\\r\\n]+)").findAll(text).map { (it.groupValues[1].toDoubleOrNull() ?: 0.0) to it.groupValues[2].trim() }.toList()
            if (samples.isEmpty()) throw PlaybackFailure("빈 영상 재생목록")
            val rates = mutableListOf<Double>(); var size = 0.0; var duration = 0.0
            for ((seconds, path) in samples.drop(if (samples.size > 4) 3 else 0).take(2)) {
                val target = resolveURL(url, path) ?: throw PlaybackFailure("영상 조각 주소 오류")
                val started = System.currentTimeMillis()
                val bytes = HlsData.fetch(target, stream, timeoutSeconds = 6).data
                if (bytes.isEmpty()) throw PlaybackFailure("빈 영상 조각")
                size += bytes.size; duration += seconds
                rates += bytes.size / maxOf(0.05, (System.currentTimeMillis() - started) / 1000.0)
            }
            return@withTimeout Probe(rates.minOrNull() ?: 0.0, maxOf(bandwidth / 8, if (duration > 0) size / duration else 0.0) * 1.5)
        }
        throw PlaybackFailure("재생목록이 너무 깊습니다.")
    }
}
