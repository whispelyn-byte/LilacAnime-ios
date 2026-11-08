package com.lilac.anime.android.player

import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A response read whole: its bytes, status, headers and final address. */
class Fetched(val data: ByteArray, val status: Int, val headers: Headers, val url: String) {
    val mimeType: String? get() = headers["Content-Type"]?.substringBefore(";")?.trim()
}

/** iOS HLSData: what the proxy and the server checks do with playlists and segments. */
object HlsData {
    private val disguisedExtensions = setOf("jpg", "jpeg", "png", "webp", "html")
    fun disguised(url: String) = urlExtension(url) in disguisedExtensions

    /** A cookie jar shared by the player's requests: a CDN's cookie from the playlist goes with its segments. */
    private val cookies = object : CookieJar {
        private val kept = java.util.concurrent.ConcurrentHashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { kept[url.host] = (kept[url.host].orEmpty().filter { old -> cookies.none { it.name == old.name } } + cookies) }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = kept[url.host].orEmpty().filter { it.matches(url) }
    }
    /** HTTP/1.1, a connection per request: one HTTP/2 connection to Cloudflare's far edge carried every range at once and
     *  starved the one the player waited for (iOS HLSData.sessions). */
    val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1)).cookieJar(cookies)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .build()

    fun selectVariant(text: String, quality: String): String {
        val lines = text.lines()
        data class Choice(val index: Int, val bandwidth: Int, val height: Int)
        val choices = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") && it + 1 < lines.size }.map { index ->
            val attributes = lines[index].removePrefix("#EXT-X-STREAM-INF:")
            Choice(index, Regex("(?:^|,)BANDWIDTH=([0-9]+)").find(attributes)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                Regex("RESOLUTION=[0-9]+x([0-9]+)", RegexOption.IGNORE_CASE).find(attributes)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        }
        if (choices.isEmpty()) return text
        val limit = quality.replace("p", "").toIntOrNull()
        val pool = if (limit != null) choices.filter { it.height in 1..limit } else choices
        val selected = (pool.ifEmpty { choices }).maxByOrNull { it.bandwidth }?.index
        val omitted = choices.filter { it.index != selected }.flatMap { listOf(it.index, it.index + 1) }.toSet()
        return lines.filterIndexed { index, _ -> index !in omitted }.joinToString("\n")
    }

    private fun request(url: String, stream: ResolvedStream, range: String?): Request {
        val builder = Request.Builder().url(url)
        val sameHost = urlHost(url) == urlHost(stream.url)
        stream.headers.forEach { (key, value) -> if (!key.equals("cookie", true) || sameHost) builder.header(key, value) }
        if (stream.headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", PLAYER_AGENT)
        if (stream.referer.isNotEmpty()) builder.header("Referer", stream.referer)
        if (range != null) builder.header("Range", range)
        return builder.build()
    }

    suspend fun fetch(url: String, stream: ResolvedStream, range: String? = null, timeoutSeconds: Long = 60): Fetched {
        currentCoroutineContext().ensureActive()
        stream.hlsManifest?.let { manifest -> if (url == stream.url) return Fetched(manifest.toByteArray(), 200, Headers.headersOf("Content-Type", "application/vnd.apple.mpegurl"), url) }
        val call = client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build().newCall(request(url, stream, range))
        return call.await { response ->
            if (response.code !in 200..299) throw PlaybackFailure("영상 요청 실패 (${response.code})")
            Fetched(response.body?.bytes() ?: ByteArray(0), response.code, response.headers, response.request.url.toString())
        }
    }

    /** AES-128 segments must reach the decoder byte-for-byte, even if named .png or .ts. */
    fun encryptedSegments(text: String, base: String): Set<String> {
        var encrypted = false
        val result = mutableSetOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-KEY:")) encrypted = Regex("(?:^|,)METHOD=AES-128(?:,|$)").containsMatchIn(line.drop(11))
            else if (encrypted && line.isNotEmpty() && !line.startsWith("#")) resolveURL(base, line)?.let { result += it }
        }
        return result
    }

    suspend fun readable(stream: ResolvedStream): Boolean {
        var url = stream.url
        repeat(5) {
            val fetched = fetch(url, stream, timeoutSeconds = 6)
            url = fetched.url
            val text = manifest(fetched.data, stream.manifestKey)
            val entry = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return true
            val target = resolveURL(url, entry) ?: return true
            if (text.contains("#EXTINF")) {
                if (target in encryptedSegments(text, url)) return true
                return isMedia(transportStream(fetch(target, stream, "bytes=0-4095", 6).data))
            }
            url = target
        }
        return true
    }

    fun manifest(data: ByteArray, key: String?): String {
        val text = data.toString(Charsets.UTF_8).trim()
        if (text.startsWith("#EXTM3U")) return text
        val keyBytes = key?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }?.takeIf { it.isNotEmpty() }
            ?: throw PlaybackFailure("HLS 재생목록을 읽을 수 없습니다.")
        val encrypted = runCatching { Base64.decode(text.filter { !it.isWhitespace() }, Base64.DEFAULT) }.getOrNull() ?: throw PlaybackFailure("HLS 재생목록을 읽을 수 없습니다.")
        val plain = ByteArray(encrypted.size) { (encrypted[it].toInt() xor keyBytes[it % keyBytes.size].toInt()).toByte() }.toString(Charsets.UTF_8)
        if (!plain.startsWith("#EXTM3U")) throw PlaybackFailure("FlixCloud 재생목록 복호화 실패")
        return plain
    }

    fun fragment(data: ByteArray): ByteArray {
        var payload = when {
            data.size >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" && String(data, 8, 4, Charsets.US_ASCII) == "WEBP" -> data.copyOfRange(12, data.size)
            data.size >= 8 && data.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> data.copyOfRange(8, data.size)
            else -> return data
        }
        if (payload.isEmpty() || payload[0] == 71.toByte()) return payload
        val key = intArrayOf(157, 42, 241, 71, 179, 142, 92, 112, 166, 25, 228, 59, 216, 98, 15, 197)
        payload = ByteArray(payload.size) { (payload[it].toInt() xor key[it % key.size]).toByte() }
        return payload
    }
    fun isMedia(data: ByteArray): Boolean {
        if (data.isNotEmpty() && data[0] == 71.toByte() && (data.size <= 188 || data[188] == 71.toByte())) return true
        if (data.size < 8) return false
        return String(data, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "styp", "moof", "sidx", "emsg")
    }
    private fun transportPayload(data: ByteArray): ByteArray? {
        val limit = minOf(data.size - 377, 65535)
        if (limit < 0) return null
        for (index in 0..limit) if (data[index] == 71.toByte() && data[index + 188] == 71.toByte() && data[index + 376] == 71.toByte()) return data.copyOfRange(index, data.size)
        return null
    }
    fun transportStream(raw: ByteArray): ByteArray = transportPayload(raw) ?: fragment(raw).let { transportPayload(it) ?: it }
    fun validatedFragment(raw: ByteArray): ByteArray = transportStream(raw).also { if (!isMedia(it)) throw PlaybackFailure("영상 조각 복호화에 실패했습니다.") }

    /** A range asked up to three times; a quiet connection (no bytes for a few seconds) is asked again. */
    private suspend fun ranged(url: String, stream: ResolvedStream, start: Int, end: Int): Fetched {
        var failure: Throwable = PlaybackFailure("영상 조각을 받지 못했습니다.")
        repeat(3) { attempt ->
            try {
                val quick = client.newBuilder().readTimeout(if (attempt < 2) 4 else 12, TimeUnit.SECONDS).build()
                val result = quick.newCall(request(url, stream, "bytes=$start-$end")).await { response ->
                    if (response.code !in 200..299) throw PlaybackFailure("영상 요청 실패 (${response.code})")
                    Fetched(response.body?.bytes() ?: ByteArray(0), response.code, response.headers, response.request.url.toString())
                }
                if (start > 0 && (result.status != 206 || result.data.size != end - start + 1)) throw PlaybackFailure("영상 조각 범위 응답 오류")
                return result
            } catch (error: CancellationException) { throw error } catch (error: Throwable) { failure = error }
        }
        throw failure
    }

    /** A disguised segment: the first range tells its size, the rest comes in half-MB ranges six at a time. */
    suspend fun mediaSegment(url: String, stream: ResolvedStream): Fetched {
        var failure: Throwable = PlaybackFailure("영상 조각을 받지 못했습니다.")
        repeat(3) {
            try {
                val head = ranged(url, stream, 0, 262143)
                val total = head.headers["Content-Range"]?.substringAfterLast("/")?.toIntOrNull() ?: 0
                val raw = ByteArrayOutputStream().apply { write(head.data) }
                if (head.status == 206 && total > head.data.size) {
                    val ranges = (head.data.size until total step 524288).map { it to minOf(it + 524288, total) - 1 }
                    val gate = kotlinx.coroutines.sync.Semaphore(6)
                    val pieces = coroutineScope { ranges.map { (a, b) -> async { gate.acquire(); try { ranged(url, stream, a, b).data } finally { gate.release() } } }.awaitAll() }
                    pieces.forEach { raw.write(it) }
                }
                return Fetched(validatedFragment(raw.toByteArray()), head.status, head.headers, head.url)
            } catch (error: CancellationException) { throw error } catch (error: Throwable) { failure = error }
        }
        throw failure
    }

    /** What the player asks for that is not a disguised segment: asked again when it stalls, up to three times. */
    suspend fun playerFetch(url: String, stream: ResolvedStream, range: String?): Fetched {
        var failure: Throwable = PlaybackFailure("영상 요청 실패")
        for (attempt in 0 until 3) {
            try { return fetch(url, stream, range, if (attempt < 2) 12 else 30) }
            catch (error: CancellationException) { throw error } catch (error: Throwable) { failure = error }
        }
        throw failure
    }

    private val uriPattern = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    fun variantPlaylists(text: String, base: String): List<String> {
        var nextIsVariant = false
        return text.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> { nextIsVariant = true; null }
                line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ->
                    uriPattern.find(line)?.groupValues?.get(1)?.let { resolveURL(base, it) }
                nextIsVariant && line.isNotEmpty() && !line.startsWith("#") -> {
                    nextIsVariant = false
                    resolveURL(base, line)
                }
                else -> null
            }
        }.toList()
    }
    fun references(text: String, base: String): List<String> {
        val urls = mutableListOf<String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) resolveURL(base, trimmed)?.let { urls += it }
            uriPattern.findAll(line).forEach { match -> resolveURL(base, match.groupValues[1])?.let { urls += it } }
        }
        return urls.distinct()
    }
    fun rewrite(text: String, base: String, map: (String) -> String): String = text.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) resolveURL(base, trimmed)?.let(map) ?: line
        else uriPattern.replace(line) { match -> resolveURL(base, match.groupValues[1])?.let { "URI=\"" + map(it) + "\"" } ?: match.value }
    }
}

/** An OkHttp call as a suspending one, cancelled with the coroutine. */
suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(read) }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}

fun shortKey(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

/** iOS HLSProxy: a local HTTP server mpv plays from. It decrypts FlixCloud playlists, keeps only the chosen quality,
 *  serves disguised segments (.png/.webp/.html) decoded as transport stream and fetches the next ones ahead. */
class HlsProxy(
    /** The Chromecast relay (iOS LANMediaRelay): listens on the Wi-Fi address, names it in its addresses, allows CORS. */
    private val lanAddress: String? = null,
) {
    private val host get() = lanAddress ?: "127.0.0.1"
    private val blobs = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, String>>()
    private var localRoot: File? = null
    /** Bytes served at their own address (the subtitle sent with a cast). */
    fun blob(data: ByteArray, type: String, ext: String): String {
        val key = UUID.randomUUID().toString().replace("-", "")
        blobs[key] = data to type
        return "http://$host:$port/$token/$key.$ext"
    }
    /** A downloaded episode served from its folder (its playlist's files, nothing else). */
    suspend fun startLocal(file: File): String {
        localRoot = file.canonicalFile.parentFile
        startServer()
        return register(file.canonicalPath)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private val routes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val manifests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val encrypted = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val token = UUID.randomUUID().toString()
    private var stream: ResolvedStream? = null
    private var quality = "Auto"
    private var port = 0
    private val media = MediaCache(scope)

    suspend fun start(stream: ResolvedStream, quality: String = "Auto"): ResolvedStream {
        this.stream = stream; this.quality = quality
        startServer()
        if (stream.manifestKey != null || stream.hlsManifest != null || urlExtension(stream.url) == "m3u8") manifests += stream.url
        return stream.copy(url = register(stream.url), headers = emptyMap(), manifestKey = null, hlsManifest = null)
    }
    private suspend fun startServer() {
        val socket = withContext(Dispatchers.IO) {
            ServerSocket(0, 64, InetAddress.getByName(if (lanAddress != null) "0.0.0.0" else "127.0.0.1")).also { server = it }
        }
        port = socket.localPort
        scope.launch {
            while (isActive) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                launch { runCatching { respond(connection) }; runCatching { connection.close() } }
            }
        }
    }

    private fun register(url: String): String {
        val key = shortKey(url)
        routes[key] = url
        val original = urlExtension(url)
        // FFmpeg refuses HLS segments named .html/.png/.webp: they are served decoded as transport stream, so named so.
        val ext = when {
            url in manifests -> "m3u8"
            HlsData.disguised(url) -> "ts"
            else -> original.ifEmpty { "bin" }
        }
        return "http://$host:$port/$token/$key.$ext"
    }

    private suspend fun respond(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val byte = input.read(); if (byte < 0) return
            header.append(byte.toChar()); if (header.length > 32768) return
        }
        val lines = header.split("\r\n")
        val parts = lines.first().split(" ")
        val out = socket.getOutputStream()
        if (parts.size < 2 || parts[0] !in setOf("GET", "HEAD")) { send(out, 405, ByteArray(0)); return }
        val path = parts[1].trim('/').split("/")
        val stream = stream
        val routeKey = if (path.size == 2 && path[0] == token) path[1].substringBefore(".") else ""
        blobs[routeKey]?.let { (data, type) -> send(out, 200, data, mapOf("Content-Type" to type), parts[0] == "HEAD"); return }
        val url = routes[routeKey]
        if (url != null && url.startsWith("/")) { serveLocal(out, File(url), lines.firstOrNull { it.lowercase().startsWith("range:") }?.drop(6)?.trim(), parts[0] == "HEAD"); return }
        if (url == null || stream == null) { send(out, 404, ByteArray(0)); return }
        val range = lines.firstOrNull { it.lowercase().startsWith("range:") }?.drop(6)?.trim()
        try {
            val aes = url in encrypted
            val ext = urlExtension(url)
            val playlist = url in manifests || ext == "m3u8"
            val segment = !playlist && !aes && HlsData.disguised(url)
            val fetched = if (segment) media.load(url, stream) else HlsData.playerFetch(url, stream, if (!playlist && stream.manifestKey == null) range else null)
            var body = fetched.data
            var contentType = fetched.mimeType ?: "application/octet-stream"
            if (playlist || contentType.contains("mpegurl")) {
                val text = HlsData.selectVariant(HlsData.manifest(fetched.data, stream.manifestKey), quality)
                manifests.addAll(HlsData.variantPlaylists(text, fetched.url))
                val secret = HlsData.encryptedSegments(text, fetched.url)
                val references = HlsData.references(text, fetched.url)
                synchronized(encrypted) { encrypted.removeAll(references.toSet()); encrypted.addAll(secret) }
                media.order(references.filter { it !in secret && it !in manifests && HlsData.disguised(it) })
                body = HlsData.rewrite(text, fetched.url) { register(it) }.toByteArray()
                contentType = "application/vnd.apple.mpegurl"
            } else if (segment) {
                contentType = if (body.isNotEmpty() && body[0] == 71.toByte()) "video/mp2t" else "video/mp4"
            } else if (!aes && stream.manifestKey != null && (ext == "ts" || contentType.startsWith("image/"))) {
                body = HlsData.validatedFragment(body); contentType = "video/mp2t"
            }
            val headers = linkedMapOf("Content-Type" to contentType, "Accept-Ranges" to "bytes")
            if (!segment) fetched.headers["Content-Range"]?.let { headers["Content-Range"] = it }
            var status = if (segment) 200 else fetched.status
            if ((stream.manifestKey != null || segment) && range != null && range.startsWith("bytes=") && !contentType.contains("mpegurl")) {
                val bounds = range.drop(6).split("-", limit = 2)
                val size = body.size
                if (bounds.size != 2 || size == 0) { send(out, 416, ByteArray(0)); return }
                var start = bounds[0].toIntOrNull() ?: 0
                var end = bounds[1].toIntOrNull() ?: (size - 1)
                if (bounds[0].isEmpty()) { start = maxOf(0, size - (bounds[1].toIntOrNull() ?: size)); end = size - 1 }
                end = minOf(end, size - 1)
                if (start < 0 || start > end || start >= size) { send(out, 416, ByteArray(0)); return }
                body = body.copyOfRange(start, end + 1)
                headers["Content-Range"] = "bytes $start-$end/$size"; status = 206
            }
            send(out, status, body, headers, parts[0] == "HEAD")
        } catch (error: CancellationException) { throw error } catch (error: Throwable) {
            send(out, 502, (error.message ?: "error").toByteArray())
        }
    }

    /** A downloaded file: a playlist with its references named here, anything else in ranges straight from the disk. */
    private fun serveLocal(out: OutputStream, file: File, range: String?, headOnly: Boolean) {
        val root = localRoot
        if (root == null || !file.canonicalPath.startsWith(root.canonicalPath + File.separator) && file.canonicalFile.parentFile != root || !file.isFile) { send(out, 404, ByteArray(0)); return }
        val ext = file.extension.lowercase()
        if (ext == "m3u8") {
            val base = file.toURI().toString()
            val text = HlsData.rewrite(file.readText(), base) { target -> runCatching { register(File(java.net.URI(target)).canonicalPath) }.getOrDefault(target) }
            send(out, 200, text.toByteArray(), mapOf("Content-Type" to "application/vnd.apple.mpegurl"), headOnly); return
        }
        val type = mapOf("ts" to "video/mp2t", "mp4" to "video/mp4", "m4s" to "video/iso.segment", "aac" to "audio/aac", "vtt" to "text/vtt", "key" to "application/octet-stream")[ext] ?: "application/octet-stream"
        val size = file.length()
        var start = 0L; var end = size - 1; var status = 200
        if (range != null && range.startsWith("bytes=")) {
            val bounds = range.drop(6).split("-", limit = 2)
            if (bounds[0].isEmpty()) { start = maxOf(0, size - (bounds.getOrNull(1)?.toLongOrNull() ?: size)) } else { start = bounds[0].toLongOrNull() ?: 0; end = bounds.getOrNull(1)?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1) }
            if (start > end || start >= size) { send(out, 416, ByteArray(0)); return }
            status = 206
        }
        val length = end - start + 1
        val header = StringBuilder("HTTP/1.1 $status OK\r\nConnection: close\r\nContent-Length: $length\r\nContent-Type: $type\r\nAccept-Ranges: bytes\r\n")
        if (status == 206) header.append("Content-Range: bytes $start-$end/$size\r\n")
        if (lanAddress != null) header.append("Access-Control-Allow-Origin: *\r\n")
        out.write(header.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start); val buffer = ByteArray(1 shl 16); var left = length
            while (left > 0) { val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); if (n < 0) break; out.write(buffer, 0, n); left -= n }
        }
        out.flush()
    }

    private fun send(out: OutputStream, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap(), headOnly: Boolean = false) {
        val text = StringBuilder("HTTP/1.1 $status ${if (status < 400) "OK" else "Error"}\r\nConnection: close\r\nContent-Length: ${body.size}\r\n")
        if (lanAddress != null) text.append("Access-Control-Allow-Origin: *\r\n")
        headers.forEach { (key, value) -> text.append(key).append(": ").append(value).append("\r\n") }
        text.append("\r\n")
        out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    fun stop() { runCatching { server?.close() }; server = null; scope.cancel() }
}

/** Desktop flix-proxy: each disguised segment fetched once, the next five fetched after the one the player waits for. */
private class MediaCache(private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val tasks = LinkedHashMap<String, Deferred<Fetched>>()
    private val positions = HashMap<String, Pair<List<String>, Int>>()

    suspend fun order(urls: List<String>) = lock.withLock { urls.forEachIndexed { index, url -> positions[url] = urls to index } }
    private suspend fun task(url: String, stream: ResolvedStream): Deferred<Fetched> = lock.withLock {
        tasks.remove(url)?.let { tasks[url] = it; return@withLock it }
        val task = scope.async { HlsData.mediaSegment(url, stream) }
        tasks[url] = task
        while (tasks.size > 16) { val oldest = tasks.keys.first(); tasks.remove(oldest)?.cancel() }
        task
    }
    suspend fun load(url: String, stream: ResolvedStream): Fetched {
        val current = task(url, stream)
        try {
            val value = current.await()
            val following = lock.withLock { positions[url]?.let { (list, index) -> list.drop(index + 1).take(5) }.orEmpty() }
            following.forEach { task(it, stream) }
            return value
        } catch (error: CancellationException) { throw error } catch (error: Throwable) {
            lock.withLock { tasks.remove(url) }; throw error
        }
    }
}
