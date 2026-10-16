import XCTest
import LilacShared
@testable import LilacAnime

final class DownloadCancellationTests: XCTestCase {
    @MainActor func testStoppingAnEpisodeDuringAddressPreparationRejectsItsLateResult() async throws {
        try await checkCancellation(group: false)
    }
    @MainActor func testStoppingASeriesDuringAddressPreparationRejectsItsLateResult() async throws {
        try await checkCancellation(group: true)
    }
    @MainActor private func checkCancellation(group: Bool) async throws {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: #"{"id":"cancel-test","title":"test","episodes":[{"id":"1","number":1,"title":"1화"}]}"#), source: "reanime")
        let item = PlaybackItem(anime: anime, episode: anime.anime.episodes[0])
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        var pending: CheckedContinuation<Bool, Never>?
        let started = expectation(description: "preparing address")
        let store = DownloadStore(index: folder.appendingPathComponent("index.json"), restoreSession: false, prepareRaw: { _ in
            await withCheckedContinuation { continuation in pending = continuation; started.fulfill() }
        })
        store.enqueue([item], quality: "Auto")
        await fulfillment(of: [started], timeout: 2)
        XCTAssertEqual(store.state(item), .active)
        store.enqueue([item], quality: "Auto")
        XCTAssertEqual(store.pendingResolution, 1, "the item being resolved must not be queued twice")
        if group { store.stopGroup(item.anime.id) } else { store.toggle(item, quality: "Auto") }
        pending?.resume(returning: false)
        for _ in 0..<10 { await Task.yield() }
        XCTAssertEqual(store.state(item), .idle)
        XCTAssertEqual(store.pendingResolution, 0)
        XCTAssertTrue(store.entries.isEmpty)
        XCTAssertFalse(store.resolver.loading, "a cancelled preparation must not open the stream resolver")
        store.stopResolving()
    }
    func testRepeatedExportsKeepEarlierExportIntact() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let first = try await DownloadTransfer.export([], to: root)
        let marker = first.appendingPathComponent("keep.txt")
        try Data("first export".utf8).write(to: marker)
        let second = try await DownloadTransfer.export([], to: root)
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(try Data(contentsOf: marker), Data("first export".utf8))
        XCTAssertTrue(FileManager.default.fileExists(atPath: second.appendingPathComponent("index.json").path))
    }
}
