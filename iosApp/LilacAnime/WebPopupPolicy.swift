import WebKit

/// Desktop popup-policy.cjs: deny page-created windows in every provider web view.
/// Native player/authentication presentation and navigation delegates remain app-owned.
@MainActor enum ProviderWebView {
    static func make(frame: CGRect = .zero, configuration: WKWebViewConfiguration? = nil) -> WKWebView {
        let configuration = configuration ?? WKWebViewConfiguration()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let webView = WKWebView(frame: frame, configuration: configuration)
        webView.uiDelegate = PagePopupBlocker.shared
        return webView
    }
}

@MainActor final class PagePopupBlocker: NSObject, WKUIDelegate {
    static let shared = PagePopupBlocker()
    private(set) var blockedWindows: UInt = 0
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        blockedWindows &+= 1
        // Covers window.open, target=_blank, and subframe requests, including user-initiated ones.
        // Never redirect the popup into the currently playing/authenticating document.
        return nil
    }
}
