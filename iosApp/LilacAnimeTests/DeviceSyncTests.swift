import XCTest
import LilacShared
@testable import LilacAnime

final class DeviceSyncTests: XCTestCase {
    func testAddressesBecomeServerOriginsLikeTheDesktop() throws {
        XCTAssertEqual(try DeviceSyncAddress.normalize("my-server.vercel.app/"), "https://my-server.vercel.app")
        XCTAssertEqual(try DeviceSyncAddress.normalize(" https://my-server.vercel.app/api "), "https://my-server.vercel.app")
        XCTAssertEqual(try DeviceSyncAddress.normalize("127.0.0.1:3000"), "http://127.0.0.1:3000")
        XCTAssertThrowsError(try DeviceSyncAddress.normalize("http://example.com"))
        XCTAssertThrowsError(try DeviceSyncAddress.normalize(""))
    }
    func testServerErrorsSayWhatToDo() {
        XCTAssertEqual(DeviceSyncAddress.serverError(["error": "비밀번호가 틀렸습니다."], status: 401), "비밀번호가 틀렸습니다.")
        XCTAssertTrue(DeviceSyncAddress.serverError(["error": ["code": "NOT_FOUND", "message": "x"]], status: 404).contains("LilacAnime 서버가 없습니다"))
        XCTAssertTrue(DeviceSyncAddress.serverError(["message": "Protected by Vercel"], status: 401).contains("Vercel 보호 기능"))
        XCTAssertTrue(DeviceSyncAddress.serverError(nil, status: 502).contains("HTTP 502"))
    }
    func testPlanSendsOnlyChangesAndRemovals() {
        let kept: [String: Any] = ["mal_id": "a", "savedAt": 5.0], edited: [String: Any] = ["mal_id": "b", "savedAt": 7.0]
        let history: [String: Any] = ["key": "h", "updated": 1234.0]
        let snapshot = ["library": ["a": DeviceSyncPlan.hash(kept), "b": "old", "gone": "x"], "history": [String: String]()]
        let items = DeviceSyncPlan.plan(["library": ["a": kept, "b": edited], "history": ["h": history]], snapshot: snapshot, now: 9999)
        let byID = Dictionary(uniqueKeysWithValues: items.map { ($0["id"] as! String, $0) })
        XCTAssertNil(byID["a"])
        XCTAssertEqual(byID["b"]?["updated"] as? Double, 9999)
        XCTAssertEqual(byID["gone"]?["deleted"] as? Bool, true)
        XCTAssertEqual(byID["h"]?["updated"] as? Double, 1234)
        let fresh = DeviceSyncPlan.plan(["library": ["n": ["mal_id": "n", "savedAt": 42.0]], "history": [:]], snapshot: [:], now: 9999)
        XCTAssertEqual(fresh.first?["updated"] as? Double, 42, "never synced: as old as when it was saved")
    }
    @MainActor func testItemsMadeHereComeBackUnchanged() throws {
        let anime = AnimeSnapshot.shared.decode(content: #"{"id":"reanime:frieren","title":"장송의 프리렌","poster":"https://p/x.jpg","source":"reanime","detailUrl":"https://reanime.to/anime/frieren","episodes":[{"id":"1","number":1,"title":"1화"}]}"#)
        var saved = SavedAnime(anime, source: "reanime"); saved.savedAt = Date(timeIntervalSince1970: 100)
        let item = DeviceSyncMapping.libraryItem(saved)
        XCTAssertEqual(DeviceSyncMapping.libraryID(saved), "reanime:frieren")
        XCTAssertEqual(item["id"] as? String, "frieren")
        XCTAssertEqual(item["mal_id"] as? String, "reanime:frieren")
        let back = try XCTUnwrap(DeviceSyncMapping.savedAnime(item, savedAt: saved.savedAt))
        XCTAssertEqual(back.id, saved.id)
        XCTAssertEqual(DeviceSyncPlan.hash(DeviceSyncMapping.libraryItem(back)), DeviceSyncPlan.hash(item))
    }
    @MainActor func testDesktopItemsBecomeThisAppsEntries() throws {
        let library: [String: Any] = ["provider": "animenosub", "id": "one-piece", "mal_id": "animenosub:one-piece", "title": "One Piece",
            "images": ["webp": ["large_image_url": "https://p/op.jpg"]], "year": 1999, "type": "TV", "episodes": 1100, "savedAt": 1791620000000.0]
        let saved = try XCTUnwrap(DeviceSyncMapping.savedAnime(library))
        XCTAssertEqual(saved.id, "animenosub:animenosub:one-piece")
        XCTAssertEqual(saved.anime.poster, "https://p/op.jpg"); XCTAssertEqual(saved.anime.year, "1999")
        XCTAssertEqual(DeviceSyncMapping.libraryID(saved), "animenosub:one-piece")
        let history: [String: Any] = ["key": "ohli24:https://ani.ohli24.com/e/x%203화", "anime": ["provider": "ohli24", "id": "x", "title": "x"],
            "episode": ["provider": "ohli24", "url": "https://ani.ohli24.com/e/x%203화", "number": 3, "name": "3"], "progress": 42, "updated": 1791620500000.0]
        let entry = try XCTUnwrap(DeviceSyncMapping.watchEntry(history))
        XCTAssertEqual(entry.number, 3); XCTAssertEqual(entry.episodeTitle, "3화"); XCTAssertEqual(entry.progressPercent, 42)
        XCTAssertEqual(entry.watchURL, "https://ani.ohli24.com/e/x%203화")
        XCTAssertEqual(DeviceSyncMapping.historyID(entry), "ohli24:https://ani.ohli24.com/e/x%203화")
        XCTAssertEqual(DeviceSyncMapping.historyItem(entry)?["progress"] as? Double, 42)
    }
    /// One history entry past the server's 512-character id refused every sync ("items[14]: id는 512자 이하의 문자열이어야 합니다").
    @MainActor func testLongEpisodeAddressesAreSentWithIdsTheServerTakes() throws {
        let title = String(repeating: "아주긴제목", count: 12)
        let url = "https://ani.ohli24.com/e/" + title.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed)! + "%203화"
        let history: [String: Any] = ["key": "ohli24:" + url, "anime": ["provider": "ohli24", "id": "x", "title": "x"],
            "episode": ["provider": "ohli24", "url": url, "number": 3, "name": "3"], "progress": 42, "updated": 1791620500000.0]
        let entry = try XCTUnwrap(DeviceSyncMapping.watchEntry(history))
        XCTAssertGreaterThan(("ohli24:" + url).utf16.count, 512)
        let id = try XCTUnwrap(DeviceSyncMapping.historyID(entry))
        XCTAssertEqual(id, "ohli24:https://ani.ohli24.com/e/" + title + " 3화")
        XCTAssertEqual(DeviceSyncMapping.historyItem(entry)?["key"] as? String, id)
        let longer = DeviceSyncMapping.fitted("ohli24:" + String(repeating: "가", count: 600), source: "ohli24")
        XCTAssertTrue(DeviceSyncPlan.fits(longer)); XCTAssertTrue(longer.hasPrefix("ohli24:#"))
        let items = DeviceSyncPlan.plan(["library": [:], "history": [String(repeating: "x", count: 600): ["updated": 1.0], id: ["updated": 2.0]]], snapshot: [:], now: 3)
        XCTAssertEqual(items.compactMap { $0["id"] as? String }, [id])
    }
    /// "슬라임 (3기)" is …%20%283기%29 here (Ktor) and …%20(3기) on the desktop (encodeURIComponent): one id for both.
    @MainActor func testParenthesesInAddressesNameTheSameEntryAsTheDesktop() {
        XCTAssertEqual(DeviceSyncMapping.fitted("ohli24:https://ani.ohli24.com/e/a%20%283%EA%B8%B0%29", source: "ohli24"), "ohli24:https://ani.ohli24.com/e/a%20(3%EA%B8%B0)")
        XCTAssertEqual(DeviceSyncMapping.fitted("ohli24:https://ani.ohli24.com/e/a%21%27%2a", source: "ohli24"), "ohli24:https://ani.ohli24.com/e/a!'*")
        XCTAssertEqual(DeviceSyncMapping.fitted("ohli24:https://ani.ohli24.com/e/a%20(3%EA%B8%B0)", source: "ohli24"), "ohli24:https://ani.ohli24.com/e/a%20(3%EA%B8%B0)")
    }
}

final class SavedAnimeTests: XCTestCase {
    func testDecodedSnapshotFollowsItsChangesAndIsNotSaved() throws {
        var saved = SavedAnime(AnimeSnapshot.shared.decode(content: #"{"id":"a","title":"하나"}"#), source: "reanime")
        let copy = saved
        XCTAssertEqual(saved.anime.title, "하나")
        saved.snapshot = AnimeSnapshot.shared.encode(anime: AnimeSnapshot.shared.decode(content: #"{"id":"a","title":"둘"}"#))
        XCTAssertEqual(saved.anime.title, "둘")
        XCTAssertEqual(copy.anime.title, "하나")
        let data = try JSONEncoder().encode(saved)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("decoded"))
        XCTAssertEqual(try JSONDecoder().decode(SavedAnime.self, from: data).anime.title, "둘")
    }
}

final class BackgroundWriterTests: XCTestCase {
    func testChangesCloseTogetherAreWrittenOnceWithTheLatestValue() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("state.json")
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let writer = BackgroundWriter()
        let encoded = Counter()
        for value in 1...50 {
            writer.write(file, after: 0.2) { encoded.add(); return Data(String(value).utf8) }
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "nothing is written on the caller's thread")
        writer.waitUntilWritten()
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "50")
        XCTAssertEqual(encoded.value, 1)
    }
    private final class Counter: @unchecked Sendable {
        private let lock = NSLock(); private var count = 0
        func add() { lock.lock(); count += 1; lock.unlock() }
        var value: Int { lock.lock(); defer { lock.unlock() }; return count }
    }
    @MainActor func testFractionalSuffixedAndSpecialEpisodeLabelsSurviveSyncAndHistory() throws {
        for label in ["12.5", "4a", "OVA 1"] {
            let history: [String: Any] = ["key": "reanime:https://fixture.test/episode", "anime": ["provider": "reanime", "id": "x", "title": "x"],
                "episode": ["provider": "reanime", "url": "https://fixture.test/episode", "number": Double(label) ?? 4, "name": label], "progress": 42, "updated": 1791620500000.0]
            let entry = try XCTUnwrap(DeviceSyncMapping.watchEntry(history))
            XCTAssertEqual(PlaybackItem(entry: entry).displayNumber, label)
            let restored = try JSONDecoder().decode(WatchEntry.self, from: JSONEncoder().encode(entry))
            XCTAssertEqual(restored.episodeLabel, label)
            let exported = try XCTUnwrap(DeviceSyncMapping.historyItem(restored)?["episode"] as? [String: Any])
            XCTAssertEqual(exported["name"] as? String, label)
            XCTAssertEqual(exported["special"] as? Bool, label == "OVA 1")
            if label == "12.5" { XCTAssertEqual(exported["number"] as? Double, 12.5) }
        }
    }
}
