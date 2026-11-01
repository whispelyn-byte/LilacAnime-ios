import XCTest
@testable import LilacAnime
import LilacShared

/// Desktop 0.5.56: the daily 추천 and the 시작 화면 setting.
final class DesktopHomeSettingsTests: XCTestCase {
    private func anime(_ id: String, poster: Bool = true) -> Anime {
        AnimeSnapshot.shared.decode(content: #"{"id":"\#(id)","title":"작품 \#(id)","poster":"\#(poster ? "https://img.test/\(id).jpg" : "")"}"#)
    }
    private func list(_ prefix: String, _ count: Int) -> [Anime] { (0..<count).map { anime("\(prefix)\($0)") } }

    func testSpotlightTakesFiveInTurnFromEachListWithItsLabel() {
        let picks = HomeSpotlight.picks(season: list("s", 10), airing: list("a", 10), top: list("t", 10), watchedIDs: [], watchedTitles: [], day: 20_000)
        XCTAssertEqual(picks.count, 5)
        XCTAssertEqual(picks.map(\.label), ["이번 시즌 신작", "지금 방영 중", "인기 작품", "이번 시즌 신작", "지금 방영 중"])
        XCTAssertEqual(Set(picks.map(\.anime.id)).count, 5)
    }
    func testSpotlightIsTheSameAllDayAndChangesTheNext() {
        let pick = { (day: Int) in HomeSpotlight.picks(season: self.list("s", 30), airing: [], top: self.list("t", 30), watchedIDs: [], watchedTitles: [], day: day).map(\.anime.id) }
        XCTAssertEqual(pick(20_000), pick(20_000))
        XCTAssertNotEqual(pick(20_000), pick(20_001))
    }
    func testSpotlightLeavesOutWatchedWorksAndOnesWithoutArt() {
        let season = list("s", 5) + [anime("bare", poster: false)]
        let picks = HomeSpotlight.picks(season: season, airing: [], top: [], watchedIDs: ["s0", "s1"], watchedTitles: ["작품 s2"], day: 1)
        // Fewer than three unwatched: the list is used as it is (still without the work that has no picture).
        XCTAssertEqual(Set(picks.map(\.anime.id)), ["s0", "s1", "s2", "s3", "s4"])
        let fresh = HomeSpotlight.picks(season: list("s", 8), airing: [], top: [], watchedIDs: ["s0"], watchedTitles: [], day: 1)
        XCTAssertFalse(fresh.contains { $0.anime.id == "s0" })
    }
    func testDayCountsInLocalTime() {
        let seoul = TimeZone(identifier: "Asia/Seoul")!
        // 2026-11-01 23:30 UTC is already 11-02 in Seoul.
        let date = ISO8601DateFormatter().date(from: "2026-11-01T23:30:00Z")!
        XCTAssertEqual(HomeSpotlight.today(date, zone: seoul), HomeSpotlight.today(date, zone: TimeZone(identifier: "UTC")!) + 1)
    }
    func testSeekPreviewKeepsAPicturePerFiveSecondSlot() {
        XCTAssertEqual(SeekPreview.slot(0), 0)
        XCTAssertEqual(SeekPreview.slot(2.4), 0)
        XCTAssertEqual(SeekPreview.slot(2.6), 1)
        XCTAssertEqual(SeekPreview.slot(61), 12)
        XCTAssertEqual(SeekPreview.slot(-3), 0)
        XCTAssertEqual(SeekPreview.readTime(slot: 12, duration: 1_400), 60)
        // The last slot is read just inside the episode's end.
        XCTAssertEqual(SeekPreview.readTime(slot: 280, duration: 1_398), 1_397.5)
    }
    @MainActor func testSeekPreviewWithoutAVideoShowsNothing() {
        let preview = SeekPreview()
        preview.show(30, duration: 1_400, video: nil)
        XCTAssertNil(preview.image)
        preview.show(30, duration: 0, video: SubtitleAutoSync.Video(url: URL(fileURLWithPath: "/nonexistent.mp4"), headers: [:], duration: 0))
        XCTAssertNil(preview.image)
    }
    @MainActor func testHomeListsAreKeptTenMinutesInMemoryAndADayOnDisk() {
        HomeSnapshot.directory = FileManager.default.temporaryDirectory.appendingPathComponent("HomeSnapshotTest-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: HomeSnapshot.directory); HomeSnapshot.forget() }
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let lists = HomeSnapshot.Lists(sections: [SourceSection(name: "이번 시즌", items: list("s", 50))], airing: list("a", 3))
        HomeSnapshot.save("animenosub|0", lists, now: start)
        XCTAssertEqual(HomeSnapshot.fresh("animenosub|0", now: start.addingTimeInterval(9 * 60))?.sections.first?.items.count, 50)
        XCTAssertNil(HomeSnapshot.fresh("animenosub|0", now: start.addingTimeInterval(11 * 60)))
        let kept = HomeSnapshot.read("animenosub|0", now: start.addingTimeInterval(23 * 3600))
        XCTAssertEqual(kept?.sections.first?.name, "이번 시즌")
        XCTAssertEqual(kept?.sections.first?.items.map(\.id), (0..<40).map { "s\($0)" })
        XCTAssertEqual(kept?.airing.map(\.id), ["a0", "a1", "a2"])
        XCTAssertNil(HomeSnapshot.read("animenosub|0", now: start.addingTimeInterval(25 * 3600)))
        XCTAssertNil(HomeSnapshot.read("reanime|0", now: start))
    }
    func testStartSectionFallsBackToHome() {
        let defaults = UserDefaults(suiteName: "start-view-test")!
        defaults.removePersistentDomain(forName: "start-view-test")
        XCTAssertEqual(DesktopNavigation.startSection(defaults), "home")
        defaults.set("favorites", forKey: "startView")
        XCTAssertEqual(DesktopNavigation.startSection(defaults), "favorites")
        defaults.set("settings", forKey: "startView")
        XCTAssertEqual(DesktopNavigation.startSection(defaults), "home")
    }
}
