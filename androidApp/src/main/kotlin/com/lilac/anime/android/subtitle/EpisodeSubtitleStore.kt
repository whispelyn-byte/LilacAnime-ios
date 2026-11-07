package com.lilac.anime.android.subtitle

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.android.player.PlaybackItem
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A subtitle kept for an episode (iOS SavedSubtitle): where it came from, whether it is a machine translation, and the
 *  original it was translated from. */
@Serializable
data class SavedSubtitle(
    val id: String, val episodeKey: String, val name: String, val relativeFile: String, val provider: String, val translated: Boolean,
    val date: Long, val behind: Boolean? = null, val originalFile: String? = null,
) {
    val file: File? get() = resolve(relativeFile)
    val original: File? get() = originalFile?.let(::resolve)
    private fun resolve(path: String): File? {
        val result = File(SubtitleFiles.root, path)
        return result.takeIf { it.canonicalPath.startsWith(SubtitleFiles.root.canonicalPath + "/") && it.isFile }
    }
}

/** iOS EpisodeSubtitleStore: each episode's subtitles in episode-subtitles.json, the newest twenty kept. */
object EpisodeSubtitleStore {
    private lateinit var index: File
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var writing: Job? = null
    private var loaded = false
    var records by mutableStateOf(emptyList<SavedSubtitle>()); private set
    var error by mutableStateOf<String?>(null)

    fun init(context: Context) { index = File(context.filesDir, "episode-subtitles.json") }
    suspend fun load() {
        if (loaded) return
        val saved = withContext(Dispatchers.IO) { runCatching { json.decodeFromString(ListSerializer(SavedSubtitle.serializer()), index.readText()) }.getOrDefault(emptyList()) }
        val early = records.map { it.id }.toSet()
        records = saved.filter { it.id !in early } + records
        loaded = true
        if (early.isNotEmpty()) persist()
    }

    private fun key(item: PlaybackItem) = item.anime.id + "#" + item.episodeID
    fun list(item: PlaybackItem): List<SavedSubtitle> = records.filter { it.episodeKey == key(item) && it.file != null }
        .sortedWith(compareBy<SavedSubtitle> { it.behind == true }.thenComparator { a, b -> if (a.behind == true) a.date.compareTo(b.date) else b.date.compareTo(a.date) })

    fun save(file: File, item: PlaybackItem, provider: String, translated: Boolean, behind: Boolean = false, original: File? = null) {
        if (file.name.startsWith("working-")) return
        runCatching {
            val prefix = SubtitleFiles.root.canonicalPath + "/"
            var target = file
            if (!file.canonicalPath.startsWith(prefix)) {
                val folder = File(SubtitleFiles.root, "Saved").apply { mkdirs() }
                target = File(folder, SubtitleFiles.key(file.path) + "." + file.extension)
                if (!target.exists()) file.copyTo(target)
            }
            val episodeKey = key(item)
            SubtitleFiles.associateFonts(SubtitleFiles.fonts(file) + (original?.let(SubtitleFiles::fonts) ?: emptyList()), target)
            val relative = target.canonicalPath.removePrefix(prefix)
            val id = SubtitleFiles.key(episodeKey + relative)
            val originalRelative = original?.let { value ->
                if (value.canonicalPath.startsWith(prefix)) value.canonicalPath.removePrefix(prefix)
                else records.firstOrNull { it.episodeKey == episodeKey && !it.translated && it.name == value.name }?.relativeFile
            }
            var next = records.filterNot { it.id == id || (translated && it.episodeKey == episodeKey && it.translated && it.provider == provider) }
            next = next + SavedSubtitle(id, episodeKey, file.name, relative, provider, translated, System.currentTimeMillis(), behind, originalRelative)
            records = next
            val keep = (listOf(id) + list(item).filter { it.id != id }.take(19).map { it.id }).toSet()
            records = records.filterNot { it.episodeKey == episodeKey && it.id !in keep }
            persist()
        }.onFailure { error = it.message }
    }
    fun remove(id: String) {
        val file = records.firstOrNull { it.id == id }?.file
        records = records.filterNot { it.id == id }; persist()
        if (file != null && records.none { it.file == file || it.original == file }) file.delete()
    }
    fun clear() { records = emptyList(); persist() }

    private fun persist() {
        if (!loaded) return
        val value = records
        writing?.cancel()
        writing = scope.launch {
            delay(500)
            withContext(Dispatchers.IO) { runCatching { index.writeText(json.encodeToString(ListSerializer(SavedSubtitle.serializer()), value)) } }
                .onFailure { error = it.message }
        }
    }
}

/** Subtitle names as the desktop shows them (iOS SubtitleLabels): by where they came from, the user's own files by name. */
object SubtitleLabels {
    fun provider(value: String) = mapOf("whisper" to "Whisper", "linkkf" to "Linkkf", "reanime" to "사이트", "kairan" to "Kairan", "csora" to "Csora",
        "anissia" to "Anissia", "jimaku" to "Jimaku", "provider" to "제공", "download" to "다운로드", "user" to "내")[value] ?: value
    fun engine(value: String) = mapOf("gemini" to "Gemini", "openai" to "OpenAI", "deepl" to "DeepL", "qwen" to "Qwen", "claude" to "Claude", "local" to "로컬 AI")[value] ?: "AI"
    fun label(provider: String, translated: Boolean, name: String, maker: String? = null): String = when {
        translated -> engine(provider) + " 번역"
        provider == "user" -> SubtitleNames.label(name)
        provider == "anissia" -> "Anissia" + (maker?.let { " · $it" } ?: "") + " 자막"
        provider == "reanime" -> "사이트 자막 트랙 · " + SubtitleNames.label(name)
        else -> provider(provider) + " 자막"
    }
    fun label(record: SavedSubtitle, maker: String? = null) = label(record.provider, record.translated, record.name, maker)
}
