import WebKit

/// Only the hidden Miruro request page denies popups, matching miruro-browser.cjs.
@MainActor enum MiruroWebView {
    static func make(frame: CGRect = .zero, configuration: WKWebViewConfiguration? = nil) -> WKWebView {
        let configuration = configuration ?? WKWebViewConfiguration()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let webView = WKWebView(frame: frame, configuration: configuration)
        webView.uiDelegate = MiruroPopupBlocker.shared
        return webView
    }
}

@MainActor final class MiruroPopupBlocker: NSObject, WKUIDelegate {
    static let shared = MiruroPopupBlocker()
    private(set) var blockedWindows: UInt = 0
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        blockedWindows &+= 1
        // Covers window.open, target=_blank, and subframe requests, including user-initiated ones.
        // Never redirect a popup into the hidden API request document.
        return nil
    }
}
