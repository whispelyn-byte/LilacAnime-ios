package com.lilac.anime.android.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.*
import java.io.File

/** 재생 막대 미리보기 (iOS SeekPreview with NativeAudio's frame reader): the picture where the finger rests on the seek bar.
 *  A second, paused mpv without sound writes one small JPEG for each place asked (vo=image); the last asked wins. */
class SeekPreview(private val context: Context) {
    var image by mutableStateOf<Bitmap?>(null); private set
    var time by mutableStateOf(0.0); private set
    private var mpv: MPVLib? = null
    private var source: String? = null
    private val folder = File(context.cacheDir, "seek-preview").apply { deleteRecursively(); mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val cache = LinkedHashMap<Int, Bitmap>()

    private fun open(stream: ResolvedStream): MPVLib? {
        if (source == stream.url) return mpv
        close(keepState = true)
        val player = runCatching { MPVLib.create(context.applicationContext) }.getOrNull() ?: return null
        val agent = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: PLAYER_AGENT
        for ((key, value) in listOf("config" to "no", "terminal" to "no", "msg-level" to "all=no", "vo" to "image", "vo-image-format" to "jpg",
            "vo-image-jpeg-quality" to "70", "vo-image-outdir" to folder.absolutePath, "vf" to "scale=320:-2", "aid" to "no", "sid" to "no",
            "pause" to "yes", "keep-open" to "yes", "idle" to "yes", "hwdec" to "no", "hr-seek" to "no", "cache" to "yes", "user-agent" to agent,
            "demuxer-lavf-o" to if (isFileURL(stream.url)) "allowed_extensions=ALL,allowed_segment_extensions=ALL,extension_picky=0" else "")) player.setOptionString(key, value)
        val fields = stream.headers.filterKeys { !it.equals("User-Agent", true) && !it.equals("Referer", true) }.map { it.key + ": " + it.value.replace(",", "\\,") }
        if (fields.isNotEmpty()) player.setOptionString("http-header-fields", fields.joinToString(","))
        if (stream.referer.isNotEmpty()) player.setOptionString("referrer", stream.referer)
        runCatching { player.init() }.onFailure { return null }
        player.command(arrayOf("loadfile", stream.url.removePrefix("file://"), "replace"))
        mpv = player; source = stream.url
        return player
    }

    /** The picture at `seconds`, asked while dragging; a request superseded within 120 ms is never sent. */
    fun show(stream: ResolvedStream, seconds: Double) {
        time = seconds
        val bucket = (seconds / 5).toInt()
        cache[bucket]?.let { image = it; return }
        job?.cancel()
        job = scope.launch {
            delay(120)
            val bitmap = withContext(Dispatchers.IO) {
                val player = open(stream) ?: return@withContext null
                folder.listFiles()?.forEach { it.delete() }
                runCatching { player.command(arrayOf("seek", (bucket * 5.0).toString(), "absolute+keyframes")) }
                var found: File? = null
                repeat(40) { if (found == null) { found = folder.listFiles()?.filter { it.length() > 0 }?.maxByOrNull { it.lastModified() }; if (found == null) delay(75) } }
                delay(30)
                found?.let { BitmapFactory.decodeFile(it.path) }
            } ?: return@launch
            cache[bucket] = bitmap
            while (cache.size > 60) cache.remove(cache.keys.first())
            if ((time / 5).toInt() == bucket) image = bitmap
        }
    }
    fun hide() { job?.cancel(); image = null }
    fun close(keepState: Boolean = false) {
        job?.cancel()
        val player = mpv; mpv = null; source = null
        if (!keepState) { cache.clear(); image = null }
        if (player != null) Thread { runCatching { player.destroy() } }.start()
    }
}
