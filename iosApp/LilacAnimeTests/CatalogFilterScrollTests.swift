import XCTest
import SwiftUI
import UIKit
@testable import LilacAnime

final class CatalogFilterScrollTests: XCTestCase {
    @MainActor func testSelectionListKeepsScrollOffsetWhenItsParentRefreshes() async throws {
        let options = (0..<80).map { ("server-\($0)", "장르 \($0)") }
        let host = UIHostingController(rootView: CatalogOptionSheet(title: "장르", value: .constant(""), options: options))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = host; window.makeKeyAndVisible()
        defer { window.isHidden = true; window.rootViewController = nil }
        try await Task.sleep(nanoseconds: 100_000_000)
        host.view.layoutIfNeeded()
        func findScroll(_ view: UIView) -> UIScrollView? {
            if let scroll = view as? UIScrollView { return scroll }
            for child in view.subviews { if let scroll = findScroll(child) { return scroll } }
            return nil
        }
        let scroll = try XCTUnwrap(findScroll(host.view))
        XCTAssertGreaterThan(scroll.contentSize.height, scroll.bounds.height + 600)
        scroll.setContentOffset(CGPoint(x: 0, y: 600), animated: false)
        for _ in 0..<5 {
            // Match SwiftUI refreshing an ancestor while a long choice list is open.
            host.rootView = CatalogOptionSheet(title: "장르", value: .constant(""), options: options)
            try await Task.sleep(nanoseconds: 50_000_000)
            host.view.layoutIfNeeded()
            XCTAssertEqual(scroll.contentOffset.y, 600, accuracy: 1)
            XCTAssertTrue(findScroll(host.view) === scroll)
        }
    }

    @MainActor func testSelectionSheetPreservesAllOriginalServerOptions() {
        let original = [("Movie", "극장판"), ("MOVIE", "극장판"), ("Music", "뮤직비디오"), ("MUSIC", "뮤직비디오")]
        let sheet = CatalogOptionSheet(title: "형태", value: .constant("MOVIE"), options: original)
        XCTAssertEqual(sheet.options.map { $0.0 }, original.map { $0.0 })
        XCTAssertEqual(sheet.options.map { $0.1 }, original.map { $0.1 })
    }
}
