package com.lilac.anime.android.audio

import android.content.Context
import com.lilac.anime.android.player.PLAYER_AGENT
import com.lilac.anime.android.player.PlaybackFailure
import com.lilac.anime.android.player.isFileURL
import com.lilac.anime.android.player.mpvAddress
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The video to listen to (iOS SubtitleAutoSync.Video): a downloaded file, or the stream (the local proxy's address when
 *  it plays through it). */
data class AudioSource(val url: String, val headers: Map<String, String>, val duration: Double)

/** iOS NativeAudio (FFmpeg) on Android: a second, hidden mpv decodes the audio to 32-bit float mono PCM as fast as it can
 *  (ao=pcm does not play in real time). */
object AudioExtractor {
    suspend fun decode(context: Context, source: AudioSource, target: File, sampleRate: Int, start: Double? = null, length: Double? = null,
                       progress: ((Double) -> Unit)? = null) = withContext(Dispatchers.IO) {
        target.delete()
        val mpv = MPVLib.create(context.applicationContext) ?: throw PlaybackFailure("오디오를 읽을 mpv를 만들지 못했습니다.")
        val finished = CompletableDeferred<Unit>()
        var loaded = false
        val observer = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {}
            override fun eventProperty(property: String, value: Boolean) {}
            override fun eventProperty(property: String, value: String) {}
            override fun eventProperty(property: String, value: Double) {
                if (property == "time-pos" && source.duration > 0) progress?.invoke(((value - (start ?: 0.0)) / (length ?: source.duration)).coerceIn(0.0, 1.0))
            }
            override fun event(eventId: Int) {
                if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) loaded = true
                if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) finished.complete(Unit)
            }
        }
        try {
            val agent = source.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: PLAYER_AGENT
            for ((key, value) in listOf("config" to "no", "terminal" to "no", "msg-level" to "all=no", "vid" to "no", "sid" to "no", "vo" to "null",
                "ao" to "pcm", "ao-pcm-file" to target.absolutePath, "ao-pcm-waveheader" to "no", "audio-format" to "float",
                "audio-samplerate" to sampleRate.toString(), "audio-channels" to "mono", "keep-open" to "no", "idle" to "yes", "cache" to "yes",
                "input-default-bindings" to "no", "user-agent" to agent, "audio-display" to "no",
                "demuxer-lavf-o" to if (isFileURL(source.url)) "allowed_extensions=ALL,allowed_segment_extensions=ALL,extension_picky=0" else "")) mpv.setOptionString(key, value)
            val fields = source.headers.filterKeys { !it.equals("User-Agent", true) && !it.equals("Referer", true) }.map { it.key + ": " + it.value.replace(",", "\\,") }
            if (fields.isNotEmpty()) mpv.setOptionString("http-header-fields", fields.joinToString(","))
            source.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { mpv.setOptionString("referrer", it.value) }
            start?.let { mpv.setOptionString("start", it.toString()) }
            length?.let { mpv.setOptionString("length", it.toString()) }
            mpv.init()
            mpv.addObserver(observer)
            if (progress != null) mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.command(arrayOf("loadfile", mpvAddress(source.url), "replace"))
            try { finished.await() } catch (cancelled: CancellationException) { runCatching { mpv.command(arrayOf("stop")) }; throw cancelled }
            if (!loaded || !target.isFile || target.length() == 0L) throw PlaybackFailure("영상 음성을 읽지 못했어요.")
        } finally {
            runCatching { mpv.removeObserver(observer) }
            runCatching { mpv.destroy() }
        }
    }

    fun read(file: File): FloatArray = RandomAccessFile(file, "r").use { raf ->
        val bytes = ByteArray(raf.length().toInt() / 4 * 4).also { raf.readFully(it) }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** 자막 싱크 맞추기's dialogue level: 300–3400 Hz of the 16 kHz mix, in dB every 10 ms (iOS LilacSpeechLevels). */
    fun speechLevels(samples: FloatArray, sampleRate: Int = 16000): FloatArray {
        fun biquad(highPass: Boolean, frequency: Double): (Float) -> Float {
            val w = 2 * Math.PI * frequency / sampleRate; val cos = Math.cos(w); val alpha = Math.sin(w) / (2 * 0.7071)
            val b0 = if (highPass) (1 + cos) / 2 else (1 - cos) / 2; val b1 = if (highPass) -(1 + cos) else 1 - cos; val b2 = b0
            val a0 = 1 + alpha; val a1 = -2 * cos; val a2 = 1 - alpha
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            return { x -> val y = (b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0; x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y; y.toFloat() }
        }
        val high = biquad(true, 300.0); val low = biquad(false, 3400.0)
        val frame = sampleRate / 100
        val levels = FloatArray(samples.size / frame)
        for (index in levels.indices) {
            var sum = 0.0
            for (i in index * frame until (index + 1) * frame) { val v = low(high(samples[i])); sum += v * v }
            levels[index] = (10 * Math.log10(sum / frame + 1e-10)).toFloat()
        }
        return levels
    }
}
