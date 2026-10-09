import XCTest
@testable import LilacAnime

final class CatalogRetryTests: XCTestCase {
    func testLegacyMissRetriesButKnownTitleIsPreserved() throws {
        let date = Date(timeIntervalSince1970: 1000)
        var record = CatalogName(korean: "", english: "Title", overview: "", aliases: [], cast: [], anilist: 1, mal: 1, updated: date, credential: "key")
        XCTAssertFalse(record.isFresh(credential: "key", cast: false, now: date))
        record.korean = "기존 제목"
        XCTAssertTrue(record.isFresh(credential: "key", cast: false, now: date))
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(record)) as? [String: Any])
        object.removeValue(forKey: "titleLookupRevision"); object.removeValue(forKey: "castPrepared")
        let old = try JSONDecoder().decode(CatalogName.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(old.korean, "기존 제목"); XCTAssertNil(old.titleLookupRevision)
    }
    func testSuccessfulMissLastsThirtyDaysAndCredentialChangeRetries() {
        let date = Date(timeIntervalSince1970: 1000)
        let record = CatalogName(korean: "", english: "Title", overview: "", aliases: [], cast: [], anilist: 1, mal: 1, updated: date, credential: "key", titleLookupRevision: 1, castPrepared: true)
        XCTAssertTrue(record.isFresh(credential: "key", cast: true, now: date.addingTimeInterval(30 * 86400 - 1)))
        XCTAssertFalse(record.isFresh(credential: "key", cast: false, now: date.addingTimeInterval(30 * 86400)))
        XCTAssertFalse(record.isFresh(credential: "new", cast: false, now: date))
    }
}
