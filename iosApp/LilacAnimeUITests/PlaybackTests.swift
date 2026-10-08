import XCTest

final class PlaybackTests: XCTestCase {
    func testScreenHoldKeepsPlaybackRunningAfterRelease() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-preview", "player"]; app.launch()
        let play = app.buttons["재생"]
        XCTAssertTrue(play.waitForExistence(timeout: 15)); play.tap()
        XCTAssertTrue(app.buttons["일시정지"].waitForExistence(timeout: 5))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.35)).press(forDuration: 1)
        XCTAssertTrue(app.buttons["일시정지"].exists)
        XCTAssertFalse(app.staticTexts["speed-boost"].exists)
    }
    func testWorkspaceMenuCanReachCatalog() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-preview", "workspace"]; app.launch()
        let menu = app.buttons["workspace-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15)); XCTAssertTrue(menu.isHittable)
        menu.tap()
        let catalog = app.buttons["전체"].firstMatch
        if !catalog.isHittable { menu.tap() }
        XCTAssertTrue(catalog.waitForExistence(timeout: 5)); catalog.tap()
        XCTAssertTrue(app.buttons["catalog-filter-apply"].waitForExistence(timeout: 10))
    }
    func testEpisodeOpensFullscreenAndBackReturnsToDetail() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-preview", "detail"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        let play = app.buttons["detail-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        play.tap()
        let settings = app.buttons["플레이어 설정"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        let landscape = NSPredicate { _, _ in app.windows.firstMatch.frame.width > app.windows.firstMatch.frame.height }
        expectation(for: landscape, evaluatedWith: app)
        waitForExpectations(timeout: 10)
        XCTAssertFalse(app.buttons["전체 화면 해제"].exists)
        XCTAssertFalse(app.buttons["전체 화면"].exists)
        settings.tap()
        let tabs = app.segmentedControls["player-settings-tabs"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 5))
        tabs.buttons["자막 모양"].tap()
        XCTAssertTrue(app.staticTexts["글꼴"].exists || app.textFields.count > 0)
        app.buttons["닫기"].tap()
        if !app.buttons["뒤로"].isHittable { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        app.buttons["뒤로"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 10))
    }
}
