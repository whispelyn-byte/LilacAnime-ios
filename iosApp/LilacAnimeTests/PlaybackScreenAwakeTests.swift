import XCTest
import UIKit
@testable import LilacAnime

final class PlaybackScreenAwakeTests: XCTestCase {
    @MainActor func testPlaybackBackgroundResumeAndPauseRestoreAutoLock() {
        let original = UIApplication.shared.isIdleTimerDisabled
        UIApplication.shared.isIdleTimerDisabled = false
        let screen = PlaybackScreenAwake()
        defer { screen.release(); UIApplication.shared.isIdleTimerDisabled = original }
        screen.update(playing: true, active: true)
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled)
        screen.update(playing: true, active: false)
        XCTAssertFalse(UIApplication.shared.isIdleTimerDisabled)
        screen.update(playing: true, active: true)
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled)
        screen.update(playing: false, active: true)
        XCTAssertFalse(UIApplication.shared.isIdleTimerDisabled)
    }

    @MainActor func testOutgoingPlayerCannotUnlockReplacementPlayer() {
        let original = UIApplication.shared.isIdleTimerDisabled
        UIApplication.shared.isIdleTimerDisabled = false
        let first = PlaybackScreenAwake(), second = PlaybackScreenAwake()
        defer { first.release(); second.release(); UIApplication.shared.isIdleTimerDisabled = original }
        first.update(playing: true, active: true)
        second.update(playing: true, active: true)
        first.release()
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled)
        second.release()
        XCTAssertFalse(UIApplication.shared.isIdleTimerDisabled)
    }

    @MainActor func testExistingScreenLockPreferenceIsPreserved() {
        let original = UIApplication.shared.isIdleTimerDisabled
        UIApplication.shared.isIdleTimerDisabled = true
        let screen = PlaybackScreenAwake()
        defer { screen.release(); UIApplication.shared.isIdleTimerDisabled = original }
        screen.update(playing: true, active: true)
        screen.release()
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled)
    }
}
