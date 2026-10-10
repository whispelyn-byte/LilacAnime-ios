import Foundation
import WebKit
import LilacShared

/// Runs the post's own WinPNG/Jamaker converter, as the desktop hidden browser does.
@MainActor
final class WinPNGReader: NSObject, WKNavigationDelegate {
    private let webView = WKWebView(frame: .zero)
    private var pending: CheckedContinuation<[[String: String]], Error>?
    private var timeout: Task<Void, Never>?
    static func subtitle(_ url: URL, episode: Double, matched: Bool) async throws -> URL? {
        let reader = WinPNGReader()
        let entries = try await reader.read(url).filter { $0["ass"]?.isEmpty == false || $0["raw"]?.isEmpty == false || $0["smi"]?.isEmpty == false }
        let extra = "non-?telop|\\bNC(?:OP|ED)\\b|tokuten|\\bSP\\d|\\bPV\\b|\\bCM\\b|menu|preview|trailer"
        let main = entries.filter { ($0["name"] ?? "").range(of: extra, options: [.regularExpression, .caseInsensitive]) == nil }
        let names = main.map { entry -> String in
            let ext = entry["ass"]?.isEmpty == false ? "ass" : entry["raw"]?.isEmpty == false ? URL(fileURLWithPath: entry["name"] ?? "sub.srt").pathExtension : "smi"
            return URL(fileURLWithPath: entry["name"] ?? "subtitle").deletingPathExtension().lastPathComponent + "." + ext
        }
        let index = DesktopCommunityFiles.shared.select(names: names, sizes: main.map { KotlinLong(value: Int64(($0["ass"] ?? $0["raw"] ?? $0["smi"] ?? "").utf8.count)) }, episode: Double(episode), strict: !matched, bundle: false, season: 0)
        guard index >= 0 && Int(index) < main.count else { return nil }
        let selected = main[Int(index)]
        let ext = selected["ass"]?.isEmpty == false ? "ass" : selected["raw"]?.isEmpty == false ? URL(fileURLWithPath: selected["name"] ?? "sub.srt").pathExtension : "smi"
        let content = [selected["ass"], selected["raw"], selected["smi"]].compactMap { $0 }.first(where: { !$0.isEmpty }) ?? ""
        let folder = SubtitleFiles.root.appendingPathComponent(SubtitleFiles.key(url.absoluteString))
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("winpng-\(episode)." + ext)
        try content.replacingOccurrences(of: "\u{feff}", with: "").write(to: file, atomically: true, encoding: .utf8)
        return file
    }
    func read(_ url: URL, html: String? = nil) async throws -> [[String: String]] {
        webView.navigationDelegate = self
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                pending = continuation
                timeout = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: 60_000_000_000) } catch { return }
                    self?.finish(.failure(SubtitleFiles.failure("WinPNG 글을 여는 데 너무 오래 걸립니다.")))
                }
                if let html { webView.loadHTMLString(html, baseURL: url) }
                else if url.isFileURL { webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent()) }
                else { webView.load(URLRequest(url: url)) }
            }
        } onCancel: { Task { @MainActor in self.finish(.failure(CancellationError())) } }
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let file = Bundle.main.url(forResource: "winpng-reader", withExtension: "js"), let script = try? String(contentsOf: file) else { finish(.success([])); return }
        LilacCallAsyncJavaScript(webView, "return await " + script) { [weak self] value, error in
            Task { @MainActor in
                guard let self else { return }
                if let error { self.finish(.failure(error)); return }
                let rows = (value as? [[String: Any]] ?? []).map { $0.compactMapValues { $0 as? String } }
                self.finish(.success(rows))
            }
        }
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { finish(.failure(error)) }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { finish(.failure(error)) }
    private func finish(_ result: Result<[[String: String]], Error>) {
        let continuation = pending; pending = nil; timeout?.cancel(); timeout = nil
        webView.navigationDelegate = nil; webView.stopLoading(); webView.loadHTMLString("", baseURL: nil)
        continuation?.resume(with: result)
    }
}
