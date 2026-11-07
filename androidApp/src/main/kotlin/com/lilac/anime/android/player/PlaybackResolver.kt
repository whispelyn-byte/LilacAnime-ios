package com.lilac.anime.android.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Message
import android.webkit.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lilac.anime.android.data.await
import com.lilac.anime.shared.DesktopPlaybackServer
import com.lilac.anime.shared.DesktopPlaybackStream
import com.lilac.anime.shared.IosServices
import com.lilac.anime.shared.OhliDirectStream
import com.lilac.anime.shared.CrossSiteTrack
import com.lilac.anime.shared.PROVIDER_SUBTITLE_AGENT
import com.lilac.anime.android.subtitle.SubtitleFiles
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener

/** The address every page and stream is asked with (the iOS app's, so the sites answer as they do there). */
const val PLAYER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1"
/** 애니24 is asked as desktop asks it (main.cjs LINKKF_UA): its player answers a phone with another page. */
const val OHLI_AGENT = PROVIDER_SUBTITLE_AGENT

/** iOS PlaybackResolver: finds an episode's streams. Miruro and 링크애니 are read by the shared code; RE:Anime and
 *  Animenosub list servers whose pages are opened in a hidden, muted WebView that reports the media it requests;
 *  애니24's player is read directly, else its page is opened too. */
@SuppressLint("SetJavaScriptEnabled")
class PlaybackResolver(private val context: Context) {
    var streams by mutableStateOf(emptyList<ResolvedStream>()); private set
    var subtitles by mutableStateOf(emptyList<RemoteSubtitle>()); private set
    var servers by mutableStateOf(emptyList<DesktopPlaybackServer>()); private set
    var serverLabel by mutableStateOf(""); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    val webView: WebView = WebView(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val desktop = IosServices()
    private var generation = 0L
    private var timeout: Job? = null
    private var serverRace: Job? = null
    private var selected: PlaybackResolver? = null
    private var selectedWatch: Job? = null
    private var currentItem: PlaybackItem? = null
    private var serverCandidates = mutableListOf<DesktopPlaybackServer>()
    private var preferRaw = false
    private var ohliPlayer: String? = null
    private val pendingOhli = mutableListOf<OhliCandidate>()
    private val ohliCaptionsTried = mutableSetOf<String>()
    private val candidateJobs = mutableMapOf<String, Job>()
    private var lastOhliCandidate: Job? = null
    private var flixKey: String? = null
    private var webPlaybackActive = false
    private val scripts = mutableListOf<androidx.webkit.ScriptHandler>()
    var silent = false
        set(value) { field = value; installScripts(currentItem?.anime?.source == "ohli24") }
    /** The WebView a person sees when they open the web player (the winning server's). */
    val playbackWebView: WebView get() = selected?.webView ?: webView

    private data class OhliCandidate(val url: String, val frame: String?, val kind: String, val manifest: String?)

    init {
        webView.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            userAgentString = PLAYER_AGENT; setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme !in setOf("http", "https")
            override fun onPageFinished(view: WebView, url: String) { pageFinished(url) }
        }
        // Pages' new windows are refused (desktop: popups blocked in every internal web view).
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "lilacNative", setOf("*")) { _: WebView, message: WebMessageCompat, _: Uri, isMainFrame: Boolean, _: JavaScriptReplyProxy ->
                message.data?.let { received(it, isMainFrame) }
            }
        }
        installScripts(false)
    }

    private fun asset(name: String): String? = runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()

    /** The scripts every frame gets as it starts: the bridge iOS's scripts post through, the media capture, the muting
     *  of a hidden page, 애니24's capture and FlixCloud's discovery and bootstrap. */
    private fun installScripts(ohli: Boolean) {
        scripts.forEach { it.remove() }; scripts.clear()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val list = mutableListOf(BRIDGE)
        if (silent) list += MUTE
        list += CAPTURE
        if (ohli) asset("ohli-capture.js")?.let { list += it }
        asset("flix-discovery.js")?.let { list += atDocumentEnd(it) }
        asset("flix-bootstrap.js")?.let { bootstrap -> list += atDocumentEnd(FLIX_START.replace("BOOTSTRAP", bootstrap)) }
        for (script in list) scripts += WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
    }
    private fun atDocumentEnd(script: String) = "(()=>{const run=()=>{$script\n};if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run);else run();})();"

    fun resolve(item: PlaybackItem, preferRaw: Boolean = false, preferred: String? = null) {
        cancel(); currentItem = item; streams = emptyList(); subtitles = emptyList(); servers = emptyList(); serverCandidates.clear()
        error = null; loading = true; this.preferRaw = preferRaw
        installScripts(item.anime.source == "ohli24")
        webView.settings.userAgentString = if (item.anime.source == "ohli24") OHLI_AGENT else PLAYER_AGENT
        val token = generation
        item.directURL?.let { direct ->
            if (isFileURL(direct) || urlExtension(direct) in setOf("m3u8", "mp4", "mkv", "webm")) {
                streams = listOf(ResolvedStream("영상", direct, "", emptyMap())); loading = false; return
            }
        }
        val source = item.anime.source
        when {
            source == "miruro" || source == "linkani" -> scope.launch {
                val result = desktop.await<List<DesktopPlaybackStream>> { desktopStreams(source, item.anime.anime.id, item.number, item.watchURL, it) }
                if (token != generation) return@launch
                loading = false
                streams = result.getOrNull().orEmpty().map { stream ->
                    ResolvedStream(stream.label, stream.url, stream.referer, stream.headers,
                        subtitles = stream.subtitles.map { RemoteSubtitle(it.label, it.url, it.language, stream.headers) }, burnedKorean = stream.burnedKorean)
                }
                subtitles = streams.firstOrNull()?.subtitles.orEmpty()
                if (streams.isEmpty()) error = result.exceptionOrNull()?.message ?: "이 회차의 영상 서버를 찾지 못했습니다."
            }
            source == "reanime" || source == "animenosub" -> scope.launch {
                val result = desktop.await<List<DesktopPlaybackServer>> { desktopServers(source, item.watchURL, item.number, item.anime.anime.anilistId ?: 0, it) }
                if (token != generation) return@launch
                servers = result.getOrNull().orEmpty()
                fun rank(server: DesktopPlaybackServer) = when {
                    server.label == preferred -> 0; server.kind == (if (preferRaw) "raw" else "sub") -> 1
                    server.kind == "sub" -> 2; server.kind == "raw" -> 3; else -> 4
                }
                serverCandidates = (if (source == "animenosub") servers.sortedBy(::rank) else servers).toMutableList()
                if (source == "reanime") {
                    if (serverCandidates.isEmpty()) {
                        loading = false; error = result.exceptionOrNull()?.message ?: "이 작품은 현재 RE:Anime에서 재생할 수 없습니다. 다른 콘텐츠 소스를 선택해주세요."
                        return@launch
                    }
                    raceServers(serverCandidates.toList(), item)
                } else loadNextServer(item.watchURL)
            }
            // ani.ohli24.com: the episode's player is named in its page data and read directly (desktop resolveOhliEpisode).
            source == "ohli24" && urlHost(item.watchURL) == "ani.ohli24.com" -> scope.launch {
                val player = desktop.await<String> { ohliPlayer(item.watchURL, it) }
                if (token != generation) return@launch
                val address = player.getOrNull()
                if (address == null) { loading = false; error = player.exceptionOrNull()?.message ?: "이 회차의 영상 플레이어를 찾지 못했습니다."; return@launch }
                ohliPlayer = address
                ohliDirect(address, token)
            }
            else -> loadPage(item.watchURL, 45)
        }
    }

    /** 다음 화 미리 준비 > 영상: what another resolver found for this episode, taken as this one's. */
    fun adopt(item: PlaybackItem, streams: List<ResolvedStream>, subtitles: List<RemoteSubtitle>, servers: List<DesktopPlaybackServer>, label: String) {
        cancel(); currentItem = item; serverCandidates.clear(); error = null; loading = false
        this.servers = servers; serverLabel = label; this.subtitles = subtitles; this.streams = streams
    }

    fun setWebPlaybackActive(active: Boolean) {
        webPlaybackActive = active
        val target = selected ?: this
        target.webPlaybackActive = active
        if (active) { target.timeout?.cancel(); target.webView.evaluateJavascript("window.__lilacFlixDiscovery?.finish()", null) }
        target.webView.evaluateJavascript("window.postMessage({lilacWebPlayback: $active}, '*')", null)
        if (active) { target.scripts.forEach { it.remove() }; target.scripts.clear() } else target.installScripts(target.currentItem?.anime?.source == "ohli24")
        if (active && target.webView.url.isNullOrEmpty()) currentItem?.watchURL?.let { target.webView.loadUrl(it) }
        else if (!active) target.webView.evaluateJavascript(PAUSE_ALL, null)
    }

    fun selectServer(server: DesktopPlaybackServer) {
        serverRace?.cancel(); serverRace = null
        clearSelected()
        generation++; webView.stopLoading(); timeout?.cancel(); streams = emptyList(); subtitles = emptyList(); error = null; loading = true
        serverCandidates = servers.filter { it.url != server.url }.toMutableList(); serverLabel = server.label
        val item = currentItem
        if (item != null && item.anime.source == "reanime") raceServers(listOf(server), item) else loadPage(server.url, 15)
    }

    private fun loadNextServer(fallback: String? = null) {
        timeout?.cancel()
        if (serverCandidates.isNotEmpty()) {
            val candidate = serverCandidates.removeAt(0); serverLabel = candidate.label
            loadPage(candidate.url, 15); return
        }
        if (fallback != null) { serverLabel = ""; loadPage(fallback, 45); return }
        loading = false; error = "모든 영상 서버 연결에 실패했습니다."
    }

    private fun raceServers(list: List<DesktopPlaybackServer>, item: PlaybackItem) {
        serverRace?.cancel(); serverCandidates.clear()
        val token = generation
        serverRace = scope.launch {
            val resolvers = list.map { PlaybackResolver(context) }
            try {
                val winner = firstAnswering(list.zip(resolvers)) { (server, resolver) -> readServer(server, item, resolver) }
                if (token != generation || !isActive) { winner.shutdown(); return@launch }
                selected = winner
                // The winner is kept: its subtitles can come after the first video address.
                selectedWatch = launch {
                    androidx.compose.runtime.snapshotFlow { winner.streams to winner.subtitles }.collect { (s, t) -> streams = s.take(1); subtitles = t }
                }
                serverLabel = winner.serverLabel; loading = false; error = null
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) {
                if (token != generation) return@launch
                serverLabel = ""
                if (item.anime.source == "reanime") { loading = false; error = failure.message } else { error = null; loadPage(item.watchURL, 45) }
            } finally { resolvers.forEach { if (it !== selected) it.shutdown() } }
        }
    }

    private fun clearSelected() { selectedWatch?.cancel(); selectedWatch = null; selected?.shutdown(); selected = null }

    private suspend fun readServer(server: DesktopPlaybackServer, item: PlaybackItem, resolver: PlaybackResolver): PlaybackResolver {
        resolver.currentItem = item; resolver.silent = true; resolver.serverLabel = server.label; resolver.loading = true
        resolver.loadPage(server.url, 30)
        val deadline = System.currentTimeMillis() + 30_000
        while (resolver.streams.isEmpty() && System.currentTimeMillis() < deadline) {
            if (!resolver.loading) break
            delay(100)
        }
        if (resolver.streams.isEmpty()) throw PlaybackFailure(resolver.error ?: "영상 주소를 찾지 못했습니다 (응답 시간 초과).")
        if (!urlHost(server.url).lowercase().contains("flixcloud.cc")) return resolver
        // As desktop: collect the master or the separate video/audio addresses, then wait for the player key.
        val readiness = FlixReadiness()
        while (true) {
            currentCoroutineContext().ensureActive()
            val key = resolver.evaluate("window.__pk || (()=>{try{return JSON.parse(window.__lilacReAnimeFlixResult||'{}').pk||''}catch(_){return ''}})()") as? String
            if (!key.isNullOrEmpty()) resolver.streams = resolver.streams.map { it.copy(manifestKey = key) }
            val choice = readiness.select(resolver.streams, System.currentTimeMillis() / 1000.0)
            if (choice != null) {
                val tracks = resolver.evaluate("JSON.stringify(window.__lilacFlixDiscovery?.finish() || [])") as? String
                runCatching {
                    val array = org.json.JSONArray(tracks ?: "[]")
                    for (i in 0 until array.length()) {
                        val track = array.getJSONObject(i)
                        val url = track.optString("url")
                        if (url.startsWith("http") && resolver.subtitles.none { it.url == url })
                            resolver.subtitles += RemoteSubtitle(track.optString("label").ifEmpty { track.optString("language").ifEmpty { "자막" } }, url, track.optString("language").ifEmpty { "und" }, choice.headers)
                    }
                }
                val readable = try { HlsData.readable(choice) } catch (failure: CancellationException) { throw failure } catch (_: Throwable) { true }
                if (!readable) throw PlaybackFailure("RE:Anime 서버의 영상을 읽을 수 없습니다. 다른 서버를 시도합니다.")
                resolver.webView.evaluateJavascript(PAUSE_ALL, null)
                resolver.streams = listOf(choice.copy(subtitles = resolver.subtitles))
                return resolver
            }
            delay(150)
        }
    }

    /** A script's value, as JSON decoded (a string, number, object or null). */
    private suspend fun evaluate(script: String): Any? = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript("(()=>{try{return ($script)}catch(_){return null}})()") { value ->
            if (continuation.isActive) continuation.resumeWith(Result.success(runCatching { JSONTokener(value ?: "null").nextValue() }.getOrNull().takeIf { it != JSONObject.NULL }))
        }
    }

    internal fun loadPageForSubtitles(url: String, seconds: Int) { silent = true; loading = true; loadPage(url, seconds) }
    private fun loadPage(url: String, seconds: Int) {
        flixKey = null; webPlaybackActive = false
        val token = generation
        webView.loadUrl(url, mapOf("Referer" to (currentItem?.watchURL ?: "")))
        timeout?.cancel()
        timeout = scope.launch {
            delay(seconds * 1000L)
            if (generation == token && streams.isEmpty()) loadNextServer()
        }
    }

    fun cancel() {
        serverRace?.cancel(); serverRace = null
        clearSelected()
        desktop.cancel(); generation++; timeout?.cancel(); timeout = null
        candidateJobs.values.forEach { it.cancel() }; candidateJobs.clear(); ohliPlayer = null
        pendingOhli.clear(); lastOhliCandidate = null; ohliCaptionsTried.clear()
        webView.stopLoading(); loading = false
    }
    fun shutdown() {
        cancel(); scripts.forEach { it.remove() }; scripts.clear()
        webView.loadUrl("about:blank"); scope.cancel(); desktop.close()
        webView.destroy()
    }

    private fun pageFinished(url: String) {
        if (webPlaybackActive || !urlHost(url).contains("flixcloud")) return
        val script = asset("flix-bootstrap.js") ?: return
        webView.evaluateJavascript(script, null)
        val token = generation
        scope.launch {
            repeat(200) {
                if (token != generation || webPlaybackActive) return@launch
                val text = evaluate("window.__lilacReAnimeFlixResult || ''") as? String
                if (!text.isNullOrEmpty()) {
                    runCatching { JSONObject(text) }.getOrNull()?.let { result ->
                        if (result.optBoolean("ok") && result.optString("m3u8").isNotEmpty()) capture(result.optString("m3u8"), webView.url ?: "", result.optString("pk").ifEmpty { null })
                        else error = result.optString("error").ifEmpty { null }
                    }
                    return@launch
                }
                delay(250)
            }
        }
    }

    /** A message from a page's scripts (window.webkit.messageHandlers.lilacMedia on iOS). */
    private fun received(text: String, isMainFrame: Boolean) {
        if (webPlaybackActive) return
        val body = runCatching { JSONObject(text) }.getOrNull() ?: return
        val value = body.optString("url")
        if (!value.startsWith("http://") && !value.startsWith("https://")) return
        val kind = body.optString("kind")
        val frame = body.optString("__frame").ifEmpty { null }
        if (currentItem?.anime?.source == "ohli24") {
            if (kind == "ohliPlayer" && isMainFrame && frame?.let(::urlHost) == currentItem?.watchURL?.let(::urlHost)) {
                ohliPlayer = value
                for (candidate in pendingOhli) if (OhliPlayback.accepts(candidate.kind, candidate.frame, value)) {
                    if (candidate.kind == "ohliCaption") addOhliCaptions(listOf(candidate.url))
                    else verifyOhli(candidate.url, candidate.kind == "ohliVariant", candidate.manifest)
                }
                pendingOhli.clear(); return
            }
            if (ohliPlayer == null && kind in setOf("ohliVariant", "ohliMedia", "ohliCaption") && pendingOhli.size < 32) {
                pendingOhli += OhliCandidate(value, frame, kind, body.optString("manifest").ifEmpty { null }); return
            }
            if (!OhliPlayback.accepts(kind, frame, ohliPlayer)) return
            if (kind == "ohliCaption") { addOhliCaptions(listOf(value)); return }
            verifyOhli(value, kind == "ohliVariant", body.optString("manifest").ifEmpty { null })
            return
        }
        when (kind) {
            "flixError" -> if (streams.isEmpty()) { error = body.optString("error").ifEmpty { null }; loading = false; timeout?.cancel() }
            "flixKey" -> body.optString("pk").takeIf { it.isNotEmpty() }?.let { key -> flixKey = key; streams = streams.map { it.copy(manifestKey = key) } }
            "subtitle" -> if (subtitles.none { it.url == value }) subtitles = subtitles + RemoteSubtitle(body.optString("label").ifEmpty { "자막" }, value, body.optString("language"))
            else -> capture(value, body.optString("referer").ifEmpty { frame ?: "" }, body.optString("pk").ifEmpty { null })
        }
    }

    /** The player's own Korean subtitle file, given to every stream of the episode (desktop resolveOhliEpisode): the
     *  first of its files that answers as a subtitle, fetched as desktop does and kept on disk, so the subtitle tab and
     *  the automatic choice take it like the other sites' files. None that answers: the subtitle is in the picture. */
    private fun addOhliCaptions(files: List<String>) {
        val token = generation
        scope.launch { ohliCaptions(files, token) }
    }
    private suspend fun ohliCaptions(files: List<String>, token: Long) {
        val fresh = files.filter { it.startsWith("http") && ohliCaptionsTried.add(it) }
        if (fresh.isEmpty() || subtitles.any { it.origin == "ohli24" }) return
        val found = desktop.await<CrossSiteTrack> { ohliSubtitle(fresh, it) }.getOrNull() ?: return
        val track = SubtitleFiles.providerTrack(found)?.copy(label = "애니24 한국어 자막") ?: return
        if (token != generation || subtitles.any { it.origin == "ohli24" }) return
        subtitles = subtitles + track
        streams = streams.map { it.copy(subtitles = it.subtitles + track, burnedKorean = false) }
    }

    /** The player read without opening it (desktop ohliStreamDirect); when that fails, the page itself. */
    private suspend fun ohliDirect(player: String, token: Long) {
        val stream = desktop.await<OhliDirectStream> { ohliStream(player, OHLI_AGENT, it) }.getOrNull()
        if (token != generation) return
        if (stream == null || !OhliPlayback.isEpisodePlaylist(stream.manifest)) { loadPage(player, 45); return }
        // The subtitle file is read before the video is handed over, as desktop's ohliStream does.
        ohliCaptions(stream.subtitles, token)
        if (token != generation) return
        verifyOhli(stream.url, true, stream.manifest)
        if (streams.isEmpty()) loadPage(player, 45)
    }

    private fun verifyOhli(url: String, hls: Boolean, manifest: String?) {
        if (candidateJobs.containsKey(url) || streams.any { it.url == url }) return
        if (hls) {
            if (manifest == null || manifest.length > 4_000_000 || !OhliPlayback.isEpisodePlaylist(manifest)) return
            streams = streams + ResolvedStream("HLS", url, "", mapOf("User-Agent" to OHLI_AGENT), subtitles = subtitles, hlsManifest = manifest,
                burnedKorean = if (subtitles.isEmpty()) null else false)
            loading = false; error = null; timeout?.cancel()
            return
        }
        val token = generation
        val previous = lastOhliCandidate
        val job = scope.launch {
            try {
                previous?.join()
                if (generation != token || streams.isNotEmpty()) return@launch
                val referer = ohliPlayer ?: ""
                val headers = buildMap { put("User-Agent", OHLI_AGENT); if (referer.isNotEmpty()) put("Referer", referer) }
                val stream = ResolvedStream("MP4", url, referer, headers)
                // An advertisement is short: only media longer than 30 seconds is the episode.
                val length = withContext(Dispatchers.IO) { mediaDuration(url, headers) }
                if (length <= 30 || generation != token || streams.isNotEmpty()) return@launch
                streams = streams + (if (subtitles.isNotEmpty()) stream.copy(subtitles = subtitles, burnedKorean = false) else stream)
                loading = false; error = null; timeout?.cancel()
            } catch (failure: CancellationException) { throw failure } catch (_: Throwable) {
            } finally { if (generation == token) candidateJobs.remove(url) }
        }
        candidateJobs[url] = job
        lastOhliCandidate = job
    }

    private fun mediaDuration(url: String, headers: Map<String, String>): Double = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, headers)
            (retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000
        } finally { retriever.release() }
    }.getOrDefault(0.0)

    private fun capture(value: String, referer: String, key: String?) {
        val manifestKey = key?.trim()?.ifEmpty { null } ?: flixKey
        val index = streams.indexOfFirst { it.url == value }
        if (index >= 0) { if (manifestKey != null) streams = streams.toMutableList().also { it[index] = it[index].copy(manifestKey = manifestKey) }; return }
        val headers = buildMap {
            CookieManager.getInstance().getCookie(value)?.takeIf { it.isNotEmpty() }?.let { put("Cookie", it) }
            put("User-Agent", webView.settings.userAgentString ?: PLAYER_AGENT)
            if (referer.isNotEmpty()) put("Referer", referer)
        }
        val ext = urlExtension(value).uppercase()
        streams = streams + ResolvedStream(serverLabel.ifEmpty { ext.ifEmpty { "영상" } }, value, referer, headers, manifestKey, subtitles)
        loading = false; error = null; timeout?.cancel()
    }

    companion object {
        private const val PAUSE_ALL = "document.querySelectorAll('video,audio').forEach(m=>{try{m.pause()}catch(_){}})"
        /** iOS's scripts post to window.webkit.messageHandlers.lilacMedia; here that goes to the app's listener, with the
         *  frame's own address (which iOS reads from the message). */
        private val BRIDGE = """
            (() => {
              if (!window.lilacNative) return;
              const post = value => { try { window.lilacNative.postMessage(JSON.stringify(Object.assign({__frame: location.href}, value))); } catch (_) {} };
              window.webkit = window.webkit || {};
              window.webkit.messageHandlers = window.webkit.messageHandlers || {};
              window.webkit.messageHandlers.lilacMedia = { postMessage: post };
            })();
        """.trimIndent()
        private val MUTE = """
            (() => {
              const mute = element => { if (!window.__lilacWebPlayback && element instanceof HTMLMediaElement) { if (!element.muted) element.muted = true; if (element.volume !== 0) element.volume = 0; } };
              window.addEventListener('message', event => {
                if (typeof event.data?.lilacWebPlayback !== 'boolean') return;
                window.__lilacWebPlayback = event.data.lilacWebPlayback;
                document.querySelectorAll('video,audio').forEach(media => {
                  if (window.__lilacWebPlayback) { media.muted = false; media.volume = 1; } else { media.pause(); mute(media); }
                });
                document.querySelectorAll('iframe').forEach(frame => frame.contentWindow?.postMessage(event.data,'*'));
              });
              const play = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function() { mute(this); return play.apply(this, arguments); };
              document.addEventListener('play', event => mute(event.target), true);
              document.addEventListener('volumechange', event => mute(event.target), true);
            })();
        """.trimIndent()
        private val FLIX_START = """
            (() => {
              if (!location.hostname.includes('flixcloud')) return;
              BOOTSTRAP
              const poll = setInterval(() => {
                try {
                  const r = JSON.parse(window.__lilacReAnimeFlixResult || '{}');
                  if (r.ok && r.m3u8) { window.webkit.messageHandlers.lilacMedia.postMessage({url:r.m3u8,referer:location.href,pk:r.pk,kind:'video'}); clearInterval(poll); }
                } catch (_) {}
              },250);
              setTimeout(()=>clearInterval(poll),50000);
            })();
        """.trimIndent()
        /** iOS PlaybackResolver.captureScript: every media address a page asks for (fetch, XHR, resources, video tags). */
        private val CAPTURE = """
            (() => {
              const seen = new Set();
              const emit = (u, kind='video', label='', language='', referer=location.href) => {
                try {
                  const url = new URL(u, location.href).href;
                  const address = new URL(url);
                  if (/runative|magsrv|juneworewyjyna|pxltag/i.test(url) || /(?:^|[./_-])ad(?:s|server|vert\w*)?(?=[./_-]|${'$'})/i.test(address.hostname + address.pathname)) return;
                  if (seen.has(url)) return;
                  if (kind === 'video' && !/\.(m3u8|mp4|mkv|webm)(?:[?#]|${'$'})/i.test(url)) return;
                  seen.add(url);
                  window.webkit.messageHandlers.lilacMedia.postMessage({url,kind,label,language,referer,pk:typeof window.__pk==='string'?window.__pk:''});
                } catch (_) {}
              };
              const scan = () => {
                if (window.__lilacFlixDiscovery?.stopped) return;
                document.querySelectorAll('video,source').forEach(v=>emit(v.currentSrc||v.src));
                document.querySelectorAll('track[src]').forEach(t=>emit(t.src,'subtitle',t.label,t.srclang));
              };
              const master = (text, base) => {
                if (!String(text).startsWith('#EXTM3U')) return;
                const lines = String(text).split(/\r?\n/), variants = [];
                for (let i=0;i<lines.length-1;i++) {
                  if (!lines[i].startsWith('#EXT-X-STREAM-INF:')) continue;
                  const bandwidth = Number((lines[i].match(/BANDWIDTH=(\d+)/)||[])[1])||0;
                  let next=i+1; while(next<lines.length && (!lines[next].trim()||lines[next].startsWith('#'))) next++;
                  if(next<lines.length) variants.push({url:new URL(lines[next].trim(),base).href,bandwidth});
                }
                variants.sort((a,b)=>b.bandwidth-a.bandwidth).forEach(v=>emit(v.url,'video','','',''));
              };
              const isMaster = url => /\/master\.txt(?:[?#]|${'$'})/i.test(String(url));
              const originalFetch = window.fetch;
              window.fetch = function(input, init) {
                const url=typeof input==='string'?input:input.url; emit(url);
                const response=originalFetch.apply(this,arguments);
                if(isMaster(url)) response.then(r=>r.clone().text()).then(text=>master(text,url)).catch(()=>{});
                return response;
              };
              const open = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method,url) {
                emit(url);
                if(isMaster(url)) this.addEventListener('load',()=>{ try {
                  const text=this.responseType==='arraybuffer'?new TextDecoder().decode(this.response):this.responseText;
                  master(text,url);
                } catch(_) {} });
                return open.apply(this,arguments);
              };
              try { new PerformanceObserver(list=>list.getEntries().forEach(e=>emit(e.name))).observe({entryTypes:['resource']}); } catch(_) {}
              document.addEventListener('DOMContentLoaded',()=>{ scan(); new MutationObserver(scan).observe(document.documentElement,{subtree:true,childList:true,attributes:true}); });
              setInterval(scan,1000);
            })();
        """.trimIndent()
    }
}

/** The subtitle files a player page names as it plays, read in a hidden, muted resolver of its own (iOS
 *  PlaybackResolver.pageSubtitles; Linkkf's subtitle comes only with its player page). */
suspend fun pageSubtitles(context: Context, page: String, seconds: Int = 15): List<RemoteSubtitle> {
    val resolver = PlaybackResolver(context)
    try {
        resolver.loadPageForSubtitles(page, seconds)
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (resolver.subtitles.isEmpty() && resolver.error == null && System.currentTimeMillis() < deadline) delay(150)
        return resolver.subtitles
    } finally { resolver.shutdown() }
}

/** iOS KoreanSiteBrowserBridge: Linkkf's Korean subtitle read from its player page for the shared Korean-site lookup. */
class KoreanSiteBrowser(private val context: Context) : com.lilac.anime.shared.KoreanSiteBrowser {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableMapOf<String, Job>()
    override fun linkkfSubtitle(id: String, url: String, completion: (String?, String?) -> Unit) {
        jobs[id] = scope.launch {
            try {
                if (!url.startsWith("http")) { completion(null, null); return@launch }
                val found = pageSubtitles(context, url)
                val track = found.firstOrNull(SubtitlePolicy::isKorean) ?: found.firstOrNull() ?: run { completion(null, null); return@launch }
                completion(JSONObject().put("url", track.url).put("headers", JSONObject(track.headers ?: mapOf("Referer" to url))).toString(), null)
            } finally { jobs.remove(id) }
        }
    }
    override fun cancel(id: String) { jobs.remove(id)?.cancel() }
}

/** The AniList and Miruro matches kept between launches (iOS CrossSiteDiskCache). */
class CrossSiteDiskCache(context: Context) : com.lilac.anime.shared.CrossSiteCacheStore {
    private val file = java.io.File(context.cacheDir, "cross-site-anilist.json")
    @Synchronized override fun read(): String? = runCatching { file.readText() }.getOrNull()
    @Synchronized override fun write(value: String) { runCatching { file.writeText(value) } }
}

/** Miruro's API answers only inside its own page: requests run in a hidden WebView on the site (iOS MiruroBrowser). */
@SuppressLint("SetJavaScriptEnabled")
class MiruroBrowser(private val context: Context) : com.lilac.anime.shared.MiruroBrowserTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pages = mutableMapOf<String, WebView>()
    private val jobs = mutableMapOf<String, Job>()
    private val origins = setOf("https://www.miruro.to", "https://www.miruro.tv", "https://www.miruro.cx", "https://www.miruro.bz")

    private fun allowed(url: Uri) = "${url.scheme}://${url.host}" in origins && url.port == -1 && url.userInfo == null && (url.path ?: "").startsWith("/api/v1/")

    override fun request(id: String, url: String, completion: (String?, String?) -> Unit) {
        scope.launch {
            jobs[id] = coroutineContext[Job]!!
            try {
                val address = Uri.parse(url)
                if (!allowed(address)) throw PlaybackFailure("허용되지 않은 Miruro API 주소입니다.")
                completion(fetch(address), null)
            } catch (failure: CancellationException) { completion(null, "취소됨") } catch (failure: Throwable) {
                completion(null, failure.message ?: "Miruro 연결 실패")
            } finally { jobs.remove(id) }
        }
    }
    override fun cancel(id: String) { jobs.remove(id)?.cancel() }

    private fun page(origin: String): WebView = pages.getOrPut(origin) {
        WebView(context).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webChromeClient = object : WebChromeClient() { override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: Message?) = false }
            webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = r.url.scheme !in setOf("http", "https") }
            loadUrl("$origin/")
        }
    }

    private suspend fun evaluate(page: WebView, script: String): String? = suspendCancellableCoroutine { continuation ->
        page.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resumeWith(Result.success(runCatching { JSONTokener(value).nextValue() as? String }.getOrNull())) }
    }

    private suspend fun fetch(url: Uri): String {
        val origin = "${url.scheme}://${url.host}"
        val page = page(origin)
        val deadline = System.currentTimeMillis() + 25_000
        while (true) {
            val ready = evaluate(page, "location.origin + '|' + document.readyState")
            if (ready != null && ready.startsWith("$origin|") && !ready.endsWith("|loading")) break
            if (System.currentTimeMillis() > deadline) { pages.remove(origin)?.destroy(); throw PlaybackFailure("Miruro 페이지 연결 시간이 초과되었습니다.") }
            delay(150)
        }
        // evaluateJavascript cannot await a promise: the answer is left on the page and read back.
        val key = "__lilacMiruro" + System.nanoTime()
        val address = JSONObject.quote(url.toString()); val expected = JSONObject.quote(origin)
        page.evaluateJavascript("""
            (async () => {
              try {
                if (location.origin !== $expected) throw new Error('Miruro 페이지 주소가 변경되었습니다.');
                const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 15000);
                try {
                  const r = await fetch($address, {credentials:'include', signal:controller.signal});
                  const bytes = new Uint8Array(await r.arrayBuffer());
                  if (bytes.length > 8388608) throw new Error('Miruro 응답이 너무 큽니다.');
                  let text = ''; for (let i=0;i<bytes.length;i+=32768) text += String.fromCharCode(...bytes.subarray(i,i+32768));
                  window['$key'] = JSON.stringify({status:r.status, body:btoa(text)});
                } finally { clearTimeout(timer); }
              } catch (e) { window['$key'] = JSON.stringify({error: String(e && e.message || e)}); }
            })();
        """.trimIndent(), null)
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            delay(100)
            val text = evaluate(page, "window['$key'] || ''")
            if (!text.isNullOrEmpty()) {
                page.evaluateJavascript("delete window['$key']", null)
                val packet = JSONObject(text)
                if (packet.has("error")) throw PlaybackFailure(packet.getString("error"))
                return packet.toString()
            }
        }
        throw PlaybackFailure("Miruro 응답 시간이 초과되었습니다.")
    }
}
