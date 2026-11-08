package com.lilac.anime.android.audio

import android.content.Context
import com.lilac.anime.android.download.DownloadEntry
import com.lilac.anime.android.download.DownloadStore
import com.lilac.anime.android.player.Chapter
import com.lilac.anime.android.player.PlaybackFailure
import com.lilac.anime.android.player.RemoteSubtitle
import com.lilac.anime.android.subtitle.SubtitleFiles
import com.lilac.anime.shared.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** iOS SubtitleAutoSync (desktop subtitle-sync.cjs): the subtitle on screen matched to the episode — by the site's own
 *  dialogue track when it has one, else by the speech in its audio — and a retimed copy written beside it. */
object SubtitleAutoSync {
    data class Fix(val offset: Double, val scale: Double, val segments: List<SyncSegment>)
    sealed class Outcome { object Unchanged : Outcome(); data class Uncertain(val message: String) : Outcome(); data class Synced(val file: File, val fix: Fix, val method: String) : Outcome() }
    const val LABEL = " · 자동 싱크"
    fun isSynced(file: File) = Regex("\\.sync-[a-z0-9]+\\.[^.]+$|\\.synced\\.[^.]+$").containsMatchIn(file.name)
    /** "1.05초 앞당김", or by segments "구간별 0:00~ 1.01초 앞당김 · 2:08~ 89.25초 늦춤". */
    fun moved(fix: Fix): String {
        fun say(shift: Double) = "%.2f초 ".format(Math.abs(shift)) + if (shift < 0) "앞당김" else "늦춤"
        fun clock(time: Double) = "${time.toInt() / 60}:" + "%02d".format(time.toInt() % 60)
        return if (fix.segments.size > 1) "구간별 " + fix.segments.joinToString(" · ") { clock(it.from) + "~ " + say(it.shift) } else say(fix.offset)
    }
    /** desktop syncReferences: up to four of the site's own tracks, dialogue first; signs, songs, forced or dubbed left out. */
    fun referenceTracks(tracks: List<RemoteSubtitle>): List<RemoteSubtitle> {
        val usable = tracks.filter { it.origin == null && !Regex("signs|songs|s&s|forced|dubtitle|\\(ai\\)|\\bai\\b|commentary", RegexOption.IGNORE_CASE).containsMatchIn(it.label + " " + it.language) }
        val named = { t: RemoteSubtitle -> Regex("full|dialog", RegexOption.IGNORE_CASE).containsMatchIn(t.label + " " + t.language) }
        return (usable.filter(named) + usable.filterNot(named)).take(4)
    }

    private val levels = mutableMapOf<String, List<SyncCue>>()

    suspend fun sync(context: Context, subtitle: File, timing: File?, references: List<File>, video: AudioSource?, chapters: List<Chapter>,
                     status: (String, Double?) -> Unit): Outcome {
        val (timingCues, all, best) = withContext(Dispatchers.Default) {
            val content = SubtitleFiles.text(subtitle); val ext = subtitle.extension
            val spoken = timing?.let { runCatching { SubtitleSync.spokenCues(SubtitleFiles.text(it), it.extension) }.getOrNull() } ?: SubtitleSync.spokenCues(content, ext)
            val bestTrack = references.mapNotNull { file -> runCatching { SubtitleSync.spokenCues(SubtitleFiles.text(file), file.extension) }.getOrNull() }.maxByOrNull { it.size }
            Triple(spoken, SubtitleSync.allCues(content, ext), bestTrack)
        }
        if (timingCues.isEmpty()) throw PlaybackFailure("싱크를 맞출 자막 줄이 없습니다.")
        val byTrack = (best?.size ?: 0) >= 20
        var reference = best.orEmpty()
        var duration = video?.duration ?: 0.0
        if (byTrack) status("사이트 자막 트랙에 맞추는 중", null)
        else {
            video ?: throw PlaybackFailure("이 영상은 자동 싱크를 쓸 수 없습니다.")
            status("영상 음성 읽는 중", 0.0)
            reference = speech(context, video, status)
            duration = maxOf(duration, reference.lastOrNull()?.end ?: 0.0)
            status("음성에 맞추는 중", null)
        }
        val marks = chapters.filter { it.type.lowercase().let { t -> t.endsWith("op") || t.endsWith("ed") } }.map { SyncCue(it.start, it.end) }
        val result = withContext(Dispatchers.Default) { SubtitleSync.sync(timingCues, all, reference, byTrack, marks, duration) }
        if (result.uncertain) return Outcome.Uncertain("이 자막이 영상과 맞는 위치를 찾지 못해 그대로 뒀어요 (다른 판본이나 다른 회차의 자막일 수 있어요).")
        if (result.unchanged) return Outcome.Unchanged
        val fix = Fix(result.offset, result.scale, result.segments ?: emptyList())
        return Outcome.Synced(retimed(subtitle, fix), fix, result.method)
    }

    /** A retimed copy beside the subtitle (an ASS keeps the folder with its fonts); ".sync-<time>" names each run anew. */
    suspend fun retimed(file: File, fix: Fix, stable: Boolean = false): File = withContext(Dispatchers.IO) {
        val content = SubtitleFiles.text(file)
        val ext = SubtitleSync.retimedExtension(file.extension)
        val base = file.nameWithoutExtension.replace(Regex("\\.(sync-[a-z0-9]+|synced)$"), "")
        val folder = if (stable) File(SubtitleFiles.translations, "Synced") else if (file.canonicalPath.startsWith(SubtitleFiles.root.canonicalPath + "/")) file.parentFile!! else File(SubtitleFiles.root, "Synced")
        folder.mkdirs()
        val target = File(folder, if (stable) "$base.synced.$ext" else "$base.sync-" + System.currentTimeMillis().toString(36) + ".$ext")
        target.writeText(SubtitleSync.retime(content, file.extension, fix.offset, fix.scale, fix.segments))
        runCatching { SubtitleFiles.associateFonts(SubtitleFiles.fonts(file), target) }
        target
    }

    /** The speech in the episode's audio: the whole episode read once (a stream is downloaded, 1–2 minutes), its dialogue
     *  level every 10 ms, then where it rises above the quiet around it. */
    private suspend fun speech(context: Context, video: AudioSource, status: (String, Double?) -> Unit): List<SyncCue> {
        levels[video.url]?.let { return it }
        val file = File(context.cacheDir, "sync-" + UUID.randomUUID() + ".f32")
        val found = try {
            AudioExtractor.decode(context, video, file, 16000, progress = { value -> MainScope().launch { status("영상 음성 읽는 중", value) } })
            withContext(Dispatchers.Default) { SubtitleSync.speechFromLevels(AudioExtractor.speechLevels(AudioExtractor.read(file)), 0.01) }
        } finally { file.delete() }
        if (levels.size > 4) levels.clear()
        levels[video.url] = found
        return found
    }
}

/** iOS OfflineAnalyzer: the downloaded episodes of a work compared by their audio (the first and last five minutes) to find
 *  the opening and ending; kept per episode and used by the player's skip. */
object OfflineAnalyzer {
    private lateinit var directory: File
    private val json = Json { ignoreUnknownKeys = true }
    @kotlinx.serialization.Serializable private data class Stored(val type: String, val start: Double, val end: Double, val score: Double)
    fun init(context: Context) { directory = File(context.filesDir, "Chapters") }
    fun chapters(animeID: String, episodeID: String): List<Chapter> = runCatching {
        json.decodeFromString(ListSerializer(Stored.serializer()), File(directory, SubtitleFiles.key("$animeID#$episodeID") + ".json").readText()).map { Chapter(it.type, it.start, it.end) }
    }.getOrDefault(emptyList())
    fun clearCache() { directory.deleteRecursively() }

    suspend fun analyze(context: Context, entries: List<DownloadEntry>, status: (String) -> Unit): Int = withContext(Dispatchers.Default) {
        directory.mkdirs()
        var count = 0
        for (group in entries.groupBy { it.anime.id }.values.filter { it.size >= 2 }) {
            val fingerprints = mutableListOf<Triple<DownloadEntry, Pair<FloatArray, FloatArray>, Double>>()
            for (item in group.sortedBy { it.number }) {
                ensureActive()
                val local = item.localFile ?: continue
                withContext(Dispatchers.Main) { status("${item.anime.title} ${item.number}화 오디오 읽는 중") }
                val data = pcm(context, File(File(DownloadStore.directory, item.id), local)) ?: continue
                if (data.size < 120 * 8000) continue
                val front = AudioFingerprint.compute(data.copyOfRange(0, minOf(data.size, 300 * 8000)), 8000)
                val back = AudioFingerprint.compute(data.copyOfRange(maxOf(0, data.size - 300 * 8000), data.size), 8000)
                fingerprints += Triple(item, front to back, data.size / 8000.0)
            }
            for ((item, prints, duration) in fingerprints) {
                ensureActive()
                val opening = mutableListOf<AudioMatch>(); val ending = mutableListOf<AudioMatch>()
                for ((other, otherPrints, _) in fingerprints.filter { it.first.id != item.id }.take(5)) {
                    DesktopAudioFingerprint.region(prints.first, otherPrints.first)?.let { opening += it }
                    DesktopAudioFingerprint.region(prints.second, otherPrints.second)?.let { ending += it }
                }
                val selected = listOf(Triple("op", opening, 0.0), Triple("ed", ending, maxOf(0.0, duration - 300))).mapNotNull { (type, matches, offset) ->
                    DesktopAudioFingerprint.consensus(matches, offset)?.let { Stored(type, it.startSeconds, minOf(duration, it.endSeconds), it.score) }
                }
                File(directory, SubtitleFiles.key(item.anime.id + "#" + item.episodeID) + ".json").writeText(json.encodeToString(ListSerializer(Stored.serializer()), selected))
                count++
            }
        }
        count
    }
    /** A downloaded episode's audio at 8 kHz mono, decoded once and kept. */
    private suspend fun pcm(context: Context, video: File): FloatArray? {
        if (!video.isFile) return null
        val cache = File(directory, SubtitleFiles.key("desktop-s16-v1:" + video.path + video.lastModified()) + ".f32")
        if (!cache.isFile) runCatching { AudioExtractor.decode(context, AudioSource(video.absolutePath, emptyMap(), 0.0), cache, 8000) }.onFailure { cache.delete(); return null }
        return AudioExtractor.read(cache)
    }
}
