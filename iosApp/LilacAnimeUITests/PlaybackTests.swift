import XCTest

final class PlaybackTests: XCTestCase {
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
        XCTAssertTrue(app.segmentedControls["플레이어 설정"].waitForExistence(timeout: 5))
        app.segmentedControls.buttons["자막 모양"].tap()
        XCTAssertTrue(app.staticTexts["글꼴"].exists || app.textFields.count > 0)
        app.buttons["닫기"].tap()
        if !app.buttons["뒤로"].isHittable { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        app.buttons["뒤로"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 10))
    }
}
