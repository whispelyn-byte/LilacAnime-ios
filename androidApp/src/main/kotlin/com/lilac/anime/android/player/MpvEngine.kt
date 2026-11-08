package com.lilac.anime.android.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.android.data.AppPreferences
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import kotlin.coroutines.resume

data class MediaTrack(val id: Int, val type: String, val title: String, val selected: Boolean)

/** iOS MPVEngine on libmpv for Android: one player, its surface, the properties it reports, and the options set. */
class MpvEngine(private val context: Context) {
    var position by mutableDoubleStateOf(0.0)
    var duration by mutableDoubleStateOf(0.0)
    var volume by mutableDoubleStateOf(100.0)
    var muted by mutableStateOf(false)
    var subtitlesVisible by mutableStateOf(true)
    var speed by mutableDoubleStateOf(1.0)
    var speedBoosted by mutableStateOf(false)
    var paused by mutableStateOf(true)
    var buffering by mutableStateOf(false)
    var tracks by mutableStateOf(emptyList<MediaTrack>())
    var error by mutableStateOf<String?>(null); private set
    var cacheAhead by mutableDoubleStateOf(0.0); private set
    var fileLoaded by mutableStateOf(false); private set

    var onEnd: (() -> Unit)? = null
    var onProgress: ((Double, Double) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mpv: MPVLib? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var initializationFailed = false
    private var surfaceAttached = false
    private var pending: ResolvedStream? = null
    private var startAt: String? = null
    private var startPaused = false
    private var completionNotified = false
    private var waitingForStart = false
    private var openingFile = false
    private var playbackStarted = false
    private var loadTimeout: Job? = null
    private var poll: Job? = null
    private val configured = mutableMapOf<String, String>()
    private var preferences = AppPreferences()
    private var holdOriginal: Double? = null
    private var holdTimer: Job? = null
    var seekRevision = 0L; private set
    /** Where the viewer moved to themselves, and when (desktop seekPlayer): the OP/ED there is watched, not skipped. */
    var viewerSeek: Pair<Double, Long>? = null; private set
    private val defaults = context.getSharedPreferences("lilac", Context.MODE_PRIVATE)

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: Long) {}
        override fun eventProperty(property: String, value: Double) { main.post { onDouble(property, value) } }
        override fun eventProperty(property: String, value: Boolean) { main.post { onFlag(property, value) } }
        override fun eventProperty(property: String, value: String) { main.post { if (property == "track-list") onTracks(value) } }
        override fun event(eventId: Int) { main.post { onEvent(eventId) } }
    }

    /** Made once the surface exists (iOS attach): the options, the observed properties, then whatever was asked before. */
    fun create() {
        if (mpv != null) return
        val player = runCatching { MPVLib.create(context.applicationContext) }
            .onFailure { Log.e("LilacMpv", "Could not create libmpv", it) }
            .getOrNull() ?: run {
            initializationFailed = true; error = "mpv 초기화 실패"; return
        }
        val fonts = com.lilac.anime.android.subtitle.SubtitleFiles.fontDirectory.apply { mkdirs() }
        // libass finds fonts only in this folder: the system's CJK font is linked into it so Korean is drawn (iOS uses
        // Apple SD Gothic Neo, its system font).
        val korean = listOf("/system/fonts/NotoSansCJK-Regular.ttc", "/system/fonts/NotoSansCJKkr-Regular.otf", "/system/fonts/NanumGothic.ttf").map(::File).firstOrNull { it.isFile }
        if (korean != null) runCatching { val link = File(fonts, "system-" + korean.name); if (!link.exists()) android.system.Os.symlink(korean.absolutePath, link.absolutePath) }
        for ((key, value) in listOf(
            "config" to "no", "terminal" to "no", "msg-level" to "all=error", "vo" to "gpu", "gpu-context" to "android",
            "opengl-es" to "yes", "hwdec" to "mediacodec-copy", "ao" to "audiotrack,opensles", "keep-open" to "yes", "idle" to "yes",
            "force-window" to "no", "cache" to "yes", "demuxer-max-bytes" to "64MiB", "demuxer-max-back-bytes" to "16MiB",
            "input-default-bindings" to "no", "sub-auto" to "no", "save-position-on-quit" to "no", "tls-verify" to "no",
            "sub-fonts-dir" to fonts.absolutePath, "user-agent" to PLAYER_AGENT,
        )) player.setOptionString(key, value)
        try {
            player.init()
        } catch (failure: Exception) {
            Log.e("LilacMpv", "Could not initialize libmpv", failure)
            runCatching { player.destroy() }
            initializationFailed = true; error = "mpv 초기화 실패"; return
        }
        initializationFailed = false
        player.addObserver(observer)
        for ((name, format) in listOf("time-pos" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE, "duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG, "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "volume" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE, "mute" to MPVLib.MpvFormat.MPV_FORMAT_FLAG, "speed" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "track-list" to MPVLib.MpvFormat.MPV_FORMAT_STRING, "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "demuxer-cache-duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)) player.observeProperty(name, format)
        mpv = player
        configured.clear()
        // The player's volume and mute stay as they were left (app.js playerVolume / playerMuted).
        volume = defaults.getFloat("player.volume", 100f).toDouble().coerceIn(0.0, 100.0); set("volume", volume.toString())
        muted = defaults.getBoolean("player.muted", false); set("mute", if (muted) "yes" else "no")
        configure(preferences)
        poll = scope.launch { while (isActive) { delay(250); onProgress?.invoke(position, duration) } }
    }

    fun attachSurface(surface: Surface) {
        val player = mpv ?: return
        player.attachSurface(surface)
        this.surface = surface
        surfaceAttached = true
        set("force-window", "yes")
        set("vo", "gpu")
        openPending()
    }
    fun surfaceSize(width: Int, height: Int) { surfaceWidth = width; surfaceHeight = height; set("android-surface-size", "${width}x$height") }
    fun detachSurface() {
        val player = mpv ?: return
        surfaceAttached = false
        surface = null
        set("vo", "null"); set("force-window", "no")
        player.detachSurface()
    }

    fun configure(settings: AppPreferences) {
        preferences = settings
        if (mpv == null) return
        applyPresentation()
        if (!speedBoosted) changeSpeed(settings.speed)
        configure("sub-scale", (settings.subtitleSize / 100).toString())
        configure("sub-line-spacing", lineSpacing(settings.subtitleLineHeight).toString())
        configure("sub-delay", subtitleOffset.toString())
        configure("sub-font", settings.subtitleFont.ifEmpty { DEFAULT_FONT })
        configure("sub-color", settings.subtitleColor); configure("sub-border-color", settings.outlineColor)
        configure("sub-border-size", settings.outlineWidth.toString())
        configure("sub-bold", if (settings.subtitleBold) "yes" else "no")
        configure("sub-pos", (100 - settings.subtitlePadding).toString())
        configure("sub-font-size", "36"); configure("sub-margin-x", "36"); configure("sub-shadow-offset", "0")
        configure("sub-scale-with-window", "no")
        configure("sub-ass-override", assOverride)
        configure("embeddedfonts", if (translatedSubtitle) "no" else "yes")
        configureAnime4K()
    }
    var anime4kError by mutableStateOf<String?>(null); private set
    private var configuredAnime4K: Pair<Anime4KMode, VideoCleanupSettings?>? = null
    /** Anime4K 영상 보정: the chosen mode's shaders; when they cannot be applied the original picture comes back. */
    private fun configureAnime4K() {
        val mode = Anime4KMode.of(preferences.anime4k)
        if (configuredAnime4K == mode to preferences.videoCleanup) return
        configuredAnime4K = mode to preferences.videoCleanup; anime4kError = null
        // 영상 정리 first, then Anime4K (iOS: VideoCleanup.paths + mode.paths).
        runCatching { set("glsl-shaders", (VideoCleanup.paths(context, preferences.videoCleanup) + mode.paths(context)).joinToString(":")) }.onFailure {
            anime4kError = "영상 보정을 적용하지 못해 원본 영상으로 돌아왔습니다. 다른 모드를 선택해 다시 시도할 수 있어요."
            set("glsl-shaders", "")
        }
    }
    /** The subtitle's timing for this episode (the player's 자막 타이밍, else 설정's). */
    var subtitleOffset = 0.0
        set(value) { field = value; configure("sub-delay", value.toString()) }
    private var subtitleFile: String? = null
    var translatedSubtitle = false; private set
    /** iOS SubtitleAppearance.assOverride: a translation drops its styling, an ASS keeps its effects when asked. */
    private val assOverride: String get() = when {
        translatedSubtitle -> "strip"
        subtitleFile?.substringAfterLast('.')?.lowercase() in setOf("ass", "ssa") && preferences.assEffects -> "no"
        preferences.vttStyle -> "yes"
        else -> "strip"
    }
    /** mpv's line spacing adds scaled pixels to the 36px font's normal line height (iOS SubtitleLineSpacing). */
    private fun lineSpacing(percent: Double?): Double = if (percent == null || !percent.isFinite() || percent <= 0) 0.0 else 36 * (percent.coerceIn(80.0, 300.0) / 100 - 1)

    /** A subtitle file put on (iOS MPVEngine.subtitle): added and selected once the video is open, reloaded when the same
     *  file was written again (a translation as it goes). */
    fun subtitle(file: File, translated: Boolean = false) {
        val reload = subtitleFile == file.absolutePath
        subtitleFile = file.absolutePath; translatedSubtitle = translated
        configure("sub-ass-override", assOverride)
        configure("embeddedfonts", if (translated) "no" else "yes")
        if (fileLoaded) { command("sub-add", file.absolutePath, "select"); if (reload) command("sub-reload") }
    }
    fun reloadSubtitle() { command("sub-reload") }
    fun clearSubtitle() { subtitleFile = null; set("sid", "no") }
    private fun configure(name: String, value: String) { if (configured[name] != value) { configured[name] = value; set(name, value) } }
    val presentation get() = PlayerPresentation(PlayerAspect.of(preferences.playerAspect), PlayerFit.of(preferences.playerFit))
    private fun applyPresentation() { presentation.mpvOptions.toSortedMap().forEach { (name, value) -> configure(name, value) } }
    fun applyPreferences(settings: AppPreferences) { configure(settings) }

    fun load(stream: ResolvedStream, resume: Double = 0.0, paused: Boolean = false, resumeFraction: Double? = null) {
        finishHold()
        loadTimeout?.cancel()
        pending = stream; startPaused = paused; completionNotified = false
        startAt = when {
            resume.isFinite() && resume > 0 -> resume.toString()
            resumeFraction != null && resumeFraction > 0 && resumeFraction < 1 -> "${resumeFraction * 100}%"
            else -> null
        }
        if (!initializationFailed) error = null
        fileLoaded = false; playbackStarted = false
        position = 0.0; duration = 0.0; cacheAhead = 0.0; tracks = emptyList()
        waitingForStart = true; openingFile = false; buffering = true
        openPending()
    }

    private fun openPending() {
        val player = mpv ?: return
        if (!surfaceAttached) return
        val stream = pending ?: return
        pending = null
        error = null
        val agent = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: PLAYER_AGENT
        set("user-agent", agent)
        set("http-header-fields", stream.headers.filterKeys { !it.equals("User-Agent", true) && !it.equals("Referer", true) }
            .map { it.key + ": " + it.value.replace(",", "\\,") }.joinToString(","))
        set("referrer", stream.referer.ifEmpty { stream.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty() })
        // A local file is this app's own HLS: FFmpeg refuses segments not named as media unless told otherwise.
        set("demuxer-lavf-o", if (isFileURL(stream.url)) "allowed_extensions=ALL,allowed_segment_extensions=ALL,extension_picky=0" else "")
        set("pause", if (startPaused) "yes" else "no"); paused = startPaused
        val address = mpvAddress(stream.url)
        val start = startAt
        if (start == null) player.command(arrayOf("loadfile", address, "replace"))
        else player.command(arrayOf("loadfile", address, "replace", "-1", "start=$start"))
        loadTimeout = scope.launch {
            delay(30_000)
            if (!fileLoaded) reportLoadFailure()
        }
    }

    private fun reportLoadFailure() {
        buffering = false
        error = "영상 재생을 시작하지 못했습니다. 다른 영상 서버를 선택하거나 다시 시도하세요."
    }

    fun stop() {
        finishHold()
        loadTimeout?.cancel()
        pending = null; waitingForStart = false; openingFile = false
        if (!initializationFailed) error = null
        fileLoaded = false; playbackStarted = false; buffering = false; paused = true
        position = 0.0; duration = 0.0; cacheAhead = 0.0; tracks = emptyList()
        command("stop")
    }

    fun changeVolume(value: Double) { volume = value.coerceIn(0.0, 100.0); set("volume", volume.toString()); defaults.edit().putFloat("player.volume", volume.toFloat()).apply() }
    fun toggleMute() { muted = !muted; set("mute", if (muted) "yes" else "no"); defaults.edit().putBoolean("player.muted", muted).apply() }
    fun changeSpeed(value: Double) { speed = value; set("speed", value.toString()) }
    fun play() { paused = false; set("pause", "no") }
    fun pause() { finishHold(); paused = true; set("pause", "yes") }
    fun toggle() { if (paused) play() else pause() }
    fun seek(seconds: Double) { seekRevision++; command("seek", maxOf(0.0, seconds).toString(), "absolute+exact") }
    fun seekByViewer(seconds: Double) { if (seconds.isFinite()) { viewerSeek = maxOf(0.0, seconds) to System.currentTimeMillis(); seek(seconds) } }
    fun clearViewerSeek() { viewerSeek = null }
    fun skip(delta: Double) { seekByViewer(position + delta) }
    fun selectTrack(track: MediaTrack) { set(if (track.type == "audio") "aid" else "sid", track.id.toString()) }
    fun toggleSubtitleVisibility() { subtitlesVisible = !subtitlesVisible; set("sub-visibility", if (subtitlesVisible) "yes" else "no") }
    fun addSubtitle(url: String, title: String) { if (fileLoaded) command("sub-add", url, "select", title) }

    /** Touch-and-hold 2배속 (desktop hold): the speed it had comes back when let go. */
    fun boostHold() {
        if (paused || !fileLoaded || speedBoosted) return
        holdOriginal = speed; speedBoosted = true; changeSpeed(2.0)
    }
    fun beginSpaceHold() {
        if (holdTimer != null || speedBoosted) return
        holdTimer = scope.launch { delay(250); boostHold() }
    }

    fun finishHold(toggleOnTap: Boolean = false) {
        val tapped = holdTimer != null && !speedBoosted
        holdTimer?.cancel(); holdTimer = null
        holdOriginal?.let { original ->
            holdOriginal = null; speedBoosted = false
            if (speed == 2.0) changeSpeed(original)
        }
        if (tapped && toggleOnTap) toggle()
    }

    /** 스크린샷: the video frame on screen, without subtitles or controls, as a PNG file. libmpv 1.0.0's ffmpeg has no
     *  image encoders, so mpv's screenshot-to-file cannot write one ("Could not open libavcodec encoder"): the frame is
     *  copied from the surface mpv draws into (PixelCopy), with subtitles hidden for that moment, and Android encodes it. */
    suspend fun screenshot(file: File): Boolean {
        if (mpv == null || !fileLoaded) return false
        val target = surface?.takeIf { it.isValid } ?: return false
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return false
        val hideSubtitles = subtitlesVisible
        val bitmap = try {
            if (hideSubtitles) { set("sub-visibility", "no"); delay(150) }
            Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888).takeIf { copySurface(target, it) }
        } finally {
            if (hideSubtitles) set("sub-visibility", if (subtitlesVisible) "yes" else "no")
        } ?: return false
        return withContext(Dispatchers.IO) {
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } && file.length() > 0 }
            catch (_: Exception) { false } finally { bitmap.recycle() }
        }
    }
    private suspend fun copySurface(source: Surface, bitmap: Bitmap): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(source, bitmap, { result -> if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { if (continuation.isActive) continuation.resume(false) }
    }

    val canSaveProgress get() = fileLoaded && playbackStarted

    private fun set(name: String, value: String) { runCatching { mpv?.setPropertyString(name, value) } }
    private fun command(vararg args: String) { runCatching { mpv?.command(arrayOf(*args)) } }

    private fun onDouble(property: String, value: Double) {
        when (property) {
            "time-pos" -> if (fileLoaded && value.isFinite()) position = value
            "duration" -> if (fileLoaded && value.isFinite()) duration = value
            "volume" -> volume = value
            "speed" -> speed = value
            "demuxer-cache-duration" -> cacheAhead = value
        }
    }
    private fun onFlag(property: String, value: Boolean) {
        when (property) {
            // keep-open pauses on the last frame (keep-open-pause): that pause is the ending too.
            "pause" -> { paused = value; if (value && fileLoaded && atLastFrame()) reachedEnd() }
            "paused-for-cache" -> if (fileLoaded) buffering = value
            "mute" -> muted = value
            // Seeking back from the end plays to the end again, and it is said again (iOS PlaybackCompletion.resume).
            "eof-reached" -> if (fileLoaded) { if (value) reachedEnd() else completionNotified = false }
        }
    }
    private fun onTracks(json: String) {
        tracks = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optString("type") in setOf("audio", "sub") }.map { track ->
                val title = listOf(track.optString("title"), track.optString("lang")).filter { it.isNotEmpty() && it != "null" }.joinToString(" · ")
                MediaTrack(track.optInt("id"), track.optString("type"), title.ifEmpty { "트랙 " + track.optInt("id") }, track.optBoolean("selected"))
            }
        }.getOrDefault(tracks)
    }
    private fun onEvent(id: Int) {
        val player = mpv ?: return
        when (id) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> if (waitingForStart && pending == null) {
                waitingForStart = false; openingFile = true
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> if (openingFile) {
                openingFile = false
                fileLoaded = true; buffering = false; loadTimeout?.cancel(); error = null
                position = player.getPropertyDouble("time-pos")?.takeIf { it.isFinite() } ?: 0.0
                duration = player.getPropertyDouble("duration")?.takeIf { it.isFinite() } ?: 0.0
                subtitleFile?.let { command("sub-add", it, "select") }
                if (!startPaused) play()
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> if (fileLoaded) {
                player.getPropertyDouble("time-pos")?.takeIf { it.isFinite() }?.let {
                    position = it
                    playbackStarted = true
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> when {
                openingFile -> {
                    openingFile = false; paused = true
                    loadTimeout?.cancel()
                    reportLoadFailure()
                }
                // Not asked for (stop and load clear fileLoaded first): mpv let the file go itself. At its end that is
                // the ending (iOS MPV_END_FILE_REASON_EOF); the file is gone, so the last position read says so.
                fileLoaded -> {
                    val ended = player.getPropertyBoolean("eof-reached") == true || atLastFrame()
                    fileLoaded = false; buffering = false
                    if (ended) reachedEnd()
                }
            }
        }
    }
    /** mpv's eof-reached and its end-file event are one ending (iOS PlaybackCompletion). */
    private fun reachedEnd() { if (completionNotified) return; completionNotified = true; onEnd?.invoke() }
    private fun atLastFrame() = duration > 0 && position >= duration - 1.0

    companion object {
        /** The system's Korean font family (linked into the subtitle font folder). */
        const val DEFAULT_FONT = "Noto Sans CJK KR"
    }

    fun shutdown() {
        poll?.cancel(); loadTimeout?.cancel(); scope.cancel()
        val player = mpv ?: return
        mpv = null
        runCatching { player.removeObserver(observer) }
        runCatching { player.command(arrayOf("stop")) }
        // Destroying waits for the core: off the main thread.
        Thread { runCatching { player.destroy() } }.start()
    }
}
