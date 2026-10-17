import XCTest
@testable import LilacAnime

final class WeeklyScheduleTests: XCTestCase {
    func testScheduleTitleMatchingPreservesSeasonAndNormalizesPunctuation() {
        XCTAssertEqual(ScheduleTitles.key("ＡＢＣ： 애니!"), ScheduleTitles.key("abc 애니"))
        XCTAssertNotEqual(ScheduleTitles.key("애니 2기"), ScheduleTitles.key("애니 3기"))
        XCTAssertNotEqual(ScheduleTitles.key("애니 극장판"), ScheduleTitles.key("애니"))
    }
    func testKoreanMondayBoundaryAndSundayRemainInTheirWeek() {
        let formatter = ISO8601DateFormatter()
        let sunday = ScheduleWeek.dates(now: formatter.date(from: "2026-10-18T14:59:00Z")!)
        XCTAssertEqual(ScheduleWeek.key(sunday[0]), "2026-10-12")
        XCTAssertEqual(ScheduleWeek.key(sunday[6]), "2026-10-18")
        let monday = ScheduleWeek.dates(now: formatter.date(from: "2026-10-18T15:00:00Z")!)
        XCTAssertEqual(ScheduleWeek.key(monday[0]), "2026-10-19")
        XCTAssertEqual(ScheduleWeek.key(monday[6]), "2026-10-25")
    }
    func testWeekCrossesYearWithoutDroppingDates() {
        let dates = ScheduleWeek.dates(now: ISO8601DateFormatter().date(from: "2027-01-01T00:00:00Z")!)
        XCTAssertEqual(dates.count, 7)
        XCTAssertEqual(ScheduleWeek.key(dates[0]), "2026-12-28")
        XCTAssertEqual(ScheduleWeek.key(dates[6]), "2027-01-03")
    }
}
