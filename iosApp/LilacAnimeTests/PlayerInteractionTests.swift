import XCTest
@testable import LilacAnime

final class PlayerInteractionTests: XCTestCase {
    func testIPadFitModesPreserveCropOrStretchAcrossRotation() {
        for viewport in [CGSize(width: 1024, height: 768), CGSize(width: 768, height: 1024)] {
            let contain = PlayerPresentation(aspect: .original, fit: .contain).stageSize(in: viewport)
            XCTAssertLessThanOrEqual(contain.width, viewport.width); XCTAssertLessThanOrEqual(contain.height, viewport.height)
            XCTAssertEqual(contain.width / contain.height, 16 / 9, accuracy: 0.001)
            let cover = PlayerPresentation(aspect: .original, fit: .cover).stageSize(in: viewport)
            XCTAssertGreaterThanOrEqual(cover.width, viewport.width); XCTAssertGreaterThanOrEqual(cover.height, viewport.height)
            XCTAssertEqual(cover.width / cover.height, 16 / 9, accuracy: 0.001)
            XCTAssertEqual(PlayerPresentation(aspect: .original, fit: .stretch).stageSize(in: viewport), viewport)
            XCTAssertEqual(PlayerPresentation(aspect: .original, fit: .contain).stageSize(in: viewport, sourceAspect: 4 / 3).width /
                           PlayerPresentation(aspect: .original, fit: .contain).stageSize(in: viewport, sourceAspect: 4 / 3).height, 4 / 3, accuracy: 0.001)
        }
    }
    @MainActor func testFitChoiceClearsAspectOverrideAndSurvivesConfigure() {
        let engine = MPVEngine(); engine.setAspect(.ultrawide)
        XCTAssertEqual(engine.presentation.mpvOptions["video-aspect-override"], "21:9")
        engine.setFit("cover")
        XCTAssertEqual(engine.presentation.mpvOptions, ["video-aspect-override": "no", "keepaspect": "yes", "panscan": "1"])
        var preferences = AppPreferences(); preferences.selectPlayerFit(.cover); engine.configure(preferences)
        XCTAssertEqual(engine.aspect, .original); XCTAssertEqual(engine.fit, "cover")
        engine.setAspect(.fill); engine.setFit("contain")
        XCTAssertEqual(engine.presentation.mpvOptions, ["video-aspect-override": "no", "keepaspect": "yes", "panscan": "0"])
        engine.setFit("stretch")
        XCTAssertEqual(engine.presentation.mpvOptions, ["video-aspect-override": "no", "keepaspect": "no", "panscan": "0"])
        preferences.selectPlayerAspect(.standard); engine.configure(preferences)
        XCTAssertEqual(engine.presentation.mpvOptions, ["video-aspect-override": "4:3", "keepaspect": "yes", "panscan": "0"])
    }
    func testOlderFillPreferencesKeepTheirMeaning() {
        var preferences = AppPreferences(); preferences.playerAspect = "fill"; preferences.playerFit = "contain"
        XCTAssertEqual(PlayerPresentation(preferences).fit, .stretch)
        preferences.selectPlayerFit(.contain)
        XCTAssertEqual(preferences.playerAspect, "original"); XCTAssertEqual(PlayerPresentation(preferences).fit, .contain)
    }
    func testAspectFitsInsideViewportWithoutCropping() {
        let size = CGSize(width: 1200, height: 600)
        XCTAssertEqual(PlayerAspect.widescreen.stageSize(in: size).width, 600 * 16 / 9, accuracy: 0.01)
        XCTAssertEqual(PlayerAspect.ultrawide.stageSize(in: size).height, 1200 * 9 / 21, accuracy: 0.01)
        XCTAssertEqual(PlayerAspect.standard.stageSize(in: size), CGSize(width: 800, height: 600))
        XCTAssertEqual(PlayerAspect.fill.stageSize(in: size), size)
    }
    @MainActor func testTouchBoostRestoresOriginalSpeedAndPauseCancelsIt() {
        let engine = MPVEngine(); engine.setSpeed(1.25); engine.play()
        engine.boostTouchHold(); XCTAssertEqual(engine.speed, 2); XCTAssertTrue(engine.speedBoosted)
        engine.finishSpaceHold(); XCTAssertEqual(engine.speed, 1.25); XCTAssertFalse(engine.paused)
        engine.boostTouchHold(); engine.pause()
        XCTAssertEqual(engine.speed, 1.25); XCTAssertFalse(engine.speedBoosted); XCTAssertTrue(engine.paused)
    }
    @MainActor func testUserSpeedChangeDuringHoldTakesPrecedence() {
        let engine = MPVEngine(); engine.play(); engine.boostTouchHold(); engine.setSpeed(1.5)
        engine.finishSpaceHold(); XCTAssertEqual(engine.speed, 1.5)
    }
    @MainActor func testShortSpacePressTogglesOnReleaseAndPausedHoldDoesNotBoost() async throws {
        let engine = MPVEngine(); engine.beginSpaceHold()
        XCTAssertTrue(engine.paused); engine.finishSpaceHold(toggle: true); XCTAssertFalse(engine.paused)
        engine.pause(); engine.beginSpaceHold(); try await Task.sleep(nanoseconds: 450_000_000)
        XCTAssertFalse(engine.speedBoosted); engine.finishSpaceHold(toggle: true); XCTAssertFalse(engine.paused)
        engine.beginSpaceHold(); engine.finishSpaceHold(); try await Task.sleep(nanoseconds: 450_000_000)
        XCTAssertEqual(engine.speed, 1); XCTAssertFalse(engine.speedBoosted)
    }
}
