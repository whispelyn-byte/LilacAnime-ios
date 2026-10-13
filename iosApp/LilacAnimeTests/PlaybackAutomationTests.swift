import XCTest
import LilacShared
@testable import LilacAnime

final class PlaybackAutomationTests: XCTestCase {
    func testCompletionSignalsAdvanceOnlyOnceAndAllowReplay() {
        var completion = PlaybackCompletion()
        XCTAssertTrue(completion.reachedEnd()) // eof-reached with keep-open
        XCTAssertFalse(completion.reachedEnd()) // repeated property / END_FILE
        completion.resume() // seek back and replay the same episode
        XCTAssertTrue(completion.reachedEnd())
        completion.reset() // load the next episode
        XCTAssertTrue(completion.reachedEnd())
    }
}
