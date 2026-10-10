import XCTest
import LilacShared
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
    func testTitleCacheUsesSevenDaysForDisplayAndThirtyDaysForBulk() {
        let date = Date(timeIntervalSince1970: 1000)
        let record = CatalogName(korean: "", english: "Title", overview: "", aliases: [], cast: [], anilist: 1, mal: 1, updated: date, credential: "key", titleLookupRevision: 1, castPrepared: true)
        XCTAssertTrue(record.isFresh(credential: "key", cast: true, now: date.addingTimeInterval(7 * 86400 - 1)))
        XCTAssertFalse(record.isFresh(credential: "key", cast: false, now: date.addingTimeInterval(7 * 86400)))
        XCTAssertTrue(record.isFresh(credential: "key", cast: true, bulk: true, now: date.addingTimeInterval(30 * 86400 - 1)))
        XCTAssertFalse(record.isFresh(credential: "key", cast: false, bulk: true, now: date.addingTimeInterval(30 * 86400)))
        XCTAssertFalse(record.isFresh(credential: "new", cast: false, now: date))
    }
    func testExistingCatalogMetadataRefreshPreservesOrderAndDeduplicatesNewItems() {
        func item(_ id: String, _ title: String) -> SavedAnime {
            SavedAnime(AnimeSnapshot.shared.decode(content: "{\"id\":\"\(id)\",\"title\":\"\(title)\"}"), source: "reanime")
        }
        let result = CatalogIndexMerge.merge([item("1", "old"), item("2", "keep")], incoming: [item("1", "new"), item("3", "add"), item("3", "latest")])
        XCTAssertEqual(result.map(\.id), ["reanime:1", "reanime:2", "reanime:3"])
        XCTAssertEqual(result.map(\.title), ["new", "keep", "latest"])
    }
    func testCatalogRefreshesDailyAndKeepsOldSnapshotUntilThen() {
        let date = Date(timeIntervalSince1970: 1000)
        XCTAssertTrue(CatalogIndexMerge.needsRefresh(updated: nil, now: date))
        XCTAssertFalse(CatalogIndexMerge.needsRefresh(updated: date, now: date.addingTimeInterval(86400 - 1)))
        XCTAssertTrue(CatalogIndexMerge.needsRefresh(updated: date, now: date.addingTimeInterval(86400)))
    }
    func testLegacyTitleLookupCacheRefreshesOnceAndExpiresAfterSevenDays() throws {
        let suite = "TitleLookupCacheTests-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let date = Date(timeIntervalSince1970: 1000)
        defaults.set("legacy", forKey: "title")
        XCTAssertNil(TitleLookupCache.read("title", defaults: defaults, now: date))
        TitleLookupCache.write("fresh", key: "title", defaults: defaults, now: date)
        XCTAssertEqual(TitleLookupCache.read("title", defaults: defaults, now: date.addingTimeInterval(7 * 86400 - 1)), "fresh")
        XCTAssertNil(TitleLookupCache.read("title", defaults: defaults, now: date.addingTimeInterval(7 * 86400)))
        XCTAssertEqual(defaults.string(forKey: "title"), "fresh")
    }
}
