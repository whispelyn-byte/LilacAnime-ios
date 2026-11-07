package com.lilac.anime.android.subtitle

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.android.data.LibraryStore
import com.lilac.anime.android.data.SecureKeys
import com.lilac.anime.android.data.await
import com.lilac.anime.android.player.MpvEngine
import com.lilac.anime.android.player.PlaybackItem
import com.lilac.anime.android.player.RemoteSubtitle
import com.lilac.anime.android.player.ResolvedStream
import com.lilac.anime.android.player.SubtitlePolicy
import com.lilac.anime.shared.*
import com.lilac.anime.shared.ported.SubtitleEpisodeMatcher
import kotlinx.coroutines.*
import java.io.File

/** The player's subtitles (iOS EpisodePlayerModel's subtitle part, app.js switchSubtitleSource / renderSubtitleSheet):
 *  the automatic choice as an episode opens, the source chips, the site's tracks, Anissia's makers, Jimaku's files, the
 *  user's own file, and the AI translation. Whisper and the audio sync come with stage 5. */
class PlayerSubtitles(
    context: Context,
    private val library: LibraryStore,
    private val engine: MpvEngine,
    private val item: () -> PlaybackItem,
    private val active: () -> ResolvedStream?,
    private val ownTracks: () -> List<RemoteSubtitle>,
    private val token: () -> Long,
    private val notify: (String) -> Unit,
    /** The video to listen to (Whisper, 자막 싱크): the stream as mpv plays it (through the proxy when it does). */
    private val video: () -> com.lilac.anime.android.audio.AudioSource? = { null },
    private val chapters: () -> List<com.lilac.anime.android.player.Chapter> = { emptyList() },
) {
    private val appContext = context.applicationContext
    val whisper = com.lilac.anime.android.audio.WhisperSubtitles(appContext)
    private val whisperLive = com.lilac.anime.android.audio.WhisperLiveTranslation()
    var whisperStatus by mutableStateOf(""); private set
    var syncStatus by mutableStateOf<String?>(null); private set
    var syncRunning by mutableStateOf(false); private set
    var syncNotice by mutableStateOf<String?>(null); private set
    private var syncUndo: (() -> Unit)? = null
    private var syncJob: Job? = null
    private val syncChecked = mutableSetOf<String>()
    val preparer = SubtitlePreparer(context)
    val translation = TranslationCoordinator()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val defaults = context.getSharedPreferences("lilac", Context.MODE_PRIVATE)

    var subtitle by mutableStateOf<File?>(null); private set
    private var sourceSubtitle: File? = null
    var selectedProvider by mutableStateOf(""); private set
    var selectedTrackURL by mutableStateOf<String?>(null); private set
    var selectedMaker by mutableStateOf<String?>(null); private set
    var selectedJimakuURL by mutableStateOf<String?>(null); private set
    var subtitleState by mutableStateOf<String?>(null); private set
    var koreanOffer by mutableStateOf<String?>(null); private set
    var makers by mutableStateOf(emptyList<SubtitleMaker>()); private set
    var searching by mutableStateOf(false); private set
    var searchError by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)
    var offset by mutableDoubleStateOf(0.0); private set
    private var pressedSource by mutableStateOf<String?>(null)
    private var pressedFor by mutableStateOf<File?>(null)
    private var pickedSheet by mutableStateOf<String?>(null)
    private var sheetPicked by mutableStateOf(false)
    private val koreanFiles = mutableStateMapOf<String, Boolean>()
    private var koreanFound = false
    private var request = 0L
    private var chipRequest = 0L
    private var chipJob: Job? = null
    private var automaticJob: Job? = null
    private var offerJob: Job? = null
    private var classification: Job? = null
    private var offered: Found? = null
    private val crossService = IosServices()
    /** The same episode's tracks on the other sites (RE:Anime, Miruro), asked once as it opens (iOS CrossSiteTracks). */
    var otherTracks by mutableStateOf(emptyList<RemoteSubtitle>()); private set
    private var otherJob: Deferred<List<RemoteSubtitle>>? = null
    private var otherKoreanJob: Deferred<List<CrossSiteTrack>>? = null

    fun otherSiteTracksChanged(enabled: Boolean) {
        library.update { it.copy(otherSiteTracks = enabled) }
        loadOther()
    }

    private fun clearOther() {
        otherJob?.cancel(); otherJob = null
        otherKoreanJob?.cancel(); otherKoreanJob = null
        otherTracks = emptyList()
    }

    private fun loadOther() {
        val item = item()
        clearOther()
        val label = item.displayNumber.trim()
        val number = label.toIntOrNull() ?: item.number
        val special = Regex("^\\d+(?:\\.\\d+|[a-z])$", RegexOption.IGNORE_CASE).matches(label) ||
            Regex("^(?:ova|ona|sp|special|특별|극장)", RegexOption.IGNORE_CASE).containsMatchIn(label)
        val dubbed = Regex("(?:^|[^a-z])dub(?:bed)?(?:[^a-z]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(item.watchURL + " " + item.episodeID)
        if (!library.preferences.otherSiteTracks || item.anime.source == "local" || special || dubbed || number <= 0 || item.directURL != null) return
        val at = token()
        val korean = scope.async {
            com.lilac.anime.android.data.DesktopCatalog.enrich(item.anime.anime, item.anime.source)
            val record = com.lilac.anime.android.data.DesktopCatalog.record(item.anime)
            val titles = (listOf(record?.korean.orEmpty(), item.anime.title) + record?.aliases.orEmpty())
                .filter { TitleCandidates.isKorean(it) }.distinct().take(3)
            if (titles.isEmpty()) emptyList()
            else crossService.await<List<CrossSiteTrack>> { koreanSiteTracks(item.anime.anime, item.anime.source, number, false, false, titles, it) }.getOrDefault(emptyList())
        }
        otherKoreanJob = korean
        otherJob = scope.async {
            val sites = crossService.await<List<CrossSiteTrack>> { crossSiteTracks(item.anime.anime, item.anime.source, number, false, false, it) }.getOrDefault(emptyList())
            val fast = sites.map { RemoteSubtitle(it.label, it.url, it.language, it.headers.ifEmpty { null }, it.origin) }
            if (at == token() && otherKoreanJob === korean) otherTracks = fast
            // The Korean sites' files join the list when they come; the automatic choice waits for them only while the
            // other sites have no Korean track (desktop d8f7978).
            suspend fun finish(): List<RemoteSubtitle> {
                val more = korean.await().mapNotNull { SubtitleFiles.providerTrack(it) }
                val found = (fast + more).distinctBy { it.url }
                if (at == token() && otherKoreanJob === korean) otherTracks = found
                return found
            }
            if (fast.any(SubtitlePolicy::isKorean)) {
                scope.launch { finish() }
                fast
            } else finish()
        }
    }
    private val trackSites = setOf("reanime", "miruro")
    private val koreanSites = setOf("ohli24", "linkani", "linkkf")
    /** iOS CrossSiteTrackPolicy.merged: the site's own tracks, then the other sites' — all when the site has none, only a
     *  Korean one beside the site's own, the Korean sites' files always. */
    private fun merged(own: List<RemoteSubtitle>, other: List<RemoteSubtitle>): List<RemoteSubtitle> {
        val mine = own.filter { it.origin == null }
        val others = (own.filter { it.origin != null } + other).filter { t -> mine.none { it.url == t.url } }.distinctBy { it.url }
        if (mine.any(SubtitlePolicy::isKorean)) return mine + others.filter { it.origin in koreanSites }
        return if (mine.isEmpty()) others else mine + others.filter(SubtitlePolicy::isKorean)
    }
    fun originLabel(track: RemoteSubtitle) = mapOf("reanime" to "Re:Anime", "miruro" to "Miruro", "animenosub" to "Animenosub", "ohli24" to "애니24",
        "linkani" to "링크애니", "linkkf" to "Linkkf")[track.origin ?: item().anime.source] ?: "Re:Anime"

    /** As an episode opens: its timing, and the subtitle put on it by hand when 자막 소스 is 직접 선택. */
    fun begin() {
        val item = item()
        val choice = library.subtitleChoice(item.anime.id, item.episodeID)
        offset = choice?.offset ?: library.preferences.subtitleOffset
        engine.subtitleOffset = offset
        if (library.preferences.subtitleProvider == "manual") choice?.relativeFile?.let { relative ->
            val file = File(SubtitleFiles.root, relative)
            if (file.isFile && file.canonicalPath.startsWith(SubtitleFiles.root.canonicalPath + "/")) {
                sourceSubtitle = file; subtitle = file; engine.subtitle(file)
                scope.launch { isKorean(file) }
            }
        }
        scope.launch { EpisodeSubtitleStore.load() }
        loadOther()
    }
    /** Another episode: everything of the last one let go. */
    fun reset() {
        request++; chipRequest++; chipJob?.cancel(); automaticJob?.cancel(); classification?.cancel(); dismissOffer(); offerJob?.cancel()
        translation.cancel(); preparer.cancel(); engine.clearSubtitle()
        subtitle = null; sourceSubtitle = null; selectedProvider = ""; selectedTrackURL = null; selectedMaker = null; selectedJimakuURL = null
        subtitleState = null; makers = emptyList(); searching = false; searchError = null; pressedSource = null; pressedFor = null; pickedSheet = null; sheetPicked = false
        whisper.cancel(); whisperLive.cancel(); whisperStatus = ""; syncJob?.cancel(); syncRunning = false; syncStatus = null; dismissSync()
        koreanFound = false; error = null; clearOther()
    }

    suspend fun isKorean(file: File): Boolean = koreanFiles[file.path] ?: SubtitleFiles.isKorean(file).also { koreanFiles[file.path] = it }

    // MARK: the automatic choice

    /** As the video starts (iOS automaticSubtitle): saved, the site's Korean track, the blogs, else a source to translate. */
    fun automatic(stream: ResolvedStream) {
        val requested = ++request
        if (library.preferences.subtitleProvider == "manual" || subtitle != null) return
        if (DesktopSubtitlePolicy.burnedKorean(item().anime.source, stream)) return
        val prefs = library.preferences
        val opening = item(); val at = token()
        automaticJob?.cancel()
        automaticJob = scope.launch {
            try {
                // desktop ensureSubtitle: the other sites' tracks are waited for (16 s at most) only once nothing saved
                // and no subtitle of the stream's own is there, so those go on at once.
                val job = otherJob
                var waited: List<RemoteSubtitle>? = null
                // Waited for once: the translation step asks again and takes what came.
                val later: (suspend () -> List<RemoteSubtitle>)? = if (job == null || stream.subtitles.any(SubtitlePolicy::isKorean)) null else suspend {
                    waited ?: try {
                        withTimeoutOrNull(16_000) { job.await() } ?: otherTracks
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        otherTracks
                    }.also { waited = it }
                }
                val current = { at == token() && requested == request && subtitle == null }
                val found = preparer.prepare(opening, merged(stream.subtitles, otherTracks), library.preferences, library.aiSubtitleSeries[opening.anime.id] == true,
                    stream = stream, later = later, status = { if (current()) subtitleState = it })
                if (found != null && current()) {
                    subtitleState = null
                    select(found.file, found.provider, automatic = true)
                    // Another site's Korean track: marked in the list, and said to be synced to this video's audio.
                    found.track?.let { track ->
                        selectedTrackURL = track.url
                        notify(originLabel(track) + "의 같은 회차 한국어 자막이에요. 이 영상에 싱크를 맞춰요.")
                    }
                }
                // Nothing found: the episode heard with Whisper, when a model is installed (desktop whisperWhenNoSubtitle).
                else if (found == null && current() && prefs.whisperAuto &&
                    com.lilac.anime.android.audio.WhisperModels.file(prefs.whisperModel) != null) { subtitleState = null; startWhisper(manual = false) }
                else if (found == null && current()) subtitleState = when {
                    siteTracks.any(SubtitlePolicy::isKorean) -> "사이트 트랙에 한국어 자막이 있어요. 플레이어 설정 > 자막에서 고를 수 있어요."
                    siteTracks.isNotEmpty() -> "한국어 자막이 없습니다. 아래 사이트 트랙에서 다른 언어를 고르거나 내 자막 파일을 열 수 있어요."
                    else -> "자동으로 찾은 자막이 없습니다. 내 자막 파일을 열 수 있어요."
                }
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) { if (at == token()) error = failure.message }
        }
    }

    /** A subtitle put on (iOS selectSubtitle): saved for the episode, remembered when chosen by hand, translated when it is
     *  not Korean and 자동 번역 is set up, and a Korean one looked for to offer when it was chosen automatically. */
    fun select(file: File, provider: String? = null, translate: Boolean = true, automatic: Boolean = false) {
        classification?.cancel(); translation.cancel(); dismissOffer(); offerJob?.cancel()
        val requested = ++request
        val item = item()
        val saved = EpisodeSubtitleStore.list(item).firstOrNull { it.file?.canonicalPath == file.canonicalPath }
        selectedProvider = provider ?: saved?.provider ?: "user"
        if (selectedProvider in listOf("kairan", "csora", "anissia", "linkkf")) koreanFound = true
        SubtitleFiles.repairEntities(file)
        sourceSubtitle = saved?.original ?: file; subtitle = file
        engine.subtitle(file, saved?.translated == true)
        EpisodeSubtitleStore.save(file, item, selectedProvider, saved?.translated ?: false, original = saved?.original)
        if (selectedProvider != "jimaku") selectedJimakuURL = null
        if (selectedProvider != "reanime") selectedTrackURL = null
        if (selectedProvider != "anissia") selectedMaker = null
        if (!automatic) {
            library.saveSubtitle(item.anime.id, item.episodeID, file, offset)
            if (saved?.translated == true) library.preferAI(true, item.anime.id)
            // Jimaku is picked per episode and never kept as the default source; the site's tracks are 자동.
            if (selectedProvider in listOf("kairan", "csora", "anissia")) library.update { it.copy(subtitleProvider = selectedProvider) }
            else if (selectedProvider == "reanime" && library.preferences.subtitleProvider != "manual") library.update { it.copy(subtitleProvider = "auto") }
        }
        val at = token()
        if (library.preferences.subtitleSyncCheck && !com.lilac.anime.android.audio.SubtitleAutoSync.isSynced(file)) checkSync(file)
        classification = scope.launch {
            val korean = isKorean(file)
            if (at != token() || requested != request || subtitle != file) return@launch
            if (!automatic && saved?.translated != true && korean) library.preferAI(false, item.anime.id)
            if (translate && selectedProvider != "user" && saved?.translated != true && library.preferences.translationPreferences(automatic = true) != null && !korean)
                translate(manual = false)
            if (automatic && saved?.translated != true && !korean && selectedProvider !in listOf("kairan", "csora", "anissia", "user")) offerKorean()
        }
    }

    /** A file downloaded or opened and put on (iOS importSubtitle). */
    fun import(url: String, headers: Map<String, String> = emptyMap(), translate: Boolean = true, provider: String? = null, asset: SubtitleAsset? = null) {
        val requested = ++request; val at = token(); val item = item()
        scope.launch {
            try {
                val japanese = provider == "jimaku" || asset?.source == "jimaku"
                if (japanese && !JimakuRules.validDownload(url)) throw IllegalStateException("Jimaku 파일 주소가 아닙니다.")
                val files = SubtitleFiles.prepare(url, headers, japanese, if (japanese) asset?.name else null, asset?.label)
                if (at != token() || requested != request) return@launch
                val first = preferred(files, asset, item)
                when {
                    first != null -> {
                        if (asset?.source == "jimaku") { preparer.jimaku.remember(asset.name, item); selectedJimakuURL = asset.url }
                        select(first, provider ?: if (url.startsWith("file:") || url.startsWith("/")) "user" else "reanime", translate)
                    }
                    files.isEmpty() -> error = "자막이 없습니다. ASS · SSA · SRT · VTT · SMI 파일을 선택하세요."
                    else -> error = "여러 자막을 가져왔습니다. 에피소드 번호가 들어간 파일을 직접 열어 주세요."
                }
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) { if (at == token() && requested == request) error = failure.message }
        }
    }
    private fun preferred(files: List<File>, asset: SubtitleAsset?, item: PlaybackItem): File? {
        if (asset != null && asset.source in listOf("kairan", "csora", "anissia")) {
            val exact = asset.tier >= 0 && asset.exact
            return preparer.communitySelect(files, item, asset.matchedEpisode ?: (asset.episode ?: item.number).toDouble(), if (asset.tier < 0 || exact) asset.strict else true, asset.bundle, exact, asset.fileSeason)
        }
        return files.firstOrNull { SubtitleEpisodeMatcher.matches(it.name, item.number, null) } ?: files.singleOrNull()
    }

    // MARK: the subtitle tab

    /** The tracks the stream brought; Linkkf's, 애니24's and 링크애니's own subtitle is not listed among them. */
    val siteTracks: List<RemoteSubtitle> get() = merged(if (item().anime.source in trackSites) ownTracks() else ownTracks().filter { it.origin != null }, otherTracks)
    val linkkfSubtitle: Boolean get() = item().anime.source == "linkkf" && ownTracks().isNotEmpty()
    val hasTrackList: Boolean get() = siteTracks.isNotEmpty()
    val trackSourceLabel: String get() = (listOfNotNull(item().anime.source.takeIf { it in trackSites && siteTracks.any { t -> t.origin == null } }) + siteTracks.mapNotNull { it.origin })
        .distinct().mapNotNull { mapOf("reanime" to "Re:Anime", "miruro" to "Miruro", "animenosub" to "Animenosub", "ohli24" to "애니24", "linkani" to "링크애니", "linkkf" to "Linkkf")[it] }
        .joinToString("·").ifEmpty { "Re:Anime" }
    private val currentSaved: SavedSubtitle? get() = subtitle?.let { file -> EpisodeSubtitleStore.list(item()).firstOrNull { it.file?.canonicalPath == file.canonicalPath } }
    /** The chip lit: the one pressed last until the subtitle on screen changes, then the source of the subtitle on screen. */
    val activeSource: String get() {
        pressedSource?.let { if (pressedFor == subtitle) return it }
        if (pressedSource == "ai" && translation.running) return "ai"
        if (subtitle == null) return ""
        if (currentSaved?.translated == true) return "ai"
        return if (selectedProvider in listOf("linkkf", "provider", "reanime")) (if (linkkfSubtitle) "linkkf" else if (hasTrackList) "reanime" else selectedProvider) else selectedProvider
    }
    val openSheet: String? get() = if (sheetPicked) pickedSheet else activeSource.takeIf { it in listOf("reanime", "jimaku", "anissia") }
    val label: String? get() {
        val file = subtitle ?: return null
        return currentSaved?.let { SubtitleLabels.label(it, selectedMaker) } ?: SubtitleLabels.label(selectedProvider, false, file.name, selectedMaker)
    }
    /** The 번역 row: while a subtitle that is not Korean is on, a translation can be made again, or one runs. */
    val translatable: Boolean get() = translation.running || currentSaved?.translated == true || subtitle?.let { koreanFiles[it.path] == false } == true
    val retranslatable: Boolean get() = currentSaved?.translated == true && sourceSubtitle != null && sourceSubtitle != subtitle

    private fun press(source: String, sheet: String?) {
        chipJob?.cancel(); chipRequest++
        pressedSource = source; pressedFor = subtitle; pickedSheet = sheet; sheetPicked = true; subtitleState = null
    }
    fun chooseSource(source: String) {
        val item = item()
        when (source) {
            "jimaku" -> { press("jimaku", "jimaku"); scope.launch { preparer.jimaku.load(item, retryFailure = true) }; return }
            "ai" -> {
                press("ai", null)
                EpisodeSubtitleStore.list(item).firstOrNull { it.translated }?.let { record -> record.file?.let { library.preferAI(true, item.anime.id); select(it, record.provider, translate = false); return } }
                translate(); return
            }
        }
        press(source, if (source in listOf("reanime", "anissia")) source else null)
        library.preferAI(false, item.anime.id)
        if (library.preferences.subtitleProvider != "manual" || source in listOf("kairan", "csora", "anissia"))
            library.update { it.copy(subtitleProvider = if (source in listOf("kairan", "csora", "anissia")) source else "auto") }
        if (source == "anissia") loadMakers()
        if (source == "reanime" || source == "linkkf") {
            val tracks = if (source == "linkkf") ownTracks() else siteTracks
            (tracks.firstOrNull(SubtitlePolicy::isKorean) ?: DesktopSubtitlePolicy.translationTrack(tracks) ?: tracks.firstOrNull())?.let { chooseTrack(it) }
                ?: run { subtitleState = "사이트 자막 트랙이 없습니다." }
            return
        }
        EpisodeSubtitleStore.list(item).firstOrNull { it.provider == source && !it.translated }?.file?.let { select(it, source); return }
        val name = SubtitleLabels.provider(source)
        subtitleState = "$name 자막을 찾는 중..."
        val at = token(); val requested = chipRequest
        chipJob = scope.launch {
            val found = runCatching { preparer.community(item, source) }.getOrNull()
            if (at != token() || requested != chipRequest) return@launch
            if (found != null) { subtitleState = null; select(found.file, found.provider) } else subtitleState = "$name 자막을 찾지 못했습니다."
        }
    }
    fun chooseTrack(track: RemoteSubtitle, translate: Boolean = false) {
        if (pressedSource == null || pressedFor != subtitle) { pressedSource = if (linkkfSubtitle) "linkkf" else "reanime"; pressedFor = subtitle }
        selectedTrackURL = track.url
        import(track.url, track.headers ?: emptyMap(), translate, "reanime")
    }
    fun loadMakers() {
        val at = token(); val item = item()
        searching = true; searchError = null; makers = emptyList()
        scope.launch {
            val result = preparer.makers(item)
            if (at != token()) return@launch
            makers = result.getOrDefault(emptyList()); searchError = result.exceptionOrNull()?.message; searching = false
        }
    }
    fun chooseMaker(maker: SubtitleMaker) {
        press("anissia", "anissia")
        subtitleState = "Anissia · " + maker.name + " 자막을 찾는 중..."
        val at = token(); val requested = chipRequest; val item = item()
        chipJob = scope.launch {
            val found = runCatching { preparer.maker(item, maker.website) }.getOrNull()
            if (at != token() || requested != chipRequest) return@launch
            if (found != null) { subtitleState = null; select(found.file, "anissia"); selectedMaker = maker.name }
            else subtitleState = maker.name + "의 " + item.subtitleEpisodeKey + "화 자막을 찾지 못했습니다."
        }
    }
    fun chooseJimaku(asset: SubtitleAsset) { import(asset.url, provider = "jimaku", asset = asset) }

    /** 한국어 자막 다시 찾기: the automatic order again, past the saved subtitles and earlier results. */
    fun findKoreanAgain() {
        chipJob?.cancel(); chipRequest++; pressedSource = null; sheetPicked = false
        val item = item()
        library.preferAI(false, item.anime.id)
        preparer.resetSearches(); selectedTrackURL = null
        subtitleState = "온라인 자막을 찾는 중..."
        val preferences = library.preferences.let { if (it.subtitleProvider == "manual") it.copy(subtitleProvider = "auto") else it }
        val at = token(); val requested = chipRequest; val stream = active()
        chipJob = scope.launch {
            val found = runCatching { preparer.prepare(item, emptyList(), preferences, skipSaved = true, stream = stream) }.getOrNull()
            if (at != token() || requested != chipRequest) return@launch
            if (found != null) { subtitleState = "자막 적용 완료"; select(found.file, found.provider) }
            else subtitleState = "자막을 찾지 못했습니다. 자막 파일을 직접 열 수 있어요."
        }
    }

    // MARK: translation

    /** The configured translation API (the chosen one first), for the 번역 button. */
    fun cloudProvider(): String? {
        val clouds = listOf("gemini", "openai", "deepl", "qwen", "claude"); val wanted = library.preferences.translationProvider
        return (if (wanted in clouds) listOf(wanted) + clouds.filter { it != wanted } else clouds).firstOrNull { SecureKeys.load(it).isNotEmpty() }
    }
    fun translate(fresh: Boolean = false, provider: String? = null, manual: Boolean = true) {
        if (manual) { request++; automaticJob?.cancel(); dismissOffer() }
        var preferences = library.preferences
        if (provider != null) preferences = preferences.copy(translationProvider = provider, translationModel = preferences.translationModels[provider] ?: "")
        val localOnly = manual && preferences.translationProvider == "local"
        val configured = preferences.translationPreferences(localOnly = localOnly) ?: run { error = "번역 API 키 또는 로컬 GGUF 모델을 먼저 설정하세요."; return }
        val source = sourceSubtitle
        if (source != null) { runTranslation(source, configured, fresh, manual); return }
        val at = token(); val requested = request; val item = item()
        automaticJob?.cancel()
        automaticJob = scope.launch {
            try {
                val prepared = preparer.translationSource(item, siteTracks.ifEmpty { ownTracks() }, configured, requireAutomatic = false)
                if (at != token() || requested != request) return@launch
                if (prepared == null) { error = "번역할 원문 자막을 찾지 못했습니다. 자막 파일을 직접 열거나 사이트 자막 트랙을 선택하세요."; return@launch }
                sourceSubtitle = prepared.file
                EpisodeSubtitleStore.save(prepared.file, item, prepared.provider, false)
                runTranslation(prepared.file, configured, fresh, manual)
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) { if (at == token()) error = failure.message }
        }
    }
    private fun runTranslation(original: File, preferences: com.lilac.anime.android.data.AppPreferences, fresh: Boolean, manual: Boolean) {
        val at = token(); val requested = request; val episode = item()
        // Pressed with a Korean subtitle there, the series goes on in machine translation (desktop runTranslation).
        if (manual && (koreanFound || DesktopSubtitlePolicy.siteKorean(episode.anime.source, active()?.subtitles ?: emptyList(), active()))) library.preferAI(true, episode.anime.id)
        translation.translate(original, preferences, { engine.position }, { engine.seekRevision }, episode.anime, fresh, manual && preferences.translationProvider == "local") { output ->
            if (at != token() || requested != request) return@translate
            EpisodeSubtitleStore.save(output, episode, preferences.translationProvider, true, original = original)
            if (subtitle == output) engine.reloadSubtitle() else { subtitle = output; engine.subtitle(output, true) }
        }
    }

    /** A machine translation or another language on screen: a Korean subtitle found later is offered for 20 seconds. */
    private fun offerKorean() {
        val at = token(); val requested = request; val item = item()
        offerJob = scope.launch {
            val found = runCatching { preparer.community(item, library.preferences) }.getOrNull() ?: return@launch
            if (at != token() || requested != request) return@launch
            offered = found; koreanOffer = "한국어 자막(${SubtitleLabels.provider(found.provider)})을 찾았어요. 바꿀까요?"
            delay(20_000)
            if (at == token() && requested == request) dismissOffer()
        }
    }
    fun dismissOffer() { koreanOffer = null; offered = null }
    fun acceptOffer() {
        val found = offered ?: return
        library.preferAI(false, item().anime.id)
        select(found.file, found.provider, translate = false)
    }

    // MARK: timing

    fun changeOffset(seconds: Double) {
        offset = seconds; engine.subtitleOffset = seconds
        val item = item()
        library.saveSubtitle(item.anime.id, item.episodeID, subtitle, seconds)
    }
    fun shift(delta: Double) = changeOffset(Math.round((offset + delta) * 1000) / 1000.0)

    // MARK: Whisper (iOS startWhisper)

    fun startWhisper(manual: Boolean = true) {
        if (whisper.running) { if (manual) { whisper.cancel(); whisperLive.cancel(); whisperStatus = "음성 인식 중단" }; return }
        whisperLive.cancel()
        whisperStatus = "Whisper 음성 준비 중"
        if (manual) { request++; automaticJob?.cancel(); translation.cancel(); selectedProvider = "whisper"; pressedSource = "whisper"; pressedFor = subtitle }
        val at = token(); val item = item()
        scope.launch {
            repeat(100) { if (engine.duration > 0) return@repeat; delay(100) }
            if (at != token()) return@launch
            val source = video() ?: run { whisperStatus = "영상 연결 후 음성 인식을 시작해 주세요."; return@launch }
            whisper.start(source.copy(duration = engine.duration), item.anime.id + "#" + item.episodeID, library.preferences.whisperModel, { engine.position },
                receive = { file, lines, complete ->
                    if (at != token()) return@start
                    whisperStatus = if (complete) (if (lines.isEmpty()) "인식된 대사가 없습니다." else "Whisper 자막 저장 완료") else "Whisper ${lines.size}줄 인식 · 눌러서 중단"
                    if (lines.isEmpty()) return@start
                    EpisodeSubtitleStore.save(file, item, "whisper", false)
                    if (subtitle == null || selectedProvider == "whisper") {
                        selectedProvider = "whisper"; sourceSubtitle = file
                        if (subtitle == file) engine.reloadSubtitle() else { subtitle = file; engine.subtitle(file, false) }
                    }
                    library.preferences.translationPreferences(automatic = true)?.let { preferences ->
                        whisperLive.update(lines, complete, file, preferences, item.anime) { output ->
                            if (at != token()) return@update
                            EpisodeSubtitleStore.save(output, item, "whisper", true, original = file)
                            if (selectedProvider == "whisper") { if (subtitle == output) engine.reloadSubtitle() else { subtitle = output; engine.subtitle(output, true) } }
                        }
                    }
                }, failed = { message -> whisperStatus = message; if (manual) notify(message) })
        }
    }

    // MARK: 자막 싱크 맞추기 (iOS runSubtitleSync / checkSubtitleSync)

    val syncAvailable: Boolean get() = subtitle != null && video() != null
    val syncButtonTitle: String get() = if (syncRunning) (syncStatus ?: "싱크 맞추는 중") + " · 눌러서 취소" else "음성에 맞춰 싱크 맞추기"
    fun dismissSync() { syncNotice = null; syncUndo = null }
    fun undoSync() { syncUndo?.invoke(); dismissSync() }
    /** As a subtitle goes on: checked against the site's own dialogue track (no audio read) when there is one. */
    private fun checkSync(file: File) {
        if (!syncChecked.add(file.path)) return
        if (com.lilac.anime.android.audio.SubtitleAutoSync.referenceTracks(ownTracks()).isEmpty()) return
        runSync(auto = true)
    }
    fun runSync(auto: Boolean = false) {
        if (syncRunning) { syncJob?.cancel(); syncRunning = false; syncStatus = null; return }
        val target = subtitle ?: return
        val at = token(); val item = item()
        val provider = selectedProvider
        val original = sourceSubtitle?.takeIf { it != target }
        syncRunning = true; syncStatus = "싱크 맞추는 중"
        syncJob = scope.launch {
            try {
                val references = com.lilac.anime.android.audio.SubtitleAutoSync.referenceTracks(ownTracks()).mapNotNull { track ->
                    runCatching { SubtitleFiles.prepare(track.url, track.headers ?: emptyMap()).firstOrNull() }.getOrNull()
                }
                val source = video()?.let { it.copy(duration = engine.duration) }
                if (auto && references.isEmpty()) return@launch
                val outcome = com.lilac.anime.android.audio.SubtitleAutoSync.sync(appContext, target, original, references, if (auto) null else source, chapters()) { text, value ->
                    syncStatus = text + (value?.let { " " + Math.round(it * 100) + "%" } ?: "")
                }
                if (at != token() || subtitle != target) return@launch
                when (outcome) {
                    is com.lilac.anime.android.audio.SubtitleAutoSync.Outcome.Unchanged -> if (!auto) notify("자막 싱크가 이미 맞아요.")
                    is com.lilac.anime.android.audio.SubtitleAutoSync.Outcome.Uncertain -> if (!auto) notify(outcome.message)
                    is com.lilac.anime.android.audio.SubtitleAutoSync.Outcome.Synced -> {
                        val saved = EpisodeSubtitleStore.list(item).firstOrNull { it.file?.canonicalPath == target.canonicalPath }
                        EpisodeSubtitleStore.save(outcome.file, item, provider.ifEmpty { "user" }, saved?.translated ?: false, original = saved?.original ?: original)
                        subtitle = outcome.file; engine.subtitle(outcome.file, saved?.translated == true)
                        syncNotice = "자막 싱크를 맞췄어요 (" + com.lilac.anime.android.audio.SubtitleAutoSync.moved(outcome.fix) + ")"
                        syncUndo = { if (subtitle == outcome.file) { subtitle = target; engine.subtitle(target, saved?.translated == true) } }
                        scope.launch { delay(12_000); if (syncNotice != null && subtitle == outcome.file) dismissSync() }
                    }
                }
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) { if (!auto && at == token()) notify(failure.message ?: "싱크를 맞추지 못했습니다.") }
            finally { if (at == token()) { syncRunning = false; syncStatus = null } }
        }
    }

    fun shutdown() { reset(); translation.shutdown(); preparer.close(); scope.cancel(); crossService.close() }
}
