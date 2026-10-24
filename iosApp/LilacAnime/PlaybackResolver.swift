import Foundation
import Combine
import WebKit
import AVFoundation
import LilacShared

struct ResolvedStream: Codable, Identifiable, Hashable {
    var id: String { url.absoluteString }
    var label: String
    var url: URL
    var referer: String
    var headers: [String: String]
    var manifestKey: String?
    var subtitles: [RemoteSubtitle] = []
    var hlsManifest: String? = nil
    var burnedKorean: Bool? = nil
}
struct RemoteSubtitle: Codable, Identifiable, Hashable {
    var id: String { url.absoluteString }
    var label: String
    var url: URL
    var language: String
    var headers: [String: String]? = nil
}
struct PlaybackItem {
    let anime: SavedAnime
    let episodeID: String
    let title: String
    let number: Int
    let displayNumber: String
    let watchURL: URL
    let directURL: URL?
    var next: [PlaybackItem] = []
    var preceding: [PlaybackItem] = []
    var localSubtitles: [URL] = []
    var localSubtitleTracks: [RemoteSubtitle] = []
    var burnedKorean: Bool? = nil
    /// The episode the subtitle sites are searched for: its label ("12", "9.5", "4a"), or its number when the label is the
    /// episode's own title ("80000대장경"), as desktop searches by episodeNumberOf.
    var subtitleEpisodeKey: String { displayNumber.range(of: #"^\d+(?:\.\d+)?[a-z]?$"#, options: [.regularExpression, .caseInsensitive]) != nil ? displayNumber : String(number) }
    init(anime: SavedAnime, episode: Episode) {
        self.anime = anime; episodeID = episode.id; title = episode.title; number = Int(episode.number)
        displayNumber = episode.displayNumber; watchURL = URL(string: episode.videoUrl ?? anime.anime.detailUrl) ?? URL(string: "https://linkkf.app/")!
        directURL = nil
    }
    init(entry: WatchEntry) {
        anime = entry.anime; episodeID = entry.episodeID; title = entry.episodeTitle; number = entry.number
        displayNumber = entry.episodeLabel; watchURL = URL(string: entry.watchURL) ?? URL(string: "https://linkkf.app/")!
        directURL = entry.directURL.flatMap(URL.init(string:)).flatMap { $0.isFileURL ? $0 : nil }
        let episodes = entry.anime.anime.episodes
        if let index = episodes.firstIndex(where: { $0.id == entry.episodeID }) {
            next = episodes.dropFirst(index + 1).map { PlaybackItem(anime: entry.anime, episode: $0) }
            preceding = episodes.prefix(index).map { PlaybackItem(anime: entry.anime, episode: $0) }
        }
    }
}
@MainActor
final class PlaybackResolver: NSObject, ObservableObject, WKNavigationDelegate, WKScriptMessageHandler {
    @Published private(set) var streams: [ResolvedStream] = []
    @Published private(set) var subtitles: [RemoteSubtitle] = []
    @Published private(set) var servers: [DesktopPlaybackServer] = []
    @Published private(set) var serverLabel = ""
    @Published private(set) var loading = false
    @Published var error: String?
    let webView: WKWebView
    var silent = false { didSet { installCaptureScripts(ohli: currentItem?.anime.source == "ohli24") } }
    private var timeout: Task<Void, Never>?
    private var generation = UUID()
    private var currentItem: PlaybackItem?
    private var serverCandidates: [DesktopPlaybackServer] = []
    private var serverRace: Task<Void, Never>?
    private var selectedResolver: PlaybackResolver?
    private var selectedSubscriptions = Set<AnyCancellable>()
    var playbackWebView: WKWebView { selectedResolver?.webView ?? webView }
    private var preferRaw = false
    private var ohliPlayer: URL?
    private var candidateTasks: [URL: Task<Void, Never>] = [:]
    private var pendingOhli: [(url: URL, frame: URL?, kind: String, manifest: String?)] = []
    private var lastOhliCandidate: Task<Void, Never>?
    private var flixKey: String?
    private var webPlaybackActive = false
    private let desktop = IosServices()
    private let agent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1"

    override init() {
        let controller = WKUserContentController()
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        // Weak proxy avoids a content-controller -> self -> webView retain cycle.
        controller.add(WeakScriptHandler(self), name: "lilacMedia")
        installCaptureScripts(ohli: false)
        webView.navigationDelegate = self
        webView.customUserAgent = agent
    }
    private func installCaptureScripts(ohli: Bool) {
        let controller = webView.configuration.userContentController
        controller.removeAllUserScripts()
        if silent {
            controller.addUserScript(WKUserScript(source: """
            (() => {
              const mute = element => { if (!window.__lilacWebPlayback && element instanceof HTMLMediaElement) { if (!element.muted) element.muted = true; if (element.volume !== 0) element.volume = 0; } };
              window.addEventListener('message', event => {
                if (typeof event.data?.lilacWebPlayback !== 'boolean') return;
                window.__lilacWebPlayback = event.data.lilacWebPlayback;
                document.querySelectorAll('video,audio').forEach(media => {
                  if (window.__lilacWebPlayback) { media.muted = false; media.volume = 1; }
                  else { media.pause(); mute(media); }
                });
                document.querySelectorAll('iframe').forEach(frame => frame.contentWindow?.postMessage(event.data,'*'));
              });
              const play = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function() { mute(this); return play.apply(this, arguments); };
              document.addEventListener('play', event => mute(event.target), true);
              document.addEventListener('volumechange', event => mute(event.target), true);
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        }
        controller.addUserScript(WKUserScript(source: Self.captureScript, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        if ohli, let file = Bundle.main.url(forResource: "ohli-capture", withExtension: "js"), let script = try? String(contentsOf: file) {
            controller.addUserScript(WKUserScript(source: script, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        }
        if let url = Bundle.main.url(forResource: "flix-discovery", withExtension: "js"), let script = try? String(contentsOf: url) {
            controller.addUserScript(WKUserScript(source: script, injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        }
        if let url = Bundle.main.url(forResource: "flix-bootstrap", withExtension: "js"),
           let bootstrap = try? String(contentsOf: url) {
            let script = """
            (() => {
              if (!location.hostname.includes('flixcloud')) return;
              const start = () => {
                BOOTSTRAP
                const poll = setInterval(() => {
                  try {
                    const r = JSON.parse(window.__lilacReAnimeFlixResult || '{}');
                    if (r.ok && r.m3u8) {
                      window.webkit.messageHandlers.lilacMedia.postMessage({url:r.m3u8,referer:location.href,pk:r.pk,kind:'video'});
                      clearInterval(poll);
                    }
                  } catch (_) {}
                },250);
                setTimeout(()=>clearInterval(poll),50000);
              };
              if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded',start);
              else start();
            })();
            """
            controller.addUserScript(WKUserScript(source: script.replacingOccurrences(of: "BOOTSTRAP", with: bootstrap), injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        }
    }
    func resolve(_ item: PlaybackItem, preferRaw: Bool = false, preferred: String? = nil) {
        cancel(); currentItem = item; streams = []; subtitles = []; servers = []; serverCandidates = []; error = nil; loading = true; self.preferRaw = preferRaw
        installCaptureScripts(ohli: item.anime.source == "ohli24")
        let token = generation
        if let direct = item.directURL, direct.isFileURL || ["m3u8", "mp4", "mkv", "webm"].contains(direct.pathExtension.lowercased()) {
            streams = [ResolvedStream(label: "영상", url: direct, referer: "", headers: [:], subtitles: item.localSubtitleTracks, burnedKorean: item.burnedKorean)]; loading = false; return
        }
        if ["miruro", "linkani"].contains(item.anime.source) {
            desktop.desktopStreams(sourceKey: item.anime.source, animeId: item.anime.anime.id, number: Int32(item.number), url: item.watchURL.absoluteString) { [weak self] results, failure in
                Task { @MainActor in
                    guard let self, token == self.generation else { return }
                    self.loading = false
                    self.streams = (results ?? []).compactMap { stream in
                        guard let url = URL(string: stream.url) else { return nil }
                        let tracks = stream.subtitles.compactMap { track -> RemoteSubtitle? in
                            guard let url = URL(string: track.url) else { return nil }
                            return RemoteSubtitle(label: track.label, url: url, language: track.language, headers: stream.headers)
                        }
                        return ResolvedStream(label: stream.label, url: url, referer: stream.referer, headers: stream.headers, subtitles: tracks, burnedKorean: stream.burnedKorean)
                    }
                    self.subtitles = self.streams.first?.subtitles ?? []
                    if self.streams.isEmpty { self.error = failure ?? "이 회차의 영상 서버를 찾지 못했습니다." }
                }
            }
            return
        }
        if ["reanime", "animenosub"].contains(item.anime.source) {
            desktop.desktopServers(sourceKey: item.anime.source, url: item.watchURL.absoluteString, number: Int32(item.number), anilist: item.anime.anime.anilistId?.int32Value ?? 0) { [weak self] servers, failure in
                Task { @MainActor in
                    guard let self, token == self.generation else { return }
                    self.servers = servers ?? []
                    func rank(_ server: DesktopPlaybackServer) -> Int { server.label == preferred ? 0 : server.kind == (preferRaw ? "raw" : "sub") ? 1 : server.kind == "sub" ? 2 : server.kind == "raw" ? 3 : 4 }
                    self.serverCandidates = item.anime.source == "animenosub" ? self.servers.sorted { rank($0) < rank($1) } : self.servers
                    if item.anime.source == "reanime" {
                        guard !self.serverCandidates.isEmpty else {
                            self.loading = false
                            self.error = failure ?? "이 작품은 현재 RE:Anime에서 재생할 수 없습니다. 다른 콘텐츠 소스를 선택해주세요."
                            return
                        }
                        self.raceServers(self.serverCandidates, item: item)
                    } else { self.loadNextServer(fallback: item.watchURL) }
                }
            }
            return
        }
        // ani.ohli24.com: the episode's player is named in its page data; it is opened directly (desktop resolveOhliEpisode).
        if item.anime.source == "ohli24" && item.watchURL.host == "ani.ohli24.com" {
            desktop.ohliPlayer(url: item.watchURL.absoluteString) { [weak self] value, failure in
                Task { @MainActor in
                    guard let self, token == self.generation else { return }
                    guard let value, let player = URL(string: value) else { self.loading = false; self.error = failure ?? "이 회차의 영상 플레이어를 찾지 못했습니다."; return }
                    self.ohliPlayer = player
                    self.ohliDirect(player, token: token)
                }
            }
            return
        }
        loadPage(item.watchURL, seconds: 45)
    }
    /// 다음 화 미리 준비 > 영상: what another resolver found for this episode while the one before it played, taken as
    /// this one's (the player checks first that the stream still answers).
    func adopt(_ item: PlaybackItem, streams: [ResolvedStream], subtitles: [RemoteSubtitle], servers: [DesktopPlaybackServer], label: String) {
        cancel(); currentItem = item; serverCandidates = []; error = nil; loading = false
        self.servers = servers; serverLabel = label; self.subtitles = subtitles; self.streams = streams
    }
    func setWebPlaybackActive(_ active: Bool) {
        webPlaybackActive = active
        let target = selectedResolver ?? self
        target.webPlaybackActive = active
        if active { target.timeout?.cancel(); target.webView.evaluateJavaScript("window.__lilacFlixDiscovery?.finish()", completionHandler: nil) }
        target.webView.evaluateJavaScript("window.postMessage({lilacWebPlayback: \(active ? "true" : "false")}, '*')", completionHandler: nil)
        if active { target.webView.configuration.userContentController.removeAllUserScripts() }
        else { target.installCaptureScripts(ohli: target.currentItem?.anime.source == "ohli24") }
        if active, target.webView.url == nil, let url = currentItem?.watchURL {
            target.webView.load(URLRequest(url: url))
        } else if !active { target.webView.pauseAllMediaPlayback(completionHandler: nil) }
    }
    func selectServer(_ server: DesktopPlaybackServer) {
        serverRace?.cancel(); serverRace = nil
        clearSelectedResolver()
        generation = UUID(); webView.stopLoading(); timeout?.cancel(); streams = []; subtitles = []; error = nil; loading = true
        serverCandidates = servers.filter { $0.url != server.url }; serverLabel = server.label
        if let item = currentItem, item.anime.source == "reanime" {
            raceServers([server], item: item)
        } else if let url = URL(string: server.url) { loadPage(url, seconds: 15) }
    }
    private func loadNextServer(fallback: URL? = nil) {
        timeout?.cancel()
        if !serverCandidates.isEmpty {
            let candidate = serverCandidates.removeFirst(); serverLabel = candidate.label
            if let url = URL(string: candidate.url) { loadPage(url, seconds: 15); return }
        }
        if let fallback { serverLabel = ""; loadPage(fallback, seconds: 45); return }
        loading = false; error = "모든 영상 서버 연결에 실패했습니다."
    }
    private func raceServers(_ servers: [DesktopPlaybackServer], item: PlaybackItem) {
        serverRace?.cancel()
        serverCandidates = []
        let token = generation
        serverRace = Task { [weak self] in
            let resolvers = servers.map { _ in PlaybackResolver() }
            defer { for resolver in resolvers where resolver !== self?.selectedResolver { resolver.shutdown() } }
            do {
                let winner = try await StaggeredResolver.first(Array(zip(servers, resolvers))) { pair in
                    try await Self.readServer(pair.0, item: item, resolver: pair.1)
                }
                guard let self, token == self.generation, !Task.isCancelled else { winner.shutdown(); return }
                self.selectedResolver = winner
                // Keep the winner alive: captions can arrive after the first video URL.
                winner.$streams.sink { [weak self] in self?.streams = Array($0.prefix(1)) }.store(in: &self.selectedSubscriptions)
                winner.$subtitles.sink { [weak self] in self?.subtitles = $0 }.store(in: &self.selectedSubscriptions)
                self.serverLabel = winner.serverLabel
                self.loading = false; self.error = nil
            } catch {
                guard let self, token == self.generation, !Task.isCancelled else { return }
                self.serverLabel = ""
                if item.anime.source == "reanime" {
                    self.loading = false; self.error = error.localizedDescription
                } else {
                    self.error = nil; self.loadPage(item.watchURL, seconds: 45)
                }
            }
        }
    }
    private func clearSelectedResolver() {
        selectedSubscriptions.removeAll()
        selectedResolver?.shutdown(); selectedResolver = nil
    }
    private static func readServer(_ server: DesktopPlaybackServer, item: PlaybackItem, resolver: PlaybackResolver) async throws -> PlaybackResolver {
        guard let url = URL(string: server.url) else { throw SubtitleFiles.failure("영상 서버 주소가 올바르지 않습니다.") }
        resolver.currentItem = item; resolver.silent = true; resolver.serverLabel = server.label; resolver.loading = true
        resolver.loadPage(url, seconds: 30)
        let deadline = Date().addingTimeInterval(30)
        let flix = url.host?.lowercased().contains("flixcloud.cc") == true
        var readiness = DesktopFlixReadiness()
        while resolver.streams.isEmpty && Date() < deadline {
            try Task.checkCancellation()
            if !resolver.loading { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        guard !resolver.streams.isEmpty else {
            throw SubtitleFiles.failure(resolver.error ?? "영상 주소를 찾지 못했습니다 (응답 시간 초과).")
        }
        if !flix { try Task.checkCancellation(); return resolver }
        // Like desktop, collect master or separate video/audio URLs, then wait for
        // the player key. Playlist and media loading belongs to the playback proxy.
        while true {
            try Task.checkCancellation()
            let value = try? await resolver.webView.evaluateJavaScript("window.__pk || (()=>{try{return JSON.parse(window.__lilacReAnimeFlixResult||'{}').pk||''}catch(_){return ''}})()")
            if let key = value as? String, !key.isEmpty {
                for index in resolver.streams.indices { resolver.streams[index].manifestKey = key }
            }
            try Task.checkCancellation()
            if var selected = try readiness.select(resolver.streams, now: Date().timeIntervalSinceReferenceDate) {
                let result = try? await resolver.webView.evaluateJavaScript("window.__lilacFlixDiscovery?.finish() || []")
                try Task.checkCancellation()
                for track in result as? [[String: String]] ?? [] {
                    guard let raw = track["url"], let url = URL(string: raw), !resolver.subtitles.contains(where: { $0.url == url }) else { continue }
                    resolver.subtitles.append(RemoteSubtitle(label: track["label"] ?? track["language"] ?? "자막", url: url, language: track["language"] ?? "und", headers: selected.headers))
                }
                let readable: Bool
                do { readable = try await HLSData.readable(selected) }
                catch { try Task.checkCancellation(); readable = true }
                guard readable else { throw SubtitleFiles.failure("RE:Anime 서버의 영상을 읽을 수 없습니다. 다른 서버를 시도합니다.") }
                selected.subtitles = resolver.subtitles
                resolver.webView.pauseAllMediaPlayback(completionHandler: nil)
                resolver.streams = [selected]
                return resolver
            }
            try await Task.sleep(nanoseconds: 150_000_000)
        }
    }

    private func loadPage(_ url: URL, seconds: Double) {
        flixKey = nil; webPlaybackActive = false
        let token = generation
        var request = URLRequest(url: url)
        request.setValue(currentItem?.watchURL.absoluteString ?? "", forHTTPHeaderField: "Referer")
        webView.load(request)
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled, let self, self.generation == token else { return }
            if self.streams.isEmpty { self.loadNextServer() }
        }
    }
    func cancel() {
        serverRace?.cancel(); serverRace = nil
        clearSelectedResolver()
        desktop.cancel(); generation = UUID(); timeout?.cancel(); timeout = nil
        candidateTasks.values.forEach { $0.cancel() }; candidateTasks.removeAll(); ohliPlayer = nil
        pendingOhli.removeAll(); lastOhliCandidate = nil
        webView.stopLoading(); loading = false
    }
    func shutdown() { cancel(); webView.configuration.userContentController.removeScriptMessageHandler(forName: "lilacMedia"); webView.navigationDelegate = nil; webView.loadHTMLString("", baseURL: nil) }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard !webPlaybackActive else { return }
        guard webView.url?.host?.contains("flixcloud") == true,
              let scriptURL = Bundle.main.url(forResource: "flix-bootstrap", withExtension: "js"),
              let script = try? String(contentsOf: scriptURL) else { return }
        webView.evaluateJavaScript(script)
        pollFlix(generation: generation, attempt: 0)
    }
    private func pollFlix(generation token: UUID, attempt: Int) {
        guard token == generation, !webPlaybackActive, attempt < 200 else { return }
        webView.evaluateJavaScript("window.__lilacReAnimeFlixResult || ''") { [weak self] value, _ in
            guard let self, self.generation == token, !self.webPlaybackActive else { return }
            if let text = value as? String, !text.isEmpty, let data = text.data(using: .utf8),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if object["ok"] as? Bool == true, let value = object["m3u8"] as? String {
                    self.capture(value, referer: self.webView.url?.absoluteString ?? "", key: object["pk"] as? String)
                } else { self.error = object["error"] as? String }
                return
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in self?.pollFlix(generation: token, attempt: attempt + 1) }
        }
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard !webPlaybackActive else { return }
        guard let body = message.body as? [String: String], let value = body["url"],
              let url = URL(string: value), url.scheme == "https" || url.scheme == "http" else { return }
        if currentItem?.anime.source == "ohli24" {
            if body["kind"] == "ohliPlayer", message.frameInfo.isMainFrame,
               message.frameInfo.request.url?.host == currentItem?.watchURL.host {
                ohliPlayer = url
                for candidate in pendingOhli where OhliPlayback.accepts(kind: candidate.kind, frame: candidate.frame, selected: url) {
                    if candidate.kind == "ohliCaption" { addOhliCaption(candidate.url, referer: candidate.frame?.absoluteString ?? ""); continue }
                    verifyOhli(candidate.url, hls: candidate.kind == "ohliVariant", manifest: candidate.manifest)
                }
                pendingOhli.removeAll()
                return
            }
            let kind = body["kind"] ?? ""
            if ohliPlayer == nil, ["ohliVariant", "ohliMedia", "ohliCaption"].contains(kind), pendingOhli.count < 32 {
                pendingOhli.append((url, message.frameInfo.request.url, kind, body["manifest"])); return
            }
            guard OhliPlayback.accepts(kind: kind, frame: message.frameInfo.request.url, selected: ohliPlayer) else { return }
            if kind == "ohliCaption" { addOhliCaption(url, referer: message.frameInfo.request.url?.absoluteString ?? ""); return }
            verifyOhli(url, hls: body["kind"] == "ohliVariant", manifest: body["manifest"])
            return
        }
        if body["kind"] == "flixError" {
            if streams.isEmpty {
                error = body["error"]; loading = false; timeout?.cancel()
            }
            return
        }
        if body["kind"] == "flixKey" {
            if let key = body["pk"], !key.isEmpty {
                flixKey = key
                for index in streams.indices { streams[index].manifestKey = key }
            }
            return
        }
        if body["kind"] == "subtitle" {
            let track = RemoteSubtitle(label: body["label"] ?? "자막", url: url, language: body["language"] ?? "")
            if !subtitles.contains(where: { $0.url == url }) { subtitles.append(track) }
        } else {
            capture(value, referer: body["referer"] ?? message.frameInfo.request.url?.absoluteString ?? "", key: body["pk"])
        }
    }
    /// The player's own Korean subtitle file, given to every stream of the episode (it is not in the picture then).
    private func addOhliCaption(_ url: URL, referer: String) {
        guard !subtitles.contains(where: { $0.url == url }) else { return }
        var headers = ["User-Agent": agent]
        if !referer.isEmpty { headers["Referer"] = referer }
        let track = RemoteSubtitle(label: "애니24 한국어 자막", url: url, language: "ko", headers: headers)
        subtitles.append(track)
        for index in streams.indices { streams[index].subtitles.append(track); streams[index].burnedKorean = false }
    }
    /// The player read without opening it (desktop ohliStreamDirect: about 1.5 s instead of the page loading); the
    /// subtitle files it names go on first, so the stream carries them. When that fails, the page itself as before.
    private func ohliDirect(_ player: URL, token: UUID) {
        desktop.ohliStream(playerUrl: player.absoluteString, userAgent: agent) { [weak self] stream, _ in
            Task { @MainActor in
                guard let self, token == self.generation else { return }
                guard let stream, let url = URL(string: stream.url), OhliPlayback.isEpisodePlaylist(stream.manifest) else { self.loadPage(player, seconds: 45); return }
                for file in stream.subtitles.compactMap(URL.init(string:)) { self.addOhliCaption(file, referer: player.absoluteString) }
                self.verifyOhli(url, hls: true, manifest: stream.manifest)
                if self.streams.isEmpty { self.loadPage(player, seconds: 45) }
            }
        }
    }
    private func verifyOhli(_ url: URL, hls: Bool, manifest: String?) {
        guard candidateTasks[url] == nil, !streams.contains(where: { $0.url == url }) else { return }
        if hls {
            guard let manifest, manifest.utf8.count <= 4_000_000, OhliPlayback.isEpisodePlaylist(manifest) else { return }
            streams.append(ResolvedStream(label: "HLS", url: url, referer: "", headers: ["User-Agent": agent], subtitles: subtitles, hlsManifest: manifest, burnedKorean: subtitles.isEmpty ? nil : false))
            loading = false; error = nil; timeout?.cancel()
            return
        }
        let token = generation
        let previous = lastOhliCandidate
        candidateTasks[url] = Task { [weak self] in
            guard let self else { return }
            defer { if generation == token { candidateTasks[url] = nil } }
            await previous?.value
            guard generation == token, !Task.isCancelled, streams.isEmpty else { return }
            let referer = hls ? "" : ohliPlayer?.absoluteString ?? ""
            var headers = ["User-Agent": agent]
            if !referer.isEmpty { headers["Referer"] = referer }
            var stream = ResolvedStream(label: hls ? "HLS" : "MP4", url: url, referer: referer, headers: headers)
            do {
                do {
                    let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": stream.headers])
                    let duration = try await asset.load(.duration).seconds
                    guard duration.isFinite, duration > 30 else { return }
                }
                guard generation == token, !Task.isCancelled, streams.isEmpty else { return }
                if !subtitles.isEmpty { stream.subtitles = subtitles; stream.burnedKorean = false }
                streams.append(stream); loading = false; error = nil; timeout?.cancel()
            } catch { /* An advertising or unavailable candidate must not end episode discovery. */ }
        }
        lastOhliCandidate = candidateTasks[url]
    }
    private func capture(_ value: String, referer: String, key: String?) {
        guard let url = URL(string: value) else { return }
        let manifestKey = key?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? flixKey
        if let index = streams.firstIndex(where: { $0.url == url }) {
            if let key = manifestKey { streams[index].manifestKey = key }
            return
        }
        let token = generation
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
            guard let self, self.generation == token, !self.webPlaybackActive else { return }
            let relevant = cookies.filter { cookie in
                let domain = cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
                return (url.host == domain || url.host?.hasSuffix("." + domain) == true) &&
                    url.path.hasPrefix(cookie.path) && (!cookie.isSecure || url.scheme == "https")
            }
            var headers = HTTPCookie.requestHeaderFields(with: relevant)
            headers["User-Agent"] = self.agent
            if !referer.isEmpty { headers["Referer"] = referer }
            if let index = self.streams.firstIndex(where: { $0.url == url }) {
                if let key = manifestKey { self.streams[index].manifestKey = key }
                return
            }
            self.streams.append(ResolvedStream(label: self.serverLabel.isEmpty ? (url.pathExtension.uppercased().isEmpty ? "영상" : url.pathExtension.uppercased()) : self.serverLabel,
                url: url, referer: referer, headers: headers, manifestKey: manifestKey ?? self.flixKey, subtitles: self.subtitles))
            self.loading = false; self.error = nil; self.timeout?.cancel()
        }
    }
    private static let captureScript = """
    (() => {
      const seen = new Set();
      const emit = (u, kind='video', label='', language='', referer=location.href) => {
        try {
          const url = new URL(u, location.href).href;
          if (/runative|magsrv|juneworewyjyna|pxltag/i.test(url)) return;
          if (seen.has(url)) return;
          if (kind === 'video' && !/\\.(m3u8|mp4|mkv|webm)(?:[?#]|$)/i.test(url)) return;
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
        const lines = String(text).split(/\\r?\\n/), variants = [];
        for (let i=0;i<lines.length-1;i++) {
          if (!lines[i].startsWith('#EXT-X-STREAM-INF:')) continue;
          const bandwidth = Number((lines[i].match(/BANDWIDTH=(\\d+)/)||[])[1])||0;
          let next=i+1; while(next<lines.length && (!lines[next].trim()||lines[next].startsWith('#'))) next++;
          if(next<lines.length) variants.push({url:new URL(lines[next].trim(),base).href,bandwidth});
        }
        variants.sort((a,b)=>b.bandwidth-a.bandwidth).forEach(v=>emit(v.url,'video','','',''));
      };
      const isMaster = url => /\\/master\\.txt(?:[?#]|$)/i.test(String(url));
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
    """
}
private final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) { target?.userContentController(userContentController, didReceive: message) }
}

private extension String { var nilIfEmpty: String? { isEmpty ? nil : self } }
