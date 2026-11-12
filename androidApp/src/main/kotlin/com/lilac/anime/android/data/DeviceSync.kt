package com.lilac.anime.android.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.android.player.HlsData
import com.lilac.anime.android.player.PlaybackFailure
import com.lilac.anime.android.player.await
import com.lilac.anime.shared.Anime
import com.lilac.anime.shared.AnimeSnapshot
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

private class SyncHttpError(val status: Int, message: String) : Exception(message)

/** The comparison and merge rules of the desktop's src/device-sync.js (iOS DeviceSyncPlan), on desktop-shaped items. */
object DeviceSyncPlan {
    val collections = listOf("library", "history")
    /** JSON with sorted keys, so an item's hash says whether it changed. */
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Double -> if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        is Float -> canonical(value.toDouble())
        else -> value.toString()
    }
    /** FNV-1a of the item's JSON. */
    fun hash(item: Any?): String {
        var value = 0x811c9dc5u
        for (byte in canonical(item).toByteArray()) { value = (value xor byte.toUByte().toUInt()) * 0x01000193u }
        return value.toString(36)
    }
    fun hashes(lists: Map<String, Map<String, JSONObject>>) = collections.associateWith { c -> lists[c].orEmpty().mapValues { hash(it.value) } }
    fun number(value: Any?): Double? = when (value) { is Number -> value.toDouble(); is String -> value.toDoubleOrNull(); else -> null }
    /** The server takes an id of at most 512 UTF-16 units without a line break. */
    fun fits(id: String) = id.isNotEmpty() && id.length <= 512 && !id.contains("\n")
    /** Items added or changed since the snapshot, and removals. */
    fun plan(lists: Map<String, Map<String, JSONObject>>, snapshot: Map<String, Map<String, String>>, now: Double): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        for (collection in collections) {
            val before = snapshot[collection].orEmpty(); val current = lists[collection].orEmpty()
            for (id in current.keys.sorted()) {
                val item = current[id] ?: continue
                if (before[id] == hash(item) || !fits(id)) continue
                val updated = if (collection == "history") number(item.opt("updated")) ?: now else if (before[id] != null) now else number(item.opt("savedAt"))?.takeIf { it > 0 } ?: 1.0
                items += JSONObject().put("collection", collection).put("id", id).put("updated", updated).put("data", item)
            }
            for (id in before.keys.sorted()) if (current[id] == null) items += JSONObject().put("collection", collection).put("id", id).put("updated", now).put("deleted", true)
        }
        return items
    }
}

/** iOS 내 목록 / 시청 기록 <-> the desktop's item shape (lilacanimeserver README "데이터 모양"). */
object DeviceSyncMapping {
    fun desktopID(anime: Anime, source: String): String {
        for (prefix in listOf("$source:", "${source}_")) if (anime.id.startsWith(prefix)) return anime.id.removePrefix(prefix)
        return anime.id
    }
    fun iosID(id: String, source: String) = if (source in listOf("reanime", "animenosub") && !id.startsWith("$source:")) "$source:$id" else id
    private val trimmedForms = HashMap<String, Pair<Anime, String>>()
    private fun trimmed(saved: SavedAnime): Pair<Anime, String> = trimmedForms[saved.snapshot] ?: run {
        val anime = AnimeSnapshot.withEpisodes(saved.anime, emptyList())
        (anime to AnimeSnapshot.encode(anime)).also { if (trimmedForms.size > 2000) trimmedForms.clear(); trimmedForms[saved.snapshot] = it }
    }
    fun animeItem(anime: Anime, source: String): JSONObject {
        val id = desktopID(anime, source)
        return JSONObject().put("provider", source).put("id", id).put("mal_id", "$source:$id").put("title", anime.title)
            .put("title_english", anime.english).put("title_japanese", anime.native).put("images", JSONObject().put("webp", JSONObject().put("large_image_url", anime.poster)))
            .put("year", anime.year).put("type", anime.format).put("status", anime.status).put("url", anime.detailUrl)
            .put("genres", JSONArray(anime.genres.map { JSONObject().put("name", it) })).apply {
                if (anime.score > 0) put("score", anime.score)
                anime.totalEpisodes?.let { put("episodes", it); put("totalEpisodes", it) }
                anime.anilistId?.let { put("anilistId", it) }
                anime.malId?.let { put("malId", it) }
            }
    }
    fun libraryID(saved: SavedAnime) = fitted(saved.source + ":" + desktopID(saved.anime, saved.source), saved.source)
    /** An id the server takes: decoded when too long, else its source and an FNV-1a of it (as the desktop's fitId). */
    fun fitted(raw: String, source: String): String {
        var id = raw
        for ((code, mark) in listOf("%21" to "!", "%27" to "'", "%28" to "(", "%29" to ")", "%2A" to "*")) id = id.replace(code, mark, ignoreCase = true)
        if (DeviceSyncPlan.fits(id)) return id
        runCatching { URLDecoder.decode(id.replace("+", "%2B"), "UTF-8") }.getOrNull()?.takeIf(DeviceSyncPlan::fits)?.let { return it }
        var value = 0x811c9dc5u
        for (byte in id.toByteArray()) value = (value xor byte.toUByte().toUInt()) * 0x01000193u
        return "$source:#" + value.toString(36)
    }
    fun libraryItem(saved: SavedAnime): JSONObject {
        val (anime, snapshot) = trimmed(saved)
        return animeItem(anime, saved.source).put("savedAt", (saved.savedAt ?: 0L).toDouble()).put("ios", JSONObject().put("snapshot", snapshot))
    }
    fun historyID(entry: WatchEntry): String? {
        if (entry.anime.source == "local" || !(entry.watchURL.startsWith("http://") || entry.watchURL.startsWith("https://"))) return null
        return fitted(entry.anime.source + ":" + entry.watchURL, entry.anime.source)
    }
    fun historyItem(entry: WatchEntry): JSONObject? {
        val key = historyID(entry) ?: return null
        val (anime, snapshot) = trimmed(entry.anime)
        val progress = if (entry.duration > 0) Math.round((entry.position / entry.duration).coerceIn(0.0, 1.0) * 100).toDouble() else entry.progressPercent ?: 0.0
        val label = entry.episodeLabel
        return JSONObject().put("key", key).put("src", "").put("name", anime.title + " · " + entry.episodeTitle).put("subtitleTitle", anime.title).put("image", anime.poster)
            .put("episode", JSONObject().put("provider", entry.anime.source).put("url", entry.watchURL).put("id", entry.episodeID)
                .put("number", label.toDoubleOrNull() ?: entry.number.toDouble()).put("name", label)
                .put("special", !Regex("^\\d+(?:\\.\\d+)?[a-z]?$", RegexOption.IGNORE_CASE).matches(label)))
            .put("anime", animeItem(anime, entry.anime.source)).put("resolveKind", if (entry.anime.source == "linkkf") "linkkf" else JSONObject.NULL)
            .put("progress", progress).put("updated", entry.updatedAt.toDouble())
            .put("ios", JSONObject().put("animeID", entry.anime.id).put("episodeID", entry.episodeID).put("title", entry.episodeTitle).put("displayNumber", label)
                .put("position", entry.position).put("duration", entry.duration).put("snapshot", snapshot))
            // 시청함 only when set, as the desktop writes it: the other items stay as they were.
            .apply { if (entry.watched == true) put("watched", true) }
    }
    fun lists(library: LibraryStore): Map<String, Map<String, JSONObject>> {
        val favorites = linkedMapOf<String, JSONObject>(); val history = linkedMapOf<String, JSONObject>()
        for (saved in library.favorites) favorites[libraryID(saved)] = libraryItem(saved)
        for (entry in library.history) { val id = historyID(entry) ?: continue; if (history[id] == null) historyItem(entry)?.let { history[id] = it } }
        return mapOf("library" to favorites, "history" to history)
    }

    /** A desktop item as this app's saved anime: its iOS form when it was made by an iOS-family app, else built from its fields. */
    fun savedAnime(data: JSONObject, savedAt: Long? = null): SavedAnime? {
        val ios = data.optJSONObject("ios")
        var source = data.optString("provider")
        if (source.isEmpty()) source = data.optString("mal_id").substringBefore(":", "")
        if (source.isEmpty()) return null
        val anime = ios?.optString("snapshot")?.takeIf { it.isNotEmpty() }?.let { AnimeSnapshot.decode(it) } ?: run {
            var id = data.opt("id")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
            if (id.isEmpty()) id = data.optString("mal_id").substringAfter(":", "")
            if (id.isEmpty()) return null
            val images = data.optJSONObject("images")?.optJSONObject("webp")
            val genres = data.optJSONArray("genres")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") } }.orEmpty()
            val fields = JSONObject().put("id", iosID(id, source)).put("title", data.optString("title", id)).put("score", DeviceSyncPlan.number(data.opt("score")) ?: 0.0)
                .put("poster", images?.optString("large_image_url").orEmpty()).put("genres", JSONArray(genres)).put("description", data.optString("synopsis"))
                .put("airedDate", data.optString("aired")).put("year", data.opt("year")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()).put("season", data.optString("season"))
                .put("format", data.optString("type")).put("status", data.optString("status")).put("source", source)
                .put("english", data.optString("title_english")).put("native", data.optString("title_japanese")).put("detailUrl", data.optString("url"))
            DeviceSyncPlan.number(data.opt("anilistId"))?.let { fields.put("anilistId", it.toInt()) }
            DeviceSyncPlan.number(data.opt("malId"))?.let { fields.put("malId", it.toInt()) }
            (DeviceSyncPlan.number(data.opt("totalEpisodes")) ?: DeviceSyncPlan.number(data.opt("episodes")))?.let { fields.put("totalEpisodes", it.toInt()) }
            AnimeSnapshot.decode(fields.toString())
        }
        return SavedAnime.of(anime, source, savedAt)
    }
    fun watchEntry(data: JSONObject): WatchEntry? {
        val key = data.optString("key").ifEmpty { return null }
        val animeData = data.optJSONObject("anime") ?: return null
        val ios = data.optJSONObject("ios")
        val withProvider = JSONObject(animeData.toString())
        if (!withProvider.has("provider") && key.contains(":")) withProvider.put("provider", key.substringBefore(":"))
        ios?.optString("snapshot")?.takeIf { it.isNotEmpty() }?.let { withProvider.put("ios", JSONObject().put("snapshot", it)) }
        val anime = savedAnime(withProvider) ?: return null
        val episode = data.optJSONObject("episode") ?: JSONObject()
        // An entry the original Android app keyed by its own episode id, without an address, cannot be played here.
        val url = episode.optString("url").ifEmpty { if (data.optString("androidEpisodeKey").isNotEmpty()) "" else key.substringAfter(":") }
        if (!url.startsWith("http")) return null
        val listed = episode.opt("id")?.takeIf { it != JSONObject.NULL }?.toString()?.ifEmpty { null }
        val episodeID = ios?.optString("episodeID")?.ifEmpty { null } ?: listed ?: url
        val number = (DeviceSyncPlan.number(episode.opt("number")) ?: 1.0).toInt()
        val name = episode.opt("name")?.takeIf { it != JSONObject.NULL }?.toString() ?: number.toString()
        val title = ios?.optString("title")?.ifEmpty { null } ?: if (name.lastOrNull()?.isDigit() == true) name + "화" else name
        val duration = DeviceSyncPlan.number(ios?.opt("duration")) ?: 0.0
        return WatchEntry(anime.id + "#" + episodeID, anime, episodeID, title, number, url, null, DeviceSyncPlan.number(ios?.opt("position")) ?: 0.0, duration,
            (DeviceSyncPlan.number(data.opt("updated")) ?: 0.0).toLong(), if (ios == null || duration <= 0) DeviceSyncPlan.number(data.opt("progress")) else null,
            ios?.optString("displayNumber")?.ifEmpty { null } ?: name,
            // 시청함 (desktop 8065f4a): an episode played to 90% on any device stays checked.
            watched = if (data.optBoolean("watched") || (DeviceSyncPlan.number(data.opt("progress")) ?: 0.0) >= 90) true else null)
    }
    /** Writes the merged items into the lists: changed ones in place, new ones first, removals out. */
    fun apply(changes: Map<String, Map<String, JSONObject?>>, library: LibraryStore) {
        val favorites = library.favorites.toMutableList(); val history = library.history.toMutableList()
        for ((id, change) in changes["library"].orEmpty()) {
            val index = favorites.indexOfFirst { libraryID(it) == id }
            if (change == null) { if (index >= 0) favorites.removeAt(index); continue }
            val savedAt = DeviceSyncPlan.number(change.opt("savedAt"))?.takeIf { it > 0 }?.toLong()
            val saved = savedAnime(change, savedAt) ?: continue
            if (index >= 0) favorites[index] = saved else { favorites.removeAll { it.id == saved.id }; favorites.add(0, saved) }
        }
        for ((id, change) in changes["history"].orEmpty()) {
            history.removeAll { historyID(it) == id }
            val entry = change?.let(::watchEntry) ?: continue
            history.removeAll { it.id == entry.id }
            history += entry
        }
        library.replaceFromSync(favorites, history.sortedByDescending { it.updatedAt })
    }
}

/** 기기 동기화 (desktop device-sync.cjs, iOS DeviceSync): 내 목록 and 시청 기록 kept in step with the desktop app and the
 *  iPhone through the owner's own LilacAnime server. */
object DeviceSync {
    var server by mutableStateOf(""); private set
    var connected by mutableStateOf(false); private set
    var state by mutableStateOf("연결된 서버가 없습니다."); private set
    var running by mutableStateOf(false); private set
    private lateinit var file: File
    private var library: LibraryStore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timer: Job? = null
    private var due = Long.MAX_VALUE
    private var lastRun = 0L
    private var connection = 0L
    private var again = false
    private var applying = false
    private var rev = 0
    private var snapshot: Map<String, Map<String, String>> = emptyMap()
    private const val TOKEN = "device-sync-token"

    fun init(context: Context) {
        file = File(context.filesDir, "device-sync.json")
        runCatching { JSONObject(file.readText()) }.getOrNull()?.let { saved ->
            server = saved.optString("server"); rev = saved.optInt("rev")
            snapshot = saved.optJSONObject("snapshot")?.let { s -> s.keys().asSequence().associateWith { c -> s.getJSONObject(c).let { m -> m.keys().asSequence().associateWith { m.getString(it) } } } }.orEmpty()
        }
        connected = server.isNotEmpty() && SecureKeys.load(TOKEN).isNotEmpty()
        state = if (connected) "${server}에 연결됨" else if (server.isEmpty()) "연결된 서버가 없습니다." else "연결이 끊겨 있습니다. 비밀번호를 넣고 연결하세요."
    }
    /** Runs when the app opens, comes back to the front, every 5 minutes, and a little after either list changes. */
    fun start(library: LibraryStore) {
        if (this.library != null) return
        this.library = library
        library.onChange = { favorites -> if (!applying) soon(if (favorites) 3 else 30) }
        scope.launch { while (isActive) { delay(300_000); soon(0) } }
        soon(3)
    }
    fun becameActive() { if (System.currentTimeMillis() - lastRun > 60_000) soon(1) }
    /** A later request never pushes a sooner one back. */
    private fun soon(seconds: Int) {
        if (!connected) return
        val at = System.currentTimeMillis() + seconds * 1000L
        if (timer?.isActive == true && due <= at) return
        timer?.cancel(); due = at
        timer = scope.launch { delay(seconds * 1000L); run() }
    }

    private fun normalize(value: String): String {
        var text = value.trim()
        if (text.isEmpty()) throw PlaybackFailure("서버 주소를 입력하세요.")
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text))
            text = (if (Regex("^(?:localhost|127\\.0\\.0\\.1)(?::|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)) "http://" else "https://") + text
        val url = runCatching { java.net.URL(text) }.getOrNull() ?: throw PlaybackFailure("서버 주소가 올바르지 않습니다.")
        if (url.host.isNullOrEmpty()) throw PlaybackFailure("서버 주소가 올바르지 않습니다.")
        if (url.protocol == "http" && url.host.lowercase() !in listOf("localhost", "127.0.0.1")) throw PlaybackFailure("서버 주소는 https://로 시작해야 합니다.")
        return url.protocol + "://" + url.host + if (url.port > 0) ":${url.port}" else ""
    }
    private fun serverError(data: JSONObject?, status: Int): String {
        data?.opt("error")?.let { if (it is String) return it }
        val nested = data?.optJSONObject("error")
        val text = data?.optString("message").orEmpty() + " " + nested?.optString("message").orEmpty()
        if (data?.has("protection") == true || Regex("Protected (?:by Vercel|deployment)", RegexOption.IGNORE_CASE).containsMatchIn(text))
            return "Vercel 보호 기능이 이 주소를 막고 있습니다. Vercel 프로젝트의 Domains에 있는 고정 주소(Production)를 넣거나, Settings > Deployment Protection에서 Vercel Authentication을 끄세요."
        val code = nested?.optString("code").orEmpty()
        if (code == "NOT_FOUND" || status == 404) return "이 주소에 LilacAnime 서버가 없습니다. Vercel에 배포한 주소가 맞는지, 배포가 끝났는지 확인하세요."
        if (Regex("FUNCTION_INVOCATION|INTERNAL|DEPLOYMENT").containsMatchIn(code) || status >= 500) return "서버가 요청을 처리하지 못했습니다 (" + code.ifEmpty { "HTTP $status" } + "). Vercel 프로젝트의 Logs에서 오류를 확인하세요."
        nested?.optString("message")?.takeIf { it.isNotEmpty() }?.let { return "서버 오류: $it" }
        return "서버 오류 (HTTP $status)"
    }
    private suspend fun call(base: String, path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): JSONObject {
        val request = Request.Builder().url(base + path).header("Accept", "application/json").apply {
            if (body != null) method(method, body.toString().toRequestBody("application/json".toMediaType())) else method(method, null)
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        val (status, text) = try {
            HlsData.client.newBuilder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).await { it.code to (it.body?.string() ?: "") }
        } catch (error: CancellationException) { throw error } catch (error: Throwable) {
            throw PlaybackFailure(if (error is java.io.InterruptedIOException) "서버가 응답하지 않습니다." else "서버에 연결하지 못했습니다. 주소와 인터넷 연결을 확인하세요.")
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (status !in 200..299) throw SyncHttpError(status, serverError(json, status))
        return json ?: throw PlaybackFailure("LilacAnime 서버가 아닌 것 같습니다. 주소를 확인하세요.")
    }
    /** A token the server no longer takes (the password was changed) signs this device out. */
    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val token = SecureKeys.load(TOKEN)
        if (!connected || token.isEmpty()) throw PlaybackFailure("기기 동기화가 연결되어 있지 않습니다.")
        val generation = connection
        try {
            val reply = call(server, path, method, body, token)
            if (generation != connection || !connected) throw CancellationException()
            return reply
        } catch (error: SyncHttpError) {
            if (error.status == 401 && generation == connection) disconnect("서버가 이 기기를 받지 않습니다. 비밀번호가 바뀌었다면 새 비밀번호로 다시 연결하세요.")
            throw error
        }
    }

    suspend fun connect(address: String, password: String) {
        val base = normalize(address)
        val generation = ++connection
        val status = call(base, "/api/status")
        if (generation != connection) throw CancellationException()
        if (status.optString("app") != "lilacanime-server") throw PlaybackFailure("LilacAnime 서버가 아닌 것 같습니다. 주소를 확인하세요.")
        if (!status.optBoolean("ready")) throw PlaybackFailure("서버 설정이 끝나지 않았습니다. " + status.optJSONArray("problems")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } }.orEmpty())
        if (password.isEmpty()) throw PlaybackFailure("비밀번호를 입력하세요.")
        val login = call(base, "/api/login", "POST", JSONObject().put("password", password))
        if (generation != connection) throw CancellationException()
        val token = login.optString("token").ifEmpty { throw PlaybackFailure("서버가 토큰을 주지 않았습니다.") }
        SecureKeys.save(token, TOKEN)
        // What was synced with another server says nothing about this one: a new address starts over.
        if (server != base) { rev = 0; snapshot = emptyMap() }
        server = base; connected = true; state = "${base}에 연결됨"; persist()
        run()
    }
    fun disconnect(message: String = "연결을 끊었습니다.") {
        connection++; again = false
        runCatching { SecureKeys.save("", TOKEN) }
        timer?.cancel(); timer = null; due = Long.MAX_VALUE
        connected = false; state = message
    }

    suspend fun run() {
        val library = library ?: return
        if (!connected) return
        if (running) { again = true; return }
        val generation = connection
        running = true; lastRun = System.currentTimeMillis(); timer = null; due = Long.MAX_VALUE; state = "동기화하는 중..."
        try {
            val now = System.currentTimeMillis().toDouble()
            val local = DeviceSyncMapping.lists(library); val planned = DeviceSyncPlan.hashes(local)
            val outgoing = DeviceSyncPlan.plan(local, snapshot, now)
            val incoming = mutableListOf<JSONObject>()
            for (batch in outgoing.chunked(500)) {
                val reply = request("POST", "/api/sync", JSONObject().put("items", JSONArray(batch)))
                reply.optJSONArray("current")?.let { a -> (0 until a.length()).mapNotNullTo(incoming) { a.optJSONObject(it) } }
            }
            var since = rev; var more = true
            while (more) {
                val page = request("GET", "/api/sync?since=$since")
                page.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNullTo(incoming) { a.optJSONObject(it) } }
                since = DeviceSyncPlan.number(page.opt("rev"))?.toInt() ?: since; more = page.optBoolean("more")
            }
            // What was sent stands for the snapshot, so the server's own echo of it changes nothing here.
            val sent = snapshot.mapValues { it.value.toMutableMap() }.toMutableMap()
            for (item in outgoing) {
                val collection = item.optString("collection"); val id = item.optString("id")
                if (item.optBoolean("deleted")) sent[collection]?.remove(id) else planned[collection]?.get(id)?.let { sent.getOrPut(collection) { mutableMapOf() }[id] = it }
            }
            snapshot = apply(incoming, sent, library)
            rev = since; persist()
            state = "${server}에 연결됨 · 마지막 동기화 " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        } catch (error: CancellationException) { } catch (error: Throwable) {
            if (connected && generation == connection) state = "동기화하지 못했습니다: " + error.message
        } finally { running = false; if (again) { again = false; soon(0) } }
    }

    /** Takes the server's items in: one changed here while the sync ran is kept, unless the server's is a newer history entry. */
    private fun apply(incoming: List<JSONObject>, sent: MutableMap<String, MutableMap<String, String>>, library: LibraryStore): Map<String, Map<String, String>> {
        val working = DeviceSyncMapping.lists(library).mapValues { it.value.toMutableMap() }.toMutableMap()
        val changes = mutableMapOf<String, MutableMap<String, JSONObject?>>()
        for (item in incoming) {
            val collection = item.optString("collection"); val id = item.optString("id")
            if (collection !in DeviceSyncPlan.collections || id.isEmpty()) continue
            val mine = working[collection]?.get(id)
            val changedHere = mine?.let(DeviceSyncPlan::hash) != sent[collection]?.get(id)
            val newer = collection == "history" && mine != null && (DeviceSyncPlan.number(mine.opt("updated")) ?: 0.0) < (DeviceSyncPlan.number(item.opt("updated")) ?: 0.0)
            if (changedHere && !newer) continue
            if (item.optBoolean("deleted")) { working[collection]?.remove(id); sent[collection]?.remove(id); changes.getOrPut(collection) { mutableMapOf() }[id] = null }
            else item.optJSONObject("data")?.let { data -> working.getOrPut(collection) { mutableMapOf() }[id] = data; changes.getOrPut(collection) { mutableMapOf() }[id] = data }
        }
        if (changes.isEmpty()) return sent
        applying = true
        try { DeviceSyncMapping.apply(changes, library) } finally { applying = false }
        val written = DeviceSyncMapping.lists(library)
        for ((collection, ids) in changes) for (id in ids.keys) {
            val item = written[collection]?.get(id)
            if (item != null) sent.getOrPut(collection) { mutableMapOf() }[id] = DeviceSyncPlan.hash(item) else sent[collection]?.remove(id)
        }
        return sent
    }
    private fun persist() {
        val snap = JSONObject(); snapshot.forEach { (c, m) -> snap.put(c, JSONObject(m as Map<*, *>)) }
        runCatching { file.writeText(JSONObject().put("server", server).put("rev", rev).put("snapshot", snap).toString()) }
    }
}
