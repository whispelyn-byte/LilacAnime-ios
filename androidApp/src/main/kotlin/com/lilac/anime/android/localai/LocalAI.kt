package com.lilac.anime.android.localai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.android.data.AppPreferences
import com.lilac.anime.android.data.LocalModelInputOptions
import com.lilac.anime.android.data.SecureKeys
import com.lilac.anime.android.player.HlsData
import com.lilac.anime.android.player.PlaybackFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/** iOS LilacLocalAI (llama.cpp b11490) through JNI. */
object LocalAI {
    val available: Boolean = runCatching { System.loadLibrary("lilac_local_ai"); true }.getOrDefault(false)
    external fun nativeOpen(path: String, context: Int, threads: Int, gpu: Int): Long
    external fun nativeClose(handle: Long)
    external fun nativeSetThinking(handle: Long, on: Boolean)
    external fun nativeChatTemplate(handle: Long): String?
    external fun nativeBackend(handle: Long): String?
    external fun nativeOutputTokens(handle: Long): Int
    external fun nativeSeconds(handle: Long): Double
    external fun nativeCancel(handle: Long)
    external fun nativeError(handle: Long): String?
    external fun nativeSetTranslationLanguage(handle: Long, source: String?)
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float, topK: Int, repetition: Float): String?
}

/** iOS LocalModelFiles: GGUF files in the app's Models folder. */
object LocalModelFiles {
    lateinit var directory: File; private set
    fun init(context: Context) { directory = File(context.filesDir, "Models").apply { mkdirs() } }
    fun files(): List<File> = directory.listFiles()?.filter { it.extension.lowercase() == "gguf" && it.isFile }?.sortedBy { it.name }.orEmpty()
    /** A GGUF file starts with its magic. */
    fun validate(file: File) {
        val magic = RandomAccessFile(file, "r").use { ByteArray(4).also { bytes -> it.readFully(bytes) } }
        if (String(magic, Charsets.US_ASCII) != "GGUF") throw PlaybackFailure("GGUF 모델 파일이 아닙니다.")
    }
}

/** iOS LocalInference: one model loaded at a time, kept 30 seconds after its last answer. */
object LocalInference {
    private val lock = Mutex()
    private var handle = 0L
    private var loaded = ""
    private var loadedContext = 0
    private var loadedThreads = 0
    private var loadedGPU = false
    private var idle: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var lastRun by mutableStateOf(""); private set

    private fun prepare(preferences: AppPreferences): Long {
        if (!LocalAI.available) throw PlaybackFailure("이 기기에서 로컬 AI 라이브러리를 불러오지 못했습니다.")
        idle?.cancel()
        val path = File(LocalModelFiles.directory, preferences.selectedGGUF).absolutePath
        val threads = if (preferences.threads == 0) maxOf(1, Runtime.getRuntime().availableProcessors() - 2) else preferences.threads
        if (loaded != path || loadedContext != preferences.contextSize || loadedThreads != threads || loadedGPU != preferences.localGPU) {
            if (handle != 0L) { LocalAI.nativeClose(handle); handle = 0L }
            // GPU: llama.cpp's Vulkan backend (Exynos Xclipse, Mali, Adreno); all layers go to the GPU.
            // A device without a usable Vulkan GPU loads on the CPU instead.
            handle = if (preferences.localGPU) LocalAI.nativeOpen(path, preferences.contextSize, threads, 1) else 0L
            if (handle == 0L) handle = LocalAI.nativeOpen(path, preferences.contextSize, threads, 0)
            if (handle == 0L) throw PlaybackFailure("모델을 읽지 못했습니다. 기기 메모리 또는 llama.cpp b11490의 모델 지원을 확인하세요.")
            loaded = path; loadedContext = preferences.contextSize; loadedThreads = threads; loadedGPU = preferences.localGPU
        }
        return handle
    }
    suspend fun chatTemplate(preferences: AppPreferences): String = lock.withLock { withContext(Dispatchers.Default) { LocalAI.nativeChatTemplate(prepare(preferences)).orEmpty() } }

    suspend fun generate(prompt: String, preferences: AppPreferences, sourceLanguage: String? = null): String = lock.withLock {
        withContext(Dispatchers.Default) {
            val model = prepare(preferences)
            LocalAI.nativeSetTranslationLanguage(model, sourceLanguage)
            LocalAI.nativeSetThinking(model, sourceLanguage == null && preferences.thinking == "on")
            val result = suspendCancellableCoroutine<String?> { continuation ->
                continuation.invokeOnCancellation { LocalAI.nativeCancel(model) }
                val value = LocalAI.nativeGenerate(model, prompt, preferences.maxTokens, preferences.temperature.toFloat(), preferences.topP.toFloat(),
                    preferences.topK, preferences.repetitionPenalty.toFloat())
                if (continuation.isActive) continuation.resumeWith(Result.success(value))
            } ?: throw PlaybackFailure(LocalAI.nativeError(model) ?: "로컬 모델이 답하지 않았습니다.")
            lastRun = preferences.selectedGGUF + " · " + (LocalAI.nativeBackend(model) ?: "CPU") + " · " + LocalAI.nativeOutputTokens(model) + " 토큰 · " + "%.1f초".format(LocalAI.nativeSeconds(model))
            idle = scope.launch { delay(30_000); unload() }
            result
        }
    }
    suspend fun unload() = lock.withLock { idle?.cancel(); idle = null; if (handle != 0L) { LocalAI.nativeClose(handle); handle = 0L }; loaded = "" }
}

/** iOS LocalModelInputOptions' rules. */
object LocalInput {
    fun languageTemplate(template: String) = Regex("\\bsource_lang_code\\b").containsMatchIn(template) && Regex("\\btarget_lang_code\\b").containsMatchIn(template)
    fun usesTranslationInput(options: LocalModelInputOptions, template: String) = options.inputMode == "translation" || (options.inputMode == "auto" && languageTemplate(template))
    fun validate(options: LocalModelInputOptions) {
        if (options.inputMode !in listOf("auto", "chat", "translation") || options.sourceLanguage !in listOf("auto", "ja", "en", "zh", "ko")) throw PlaybackFailure("올바른 입력 형식과 원문 언어를 선택해 주세요.")
        if (options.customPrompt.length > 8000 || (options.customPrompt.isNotEmpty() && !options.customPrompt.contains("{text}"))) throw PlaybackFailure("프롬프트는 8,000자 이하이며 원문이 들어갈 {text}를 포함해야 합니다.")
    }
    fun language(options: LocalModelInputOptions, source: String): String = when {
        options.sourceLanguage != "auto" -> options.sourceLanguage
        source.any { Character.UnicodeScript.of(it.code) in setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN) } -> "ja"
        source.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL } -> "ko"
        else -> "en"
    }
    fun prompt(options: LocalModelInputOptions, source: String): String = Regex("\\{(text|source_lang|target_lang)\\}").replace(options.customPrompt) { match ->
        when (match.groupValues[1]) { "text" -> source; "source_lang" -> language(options, source); else -> "ko" }
    }
    fun acceptsTranslation(text: String) = text.none { it.isLetter() } || text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL }
}
val AppPreferences.selectedLocalInput: LocalModelInputOptions get() = localModelInputs[selectedGGUF] ?: LocalModelInputOptions()

/** The desktop's model presets (iOS DesktopModelPreset, Resources/desktop-models.json). */
@Serializable
data class ModelPreset(val id: String, val label: String, val note: String, val repo: String, val file: String, val size: Long, val legacy: Boolean) {
    val url get() = "https://huggingface.co/$repo/resolve/main/" + file.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}

/** iOS DesktopModelInstaller: a preset downloaded (resumed from where it stopped) into the Models folder. */
object ModelInstaller {
    val progress = mutableStateMapOf<String, Double>()
    var error by mutableStateOf<String?>(null)
    var revision by mutableStateOf(0); private set
    private val jobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var presets: List<ModelPreset>? = null

    fun all(context: Context): List<ModelPreset> = presets ?: runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(ModelPreset.serializer()), context.assets.open("desktop-models.json").bufferedReader().readText())
    }.getOrDefault(emptyList()).also { presets = it }
    fun installed(model: ModelPreset) = File(LocalModelFiles.directory, model.file.substringAfterLast('/')).isFile

    fun install(model: ModelPreset) = download(model.id, model.url, model.file.substringAfterLast('/'))
    /** A GGUF file by its address (주소로 받기 too); a part already received is continued. */
    fun download(id: String, url: String, name: String) {
        if (jobs.containsKey(id)) return
        error = null; progress[id] = 0.0
        jobs[id] = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val target = File(LocalModelFiles.directory, name)
                    val partial = File(target.path + ".part")
                    val start = if (partial.isFile) partial.length() else 0L
                    val request = Request.Builder().url(url).apply {
                        if (start > 0) header("Range", "bytes=$start-")
                        SecureKeys.load("huggingface").takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
                    }.build()
                    val client = HlsData.client.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                    client.newCall(request).execute().use { response ->
                        if (response.code !in 200..299) throw PlaybackFailure(if (response.code == 401 || response.code == 403) "접근이 제한된 모델입니다. Hugging Face 토큰을 저장하세요." else "모델 다운로드 실패 (${response.code})")
                        val append = response.code == 206
                        val total = (response.body?.contentLength() ?: -1L).let { if (it > 0) it + (if (append) start else 0) else -1L }
                        var written = if (append) start else 0L
                        java.io.FileOutputStream(partial, append).use { out ->
                            val input = response.body!!.byteStream(); val buffer = ByteArray(1 shl 16)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                out.write(buffer, 0, n); written += n
                                if (total > 0) withContext(Dispatchers.Main) { progress[id] = written.toDouble() / total }
                            }
                        }
                    }
                    LocalModelFiles.validate(partial)
                    if (!partial.renameTo(target)) { target.delete(); partial.renameTo(target) }
                }
                revision++
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) { error = failure.message }
            finally { jobs.remove(id); progress.remove(id) }
        }
    }
    fun cancel(id: String) { jobs.remove(id)?.cancel(); progress.remove(id) }
    fun remove(model: ModelPreset) { File(LocalModelFiles.directory, model.file.substringAfterLast('/')).delete(); revision++ }
}
