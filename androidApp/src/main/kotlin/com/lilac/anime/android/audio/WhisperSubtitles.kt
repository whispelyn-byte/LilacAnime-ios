package com.lilac.anime.android.audio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.android.data.AppPreferences
import com.lilac.anime.android.data.SavedAnime
import com.lilac.anime.android.player.HlsData
import com.lilac.anime.android.player.PlaybackFailure
import com.lilac.anime.android.subtitle.SubtitleFiles
import com.lilac.anime.android.subtitle.TranslationCoordinator
import com.lilac.anime.shared.SubtitleTools
import com.lilac.anime.whisper.Whisper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.util.UUID

@Serializable data class WhisperLine(val start: Double, val end: Double, val text: String)

/** iOS WhisperSubtitleFile: 30-second chunks from the playing one on, and the recognized lines as WebVTT. */
object WhisperSubtitleFile {
    fun nextChunk(duration: Double, position: Double, completed: Set<Int>): Int? {
        if (!duration.isFinite() || duration <= 0) return null
        val count = Math.ceil(minOf(duration, 86400.0) / 30).toInt()
        val current = minOf(count - 1, maxOf(0, (maxOf(0.0, if (position.isFinite()) position else 0.0) / 30).toInt()))
        return ((current until count) + (0 until current)).firstOrNull { it !in completed }
    }
    fun vtt(lines: List<WhisperLine>): String {
        fun stamp(value: Double): String { val ms = Math.round(maxOf(0.0, value) * 1000); return "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000) }
        return "WEBVTT\n\n" + lines.sortedBy { it.start }.joinToString("\n") {
            "${stamp(it.start)} --> ${stamp(maxOf(it.end, it.start + 0.1))}\n" + it.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "\n"
        }
    }
}

/** Whisper models as whisper.cpp files (iOS: WhisperKit's tiny / base / small / large-v3 turbo). */
object WhisperModels {
    data class Variant(val id: String, val label: String, val file: String)
    val variants = listOf(Variant("tiny", "tiny · 75MB", "ggml-tiny.bin"), Variant("base", "base · 142MB", "ggml-base.bin"),
        Variant("small", "small · 466MB", "ggml-small.bin"), Variant("large-v3-v20240930_626MB", "large-v3 turbo · 547MB", "ggml-large-v3-turbo-q5_0.bin"))
    lateinit var root: File; private set
    var downloading by mutableStateOf<String?>(null); private set
    var progress by mutableDoubleStateOf(0.0); private set
    var error by mutableStateOf<String?>(null)
    var revision by mutableStateOf(0); private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    fun init(context: Context) { root = File(context.filesDir, "WhisperModels").apply { mkdirs() } }
    fun file(id: String): File? = variants.firstOrNull { it.id == id }?.let { File(root, it.file) }?.takeIf { it.isFile }
    fun download(id: String) {
        val variant = variants.firstOrNull { it.id == id } ?: return
        if (downloading != null) return
        downloading = id; progress = 0.0; error = null
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val target = File(root, variant.file); val partial = File(target.path + ".part")
                    val start = if (partial.isFile) partial.length() else 0L
                    val request = Request.Builder().url("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + variant.file).apply { if (start > 0) header("Range", "bytes=$start-") }.build()
                    HlsData.client.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                        if (response.code !in 200..299) throw PlaybackFailure("Whisper 모델 다운로드 실패 (${response.code})")
                        val append = response.code == 206
                        val total = (response.body?.contentLength() ?: -1).let { if (it > 0) it + if (append) start else 0 else -1 }
                        var written = if (append) start else 0L
                        java.io.FileOutputStream(partial, append).use { out ->
                            val input = response.body!!.byteStream(); val buffer = ByteArray(1 shl 16)
                            while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); written += n
                                if (total > 0) withContext(Dispatchers.Main) { progress = written.toDouble() / total } }
                        }
                    }
                    if (!partial.renameTo(target)) { target.delete(); partial.renameTo(target) }
                }
                revision++
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) { error = failure.message }
            finally { downloading = null; job = null }
        }
    }
    fun cancelDownload() { job?.cancel() }
    suspend fun remove(id: String) { WhisperRuntime.close(); file(id)?.delete(); revision++ }
}

/** One whisper.cpp model loaded at a time (iOS WhisperRuntime). */
object WhisperRuntime {
    private val lock = Mutex()
    private var handle = 0L
    private var loaded: File? = null
    suspend fun recognize(samples: FloatArray, model: File): List<WhisperLine> = lock.withLock {
        withContext(Dispatchers.Default) {
            if (!Whisper.available) throw PlaybackFailure("이 기기에서 Whisper 라이브러리를 불러오지 못했습니다.")
            if (handle == 0L || loaded != model) {
                if (handle != 0L) Whisper.nativeClose(handle)
                handle = Whisper.nativeOpen(model.absolutePath); loaded = model
                if (handle == 0L) { loaded = null; throw PlaybackFailure("Whisper 모델을 읽지 못했습니다.") }
            }
            val model = handle
            val segments = suspendCancellableCoroutine<Array<String>?> { continuation ->
                continuation.invokeOnCancellation { Whisper.nativeAbort(model) }
                val value = Whisper.nativeTranscribe(model, samples, "ja", maxOf(1, Runtime.getRuntime().availableProcessors() - 2))
                if (continuation.isActive) continuation.resumeWith(Result.success(value))
            } ?: throw PlaybackFailure("음성 인식이 중단되었습니다.")
            segments.mapNotNull { line ->
                val parts = line.split("\t", limit = 4)
                val start = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val end = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                val text = parts.getOrNull(3)?.trim().orEmpty()
                if (text.isEmpty() || end <= start || Regex("^\\[[^\\]]*\\]$|^\\([^)]*\\)$").matches(text)) null else WhisperLine(start, end, text)
            }
        }
    }
    suspend fun close() = lock.withLock { if (handle != 0L) { Whisper.nativeClose(handle); handle = 0L; loaded = null } }
}

/** iOS WhisperSubtitles: an episode heard 30 seconds at a time from where it plays, the lines saved as they come. */
class WhisperSubtitles(private val context: Context) {
    var running by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var generation = 0L
    @Serializable private data class Cache(val chunks: Set<Int> = emptySet(), val lines: List<WhisperLine> = emptyList())

    fun cancel() { generation++; job?.cancel(); job = null; running = false }
    fun start(video: AudioSource, key: String, model: String, position: () -> Double, receive: (File, List<WhisperLine>, Boolean) -> Unit, failed: (String) -> Unit) {
        cancel()
        val modelFile = WhisperModels.file(model) ?: run { failed("설정 > 자막 자동 번역 > Whisper에서 모델을 먼저 받아 주세요."); return }
        if (!video.duration.isFinite() || video.duration <= 0) { failed("영상 길이가 확인된 뒤 음성 인식을 시작해 주세요."); return }
        val token = ++generation
        running = true; status = "Whisper 모델 준비 중"
        job = scope.launch {
            try {
                val directory = File(SubtitleFiles.root, "Whisper").apply { mkdirs() }
                val name = SubtitleFiles.key("whisper-ja-v1|$model|$key|${video.duration.toInt()}")
                val file = File(directory, "$name.vtt"); val cache = File(directory, "$name.json")
                var saved = runCatching { Json.decodeFromString(Cache.serializer(), cache.readText()) }.getOrDefault(Cache())
                if (saved.lines.isNotEmpty()) receive(file, saved.lines, false)
                while (true) {
                    val chunk = WhisperSubtitleFile.nextChunk(video.duration, position(), saved.chunks) ?: break
                    val start = chunk * 30.0; val length = minOf(30.0, video.duration - start)
                    status = "Whisper %d:%02d 음성 인식 중".format(start.toInt() / 60, start.toInt() % 60)
                    val pcm = File(context.cacheDir, "whisper-" + UUID.randomUUID() + ".f32")
                    val samples = try { AudioExtractor.decode(context, video, pcm, 16000, start, length); AudioExtractor.read(pcm) } finally { pcm.delete() }
                    val lines = WhisperRuntime.recognize(samples, modelFile).filter { it.start < length }.map {
                        WhisperLine(Math.floor((start + maxOf(0.0, it.start)) * 1000) / 1000, Math.floor((start + minOf(length, it.end)) * 1000) / 1000, it.text)
                    }
                    if (token != generation) return@launch
                    saved = Cache(saved.chunks + chunk, saved.lines + lines)
                    val snapshot = saved
                    withContext(Dispatchers.IO) { file.writeText(WhisperSubtitleFile.vtt(snapshot.lines)); cache.writeText(Json.encodeToString(Cache.serializer(), snapshot)) }
                    if (token != generation) return@launch
                    receive(file, saved.lines, false)
                }
                status = if (saved.lines.isEmpty()) "인식된 대사가 없습니다." else "Whisper 자막 저장 완료"
                receive(file, saved.lines, true)
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) {
                if (token == generation) { status = failure.message ?: "Whisper 음성 인식 실패"; failed(status) }
            } finally { if (token == generation) { running = false; job = null } }
        }
    }
}

/** iOS WhisperLiveTranslation: only newly recognized lines translated (Gemini waits for 40 except at the end). */
class WhisperLiveTranslation {
    private val translator = TranslationCoordinator()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var lines = emptyList<WhisperLine>()
    private val translated = mutableMapOf<Long, String>()
    private val sent = mutableSetOf<Double>()
    private var finished = false
    private var generation = 0L
    private var failures = 0
    fun cancel() { generation++; job?.cancel(); job = null; translator.cancel(); lines = emptyList(); translated.clear(); sent.clear(); finished = false; failures = 0 }
    private fun key(start: Double) = Math.round(start * 1000)
    private fun write(original: File, receive: (File) -> Unit) {
        val all = lines.map { WhisperLine(it.start, it.end, translated[key(it.start)] ?: it.text) }
        val result = File(original.parentFile, original.nameWithoutExtension + ".ko.vtt")
        if (runCatching { result.writeText(WhisperSubtitleFile.vtt(all)) }.isSuccess) receive(result)
    }
    fun update(lines: List<WhisperLine>, complete: Boolean, original: File, preferences: AppPreferences, anime: SavedAnime, receive: (File) -> Unit) {
        this.lines = lines; finished = complete
        if (translated.isNotEmpty()) write(original, receive)
        if (job != null || failures >= 2) return
        val token = generation
        job = scope.launch {
            try {
                while (isActive && token == generation) {
                    val pending = this@WhisperLiveTranslation.lines.filter { it.start !in sent }
                    if (pending.isEmpty() || !(finished || pending.size >= if (preferences.translationProvider == "gemini") 40 else 1)) return@launch
                    val batch = pending.take(40)
                    val input = File(SubtitleFiles.translations.apply { mkdirs() }, "whisper-batch-" + UUID.randomUUID() + ".vtt")
                    input.writeText(WhisperSubtitleFile.vtt(batch))
                    var succeeded = false
                    translator.translate(input, preferences, { 0.0 }, { 0L }, anime) { output ->
                        if (token != generation) return@translate
                        val content = runCatching { SubtitleFiles.text(output) }.getOrNull() ?: return@translate
                        SubtitleTools.cues(content, "vtt").forEach { translated[key(it.startSeconds)] = it.text }
                        write(original, receive)
                        if (!output.name.startsWith("working-")) succeeded = true
                    }
                    while (translator.running) delay(100)
                    input.delete()
                    if (token != generation) return@launch
                    if (succeeded) { sent += batch.map { it.start }; failures = 0 } else { failures++; if (failures >= 2) return@launch }
                }
            } finally { if (token == generation) job = null }
        }
    }
}
