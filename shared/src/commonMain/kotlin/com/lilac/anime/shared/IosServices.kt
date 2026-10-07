package com.lilac.anime.shared
import kotlinx.coroutines.*
class IosServices {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val source = SourceRepository()
    private val discovery = SubtitleDiscovery()
    private val translator = CloudTranslator()
    private val skip = AniSkip()
    private val models = ModelRepository()
    fun browse(sourceKey: String, query: String, page: Int, filter: BrowseFilter, completion: (List<Anime>?, String?) -> Unit) {
        scope.launch { call(completion) { source.browse(sourceKey, query, page, filter) } }
    }
    fun detail(summary: Anime, sourceKey: String, completion: (SourceDetail?, String?) -> Unit) {
        scope.launch { call(completion) { source.detail(summary, sourceKey) } }
    }
    fun filters(sourceKey: String, completion: (SourceFilters?, String?) -> Unit) { scope.launch { call(completion) { source.filters(sourceKey) } } }
    fun top(period: String, completion: (List<Anime>?, String?) -> Unit) { scope.launch { call(completion) { source.top(period) } } }
    fun schedule(week: Int, completion: (List<Anime>?, String?) -> Unit) { scope.launch { call(completion) { source.schedule(week) } } }
    fun findSubtitles(provider: String, title: String, episode: Int, episodeKey: String, anilistId: Int, completion: (List<SubtitleAsset>?, String?) -> Unit) {
        scope.launch { call(completion) { discovery.search(provider, title, episode, episodeKey, anilistId) } }
    }
    fun translate(content: String, extension: String, config: TranslationConfig, progress: (Int, Int) -> Unit, partial: (String) -> Unit, completion: (String?, String?) -> Unit) {
        scope.launch { call(completion) {
            val lines = SubtitleTools.lines(content, extension)
            require(lines.isNotEmpty()) { "번역할 자막이 없습니다." }
            val translated = mutableListOf<String>()
            for (batch in lines.chunked(16)) {
                ensureActive()
                translated += translator.translate(batch, config)
                progress(translated.size, lines.size)
                partial(SubtitleTools.replace(content, extension, translated + lines.drop(translated.size)))
            }
            SubtitleTools.replace(content, extension, translated)
        } }
    }
    fun skipSegments(anilistId: Int, malId: Int, episode: Int, duration: Int, completion: (List<SkipSegment>?, String?) -> Unit) {
        scope.launch { call(completion) { skip.segments(anilistId, malId, episode, duration) } }
    }
    private suspend fun <T> call(completion: (T?, String?) -> Unit, block: suspend () -> T) {
        try { completion(block(), null) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { completion(null, error.message ?: "요청을 완료할 수 없습니다.") }
    }
    fun searchModels(query: String, completion: (List<ModelSearchResult>?, String?) -> Unit) {
        scope.launch { call(completion) { models.search(query) } }
    }
    fun modelFiles(repo: String, completion: (List<ModelFile>?, String?) -> Unit) {
        scope.launch { call(completion) { models.files(repo) } }
    }
    fun cancel() { scope.coroutineContext.cancelChildren() }
    fun close() { scope.cancel(); source.close(); discovery.close(); translator.close(); skip.close(); models.close() }
}
