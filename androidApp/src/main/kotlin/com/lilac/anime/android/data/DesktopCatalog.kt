package com.lilac.anime.android.data

import android.content.Context
import androidx.compose.runtime.*
import com.lilac.anime.android.subtitle.SubtitleFiles
import com.lilac.anime.shared.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CastCharacter(val name: String, val native: String, val first: String, val last: String, val gender: String) {
    val shared get() = AnimeCharacter(name, native, first, last, gender)
}

/** A work's Korean title and what came with it (iOS CatalogName). */
@Serializable
data class CatalogName(
    val korean: String, val english: String, val overview: String, val aliases: List<String>, val cast: List<CastCharacter>,
    val anilist: Int, val mal: Int, val updated: Long, val credential: String, val titleLookupRevision: Int? = null, val castPrepared: Boolean? = null,
) {
    fun isFresh(credential: String, cast: Boolean, bulk: Boolean = false, now: Long = System.currentTimeMillis()) =
        this.credential == credential && (korean.isNotEmpty() || titleLookupRevision == 2) &&
            now - updated < (if (bulk) 30L else 7L) * 86_400_000 && (!cast || castPrepared == true || this.cast.isNotEmpty())
}

/** DesktopTitleRules.key, worked out once per title (thousands of titles are compared on each search). */
object TitleKeys {
    private val cache = ConcurrentHashMap<String, String>()
    fun key(title: String): String = cache[title] ?: DesktopTitleRules.key(title).also { if (cache.size > 100_000) cache.clear(); cache[title] = it }
}

/** iOS DesktopCatalog: each source's whole list and the Korean titles found for its works (Wikidata in bulk, then TMDB,
 *  AniList), kept on disk; titles shown in Korean, searches matched in any language. */
object DesktopCatalog {
    var names by mutableStateOf(emptyMap<String, CatalogName>()); private set
    var catalogs by mutableStateOf(emptyMap<String, List<SavedAnime>>()); private set
    var status by mutableStateOf("대기"); private set
    var source by mutableStateOf(""); private set
    var running by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    private lateinit var directory: File
    private lateinit var defaults: android.content.SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val service by lazy { IosServices() }
    private var job: Job? = null
    private var retry: Job? = null
    private var generation = 0L
    private val lookups = mutableMapOf<String, Deferred<Boolean>>()
    private var nextLookup = 0L
    private val failures = mutableMapOf<String, Pair<String, Double>>()
    private var loaded = false
    private var loading: Deferred<Unit>? = null
    private val writes = mutableMapOf<String, Job>()

    fun init(context: Context) {
        directory = File(context.filesDir, "DesktopCatalog").apply { mkdirs() }
        defaults = context.getSharedPreferences("lilac", Context.MODE_PRIVATE)
    }

    suspend fun load() {
        if (loaded) return
        loading?.let { it.await(); return }
        val task = scope.async {
            val (savedNames, savedCatalogs) = withContext(Dispatchers.IO) {
                val n = runCatching { json.decodeFromString(MapSerializer(String.serializer(), CatalogName.serializer()), File(directory, "names.json").readText()) }.getOrDefault(emptyMap())
                val c = listOf("linkkf", "ohli24", "linkani", "animenosub", "reanime", "miruro").mapNotNull { key ->
                    runCatching { key to json.decodeFromString(ListSerializer(SavedAnime.serializer()), File(directory, "$key.json").readText()) }.getOrNull()
                }.toMap()
                n to c
            }
            names = savedNames + names; catalogs = savedCatalogs + catalogs; loaded = true
        }
        loading = task; task.await()
    }

    /** The title in the chosen language: Korean when found, English when asked, else the site's. */
    fun title(anime: Anime, source: String, language: String?): String {
        val cached = names["$source:${anime.id}"]
        if (language == "ko") cached?.korean?.takeIf { it.isNotEmpty() }?.let { return it }
        if (language == "en") return cached?.english?.takeIf { it.isNotEmpty() } ?: anime.english.ifEmpty { anime.title }
        return anime.title
    }
    fun record(anime: SavedAnime) = names[anime.id]
    fun record(anime: Anime, source: String) = names["$source:${anime.id}"]

    /** The Korean title (and the cast when asked) found for one work, at most one lookup a second outside the bulk run. */
    suspend fun enrich(anime: Anime, source: String, cast: Boolean = false, force: Boolean = false, bulk: Boolean = false): Boolean {
        load()
        val key = "$source:${anime.id}"
        val credential = SubtitleFiles.key(SecureKeys.load("tmdb"))
        if (!force && names[key]?.isFresh(credential, cast, bulk) == true) return true
        lookups[key]?.let { pending ->
            val success = pending.await()
            if (!success || !cast || names[key]?.castPrepared == true || names[key]?.cast?.isNotEmpty() == true) return success
        }
        val wait = if (bulk) 0L else maxOf(0L, nextLookup - System.currentTimeMillis())
        if (!bulk) nextLookup = System.currentTimeMillis() + wait + 1000
        val task = scope.async {
            try {
                if (wait > 0) delay(wait)
                val result = service.await<DesktopMetadata> { desktopMetadata(anime, SecureKeys.load("tmdb"), cast, bulk, it) }.getOrNull() ?: return@async false
                if (result.titleLookupFailure.isNotEmpty() && result.korean.isEmpty()) {
                    failures[key] = result.titleFailureCode to result.titleRetryAfterMs / 1000.0
                    error = result.titleLookupFailure; return@async false
                }
                failures.remove(key)
                val old = names[key]
                names = names + (key to CatalogName(result.korean.ifEmpty { old?.korean ?: "" }, result.english, result.overview.ifEmpty { old?.overview ?: "" }, result.aliases,
                    if (result.characters.isEmpty()) old?.cast ?: emptyList() else result.characters.map { CastCharacter(it.name, it.native, it.first, it.last, it.gender) },
                    result.anilistId, result.malId, System.currentTimeMillis(), credential, 2, cast || old?.castPrepared == true))
                saveNames(); true
            } finally { lookups.remove(key) }
        }
        lookups[key] = task
        return task.await()
    }

    suspend fun search(query: String, source: String): List<Anime> {
        load()
        val catalog = catalogs[source].orEmpty(); val known = names
        return withContext(Dispatchers.Default) {
            val wanted = TitleKeys.key(query)
            if (wanted.isEmpty()) return@withContext emptyList()
            catalog.filter { saved ->
                ensureActive()
                val name = known[saved.id]
                (listOf(saved.title, saved.anime.native, saved.anime.english, saved.anime.romaji, name?.korean ?: "", name?.english ?: "") + (name?.aliases ?: emptyList()))
                    .any { TitleKeys.key(it).contains(wanted) }
            }.map { it.anime }
        }
    }
    /** The other-language names already known for titles containing the query (main.cjs lookupTitleVariants). */
    suspend fun variants(query: String): List<String> {
        load()
        val known = names
        return withContext(Dispatchers.Default) {
            val key = TitleKeys.key(query)
            if (key.isEmpty()) return@withContext emptyList()
            val korean = TitleCandidates.isKorean(query)
            val found = mutableListOf<String>()
            for (name in known.values) {
                val from = if (korean) name.korean else name.english; val to = if (korean) name.english else name.korean
                if (from.isNotEmpty() && to.isNotEmpty() && to !in found && TitleKeys.key(from).contains(key)) found += to
            }
            found
        }
    }

    fun failure(source: String) = if (this.source == source) error else null
    fun progress(source: String) = if (this.source == source || this.source.isEmpty()) status else "저장된 목록 · ${catalogs[source]?.size ?: 0}개"
    fun needsRefresh(source: String): Boolean {
        if (defaults.getInt("catalog-metadata-revision:$source", 0) < 2) return true
        val updated = defaults.getLong("catalog-updated:$source", 0L)
        return updated == 0L || System.currentTimeMillis() - updated >= 86_400_000
    }

    /** The whole list gathered page by page (kept as it comes), the Wikidata index applied, then TMDB six at a time. */
    fun start(source: String, refresh: Boolean = false, titlesOnly: Boolean = false) {
        stop(); this.source = source; running = true; error = null
        val token = ++generation
        job = scope.launch {
            load()
            try {
                var gathered = if (refresh) emptyList() else catalogs[source].orEmpty()
                var page = 1
                val seen = mutableSetOf<String>()
                while (!titlesOnly && isActive && token == generation) {
                    status = "전체 목록 수집 · ${gathered.size}개"
                    val result = service.await<List<Anime>> { browse(source, "", page, AnimeSnapshot.filter("", "", "", ""), it) }.getOrThrow()
                    if (token != generation) return@launch
                    if (result.isEmpty() || !seen.add(result.joinToString("|") { it.id })) break
                    gathered = merge(gathered, result.map { SavedAnime.of(it, source) })
                    catalogs = catalogs + (source to gathered)
                    persist("$source.json") { json.encodeToString(ListSerializer(SavedAnime.serializer()), gathered) }
                    page++; delay(400)
                }
                if (token != generation) return@launch
                if (seen.isNotEmpty()) defaults.edit().putInt("catalog-metadata-revision:$source", 2).putLong("catalog-updated:$source", System.currentTimeMillis()).apply()
                status = "Wikidata 한국어 제목 일괄 적용"
                service.await<String> { catalogKoreanIndex(it) }.getOrNull()?.let { bulk ->
                    val credential = SubtitleFiles.key(SecureKeys.load("tmdb"))
                    val missing = gathered.filter { names[it.id]?.korean.isNullOrEmpty() }
                    val found = withContext(Dispatchers.Default) {
                        val titles = runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), bulk) }.getOrDefault(emptyMap())
                        missing.mapNotNull { item ->
                            val anime = item.anime
                            val id = anime.anilistId ?: return@mapNotNull null
                            val korean = titles[id.toString()] ?: return@mapNotNull null
                            item.id to CatalogName(DesktopTitleRules.seasonal(korean, item.title, anime.format), anime.english, "", listOf(korean), emptyList(), id, anime.malId ?: 0, System.currentTimeMillis(), credential)
                        }.toMap()
                    }
                    if (token != generation) return@launch
                    if (found.isNotEmpty()) { names = names + found.filterKeys { names[it]?.korean.isNullOrEmpty() }; saveNames() }
                }
                if (SecureKeys.load("tmdb").isEmpty()) { status = "${knownCount(gathered)}/${gathered.size}개 한국어 제목 · 나머지는 TMDB 키를 설정하면 찾습니다."; return@launch }
                val pending = gathered.filter { !TitleCandidates.isKorean(it.title) && names[it.id]?.korean.isNullOrEmpty() }
                for (batch in pending.chunked(6)) {
                    if (token != generation) return@launch
                    status = "한국어 제목 · ${knownCount(gathered)}/${gathered.size}"
                    val success = batch.map { item -> async { enrich(item.anime, source, bulk = true) } }.awaitAll().all { it }
                    if (!success) {
                        val failed = batch.mapNotNull { failures[it.id] }
                        val wait = maxOf(if (failed.any { it.first == "auth" }) 1800.0 else 60.0, failed.maxOfOrNull { it.second } ?: 0.0)
                        status = "${knownCount(gathered)}/${gathered.size}개 한국어 제목 · ${error ?: "제목 조회 실패"}, ${Math.ceil(wait / 60).toInt()}분 뒤 재시도"
                        retry = scope.launch { delay((wait * 1000).toLong()); if (generation == token && SecureKeys.load("tmdb").isNotEmpty()) start(source, titlesOnly = true) }
                        return@launch
                    }
                    delay(200)
                }
                if (token == generation) status = "완료 · ${knownCount(gathered)}/${gathered.size}개 한국어 제목"
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) {
                if (token == generation) { error = failure.message; status = "중단 · 다시 시작하면 저장된 목록에서 이어집니다." }
            } finally { if (token == generation) running = false }
        }
    }
    private fun knownCount(values: List<SavedAnime>) = values.count { names[it.id]?.korean?.isNotEmpty() == true || TitleCandidates.isKorean(it.title) }
    private fun merge(existing: List<SavedAnime>, incoming: List<SavedAnime>): List<SavedAnime> {
        val values = existing.toMutableList(); val indices = existing.withIndex().associate { it.value.id to it.index }.toMutableMap()
        for (item in incoming) { val at = indices[item.id]; if (at != null) values[at] = item else { indices[item.id] = values.size; values += item } }
        return values
    }
    fun stop() { generation++; job?.cancel(); job = null; retry?.cancel(); retry = null; running = false; status = "일시 중지" }
    fun clear() { stop(); lookups.values.forEach { it.cancel() }; lookups.clear(); names = emptyMap(); catalogs = emptyMap(); directory.deleteRecursively(); directory.mkdirs() }

    /** The whole catalog and the names are large: written off the main thread, at most every 2 s. */
    private fun persist(name: String, encode: () -> String) {
        writes[name]?.cancel()
        writes[name] = scope.launch { delay(2000); val text = withContext(Dispatchers.Default) { encode() }; withContext(Dispatchers.IO) { runCatching { File(directory, name).writeText(text) }.onFailure { error = it.message } } }
    }
    private fun saveNames() { val value = names; persist("names.json") { json.encodeToString(MapSerializer(String.serializer(), CatalogName.serializer()), value) } }
}

/** iOS AnimeDisplayTitle: a work's title in the chosen language, its Korean title looked up when first shown. */
@Composable
fun displayTitle(anime: Anime, source: String? = null): String {
    val library = com.lilac.anime.android.LocalApp.current.library
    val from = source ?: anime.source.ifEmpty { library.preferences.source }
    val language = library.preferences.titleLanguage
    LaunchedEffect(anime.id, from, language) { if (language != "original") DesktopCatalog.enrich(anime, from) }
    return DesktopCatalog.title(anime, from, language)
}
