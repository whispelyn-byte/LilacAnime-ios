package com.lilac.anime.android.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.shared.*
import kotlinx.coroutines.*
import java.util.Calendar

/** iOS CatalogModel: a source's list (browse, search, top, season, schedule, or the full catalog with filters), page by page. */
class CatalogModel {
    var items by mutableStateOf(emptyList<Anime>())
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var canLoadMore by mutableStateOf(true)
    var filters by mutableStateOf<SourceFilters?>(null)
    var filterError by mutableStateOf<String?>(null)
    var filtersLoading by mutableStateOf(false)
    var searchNote by mutableStateOf<String?>(null)

    var query = ""
    var source = "reanime"
    var mode = "browse"
    var genre = ""; var year = ""; var format = ""; var status = ""; var season = ""; var studio = ""; var sort = ""
    var extras: Map<String, String> = emptyMap()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val service = IosServices()
    private val filterService = IosServices()
    private var job: Job? = null
    private var filterJob: Job? = null
    private var page = 1
    private var catalogSignature: String? = null

    fun loadFilters() {
        filters = null; filterError = null; filtersLoading = true
        val requested = source
        filterJob?.cancel()
        filterJob = scope.launch {
            val result = filterService.await<SourceFilters> { filters(requested, it) }
            if (source != requested) return@launch
            filters = result.getOrNull(); filterError = result.exceptionOrNull()?.message; filtersLoading = false
        }
    }

    fun load(reset: Boolean = true) {
        if (reset) { job?.cancel(); service.cancel(); page = 1; items = emptyList(); canLoadMore = true; catalogSignature = null }
        else if (loading || !canLoadMore) return
        loading = true; error = null; searchNote = null
        val requestPage = page
        job = scope.launch {
            if (mode == "catalog") {
                val result = service.await<SourceCatalogPage> {
                    catalog(source, query, requestPage, AnimeSnapshot.extendedFilter(genre, year, season, format, sort, extras), it)
                }
                loading = false
                result.onSuccess { value ->
                    val known = items.map { it.id }.toSet()
                    items = items + value.items.filter { it.id !in known }
                    canLoadMore = value.hasMore && value.signature != catalogSignature
                    catalogSignature = value.signature; page += 1
                }.onFailure { error = it.message }
                return@launch
            }
            val typed = query.trim()
            val result: Result<List<Anime>> = when {
                mode == "top" -> service.await { browse(source, "", requestPage, AnimeSnapshot.sortedFilter("", "", "", "", "", "", "popular"), it) }
                mode == "schedule" -> service.await { sourceSchedule(source, (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7, it) }
                // Animenosub's and Miruro's own searches are English only: a Korean query returns their newest posts instead.
                englishOnly(source, typed) -> Result.success(emptyList())
                else -> service.await { browse(source, query, requestPage, AnimeSnapshot.sortedFilter(genre, year, season, format, status, studio, sort), it) }
            }
            loading = false
            result.onSuccess { values ->
                val known = items.map { it.id }.toMutableSet()
                items = items + values.filter { known.add(it.id) }
                canLoadMore = values.isNotEmpty() && mode in listOf("browse", "top", "season", "pv", "movie", "adult"); page += 1
            }.onFailure { error = it.message }
            if (requestPage == 1 && typed.isNotEmpty() && mode == "browse" && listOf(genre, year, format, status, season, studio).all { it.isEmpty() }) {
                searchVariants(typed)
            }
        }
    }

    /** The query's other-language titles from TMDB, searched six at a time; new results are added (iOS searchVariants).
     *  The names index (DesktopCatalog) comes with stage 4. */
    private suspend fun searchVariants(typed: String) {
        val credential = SecureKeys.load("tmdb")
        if (credential.isEmpty()) return
        if (items.isEmpty()) searchNote = "다른 언어 제목으로 찾는 중..."
        val korean = TitleCandidates.isKorean(typed)
        val remote = service.await<List<String>> { titleVariants(typed, credential, it) }.getOrNull().orEmpty()
        val usable = remote.filter { TitleCandidates.isKorean(it) != korean && !englishOnly(source, it) }.distinct().take(30)
        if (usable.isEmpty()) { searchNote = null; return }
        var added = 0
        for (chunk in usable.chunked(6)) {
            val lists = coroutineScope {
                chunk.map { text -> async { service.await<List<Anime>> { browse(source, text, 1, AnimeSnapshot.fullFilter("", "", "", "", "", ""), it) }.getOrNull().orEmpty() } }.awaitAll()
            }
            val known = items.map { it.id }.toMutableSet()
            val fresh = lists.flatten().filter { known.add(it.id) }
            items = items + fresh; added += fresh.size
        }
        searchNote = if (added > 0) "다른 언어 제목 " + (if (usable.size > 3) usable.take(3).joinToString(", ") + " 외 ${usable.size - 3}개" else usable.joinToString(", ")) + " 포함" else null
    }

    fun close() { scope.cancel(); service.close(); filterService.close() }

    companion object {
        fun englishOnly(source: String, query: String) = source in listOf("animenosub", "miruro") && TitleCandidates.isKorean(query)
    }
}
