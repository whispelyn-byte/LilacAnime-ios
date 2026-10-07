import Foundation
import WebKit
import LilacShared

struct ResolvedStream: Codable, Identifiable, Hashable {
    var id: String { url.absoluteString }
    var label: String
    var url: URL
    var referer: String
    var headers: [String: String]
    var manifestKey: String?
    var subtitles: [RemoteSubtitle] = []
}
struct RemoteSubtitle: Codable, Identifiable, Hashable {
    var id: String { url.absoluteString }
    var label: String
    var url: URL
    var language: String
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
    init(anime: SavedAnime, episode: Episode) {
        self.anime = anime; episodeID = episode.id; title = episode.title; number = Int(episode.number)
        displayNumber = episode.displayNumber; watchURL = URL(string: episode.videoUrl ?? anime.anime.detailUrl) ?? URL(string: "https://linkkf.app/")!
        directURL = nil
    }
    init(entry: WatchEntry) {
        anime = entry.anime; episodeID = entry.episodeID; title = entry.episodeTitle; number = entry.number
        displayNumber = String(entry.number); watchURL = URL(string: entry.watchURL) ?? URL(string: "https://linkkf.app/")!
        directURL = entry.directURL.flatMap(URL.init(string:)).flatMap { $0.isFileURL ? $0 : nil }
    }
}
@MainActor
final class PlaybackResolver: NSObject, ObservableObject, WKNavigationDelegate, WKScriptMessageHandler {
    @Published private(set) var streams: [ResolvedStream] = []
    @Published private(set) var subtitles: [RemoteSubtitle] = []
    @Published private(set) var loading = false
    @Published var error: String?
    let webView: WKWebView
    private var timeout: Task<Void, Never>?
    private var generation = UUID()
    private var currentItem: PlaybackItem?
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
        controller.addUserScript(WKUserScript(source: Self.captureScript, injectionTime: .atDocumentStart, forMainFrameOnly: false))
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
        webView.navigationDelegate = self
        webView.customUserAgent = agent
    }
    func resolve(_ item: PlaybackItem) {
        cancel(); currentItem = item; streams = []; subtitles = []; error = nil; loading = true
        let token = generation
        if let direct = item.directURL, direct.isFileURL || ["m3u8", "mp4", "mkv", "webm"].contains(direct.pathExtension.lowercased()) {
            streams = [ResolvedStream(label: "영상", url: direct, referer: "", headers: [:])]; loading = false; return
        }
        var request = URLRequest(url: item.watchURL)
        request.setValue(item.watchURL.scheme! + "://" + (item.watchURL.host ?? "") + "/", forHTTPHeaderField: "Referer")
        webView.load(request)
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 45_000_000_000)
            guard !Task.isCancelled, let self, self.generation == token else { return }
            self.loading = false
            if self.streams.isEmpty { self.error = "영상 URL을 찾지 못했습니다. 웹 플레이어에서 재생을 시작하거나 서버를 바꿔보세요." }
        }
    }
    func cancel() { generation = UUID(); timeout?.cancel(); timeout = nil; webView.stopLoading(); loading = false }
    func shutdown() { cancel(); webView.configuration.userContentController.removeScriptMessageHandler(forName: "lilacMedia"); webView.navigationDelegate = nil; webView.loadHTMLString("", baseURL: nil) }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard webView.url?.host?.contains("flixcloud") == true,
              let scriptURL = Bundle.main.url(forResource: "flix-bootstrap", withExtension: "js"),
              let script = try? String(contentsOf: scriptURL) else { return }
        webView.evaluateJavaScript(script)
        pollFlix(generation: generation, attempt: 0)
    }
    private func pollFlix(generation token: UUID, attempt: Int) {
        guard token == generation, attempt < 200 else { return }
        webView.evaluateJavaScript("window.__lilacReAnimeFlixResult || ''") { [weak self] value, _ in
            guard let self, self.generation == token else { return }
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
        guard let body = message.body as? [String: String], let value = body["url"],
              let url = URL(string: value), url.scheme == "https" || url.scheme == "http" else { return }
        if body["kind"] == "subtitle" {
            let track = RemoteSubtitle(label: body["label"] ?? "자막", url: url, language: body["language"] ?? "")
            if !subtitles.contains(where: { $0.url == url }) { subtitles.append(track) }
        } else {
            capture(value, referer: body["referer"] ?? message.frameInfo.request.url?.absoluteString ?? "", key: body["pk"])
        }
    }
    private func capture(_ value: String, referer: String, key: String?) {
        guard let url = URL(string: value) else { return }
        let manifestKey = key?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        if let index = streams.firstIndex(where: { $0.url == url }) {
            if let key = manifestKey { streams[index].manifestKey = key }
            return
        }
        let token = generation
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
            guard let self, self.generation == token else { return }
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
            self.streams.append(ResolvedStream(label: url.pathExtension.uppercased().isEmpty ? "영상" : url.pathExtension.uppercased(),
                url: url, referer: referer, headers: headers, manifestKey: manifestKey, subtitles: self.subtitles))
            self.loading = false; self.error = nil
        }
    }
    private static let captureScript = """
    (() => {
      const seen = new Set();
      const emit = (u, kind='video', label='', language='') => {
        try {
          const url = new URL(u, location.href).href;
          if (seen.has(url)) return;
          if (kind === 'video' && !/\\.(m3u8|mp4|mkv|webm)(?:[?#]|$)/i.test(url)) return;
          seen.add(url);
          window.webkit.messageHandlers.lilacMedia.postMessage({url,kind,label,language,referer:location.href,pk:typeof window.__pk==='string'?window.__pk:''});
        } catch (_) {}
      };
      const scan = () => {
        document.querySelectorAll('video,source').forEach(v=>emit(v.currentSrc||v.src));
        document.querySelectorAll('track[src]').forEach(t=>emit(t.src,'subtitle',t.label,t.srclang));
      };
      const originalFetch = window.fetch;
      window.fetch = function(input, init) { emit(typeof input==='string'?input:input.url); return originalFetch.apply(this,arguments); };
      const open = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(method,url) { emit(url); return open.apply(this,arguments); };
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
