package com.lilac.anime.android.download

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.android.data.AppPreferences
import com.lilac.anime.android.data.SavedAnime
import com.lilac.anime.android.player.*
import com.lilac.anime.android.subtitle.SubtitleFiles
import com.lilac.anime.android.subtitle.SubtitlePreparer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

@Serializable data class DownloadPart(val url: String, val name: String, val done: Boolean = false, val encrypted: Boolean = false)
@Serializable data class StoredSubtitle(val label: String, val url: String, val language: String, val headers: Map<String, String>? = null)
@Serializable data class StoredStream(val label: String, val url: String, val referer: String, val headers: Map<String, String>, val manifestKey: String? = null,
                                       val subtitles: List<StoredSubtitle> = emptyList(), val hlsManifest: String? = null, val burnedKorean: Boolean? = null) {
    fun resolved() = ResolvedStream(label, url, referer, headers, manifestKey, subtitles.map { RemoteSubtitle(it.label, it.url, it.language, it.headers) }, hlsManifest, burnedKorean)
    companion object {
        fun of(stream: ResolvedStream) = StoredStream(stream.label, stream.url, stream.referer, stream.headers, stream.manifestKey,
            stream.subtitles.map { StoredSubtitle(it.label, it.url, it.language, it.headers) }, stream.hlsManifest, stream.burnedKorean)
    }
}

/** One saved (or saving) episode (iOS DownloadEntry). */
@Serializable
data class DownloadEntry(
    val id: String, val anime: SavedAnime, val episodeID: String, val title: String, val number: Int, val displayNumber: String = "", val watchURL: String,
    val stream: StoredStream, val localFile: String? = null, val subtitleFiles: List<String> = emptyList(), val rootFile: String? = null,
    val parts: List<DownloadPart> = emptyList(), val quality: String = "Auto", val planIdentity: String? = null, val bytes: Long = 0,
    val status: String = "대기", val completed: Int = 0, val total: Int = 0, val error: String? = null, val date: Long = System.currentTimeMillis(),
    val subtitlesPrepared: Boolean = false,
)

/** iOS DownloadStore: episodes resolved one at a time, their HLS playlists planned into local files, the parts fetched a
 *  few at once, decoded when disguised, and the subtitles saved beside them; a saved episode plays from its files. */
object DownloadStore {
    var entries by mutableStateOf(emptyList<DownloadEntry>()); private set
    var notice by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var pendingResolution by mutableStateOf(0); private set
    var resolvingTitle by mutableStateOf(""); private set
    val transferRate = mutableStateMapOf<String, Double>()

    lateinit var directory: File; private set
    private lateinit var context: Context
    private lateinit var index: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayDeque<Pair<PlaybackItem, String>>()
    private var resolving: PlaybackItem? = null
    private var resolutionJob: Job? = null
    private val tasks = mutableMapOf<String, Job>()
    private val episodes = Semaphore(2)
    private var preferences: () -> AppPreferences = { AppPreferences() }
    private var writing: Job? = null
    private var loaded = false

    fun init(context: Context) {
        this.context = context.applicationContext
        directory = File(context.filesDir, "Downloads").apply { mkdirs() }
        index = File(context.filesDir, "downloads.json")
    }
    fun attach(preferences: () -> AppPreferences) { this.preferences = preferences }
    suspend fun load() {
        if (loaded) return
        val saved = withContext(Dispatchers.IO) { runCatching { json.decodeFromString(ListSerializer(DownloadEntry.serializer()), index.readText()) }.getOrDefault(emptyList()) }
        // Anything running when the app stopped is stopped now; it resumes from the parts already saved.
        entries = saved.map { if (it.localFile == null && it.status in listOf("대기", "준비 중", "다운로드 중")) it.copy(status = "중단됨") else it } + entries.filter { e -> saved.none { it.id == e.id } }
        loaded = true
    }

    fun entryID(item: PlaybackItem) = SubtitleFiles.key(item.anime.id + "#" + item.episodeID)
    private fun folder(id: String) = File(directory, id)

    enum class EpisodeState { Idle, Active, Stopped, Completed }
    fun state(item: PlaybackItem): EpisodeState {
        val id = entryID(item)
        if ((resolving?.anime?.id == item.anime.id && resolving?.episodeID == item.episodeID) || queue.any { it.first.anime.id == item.anime.id && it.first.episodeID == item.episodeID }) return EpisodeState.Active
        val entry = entries.firstOrNull { it.id == id } ?: return EpisodeState.Idle
        if (entry.localFile != null) return EpisodeState.Completed
        return if (entry.status in listOf("대기", "준비 중", "다운로드 중") || tasks.containsKey(id)) EpisodeState.Active else EpisodeState.Stopped
    }

    /** desktop resolveIntoPlayer: a saved episode plays from its file, and on through the saved episodes. */
    fun offline(item: PlaybackItem): PlaybackItem? {
        if (item.directURL != null) return null
        val entry = entries.firstOrNull { it.id == entryID(item) } ?: return null
        val file = entry.localFile ?: return null
        if (!File(folder(entry.id), file).isFile) return null
        return playback(entry)
    }
    fun playback(entry: DownloadEntry): PlaybackItem {
        val saved = ordered(entry.anime.id)
        val index = saved.indexOfFirst { it.id == entry.id }
        fun single(e: DownloadEntry) = PlaybackItem(e.anime, e.episodeID, e.title, e.number, e.displayNumber.ifEmpty { e.number.toString() }, e.watchURL,
            e.localFile?.let { File(folder(e.id), it).absolutePath }, localSubtitles = e.subtitleFiles.map { File(folder(e.id), it).absolutePath })
        return single(entry).copy(next = if (index >= 0) saved.drop(index + 1).map(::single) else emptyList(), preceding = if (index >= 0) saved.take(index).map(::single) else emptyList())
    }
    private fun ordered(animeID: String): List<DownloadEntry> {
        val list = entries.filter { it.anime.id == animeID && it.localFile != null }
        val order = list.firstOrNull()?.anime?.anime?.episodes?.withIndex()?.associate { it.value.id to it.index }.orEmpty()
        return list.sortedWith(compareBy({ order[it.episodeID] ?: Int.MAX_VALUE }, { it.number }))
    }
    fun group(animeID: String) = entries.filter { it.anime.id == animeID }.sortedBy { it.number }

    // MARK: queueing

    /** The episode button: delete a saved one, stop one in progress, resume a stopped one, else queue it. */
    fun toggle(item: PlaybackItem) {
        val id = entryID(item)
        when (state(item)) {
            EpisodeState.Completed -> { delete(id); notice = "${item.title} 다운로드를 삭제했습니다." }
            EpisodeState.Active -> {
                queue.removeAll { it.first.anime.id == item.anime.id && it.first.episodeID == item.episodeID }; pendingResolution = queue.size
                if (resolving?.anime?.id == item.anime.id && resolving?.episodeID == item.episodeID) cancelResolution()
                if (entries.any { it.id == id }) cancel(id)
                notice = "${item.title} 다운로드를 중지했습니다."
            }
            EpisodeState.Stopped -> entries.firstOrNull { it.id == id }?.let { retry(it); notice = "${item.title} 다운로드를 다시 시작합니다." }
            EpisodeState.Idle -> enqueue(listOf(item))
        }
    }

    fun enqueue(items: List<PlaybackItem>, quality: String = preferences().quality) {
        for (item in items) if (!(resolving?.anime?.id == item.anime.id && resolving?.episodeID == item.episodeID) && queue.none { it.first.anime.id == item.anime.id && it.first.episodeID == item.episodeID })
            queue.addLast(item to quality)
        pendingResolution = queue.size + if (resolving != null) 1 else 0
        if (items.isNotEmpty()) notice = "${items.size}개 회차를 다운로드 대기에 추가했습니다. 내 목록 → 다운로드에서 확인하세요."
        DownloadService.start(context)
        if (resolutionJob != null) return
        error = null
        resolutionJob = scope.launch {
            val resolver = PlaybackResolver(context)
            try {
                while (queue.isNotEmpty()) {
                    val (item, wanted) = queue.removeFirst()
                    resolving = item; resolvingTitle = item.anime.title + " · " + item.title
                    val id = entryID(item)
                    if (entries.any { it.id == id && (it.localFile != null || tasks.containsKey(id)) }) continue
                    val preferRaw = item.anime.source in setOf("miruro", "animenosub") &&
                        context.getSharedPreferences("lilac", Context.MODE_PRIVATE).getStringSet("desktop.korean-series", emptySet())!!.contains(item.anime.id)
                    val preferred = preferences().preferredServers[item.anime.source]
                    resolver.resolve(item, preferRaw, preferred)
                    while (resolver.loading && resolver.streams.isEmpty()) delay(200)
                    val ordered = DesktopStreamPolicy.ordered(resolver.streams, preferRaw, preferred, null)
                    val chosen = if (item.anime.source == "miruro") runCatching { MiruroChooser.choose(ordered, preferRaw, preferred) }.getOrNull() else ordered.firstOrNull()
                    if (chosen != null) download(item, chosen, wanted) else error = item.title + ": " + (resolver.error ?: "영상 주소를 찾지 못했습니다.")
                    pendingResolution = queue.size
                }
            } finally {
                resolver.shutdown(); resolving = null; resolvingTitle = ""; resolutionJob = null; pendingResolution = queue.size
                if (queue.isNotEmpty()) enqueue(emptyList()) else stopServiceWhenIdle()
            }
        }
    }
    private fun cancelResolution() { resolutionJob?.cancel(); resolving = null; resolvingTitle = ""; pendingResolution = queue.size }
    fun stopResolving() { queue.clear(); cancelResolution() }

    private fun download(item: PlaybackItem, stream: ResolvedStream, quality: String) {
        val id = entryID(item)
        if (entries.none { it.id == id }) entries = listOf(DownloadEntry(id, item.anime, item.episodeID, item.title, item.number, item.displayNumber, item.watchURL, StoredStream.of(stream), quality = quality)) + entries
        else update(id) { it.copy(stream = StoredStream.of(stream), quality = quality, planIdentity = null) }
        start(id)
    }

    fun retry(entry: DownloadEntry) { if (entry.status == "실패") enqueue(listOf(playbackFor(entry)), entry.quality) else start(entry.id) }
    private fun playbackFor(entry: DownloadEntry) = PlaybackItem(entry.anime, entry.episodeID, entry.title, entry.number, entry.displayNumber.ifEmpty { entry.number.toString() }, entry.watchURL)

    private fun start(id: String) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        if (entry.localFile != null || tasks.containsKey(id)) return
        update(id) { it.copy(status = "대기", error = null) }
        DownloadService.start(context)
        tasks[id] = scope.launch {
            try {
                episodes.withPermit { perform(id) }
            } catch (failure: CancellationException) { update(id) { it.copy(status = "중단됨") } }
            catch (failure: Throwable) { update(id) { it.copy(status = "실패", error = failure.message) } }
            finally { tasks.remove(id); transferRate.remove(id); stopServiceWhenIdle() }
        }
    }

    private suspend fun perform(id: String) {
        update(id) { it.copy(status = "준비 중") }
        val entry = entries.first { it.id == id }
        val stream = entry.stream.resolved()
        val folder = folder(id).apply { mkdirs() }
        val (root, planned, identity) = withContext(Dispatchers.IO) {
            if (urlExtension(stream.url) in listOf("mp4", "mkv", "webm", "m4v")) Triple("video." + urlExtension(stream.url), listOf(DownloadPart(stream.url, "video." + urlExtension(stream.url))), resource(stream.url))
            else HlsPlanBuilder(stream, folder, entry.quality).build()
        }
        // A new plan (fresh tokens, another quality) keeps the parts it shares with the old one.
        if (entry.planIdentity != null && entry.planIdentity != identity) entry.parts.filter { old -> planned.none { it.name == old.name } }.forEach { File(folder, it.name).delete() }
        val parts = planned.map { it.copy(done = File(folder, it.name).isFile) }
        update(id) { it.copy(planIdentity = identity, rootFile = root, parts = parts, completed = parts.count { p -> p.done }, total = parts.size, status = "다운로드 중",
            bytes = parts.filter { p -> p.done }.sumOf { p -> File(folder, p.name).length() }) }
        val gate = Semaphore(6)
        var received = 0L; var since = System.currentTimeMillis()
        coroutineScope {
            parts.filter { !it.done }.map { part ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val size = fetchPart(part, stream, File(folder, part.name))
                        withContext(Dispatchers.Main) {
                            received += size
                            val now = System.currentTimeMillis()
                            if (now - since >= 1000) { transferRate[id] = received * 1000.0 / (now - since); received = 0; since = now }
                            update(id) { e -> e.copy(completed = e.completed + 1, bytes = e.bytes + size, parts = e.parts.map { if (it.name == part.name) it.copy(done = true) else it }) }
                        }
                    }
                }
            }.awaitAll()
        }
        update(id) { it.copy(localFile = root, status = "완료") }
        prepareSubtitles(id)
    }

    /** A part written whole: a disguised segment (.png/.webp/.html) decoded into transport stream as the player's proxy does. */
    private suspend fun fetchPart(part: DownloadPart, stream: ResolvedStream, target: File): Long {
        val ext = urlExtension(part.url)
        val disguised = !part.encrypted && ((stream.hlsManifest != null && ext == "html") || (stream.manifestKey != null && ext in listOf("png", "webp")))
        val partial = File(target.path + ".part")
        if (disguised) {
            val data = HlsData.mediaSegment(part.url, stream).data
            partial.writeBytes(data)
        } else {
            var failure: Throwable? = null
            for (attempt in 0 until 3) {
                try {
                    val fetched = HlsData.fetch(part.url, stream, timeoutSeconds = 120)
                    val data = if (!part.encrypted && (target.extension == "ts" && stream.manifestKey != null)) HlsData.transportStream(fetched.data) else fetched.data
                    partial.writeBytes(data); failure = null; break
                } catch (error: CancellationException) { throw error } catch (error: Throwable) { failure = error; delay(1000L * (attempt + 1)) }
            }
            failure?.let { throw it }
        }
        if (!partial.renameTo(target)) { target.delete(); partial.renameTo(target) }
        return target.length()
    }

    /** 다운로드에 자막 포함: the subtitle the player would put on (saved, the site's Korean track, the blogs, else a source to
     *  translate), copied beside the video, and the site's other tracks with it. */
    private suspend fun prepareSubtitles(id: String) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        if (entry.subtitlesPrepared) return
        val prefs = preferences()
        val folder = folder(id)
        val files = mutableListOf<String>()
        runCatching {
            val preparer = SubtitlePreparer(context)
            try {
                val stream = entry.stream.resolved()
                preparer.prepare(playbackFor(entry), stream.subtitles, prefs, stream = stream)?.let { found ->
                    val name = "subtitle-" + found.provider + "." + found.file.extension
                    found.file.copyTo(File(folder, name), overwrite = true); files += name
                }
                stream.subtitles.take(6).forEachIndexed { index, track ->
                    runCatching { SubtitleFiles.prepare(track.url, track.headers ?: emptyMap()).firstOrNull() }.getOrNull()?.let { file ->
                        val name = "track-$index." + file.extension
                        file.copyTo(File(folder, name), overwrite = true); files += name
                    }
                }
            } finally { preparer.close() }
        }
        update(id) { it.copy(subtitleFiles = files.distinct(), subtitlesPrepared = true) }
    }

    fun cancel(id: String) { tasks[id]?.cancel(); update(id) { it.copy(status = "중단됨") } }
    fun delete(id: String) {
        tasks[id]?.cancel()
        folder(id).deleteRecursively()
        entries = entries.filterNot { it.id == id }; persist()
    }
    fun stopGroup(animeID: String) {
        queue.removeAll { it.first.anime.id == animeID }; pendingResolution = queue.size
        if (resolving?.anime?.id == animeID) cancelResolution()
        group(animeID).filter { it.localFile == null }.forEach { cancel(it.id) }
    }
    fun resumeGroup(animeID: String) { group(animeID).filter { it.localFile == null && it.status in listOf("중단됨", "실패") }.forEach(::retry) }
    fun removeGroup(animeID: String) { stopGroup(animeID); group(animeID).forEach { delete(it.id) } }
    fun pauseAll() { stopResolving(); entries.filter { it.localFile == null }.forEach { cancel(it.id) } }
    fun resumeAll() { entries.filter { it.localFile == null && !tasks.containsKey(it.id) }.forEach(::retry) }
    /** Episodes taken in from an exported folder (DownloadTransfer.import). */
    fun importCompleted(values: List<DownloadEntry>) { entries = values.filter { v -> entries.none { it.id == v.id } } + entries; persist() }
    fun clearAll() { pauseAll(); entries.forEach { folder(it.id).deleteRecursively() }; entries = emptyList(); persist() }

    /** "1~3, 5, 7화" (app.js episodeRanges). */
    fun episodeRanges(numbers: List<Int>): String {
        val sorted = numbers.distinct().sorted(); val parts = mutableListOf<String>(); var i = 0
        while (i < sorted.size) {
            var end = i
            while (end + 1 < sorted.size && sorted[end + 1] == sorted[end] + 1) end++
            parts += if (end > i + 1) "${sorted[i]}~${sorted[end]}" else if (end > i) "${sorted[i]}, ${sorted[end]}" else "${sorted[i]}"
            i = end + 1
        }
        return parts.joinToString(", ")
    }

    private fun stopServiceWhenIdle() { if (tasks.isEmpty() && resolutionJob == null && queue.isEmpty()) DownloadService.stop(context) }
    private fun update(id: String, change: (DownloadEntry) -> DownloadEntry) {
        entries = entries.map { if (it.id == id) change(it) else it }; persist()
    }
    private fun persist() {
        if (!loaded) return
        val value = entries
        writing?.cancel()
        writing = scope.launch {
            delay(500)
            withContext(Dispatchers.IO) { runCatching { index.writeText(json.encodeToString(ListSerializer(DownloadEntry.serializer()), value)) } }
        }
    }

    /** A resource's identity without its query (the token in a CDN address changes, the file does not). */
    fun resource(url: String) = url.substringBefore('?').substringBefore('#')
}

/** iOS HLSPlanBuilder: a stream's playlists read (decrypted, the chosen quality kept), every segment, key and init file
 *  named locally, and each playlist written with those names. */
private class HlsPlanBuilder(private val stream: ResolvedStream, private val folder: File, private val quality: String) {
    private val files = mutableMapOf<String, String>()
    private val parts = mutableMapOf<String, DownloadPart>()
    private val active = mutableSetOf<String>()
    private val identity = mutableListOf<String>()

    suspend fun build(): Triple<String, List<DownloadPart>, String> {
        val root = playlist(stream.url, 0)
        return Triple(root, parts.values.sortedBy { it.name }, SubtitleFiles.key(identity.joinToString("\n")))
    }
    private fun filename(url: String, manifest: Boolean = false, namespace: String = ""): String {
        val original = urlExtension(url)
        val disguised = (stream.hlsManifest != null && original == "html") || (stream.manifestKey != null && original in listOf("png", "webp"))
        val ext = if (manifest) "m3u8" else if (disguised) "ts" else original
        val suffix = if (Regex("^[a-z0-9]{1,10}$").matches(ext)) ext else "bin"
        return SubtitleFiles.key(namespace + DownloadStore.resource(url)).take(32) + "." + suffix
    }
    private suspend fun playlist(url: String, depth: Int): String {
        if (depth >= 8 || url in active || files.size >= 10000) throw PlaybackFailure("재생목록 구조가 잘못되었습니다.")
        files[url]?.let { return it }
        active += url
        try {
            val body = HlsData.fetch(url, stream).data
            val text = HlsData.selectVariant(HlsData.manifest(body, stream.manifestKey), quality)
            val name = filename(url, manifest = true); files[url] = name
            val lines = text.lines()
            val master = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
            identity += DownloadStore.resource(url)
            identity += lines.filter { it.startsWith("#EXTINF:") || it.startsWith("#EXT-X-MEDIA-SEQUENCE:") || it.startsWith("#EXT-X-BYTERANGE:") }
            if (!master && !text.contains("#EXT-X-ENDLIST")) throw PlaybackFailure("방송 중인 HLS는 완료된 회차로 저장할 수 없습니다.")
            val children = mutableSetOf<String>()
            val uri = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            lines.forEachIndexed { index, line ->
                if (line.startsWith("#EXT-X-STREAM-INF") && index + 1 < lines.size) resolveURL(url, lines[index + 1].trim())?.let { children += it }
                if (line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) uri.findAll(line).forEach { m -> resolveURL(url, m.groupValues[1])?.let { children += it } }
            }
            val encrypted = HlsData.encryptedSegments(text, url)
            val resources = HlsData.references(text, url)
            if (files.size + resources.size > 10000) throw PlaybackFailure("재생목록 조각이 너무 많습니다.")
            resources.forEachIndexed { index, resource ->
                if (resource in children || urlExtension(resource) == "m3u8") playlist(resource, depth + 1)
                else {
                    val file = files[resource] ?: filename(resource, namespace = DownloadStore.resource(url) + "#" + index + "#")
                    files[resource] = file
                    identity += DownloadStore.resource(resource)
                    parts[resource] = DownloadPart(resource, file, encrypted = resource in encrypted)
                }
            }
            File(folder, name).writeText(HlsData.rewrite(text, url) { files[it] ?: "" })
            return name
        } finally { active -= url }
    }
}
