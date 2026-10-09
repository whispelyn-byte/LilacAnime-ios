import XCTest

final class PlaybackTests: XCTestCase {
    func testLocalModelsBackAndWorkspaceNavigation() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-preview", "workspace"]; app.launch()
        let settings = app.buttons["workspace-settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 15)); settings.tap()
        let category = app.buttons["settings-category-translate"]
        for _ in 0..<3 { if category.isHittable { break }; app.scrollViews["settings-categories"].swipeLeft(velocity: .slow) }
        XCTAssertTrue(category.isHittable); category.tap()
        let models = app.buttons["settings-local-models"]
        let form = app.descendants(matching: .any)["settings-form"].firstMatch
        for _ in 0..<6 { if models.exists && models.isHittable { break }; dragUp(form) }
        XCTAssertTrue(models.isHittable); models.tap()
        let list = app.descendants(matching: .any)["local-models-list"].firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 5))
        let back = app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", ["설정", "Back", "뒤로"])).firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5)); XCTAssertTrue(back.isHittable); back.tap()
        XCTAssertTrue(models.waitForExistence(timeout: 5)); XCTAssertFalse(list.exists)
        models.tap(); XCTAssertTrue(list.waitForExistence(timeout: 5))
        let menu = app.buttons["workspace-menu"]; menu.tap()
        let home = app.buttons["workspace-route-home"]
        if !home.isHittable { menu.tap() }
        XCTAssertTrue(home.waitForExistence(timeout: 5)); home.tap()
        XCTAssertTrue(app.buttons["전체 작품 둘러보기"].waitForExistence(timeout: 10))
        XCTAssertFalse(list.exists)
        settings.tap()
        XCTAssertTrue(app.staticTexts["화면과 콘텐츠"].waitForExistence(timeout: 5))
        XCTAssertFalse(list.exists)
    }
    func testPlayerPanelAndFitModes() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-preview", "player"]; app.launch()
        XCTAssertTrue(app.buttons["플레이어 설정"].waitForExistence(timeout: 15))
        let landscape = NSPredicate { _, _ in app.windows.firstMatch.frame.width > app.windows.firstMatch.frame.height }
        expectation(for: landscape, evaluatedWith: app); waitForExpectations(timeout: 10)
        selectPlayerOption(app, identifier: "player-aspect-21:9")
        selectPlayerOption(app, identifier: "player-fit-contain")
        let video = app.descendants(matching: .any)["preview-video-frame"].firstMatch
        XCTAssertTrue(video.waitForExistence(timeout: 5))
        let viewport = app.windows.firstMatch.frame
        let contain = video.frame
        XCTAssertEqual(contain.width / contain.height, 16 / 9, accuracy: 0.03)
        XCTAssertTrue(contain.width < viewport.width - 10 || contain.height < viewport.height - 10)
        selectPlayerOption(app, identifier: "player-fit-cover")
        XCTAssertGreaterThanOrEqual(video.frame.width, viewport.width - 2)
        XCTAssertGreaterThanOrEqual(video.frame.height, viewport.height - 2)
        selectPlayerOption(app, identifier: "player-fit-stretch")
        XCTAssertEqual(video.frame.width, viewport.width, accuracy: 2)
        XCTAssertEqual(video.frame.height, viewport.height, accuracy: 2)
        app.buttons["플레이어 설정"].tap()
        let panel = app.otherElements["player-settings-panel"]
        XCTAssertTrue(panel.waitForExistence(timeout: 5))
        // The accessibility container includes the right safe-area padding on notched iPhones.
        XCTAssertLessThanOrEqual(panel.frame.width, min(480, viewport.width))
        XCTAssertGreaterThanOrEqual(panel.frame.minX, viewport.minX)
        XCTAssertLessThanOrEqual(panel.frame.maxY, viewport.maxY)
        for tab in 0...2 { app.buttons["player-settings-tab-\(tab)"].tap() }
        app.buttons["닫기"].tap()
        XCTAssertFalse(panel.exists)
    }
    private func selectPlayerOption(_ app: XCUIApplication, identifier: String) {
        app.buttons["플레이어 설정"].tap()
        XCTAssertTrue(app.buttons["player-settings-tab-0"].waitForExistence(timeout: 5))
        app.buttons["player-settings-tab-0"].tap()
        let option = app.buttons[identifier]
        // Stop each short drag before lifting the finger so momentum cannot skip an option group.
        let scroll = app.scrollViews["player-settings-scroll"]
        for _ in 0..<10 {
            if option.exists && option.isHittable { break }
            dragUp(scroll)
        }
        XCTAssertTrue(option.isHittable); option.tap()
        app.buttons["닫기"].tap()
    }
    private func dragUp(_ element: XCUIElement) {
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8)).press(forDuration: 0.05,
            thenDragTo: element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)),
            withVelocity: .slow, thenHoldForDuration: 0.2)
    }
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
        let style = app.buttons["player-settings-tab-2"]
        XCTAssertTrue(style.waitForExistence(timeout: 5))
        style.tap()
        XCTAssertTrue(app.staticTexts["글꼴"].exists || app.textFields.count > 0)
        app.buttons["닫기"].tap()
        if !app.buttons["뒤로"].isHittable { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        app.buttons["뒤로"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 10))
    }
}
