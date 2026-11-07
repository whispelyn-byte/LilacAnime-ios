package com.lilac.anime.android.player

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    var error by mutableStateOf<String?>(null)
    var cacheAhead by mutableDoubleStateOf(0.0); private set
    var fileLoaded by mutableStateOf(false); private set

    var onEnd: (() -> Unit)? = null
    var onProgress: ((Double, Double) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mpv: MPVLib? = null
    private var pending: ResolvedStream? = null
    private var resumePosition = 0.0
    private var startPaused = false
    private var completionNotified = false
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
        val player = runCatching { MPVLib.create(context.applicationContext) }.getOrNull() ?: run { error = "mpv 초기화 실패"; return }
        val fonts = File(context.filesDir, "fonts").apply { mkdirs() }
        for ((key, value) in listOf(
            "config" to "no", "terminal" to "no", "msg-level" to "all=error", "vo" to "gpu", "gpu-context" to "android",
            "opengl-es" to "yes", "hwdec" to "mediacodec-copy", "ao" to "audiotrack,opensles", "keep-open" to "yes", "idle" to "yes",
            "force-window" to "no", "cache" to "yes", "demuxer-max-bytes" to "64MiB", "demuxer-max-back-bytes" to "16MiB",
            "input-default-bindings" to "no", "sub-auto" to "no", "save-position-on-quit" to "no", "tls-verify" to "no",
            "sub-fonts-dir" to fonts.absolutePath, "user-agent" to PLAYER_AGENT,
        )) player.setOptionString(key, value)
        player.init()
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
        pending?.let { load(it, resumePosition, startPaused) }
    }

    fun attachSurface(surface: Surface) {
        val player = mpv ?: return
        player.attachSurface(surface)
        player.setOptionString("force-window", "yes")
        set("vo", "gpu")
    }
    fun surfaceSize(width: Int, height: Int) { set("android-surface-size", "${width}x$height") }
    fun detachSurface() {
        val player = mpv ?: return
        set("vo", "null"); player.setOptionString("force-window", "no")
        player.detachSurface()
    }

    fun configure(settings: AppPreferences) {
        preferences = settings
        if (mpv == null) return
        applyPresentation()
        if (!speedBoosted) changeSpeed(settings.speed)
        configure("sub-scale", (settings.subtitleSize / 100).toString())
        configure("sub-font-size", "36"); configure("sub-margin-x", "36"); configure("sub-shadow-offset", "0")
        configure("sub-scale-with-window", "no"); configure("sub-border-size", "2"); configure("sub-bold", "yes")
    }
    private fun configure(name: String, value: String) { if (configured[name] != value) { configured[name] = value; set(name, value) } }
    val presentation get() = PlayerPresentation(PlayerAspect.of(preferences.playerAspect), PlayerFit.of(preferences.playerFit))
    private fun applyPresentation() { presentation.mpvOptions.toSortedMap().forEach { (name, value) -> configure(name, value) } }
    fun applyPreferences(settings: AppPreferences) { configure(settings) }

    fun load(stream: ResolvedStream, resume: Double = 0.0, paused: Boolean = false) {
        finishHold()
        loadTimeout?.cancel()
        pending = stream; resumePosition = resume; startPaused = paused; completionNotified = false
        val player = mpv ?: return
        error = null; fileLoaded = false
        val agent = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: PLAYER_AGENT
        set("user-agent", agent)
        set("http-header-fields", stream.headers.filterKeys { !it.equals("User-Agent", true) && !it.equals("Referer", true) }
            .map { it.key + ": " + it.value.replace(",", "\\,") }.joinToString(","))
        set("referrer", stream.referer)
        // A local file is this app's own HLS: FFmpeg refuses segments not named as media unless told otherwise.
        set("demuxer-lavf-o", if (isFileURL(stream.url)) "allowed_extensions=ALL,allowed_segment_extensions=ALL,extension_picky=0" else "")
        set("start", if (resume > 1) resume.toString() else "none")
        set("pause", if (paused) "yes" else "no"); this.paused = paused
        player.command(arrayOf("loadfile", if (stream.url.startsWith("file://")) stream.url.removePrefix("file://") else stream.url, "replace"))
        loadTimeout = scope.launch {
            delay(30_000)
            if (!fileLoaded) error = "영상 재생을 시작하지 못했습니다. 다른 영상 서버를 선택하거나 다시 시도하세요."
        }
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
        if (paused || speedBoosted) return
        holdOriginal = speed; speedBoosted = true; changeSpeed(2.0)
    }
    fun finishHold() {
        holdTimer?.cancel(); holdTimer = null
        val original = holdOriginal ?: return
        holdOriginal = null; speedBoosted = false
        changeSpeed(if (speed == 2.0) original else speed)
    }

    /** 스크린샷: the frame on screen at the video's own size, without subtitles or controls, as a PNG file. */
    suspend fun screenshot(file: File): Boolean = withContext(Dispatchers.IO) {
        val player = mpv ?: return@withContext false
        if (!fileLoaded) return@withContext false
        runCatching { player.command(arrayOf("screenshot-to-file", file.absolutePath, "video")) }
        repeat(30) { if (file.isFile && file.length() > 0) return@withContext true; delay(100) }
        false
    }

    val canSaveProgress get() = mpv != null && fileLoaded

    private fun set(name: String, value: String) { runCatching { mpv?.setPropertyString(name, value) } }
    private fun command(vararg args: String) { runCatching { mpv?.command(arrayOf(*args)) } }

    private fun onDouble(property: String, value: Double) {
        when (property) {
            "time-pos" -> if (value.isFinite()) position = value
            "duration" -> if (value.isFinite()) duration = value
            "volume" -> volume = value
            "speed" -> speed = value
            "demuxer-cache-duration" -> cacheAhead = value
        }
    }
    private fun onFlag(property: String, value: Boolean) {
        when (property) {
            "pause" -> paused = value
            "paused-for-cache" -> buffering = value
            "mute" -> muted = value
            "eof-reached" -> if (value && fileLoaded) reachedEnd()
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
        when (id) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> { buffering = true }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                fileLoaded = true; buffering = false; loadTimeout?.cancel(); error = null
                set("start", "none")
                if (!startPaused) play()
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> if (fileLoaded) reachedEnd()
        }
    }
    /** mpv's eof-reached and its end-file event are one ending (iOS PlaybackCompletion). */
    private fun reachedEnd() { if (completionNotified) return; completionNotified = true; onEnd?.invoke() }

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
