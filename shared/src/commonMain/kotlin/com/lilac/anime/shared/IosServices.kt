package com.lilac.anime.shared
import kotlinx.coroutines.*
/** Swift calls in on the main thread and gets its callbacks there, but the work itself (parsing pages, ranking a blog's
 *  posts for subtitles) runs here: on the main thread it froze the player until iOS killed the app. One worker at a
 *  time keeps the repositories' caches single-threaded, as they were on the main thread. */
@OptIn(ExperimentalCoroutinesApi::class)
internal val kotlinWork: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

class IosServices internal constructor(private val worker: CoroutineDispatcher) {
    constructor() : this(kotlinWork)
    // Enter the callback wrapper before dispatching work: cancellation before a queued
    // launch starts would otherwise leave Swift checked continuations suspended forever.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Each holds its own HTTP client; the app makes several of these at once (the player alone about seven), and
    // most use one or two, so each is made when first used.
    private val sourceLazy = lazy { SourceRepository() }
    private val discoveryLazy = lazy { SubtitleDiscovery() }
    private val translatorLazy = lazy { CloudTranslator() }
    private val skipLazy = lazy { AniSkip() }
    private val modelsLazy = lazy { ModelRepository() }
    private val tmdbLazy = lazy { TmdbTitleResolver() }
    private val metadataLazy = lazy { DesktopMetadataRepository() }
    private val source by sourceLazy
    private val discovery by discoveryLazy
    private val translator by translatorLazy
    private val skip by skipLazy
    private val models by modelsLazy
    private val tmdb by tmdbLazy
    private val metadata by metadataLazy
    fun catalogKoreanIndex(completion: (String?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { metadata.wikidataIndex() } } }
    fun desktopMetadata(anime: Anime, credential: String, includeCast: Boolean, background: Boolean, completion: (DesktopMetadata?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { metadata.resolve(anime, credential, includeCast, background) } }
    }
    fun koreanTitle(titles: List<String>, credential: String, completion: (String?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { tmdb.desktopTitles(titles, credential, light = true).firstOrNull() } }
    }
    fun testTmdb(credential: String, completion: (String?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { tmdb.test(credential) } }
    }
    fun browse(sourceKey: String, query: String, page: Int, filter: BrowseFilter, completion: (List<Anime>?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.browse(sourceKey, query, page, filter) } }
    }
    fun detail(summary: Anime, sourceKey: String, completion: (SourceDetail?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.detail(summary, sourceKey) } }
    }
    fun filters(sourceKey: String, completion: (SourceFilters?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.filters(sourceKey) } } }
    fun catalog(sourceKey: String, query: String, page: Int, filter: BrowseFilter, completion: (SourceCatalogPage?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.catalog(sourceKey, page, filter, query) } }
    }
    fun desktopStreams(sourceKey: String, animeId: String, number: Int, url: String, completion: (List<DesktopPlaybackStream>?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.desktopStreams(sourceKey, animeId, number, url) } }
    }
    fun ohliPlayer(url: String, completion: (String?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.ohliPlayer(url) } } }
    fun ohliStream(playerUrl: String, userAgent: String, completion: (OhliDirectStream?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.ohliStream(playerUrl, userAgent) } } }
    fun desktopServers(sourceKey: String, url: String, number: Int, anilist: Int, completion: (List<DesktopPlaybackServer>?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.desktopServers(sourceKey, url, number, anilist) } }
    }
    fun sourceSections(sourceKey: String, completion: (List<SourceSection>?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.sourceSections(sourceKey) } } }
    fun weeklySchedule(sourceKey: String, day: Int, date: String, monday: String, completion: (WeeklyScheduleResult?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.weeklySchedule(sourceKey, day, date, monday) } } }
    fun sourceSchedule(sourceKey: String, day: Int, completion: (List<Anime>?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.sourceSchedule(sourceKey, day) } } }
    fun recordView(anime: Anime, completion: (SourceExtras?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.recordView(anime) } } }
    fun sourceExtras(anime: Anime, completion: (SourceExtras?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.extras(anime) } } }
    fun top(period: String, completion: (List<Anime>?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.top(period) } } }
    fun sourceUpdates(sourceKey: String, page: Int, completion: (List<Anime>?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.updates(sourceKey, page) } } }
    fun schedule(week: Int, completion: (List<Anime>?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { source.schedule(week) } } }
    fun episodeOffsets(anilistId: Int, title: String, completion: (List<Int>?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { discovery.offsets(anilistId, title) } } }
    fun subtitleMakers(title: String, completion: (List<SubtitleMaker>?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { discovery.makers(title) } } }
    fun makerSubtitles(title: String, episode: Int, episodeKey: String, website: String, anilistId: Int, completion: (List<SubtitleAsset>?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { discovery.makerSubtitles(title, episode, episodeKey, website, anilistId) } }
    }
    fun titleVariants(query: String, credential: String, completion: (List<String>?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { tmdb.variants(query, credential) } } }
    fun findSubtitles(provider: String, title: String, episode: Int, episodeKey: String, anilistId: Int, completion: (List<SubtitleAsset>?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { discovery.search(provider, title, episode, episodeKey, anilistId) } }
    }
    fun refreshSubtitles(provider: String, title: String, episode: Int, episodeKey: String, anilistId: Int, completion: (List<SubtitleAsset>?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { discovery.search(provider, title, episode, episodeKey, anilistId, fresh = true) } }
    }
    fun translate(content: String, extension: String, config: TranslationConfig, progress: (Int, Int) -> Unit, partial: (String) -> Unit, completion: (String?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) {
            val lines = SubtitleTools.lines(content, extension)
            require(lines.isNotEmpty()) { "번역할 자막이 없습니다." }
            val translated = mutableListOf<String>()
            for (batch in lines.chunked(16)) {
                ensureActive()
                translated += translator.translate(batch, config)
                val preview = SubtitleTools.replace(content, extension, translated + lines.drop(translated.size))
                withContext(Dispatchers.Main) { progress(translated.size, lines.size); partial(preview) }
            }
            SubtitleTools.replace(content, extension, translated)
        } }
    }
    fun cloudModels(config: TranslationConfig, completion: (List<String>?, String?) -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { translator.models(config) } } }
    fun clearTranslationCache() { if (translatorLazy.isInitialized()) scope.launch(kotlinWork) { translator.clearCache() } }
    fun translateLines(lines: List<String>, config: TranslationConfig, completion: (List<String>?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { translator.translate(lines, config) } }
    }
    fun skipSegments(anilistId: Int, malId: Int, episode: Int, duration: Int, completion: (List<SkipSegment>?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { skip.segments(anilistId, malId, episode, duration) } }
    }
    private suspend fun <T> call(completion: (T?, String?) -> Unit, block: suspend () -> T) {
        try { completion(withContext(worker) { block() }, null) }
        catch (error: CancellationException) { completion(null, "요청을 취소했습니다."); throw error }
        catch (error: Exception) { completion(null, error.message ?: "요청을 완료할 수 없습니다.") }
    }
    fun searchModels(query: String, completion: (List<ModelSearchResult>?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { models.search(query) } }
    }
    fun modelFiles(repo: String, completion: (List<ModelFile>?, String?) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { call(completion) { models.files(repo) } }
    }
    fun cancel() { scope.coroutineContext.cancelChildren() }
    fun close() {
        scope.cancel()
        if (sourceLazy.isInitialized()) source.close()
        if (discoveryLazy.isInitialized()) discovery.close()
        if (translatorLazy.isInitialized()) translator.close()
        if (skipLazy.isInitialized()) skip.close()
        if (modelsLazy.isInitialized()) models.close()
        if (tmdbLazy.isInitialized()) tmdb.close()
        if (metadataLazy.isInitialized()) metadata.close()
    }
}
