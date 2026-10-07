package com.lilac.anime.shared

import kotlinx.coroutines.*

/** Callback facade keeps Swift independent of Kotlin suspend interop tooling. */
class IosCatalog {
    private val repository = LinkkfRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var request: Job? = null

    fun loadCatalog(query: String, page: Int, completion: (List<Anime>?, String?) -> Unit) {
        request?.cancel()
        request = scope.launch {
            try {
                completion(if (query.isBlank()) repository.home(page) else repository.search(query, page), null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { completion(null, error.message ?: "목록을 불러올 수 없습니다.") }
        }
    }

    fun loadDetail(id: String, completion: (Anime?, List<EpisodeServer>?, String?) -> Unit) {
        request?.cancel()
        request = scope.launch {
            try {
                coroutineScope {
                    val anime = async { repository.detail(id) }
                    val servers = async { repository.servers(id) }
                    completion(anime.await(), servers.await(), null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { completion(null, null, error.message ?: "상세 정보를 불러올 수 없습니다.") }
        }
    }

    fun cancel() { request?.cancel() }
    fun close() { scope.cancel(); repository.close() }
}
