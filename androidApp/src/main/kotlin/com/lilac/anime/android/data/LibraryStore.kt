package com.lilac.anime.android.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.shared.Anime
import com.lilac.anime.shared.AnimeSnapshot
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.File

/** A work kept in 내 목록 or 시청기록 (iOS SavedAnime): its id is source + ":" + the work's id, the work a JSON snapshot. */
@Serializable
data class SavedAnime(
    val id: String,
    val title: String,
    val poster: String,
    val source: String,
    val snapshot: String,
    val savedAt: Long? = null,
) {
    @Transient private var decoded: Anime? = null
    /** Decoded once per snapshot: lists read this on every redraw. */
    val anime: Anime get() = decoded ?: AnimeSnapshot.decode(snapshot).also { decoded = it }

    companion object {
        fun of(anime: Anime, source: String, savedAt: Long? = null) = SavedAnime(
            id = source + ":" + anime.id, title = anime.title, poster = anime.poster, source = source,
            snapshot = AnimeSnapshot.encode(anime), savedAt = savedAt,
        ).also { it.decoded = anime }
    }
}

/** A watched episode (iOS WatchEntry). Times in epoch milliseconds. */
@Serializable
data class WatchEntry(
    val id: String,
    val anime: SavedAnime,
    val episodeID: String,
    val episodeTitle: String,
    val number: Int,
    val watchURL: String,
    val directURL: String? = null,
    val position: Double,
    val duration: Double,
    val updatedAt: Long,
    val progressPercent: Double? = null,
    val displayNumber: String? = null,
) {
    val progress: Double get() = if (duration > 0) minOf(position / duration, 1.0) else (progressPercent ?: 0.0) / 100
    val episodeLabel: String get() = displayNumber ?: anime.anime.episodes.firstOrNull { it.id == episodeID }?.displayNumber ?: number.toString()
}

/** iOS AppPreferences: the same keys and defaults, added to as each part is ported. */
@Serializable
data class AppPreferences(
    val theme: String = "dark",
    val source: String = "reanime",
    val quality: String = "1080p",
    val preferredServer: String? = null,
    val titleLanguage: String = "ko",
    val playerFit: String = "contain",
    val playerAspect: String? = null,
    val preferredServers: Map<String, String> = emptyMap(),
    val preferredStream: String? = null,
    val speed: Double = 1.0,
    val seekSeconds: Double = 10.0,
    val autoPlay: Boolean = true,
    val autoSkip: Boolean = false,
    val showSkipButton: Boolean = true,
    val backgroundAudio: Boolean = true,
    val subtitleSize: Double = 100.0,
    val subtitleLineHeight: Double? = null,
    val subtitleFont: String = "",
    val subtitleColor: String = "#FFFFFF",
    val outlineColor: String = "#000000",
    val outlineWidth: Double = 2.0,
    val subtitleBold: Boolean = true,
    val subtitlePadding: Double = 12.0,
    val subtitleOffset: Double = 0.0,
    val assEffects: Boolean = true,
    val vttStyle: Boolean = true,
    val subtitleProvider: String = "auto",
    val autoTranslation: Boolean = true,
    val translationProvider: String = "gemini",
    val translationModel: String = "",
    val translationModels: Map<String, String> = emptyMap(),
    val cloudFallback: Boolean = true,
    val translationFallback: Boolean = true,
    val qwenRegion: String = "international",
    val translationGlossary: String = "",
    val dropCaptions: Boolean = false,
    val promptExtra: String = "",
    val promptRules: String = "",
    val selectedGGUF: String = "",
    val anime4k: String = "off",
    val whisperAuto: Boolean = true,
    val whisperModel: String = "base",
    val subtitleSyncCheck: Boolean = true,
    val otherSiteTracks: Boolean = true,
    val localGPU: Boolean = true,
    val contextSize: Int = 4096,
    val threads: Int = 0,
    val maxTokens: Int = 512,
    val temperature: Double = 0.25,
    val topP: Double = 0.85,
    val topK: Int = 40,
    val repetitionPenalty: Double = 1.05,
    val contextCues: Int = 6,
    val prefetchAhead: Int = 10,
    val thinking: String = "off",
    val modelSampling: Boolean = true,
    val prompt: String = "이전 맥락\n{context}\n이후 맥락\n{future_context}\n\n{source_text}",
    val localModelInputs: Map<String, LocalModelInputOptions> = emptyMap(),
) {
    /** Desktop automatically uses a configured API, then the installed local model (iOS translationPreferences). */
    fun translationPreferences(automatic: Boolean = false, localOnly: Boolean = false, hasKey: (String) -> Boolean = { SecureKeys.load(it).isNotEmpty() }): AppPreferences? {
        if (automatic && !autoTranslation) return null
        val clouds = listOf("gemini", "openai", "deepl", "qwen", "claude")
        val wanted = translationProvider
        val files = runCatching { com.lilac.anime.android.localai.LocalModelFiles.files() }.getOrDefault(emptyList())
        val installedFile = files.firstOrNull { it.name == selectedGGUF } ?: files.firstOrNull { it.name == "gemma-4-E4B_q4_0-it.gguf" } ?: files.firstOrNull()
        val local = if (installedFile != null && com.lilac.anime.android.localai.LocalAI.available) copy(selectedGGUF = installedFile.name) else null
        if (localOnly) return if (wanted == "local") local else null
        if (wanted == "local" && local != null) return local
        val preferred = if (wanted in clouds) listOf(wanted) + clouds.filter { it != wanted } else clouds
        preferred.firstOrNull(hasKey)?.let { provider ->
            return copy(translationProvider = provider, translationModel = translationModels[provider] ?: if (wanted == provider) translationModel else "")
        }
        return local?.copy(translationProvider = "local")
    }
    /** 번역 프롬프트: the user's rules after the app's (extra) or in their place (custom), 4000 characters each. */
    val rulesExtra: String get() = promptExtra.trim().take(4000)
    val rulesCustom: String get() = promptRules.trim().take(4000)
}

/** How a local model is asked (iOS LocalModelInputOptions): 자동, 일반 지시문 or 번역 전용 input, the source language, and a
 *  prompt of the user's own with {text}. */
@Serializable
data class LocalModelInputOptions(val inputMode: String = "auto", val sourceLanguage: String = "auto", val customPrompt: String = "")

/** The subtitle put on an episode by hand, and its timing (iOS SubtitleChoice). */
@Serializable
data class SubtitleChoice(val relativeFile: String? = null, val offset: Double = 0.0)

@Serializable
private data class LibraryState(
    val preferences: AppPreferences = AppPreferences(),
    val favorites: List<SavedAnime> = emptyList(),
    val history: List<WatchEntry> = emptyList(),
    val subtitles: Map<String, SubtitleChoice> = emptyMap(),
    val aiSubtitleSeries: Map<String, Boolean> = emptyMap(),
)

/** 내 목록 · 시청 기록 · 설정 in library.json (iOS LibraryStore), written off the main thread half a second after a change. */
class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var writing: Job? = null
    private var ready = false

    var preferences by mutableStateOf(AppPreferences()); private set
    var favorites by mutableStateOf(emptyList<SavedAnime>()); private set
    var history by mutableStateOf(emptyList<WatchEntry>()); private set
    var subtitleChoices by mutableStateOf(emptyMap<String, SubtitleChoice>()); private set
    var aiSubtitleSeries by mutableStateOf(emptyMap<String, Boolean>()); private set
    var persistenceError by mutableStateOf<String?>(null)

    fun subtitleChoice(animeID: String, episodeID: String) = subtitleChoices["$animeID#$episodeID"]
    fun preferAI(value: Boolean, animeID: String) { aiSubtitleSeries = if (value) aiSubtitleSeries + (animeID to true) else aiSubtitleSeries - animeID; persist() }
    fun saveSubtitle(animeID: String, episodeID: String, file: java.io.File?, offset: Double) {
        val prefix = com.lilac.anime.android.subtitle.SubtitleFiles.root.canonicalPath + "/"
        val relative = file?.canonicalPath?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        subtitleChoices = subtitleChoices + ("$animeID#$episodeID" to SubtitleChoice(relative, offset)); persist()
    }
    fun clearSubtitleFiles() { subtitleChoices = subtitleChoices.mapValues { it.value.copy(relativeFile = null) }; persist() }

    suspend fun load() {
        if (ready) return
        val state = withContext(Dispatchers.IO) {
            runCatching { if (file.isFile) json.decodeFromString(LibraryState.serializer(), file.readText()) else LibraryState() }
        }
        state.onSuccess { preferences = it.preferences; favorites = it.favorites; history = it.history; subtitleChoices = it.subtitles; aiSubtitleSeries = it.aiSubtitleSeries }
            .onFailure { persistenceError = "저장된 보관함을 읽지 못했습니다: " + it.message }
        ready = true
    }

    fun update(change: (AppPreferences) -> AppPreferences) { preferences = change(preferences); persist() }

    fun contains(anime: Anime, source: String) = favorites.any { it.id == source + ":" + anime.id }
    fun toggle(anime: Anime, source: String) {
        val id = source + ":" + anime.id
        favorites = if (favorites.any { it.id == id }) favorites.filterNot { it.id == id }
        else favorites + SavedAnime.of(anime, source, System.currentTimeMillis())
        persist()
    }

    fun progress(anime: SavedAnime, episodeID: String, title: String, number: Int, watchURL: String, directURL: String?,
                 position: Double, duration: Double, displayNumber: String? = null) {
        if (!position.isFinite() || !duration.isFinite() || position < 0) return
        val id = anime.id + "#" + episodeID
        // Only the newest entry of a series keeps its episode list (iOS: library.json grew without end otherwise).
        val older = history.filter { it.id != id }.map { entry ->
            if (entry.anime.id != anime.id || entry.anime.snapshot.contains("\"episodes\":[]")) entry
            else entry.copy(displayNumber = entry.episodeLabel,
                anime = SavedAnime.of(AnimeSnapshot.withEpisodes(entry.anime.anime, emptyList()), entry.anime.source, entry.anime.savedAt))
        }
        history = (listOf(WatchEntry(id, anime, episodeID, title, number, watchURL, directURL, position, duration, System.currentTimeMillis(), displayNumber = displayNumber)) + older).take(500)
        persist()
    }
    fun resume(anime: SavedAnime, episodeID: String): Double =
        history.firstOrNull { it.anime.id == anime.id && it.episodeID == episodeID }?.let { if (it.duration > 0 && it.position / it.duration > 0.95) 0.0 else it.position } ?: 0.0

    fun deleteHistory(ids: Set<String>) { history = history.filterNot { it.id in ids }; persist() }
    fun clearHistory() { history = emptyList(); persist() }

    private fun persist() {
        if (!ready) return
        val state = LibraryState(preferences, favorites, history, subtitleChoices, aiSubtitleSeries)
        writing?.cancel()
        writing = scope.launch {
            delay(500)
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    val partial = File(file.path + ".part")
                    partial.writeText(json.encodeToString(LibraryState.serializer(), state))
                    if (!partial.renameTo(file)) { file.delete(); check(partial.renameTo(file)) { "파일을 바꾸지 못했습니다" } }
                }.exceptionOrNull()
            }
            if (failure != null) persistenceError = "보관함 저장 실패: " + failure.message
        }
    }
}
