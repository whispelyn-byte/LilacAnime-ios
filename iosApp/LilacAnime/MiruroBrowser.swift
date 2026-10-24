import Foundation
import WebKit
import UIKit
import LilacShared

/// Requests run inside a persistent Miruro document. Cloudflare verification stays in that same data store.
final class MiruroBrowser: NSObject, MiruroBrowserTransport {
    static let shared = MiruroBrowser()
    @MainActor private var pages: [String: WKWebView] = [:]
    @MainActor private var jobs: [String: Task<Void, Never>] = [:]
    @MainActor private var verification: [String: Task<Void, Error>] = [:]
    @MainActor private var cooldown: [String: Date] = [:]
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
                    let origin = Self.origin(address)
                    if let pending = verification[origin] { try await pending.value }
                    var result = try await fetch(address)
                    if Self.blocked(result) { throw Self.blockedError() }
                    if Self.challenge(result) {
                        if let pending = verification[origin] { try await pending.value }
                        else {
                            guard (cooldown[origin] ?? .distantPast) < Date() else { throw Self.stopped("Miruro 인증이 아직 유효하지 않습니다. 잠시 후 다시 시도해 주세요.") }
                            let task = Task { try await self.authenticate(address) }
                            verification[origin] = task
                            do { try await task.value; verification[origin] = nil }
                            catch { verification[origin] = nil; throw error }
                        }
                        try Task.checkCancellation()
                        result = try await fetch(address)
                        if Self.blocked(result) { throw Self.blockedError() }
                        if Self.challenge(result) { throw Self.stopped("Miruro 보안 인증이 계속 요구됩니다. 다른 소스를 선택해 주세요.") }
                    }
                    try Task.checkCancellation()
                    completion(String(data: try JSONSerialization.data(withJSONObject: result), encoding: .utf8), nil)
                } catch { completion(nil, error.localizedDescription) }
            }
        }
    }
    func cancel(id: String) {
        Task { @MainActor in
            jobs[id]?.cancel(); jobs[id] = nil
            if jobs.isEmpty { verification.values.forEach { $0.cancel() }; verification.removeAll() }
        }
    }
    static func stopped(_ text: String) -> NSError { SubtitleFiles.failure("MIRURO_STOP: " + text) }
    static func blockedError() -> NSError { stopped("Miruro가 현재 접속을 차단했습니다. 인증으로 해제할 수 없는 화면입니다. 다른 소스를 선택하거나 잠시 후 다시 시도해 주세요.") }
    static func text(_ result: [String: Any]) -> String {
        String(data: Data(base64Encoded: result["body"] as? String ?? "") ?? Data(), encoding: .utf8) ?? ""
    }
    static func challenge(_ result: [String: Any]) -> Bool {
        result["mitigated"] as? String == "challenge" || (result["type"] as? String ?? "").contains("text/html") &&
            text(result).range(of: "cdn-cgi/challenge-platform|cf-chl-|Just a moment", options: [.regularExpression, .caseInsensitive]) != nil
    }
    static func blocked(_ result: [String: Any]) -> Bool {
        result["status"] as? Int == 403 && text(result).range(of: "Sorry, you have been blocked|You are unable to access", options: [.regularExpression, .caseInsensitive]) != nil
    }
    @MainActor private func page(_ origin: String) async throws -> WKWebView {
        if let page = pages[origin] { return page }
        let config = WKWebViewConfiguration(); config.websiteDataStore = .default()
        let page = ProviderWebView.make(frame: CGRect(x: 0, y: 0, width: 960, height: 640), configuration: config)
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
                return {status:r.status, type:r.headers.get('content-type')||'', mitigated:r.headers.get('cf-mitigated')||'', body:btoa(text)};
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
    @MainActor private func authenticate(_ url: URL) async throws {
        let origin = Self.origin(url)
        let controller = MiruroVerificationController(origin: origin)
        let config = WKWebViewConfiguration(); config.websiteDataStore = .default()
        let web = ProviderWebView.make(configuration: config)
        controller.web = web; web.navigationDelegate = controller
        let nav = UINavigationController(rootViewController: controller)
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
              var presenter = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { throw Self.stopped("Miruro 인증을 위해 앱을 다시 열어 주세요.") }
        while let presented = presenter.presentedViewController { presenter = presented }
        presenter.present(nav, animated: true)
        web.load(URLRequest(url: url))
        defer { cooldown[origin] = Date().addingTimeInterval(30); web.stopLoading(); nav.dismiss(animated: true) }
        let deadline = Date().addingTimeInterval(180)
        while Date() < deadline {
            try Task.checkCancellation()
            if controller.cancelled || nav.presentingViewController == nil { throw Self.stopped("Miruro 인증 창을 닫았습니다.") }
            if let error = controller.failure { throw Self.stopped(error) }
            if let html = try? await web.evaluateJavaScript("document.body?.innerText || ''") as? String,
               html.contains("Sorry, you have been blocked") || html.contains("You are unable to access") { throw Self.blockedError() }
            if let result = try? await fetch(url) {
                if Self.blocked(result) { throw Self.blockedError() }
                if (200..<300).contains(result["status"] as? Int ?? 0), !Self.challenge(result) { return }
            }
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
        throw Self.stopped("Miruro 인증 시간이 초과되었습니다.")
    }
}

@MainActor private final class MiruroVerificationController: UIViewController, WKNavigationDelegate {
    let origin: String
    var web: WKWebView!
    var cancelled = false
    var failure: String?
    init(origin: String) { self.origin = origin; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }
    override func loadView() { view = web }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Miruro 보안 인증"
        navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(close))
    }
    @objc private func close() { cancelled = true }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url, action.targetFrame != nil,
              action.targetFrame?.isMainFrame != true || MiruroBrowser.origin(url) == origin else { decisionHandler(.cancel); return }
        decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        decisionHandler(response.canShowMIMEType ? .allow : .cancel)
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code != NSURLErrorCancelled { failure = "Miruro 인증 페이지를 열지 못했습니다: " + error.localizedDescription }
    }
}
