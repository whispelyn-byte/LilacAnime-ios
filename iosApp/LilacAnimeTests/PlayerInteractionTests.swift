import XCTest
@testable import LilacAnime

final class PlayerInteractionTests: XCTestCase {
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
