package com.lilac.anime.shared

import com.lilac.anime.shared.compat.JSONObject
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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

/** One request's place in the gate: a catalog index lookup is a background one until the screen asks for the same thing. */
class TmdbTicket(var background: Boolean = false)

/**
 * A single gate for catalog batches, foreground title lookup and credential tests (desktop tmdb-client.cjs). All callers share
 * request spacing and a 429 cooldown. Requests made for the screen go first and are spaced by [interval] (TMDB allows about
 * 50 a second); the catalog index's background lookups only start when no screen request waits, spaced by
 * [backgroundInterval]. The same request asked again while it runs shares that request, and a screen request's answer is
 * kept for [cacheTtl] (the background ones are kept by the catalog index itself).
 */
class TmdbRequestGate(
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val interval: Long = 40,
    private val backgroundInterval: Long = 150,
    private val cacheTtl: Long = 6 * 60 * 60 * 1000L,
    private val cacheSize: Int = 500
) {
    private class Running(val ticket: TmdbTicket, val result: CompletableDeferred<JSONObject> = CompletableDeferred())
    private val mutex = Mutex()
    private val waiting = mutableSetOf<TmdbTicket>()
    private val inflight = mutableMapOf<String, Running>()
    private val cache = LinkedHashMap<String, Pair<Long, JSONObject>>()
    private var lastStart = Long.MIN_VALUE / 2
    private var blockedUntil = 0L
    suspend fun takeTurn(ticket: TmdbTicket = TmdbTicket()) {
        mutex.withLock { waiting += ticket }
        try {
            while (true) {
                val wait = mutex.withLock {
                    val time = now()
                    // Long server cooldowns are handed back to the catalog scheduler, not held open here.
                    if (blockedUntil - time > 30_000) throw TmdbFailure("rate-limit", 429, blockedUntil - time)
                    val back = ticket.background
                    // A screen request that comes in meanwhile is looked at again after this wait.
                    if (back && waiting.any { !it.background }) interval
                    else (maxOf(lastStart + if (back) backgroundInterval else interval, blockedUntil) - time).also { if (it <= 0) lastStart = time }
                }
                if (wait <= 0) break
                sleep(wait)
            }
        } finally { withContext(NonCancellable) { mutex.withLock { waiting -= ticket } } }
    }
    /** [send] once for [id] however many ask for it while it runs; a screen request's answer is reused for [cacheTtl]. */
    suspend fun shared(id: String, background: Boolean, send: suspend (TmdbTicket) -> JSONObject): JSONObject {
        while (true) {
            var owner = false
            val running = mutex.withLock {
                val time = now()
                cache[id]?.let { (at, value) -> if (time - at < cacheTtl) return value; cache.remove(id) }
                // A background request still waiting moves to the screen lane when the screen asks for the same thing.
                inflight[id]?.takeIf { !it.result.isCompleted }?.also { if (!background) it.ticket.background = false }
                    ?: Running(TmdbTicket(background)).also { inflight[id] = it; owner = true }
            }
            if (!owner) {
                // The one who asked first was cancelled: ask again.
                try { return running.result.await() } catch (error: CancellationException) { currentCoroutineContext().ensureActive(); continue }
            }
            try {
                val value = send(running.ticket)
                mutex.withLock {
                    if (!running.ticket.background) {
                        cache[id] = now() to value
                        if (cache.size > cacheSize) cache.remove(cache.keys.first())
                    }
                }
                running.result.complete(value)
                return value
            } catch (error: Throwable) {
                running.result.completeExceptionally(error); throw error
            } finally { withContext(NonCancellable) { mutex.withLock { if (inflight[id] === running) inflight.remove(id) } } }
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
