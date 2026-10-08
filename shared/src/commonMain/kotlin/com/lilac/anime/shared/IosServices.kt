package com.lilac.anime.shared
import kotlinx.coroutines.*
class IosServices {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val source = SourceRepository()
    private val discovery = SubtitleDiscovery()
    private val translator = CloudTranslator()
    private val skip = AniSkip()
    private val models = ModelRepository()
    private val tmdb = TmdbTitleResolver()
    private val metadata = DesktopMetadataRepository()
    fun catalogKoreanIndex(completion: (String?, String?) -> Unit) { scope.launch { call(completion) { metadata.wikidataIndex() } } }
    fun desktopMetadata(anime: Anime, credential: String, includeCast: Boolean, completion: (DesktopMetadata?, String?) -> Unit) {
        scope.launch { call(completion) { metadata.resolve(anime, credential, includeCast) } }
    }
    fun koreanTitle(titles: List<String>, credential: String, completion: (String?, String?) -> Unit) {
        scope.launch { call(completion) { tmdb.resolve(titles, credential) } }
    }
    fun testTmdb(credential: String, completion: (String?, String?) -> Unit) {
        scope.launch { call(completion) { tmdb.test(credential) } }
    }
    fun browse(sourceKey: String, query: String, page: Int, filter: BrowseFilter, completion: (List<Anime>?, String?) -> Unit) {
        scope.launch { call(completion) { source.browse(sourceKey, query, page, filter) } }
    }
    fun detail(summary: Anime, sourceKey: String, completion: (SourceDetail?, String?) -> Unit) {
        scope.launch { call(completion) { source.detail(summary, sourceKey) } }
    }
    fun filters(sourceKey: String, completion: (SourceFilters?, String?) -> Unit) { scope.launch { call(completion) { source.filters(sourceKey) } } }
    fun desktopStreams(sourceKey: String, animeId: String, number: Int, url: String, completion: (List<DesktopPlaybackStream>?, String?) -> Unit) {
        scope.launch { call(completion) { source.desktopStreams(sourceKey, animeId, number, url) } }
    }
    fun sourceSections(sourceKey: String, completion: (List<SourceSection>?, String?) -> Unit) { scope.launch { call(completion) { source.sourceSections(sourceKey) } } }
    fun sourceSchedule(sourceKey: String, day: Int, completion: (List<Anime>?, String?) -> Unit) { scope.launch { call(completion) { source.sourceSchedule(sourceKey, day) } } }
    fun sourceExtras(anime: Anime, completion: (SourceExtras?, String?) -> Unit) { scope.launch { call(completion) { source.extras(anime) } } }
    fun top(period: String, completion: (List<Anime>?, String?) -> Unit) { scope.launch { call(completion) { source.top(period) } } }
    fun schedule(week: Int, completion: (List<Anime>?, String?) -> Unit) { scope.launch { call(completion) { source.schedule(week) } } }
    fun subtitleMakers(title: String, completion: (List<SubtitleMaker>?, String?) -> Unit) { scope.launch { call(completion) { discovery.makers(title) } } }
    fun makerSubtitles(title: String, episode: Int, episodeKey: String, website: String, completion: (List<SubtitleAsset>?, String?) -> Unit) {
        scope.launch { call(completion) { discovery.makerSubtitles(title, episode, episodeKey, website) } }
    }
    fun titleVariants(query: String, credential: String, completion: (List<String>?, String?) -> Unit) { scope.launch { call(completion) { tmdb.variants(query, credential) } } }
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
    fun translateLines(lines: List<String>, config: TranslationConfig, completion: (List<String>?, String?) -> Unit) {
        scope.launch { call(completion) { translator.translate(lines, config) } }
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
    fun close() { scope.cancel(); source.close(); discovery.close(); translator.close(); skip.close(); models.close(); tmdb.close(); metadata.close() }
}
