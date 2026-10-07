import Foundation
import WebKit
import LilacShared
@MainActor
final class TitleLookup: NSObject {
    private let web = WKWebView()
    private var continuation: CheckedContinuation<String?, Never>?
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private let service = IosServices()
    func resolve(_ query: String, aliases: [String] = []) async -> String? {
        cancel()
        let token = generation
        let credential = SecureKeys.load("tmdb")
        if !credential.isEmpty {
            let titles = [query] + aliases.filter { !$0.isEmpty }
            let cache = "tmdb.title.v1." + SubtitleFiles.key(titles.joined(separator: "|") + SubtitleFiles.key(credential))
            if let saved = UserDefaults.standard.string(forKey: cache) { return saved }
            let title: String? = await withCheckedContinuation { pending in
                service.koreanTitle(titles: titles, credential: credential) { title, _ in pending.resume(returning: title) }
            }
            guard token == generation, !Task.isCancelled else { return nil }
            if let title {
                UserDefaults.standard.set(title, forKey: cache)
                return title
            }
        }
        let key = "namu.title.v9." + SubtitleFiles.key(query)
        if let saved = UserDefaults.standard.string(forKey: key) { return saved }
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            task = Task { [weak self] in
                guard let self else { return }
                for target in ["", "title_content"] {
                    var components = URLComponents(string: "https://namu.wiki/Search")!
                    components.queryItems = [URLQueryItem(name: "q", value: query.replacingOccurrences(of: "…", with: "..."))]
                    if !target.isEmpty { components.queryItems?.append(URLQueryItem(name: "target", value: target)) }
                    web.load(URLRequest(url: components.url!))
                    for _ in 0..<10 {
                        try? await Task.sleep(nanoseconds: 1_000_000_000)
                        if Task.isCancelled { return }
                        if let html = try? await web.evaluateJavaScript("document.documentElement.outerHTML") as? String,
                           let result = TitleCandidates.shared.namu(html: html, query: query) {
                            guard token == generation, !Task.isCancelled else { return }
                            UserDefaults.standard.set(result, forKey: key)
                            finish(result); return
                        }
                    }
                }
                finish(nil)
            }
        }
    }
    private func finish(_ result: String?) { let pending = continuation; continuation = nil; web.stopLoading(); pending?.resume(returning: result) }
    func cancel() { generation = UUID(); task?.cancel(); task = nil; finish(nil) }
}
