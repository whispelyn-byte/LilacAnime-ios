package com.lilac.anime.shared

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface MiruroBrowserTransport {
    fun request(id: String, url: String, completion: (String?, String?) -> Unit)
    fun cancel(id: String)
}

/** Installed by iOS before creating repositories; JVM tests keep using their mock HTTP engine. */
object MiruroTransportRegistry {
    var transport: MiruroBrowserTransport? = null
    private var sequence = 0L
    internal suspend fun fetch(url: String): String? = withContext(Dispatchers.Main) {
        val bridge = transport ?: return@withContext null
        val id = (++sequence).toString()
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { bridge.cancel(id) }
            bridge.request(id, url) { body, error ->
                if (continuation.isActive) {
                    if (body != null) continuation.resume(body)
                    else continuation.resumeWithException(IllegalStateException(error ?: "Miruro 연결 실패"))
                }
            }
        }
    }
}
