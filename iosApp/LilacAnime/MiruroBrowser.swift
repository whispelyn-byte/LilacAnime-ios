import Foundation
import WebKit
import LilacShared

/// Requests run inside a hidden persistent Miruro document without a verification window.
final class MiruroBrowser: NSObject, MiruroBrowserTransport {
    static let shared = MiruroBrowser()
    @MainActor private var pages: [String: WKWebView] = [:]
    @MainActor private var jobs: [String: Task<Void, Never>] = [:]
    static let origins: Set<String> = ["https://www.miruro.to", "https://www.miruro.tv", "https://www.miruro.cx", "https://www.miruro.bz"]

    static func origin(_ url: URL) -> String { "\(url.scheme ?? "")://\(url.host ?? "")" }
    static func allowed(_ url: URL) -> Bool {
        origins.contains(origin(url)) && url.port == nil && url.user == nil && url.password == nil && url.path.hasPrefix("/api/v1/")
    }
    func request(id: String, url: String, completion: @escaping (String?, String?) -> Void) {
        Task { @MainActor in
            jobs[id] = Task {
                defer { jobs[id] = nil }
                do {
                    guard let address = URL(string: url), Self.allowed(address) else { throw SubtitleFiles.failure("허용되지 않은 Miruro API 주소입니다.") }
                    let result = try await fetch(address)
                    try Task.checkCancellation()
                    completion(String(data: try JSONSerialization.data(withJSONObject: result), encoding: .utf8), nil)
                } catch { completion(nil, error.localizedDescription) }
            }
        }
    }
    func cancel(id: String) {
        Task { @MainActor in
            jobs[id]?.cancel(); jobs[id] = nil
        }
    }
    @MainActor private func page(_ origin: String) async throws -> WKWebView {
        if let page = pages[origin] { return page }
        let config = WKWebViewConfiguration(); config.websiteDataStore = .default()
        let page = MiruroWebView.make(frame: CGRect(x: 0, y: 0, width: 960, height: 640), configuration: config)
        page.load(URLRequest(url: URL(string: origin + "/")!))
        pages[origin] = page
        return page
    }
    @MainActor private func fetch(_ url: URL) async throws -> [String: Any] {
        let origin = Self.origin(url), page = try await page(origin), deadline = Date().addingTimeInterval(25)
        while true {
            try Task.checkCancellation()
            if let ready = try? await page.evaluateJavaScript("location.origin + '|' + document.readyState") as? String,
               ready.hasPrefix(origin + "|"), !ready.hasSuffix("|loading") { break }
            guard Date() < deadline else { pages[origin] = nil; page.stopLoading(); throw SubtitleFiles.failure("Miruro 페이지 연결 시간이 초과되었습니다.") }
            try await Task.sleep(nanoseconds: 150_000_000)
        }
        let script = """
            if (location.origin !== origin) throw new Error('Miruro 페이지 주소가 변경되었습니다.');
            const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 15000);
            try {
                const r = await fetch(address, {credentials:'include', signal:controller.signal});
                const bytes = new Uint8Array(await r.arrayBuffer());
                if (bytes.length > 8388608) throw new Error('Miruro 응답이 너무 큽니다.');
                let text = ''; for (let i=0;i<bytes.length;i+=32768) text += String.fromCharCode(...bytes.subarray(i,i+32768));
                return {status:r.status, body:btoa(text)};
            } finally { clearTimeout(timer); }
            """
        let result = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Any?, Error>) in
            LilacCallAsyncJavaScriptWithArguments(page, script, ["address": url.absoluteString, "origin": origin]) { value, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: value) }
            }
        }
        guard let packet = result as? [String: Any] else { throw SubtitleFiles.failure("Miruro 응답 오류") }
        return packet
    }
}
