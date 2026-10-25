import XCTest
@testable import LilacAnime

final class PlayerSeekHintTests: XCTestCase {
    func testRepeatedKeysAccumulateBeforeEngineReportsNewPosition() throws {
        let first = try XCTUnwrap(PlayerSeekHint.relative(10, position: 30, duration: 120, previous: nil, now: 1))
        let second = try XCTUnwrap(PlayerSeekHint.relative(10, position: 30, duration: 120, previous: first, now: 1.2))
        XCTAssertEqual(second.target, 50); XCTAssertEqual(second.seconds, 20)
        XCTAssertNotEqual(first.id, second.id)
        let reverse = try XCTUnwrap(PlayerSeekHint.relative(-10, position: 30, duration: 120, previous: second, now: 1.3))
        XCTAssertEqual(reverse.target, 40); XCTAssertEqual(reverse.seconds, -10)
    }
    func testExpiredHintUsesCurrentPositionAndResetsTotal() throws {
        let old = PlayerSeekHint.relative(10, position: 30, duration: 120, previous: nil, now: 1)
        let next = try XCTUnwrap(PlayerSeekHint.relative(10, position: 80, duration: 120, previous: old, now: 2))
        XCTAssertEqual(next.target, 90); XCTAssertEqual(next.seconds, 10)
        XCTAssertTrue(next.isActive(at: 2.79)); XCTAssertFalse(next.isActive(at: 2.81))
    }
    func testSeeksStayWithinMediaBoundsAndUnknownDurationStillAllowsArrows() throws {
        XCTAssertEqual(PlayerSeekHint.relative(-10, position: 3, duration: 120, previous: nil)?.target, 0)
        XCTAssertEqual(PlayerSeekHint.relative(10, position: 115, duration: 120, previous: nil)?.target, 120)
        XCTAssertEqual(PlayerSeekHint.relative(10, position: 3, duration: 0, previous: nil)?.target, 13)
        XCTAssertNil(PlayerSeekHint.relative(.nan, position: 3, duration: 120, previous: nil))
        XCTAssertNil(PlayerSeekHint.relative(10, position: .nan, duration: 120, previous: nil))
    }
    func testNumberKeysShowOnlyTimeAndRequireKnownDuration() throws {
        let jump = try XCTUnwrap(PlayerSeekHint.jump(5, duration: 120, now: 1))
        XCTAssertNil(jump.seconds); XCTAssertEqual(jump.target, 60)
        XCTAssertEqual(PlayerSeekHint.jump(0, duration: 120)?.target, 0)
        XCTAssertEqual(PlayerSeekHint.jump(9, duration: 120)?.target, 108)
        XCTAssertNil(PlayerSeekHint.jump(5, duration: 0)); XCTAssertNil(PlayerSeekHint.jump(5, duration: .infinity))
        XCTAssertNil(PlayerSeekHint.jump(10, duration: 120))
        let next = try XCTUnwrap(PlayerSeekHint.relative(10, position: 0, duration: 120, previous: jump, now: 1.2))
        XCTAssertEqual(next.target, 70); XCTAssertEqual(next.seconds, 10)
    }
}
