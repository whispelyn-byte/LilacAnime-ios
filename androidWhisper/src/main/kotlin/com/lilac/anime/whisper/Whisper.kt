package com.lilac.anime.whisper

/** whisper.cpp through JNI (liblilac_whisper.so). */
object Whisper {
    val available: Boolean = runCatching { System.loadLibrary("lilac_whisper"); true }.getOrDefault(false)
    external fun nativeOpen(path: String): Long
    external fun nativeClose(handle: Long)
    external fun nativeAbort(handle: Long)
    /** Segments as "start\tend\tnoSpeech\ttext" (seconds); null when it failed or was stopped. */
    external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, threads: Int): Array<String>?
}
