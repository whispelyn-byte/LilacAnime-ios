package com.lilac.anime.android.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** 재생 막대 미리보기 (iOS SeekPreview with NativeAudio's frame reader): the picture where the finger rests on the seek bar.
 *  A second, paused mpv without sound writes one small JPEG for each place asked (vo=image); the last asked wins. */
class SeekPreview(private val context: Context) {
    var image by mutableStateOf<Bitmap?>(null); private set
    var time by mutableStateOf(0.0); private set
    private var mpv: MPVLib? = null
    private var source: String? = null
    private val folder = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "seek-preview-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val decoder = Mutex()
    private var job: Job? = null
    private var closed = false
    private val cache = LinkedHashMap<Pair<String, Int>, Bitmap>()

    private fun open(stream: ResolvedStream): MPVLib? {
        if (source == stream.url) return mpv
        releasePlayer()
        val player = runCatching { MPVLib.create(context.applicationContext) }.getOrNull() ?: return null
        val agent = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: PLAYER_AGENT
        for ((key, value) in listOf("config" to "no", "terminal" to "no", "msg-level" to "all=no", "vo" to "image", "vo-image-format" to "jpg",
            "vo-image-jpeg-quality" to "70", "vo-image-outdir" to folder.absolutePath, "vf" to "scale=320:-2", "aid" to "no", "sid" to "no",
            "pause" to "yes", "keep-open" to "yes", "idle" to "yes", "hwdec" to "no", "hr-seek" to "no", "cache" to "yes", "user-agent" to agent,
            "demuxer-lavf-o" to if (isFileURL(stream.url)) "allowed_extensions=ALL,allowed_segment_extensions=ALL,extension_picky=0" else "")) player.setOptionString(key, value)
        val fields = stream.headers.filterKeys { !it.equals("User-Agent", true) && !it.equals("Referer", true) }.map { it.key + ": " + it.value.replace(",", "\\,") }
        if (fields.isNotEmpty()) player.setOptionString("http-header-fields", fields.joinToString(","))
        if (stream.referer.isNotEmpty()) player.setOptionString("referrer", stream.referer)
        runCatching { player.init() }.onFailure { runCatching { player.destroy() }; return null }
        player.command(arrayOf("loadfile", mpvAddress(stream.url), "replace"))
        mpv = player; source = stream.url
        return player
    }

    /** The picture at `seconds`, asked while dragging; a request superseded within 120 ms is never sent. */
    fun show(stream: ResolvedStream, seconds: Double) {
        if (closed) return
        time = seconds
        val bucket = (seconds / 5).toInt()
        job?.cancel()
        val key = stream.url to bucket
        cache[key]?.let { image = it; return }
        job = scope.launch {
            delay(120)
            val bitmap = withContext(Dispatchers.IO) {
                decoder.withLock {
                    val player = open(stream) ?: return@withLock null
                    withTimeoutOrNull(3_000) {
                        while (player.getPropertyDouble("duration") == null) delay(75)
                    } ?: return@withLock null
                    folder.listFiles()?.forEach { it.delete() }
                    player.command(arrayOf("seek", (bucket * 5.0).toString(), "absolute+keyframes"))
                    withTimeoutOrNull(3_000) {
                        var frame: Bitmap? = null
                        while (frame == null) {
                            delay(75)
                            val file = folder.listFiles()?.filter { it.length() > 0 }?.maxByOrNull { it.lastModified() }
                            frame = file?.let { BitmapFactory.decodeFile(it.path) }
                        }
                        frame
                    }
                }
            } ?: return@launch
            cache[key] = bitmap
            while (cache.size > 60) cache.remove(cache.keys.first())
            if ((time / 5).toInt() == bucket) image = bitmap
        }
    }
    fun hide() { job?.cancel(); image = null }
    fun reset() { hide(); cache.clear() }
    fun close() {
        if (closed) return
        closed = true
        reset()
        scope.launch(Dispatchers.IO) {
            decoder.withLock { releasePlayer(); folder.deleteRecursively() }
            scope.cancel()
        }
    }

    private fun releasePlayer() {
        val player = mpv; mpv = null; source = null
        if (player != null) runCatching { player.destroy() }
    }
}
