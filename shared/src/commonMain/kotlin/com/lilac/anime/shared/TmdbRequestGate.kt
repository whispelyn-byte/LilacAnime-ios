package com.lilac.anime.shared

import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class TmdbFailure(val code: String, val status: Int = 0, val retryAfterMs: Long = 0) : Exception(when (code) {
    "auth" -> "TMDB API 키가 올바르지 않거나 사용할 권한이 없습니다."
    "rate-limit" -> "TMDB 요청이 많아 잠시 기다려야 합니다."
    "timeout" -> "TMDB 응답 시간이 초과됐습니다."
    "network" -> "TMDB에 연결하지 못했습니다."
    "response" -> "TMDB 응답을 읽지 못했습니다."
    else -> "TMDB HTTP $status"
})

/** A single gate for catalog batches, foreground title lookup and credential tests. */
class TmdbRequestGate(
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private val queue = Mutex()
    private var nextRequestAt = 0L
    private var blockedUntil = 0L
    suspend fun takeTurn() = queue.withLock {
        while (true) {
            val wait = mutex.withLock {
                val time = now()
                if (blockedUntil - time > 30_000) throw TmdbFailure("rate-limit", 429, blockedUntil - time)
                (maxOf(nextRequestAt, blockedUntil) - time).also { if (it <= 0) nextRequestAt = time + 150 }
            }
            if (wait <= 0) break
            sleep(wait)
        }
    }
    suspend fun retry(failure: TmdbFailure, attempt: Int): Boolean {
        val wait = maxOf(failure.retryAfterMs, 1000L shl attempt)
        if (failure.status == 429) mutex.withLock { blockedUntil = maxOf(blockedUntil, now() + wait) }
        val transient = failure.status == 0 || failure.status == 408 || failure.status == 429 || failure.status >= 500
        if (!transient || attempt >= 2 || wait > 30_000) return false
        if (failure.status != 429) sleep(wait)
        return true
    }
    fun retryAfter(header: String?): Long {
        if (header == null) return 0
        val milliseconds = header.takeIf { Regex("\\d+(?:\\.\\d+)?").matches(it) }?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            ?: runCatching { header.fromHttpToGmtDate().timestamp - now() }.getOrDefault(0)
        return maxOf(0, milliseconds)
    }
    companion object { val shared = TmdbRequestGate() }
}
