import XCTest
@testable import LilacAnime
final class TranslationPriorityTests: XCTestCase {
    func testSeekPrioritizesCurrentAndFutureBeforePast() {
        XCTAssertEqual(TranslationPriority.indices(starts: [0, 20, 40, 60], ends: [10, 30, 50, 70], lines: ["a", "b", "c", "d"], translated: [:], position: 45, limit: 3), [2, 3, 0])
    }
    func testCachedAndRepeatedDialogueIsNotTranslatedAgain() {
        XCTAssertEqual(TranslationPriority.indices(starts: [0, 10, 20, 30], ends: [5, 15, 25, 35], lines: ["done", "same", "same", "new"], translated: ["done": "translated"], position: 20, limit: 16), [3, 1])
    }
}
