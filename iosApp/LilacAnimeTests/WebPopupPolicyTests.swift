import XCTest
import WebKit
@testable import LilacAnime

@MainActor final class WebPopupPolicyTests: XCTestCase {
    private func page(configuration: WKWebViewConfiguration? = nil) async throws -> WKWebView {
        let web = ProviderWebView.make(configuration: configuration)
        web.loadHTMLString("""
        <p id="fixture">parent</p><a id="blank" target="_blank" href="about:blank#popup">open</a>
        <iframe srcdoc="<a id='blank' target='_blank' href='about:blank#frame'>open</a>"></iframe>
        """, baseURL: URL(string: "https://fixture.invalid/"))
        for _ in 0..<300 {
            if (try? await web.evaluateJavaScript("!!document.getElementById('fixture') && !!frames[0]?.document.getElementById('blank')")) as? Bool == true { return web }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        throw NSError(domain: "WebPopupPolicyTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "Local popup fixture did not load"])
    }

    func testWindowOpenBlankTargetsAndIframePopupsCannotReplaceTheDocument() async throws {
        let web = try await page()
        defer { web.stopLoading() }
        XCTAssertFalse(web.configuration.preferences.javaScriptCanOpenWindowsAutomatically)
        let automatic = try await web.evaluateJavaScript("window.open('about:blank') === null")
        XCTAssertEqual(automatic as? Bool, true)

        // Allow script-created requests in the fixture so the native deny delegate is exercised too.
        web.configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        let before = PagePopupBlocker.shared.blockedWindows
        let main = try await web.evaluateJavaScript("window.open('about:blank') === null")
        let child = try await web.evaluateJavaScript("frames[0].open('about:blank') === null")
        XCTAssertEqual(main as? Bool, true)
        XCTAssertEqual(child as? Bool, true)
        _ = try await web.evaluateJavaScript("document.getElementById('blank').click(); frames[0].document.getElementById('blank').click();")
        for _ in 0..<100 {
            if PagePopupBlocker.shared.blockedWindows >= before + 4 { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertGreaterThanOrEqual(PagePopupBlocker.shared.blockedWindows, before + 4)
        let text = try await web.evaluateJavaScript("document.getElementById('fixture').textContent")
        XCTAssertEqual(text as? String, "parent")
    }

    func testAppCreatedViewsRetainSessionPlaybackAndCaptureConfiguration() async throws {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.userContentController.addUserScript(WKUserScript(source: "window.fixtureToken = 42", injectionTime: .atDocumentStart, forMainFrameOnly: false))
        let player = try await page(configuration: configuration), authentication = try await page()
        defer { player.stopLoading(); authentication.stopLoading() }
        XCTAssertFalse(player === authentication)
        XCTAssertFalse(player.configuration.websiteDataStore.isPersistent)
        XCTAssertTrue(player.configuration.allowsInlineMediaPlayback)
        XCTAssertEqual(player.configuration.mediaTypesRequiringUserActionForPlayback, [])
        let token = try await player.evaluateJavaScript("window.fixtureToken")
        let title = try await authentication.evaluateJavaScript("document.getElementById('fixture').textContent")
        XCTAssertEqual(token as? Int, 42)
        XCTAssertEqual(title as? String, "parent")
    }
}
