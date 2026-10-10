import XCTest
import LilacShared
@testable import LilacAnime

final class TesterRegressionTests: XCTestCase {
    @MainActor func testDownloadTapQueuesOnceAndReportsDuplicate() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: "{\"id\":\"\(UUID().uuidString)\",\"title\":\"테스트\",\"episodes\":[{\"id\":\"1\",\"number\":1,\"title\":\"1화\"}]}"), source: "linkkf")
        let item = PlaybackItem(anime: anime, episode: anime.anime.episodes[0])
        let store = DownloadStore(index: folder.appendingPathComponent("index.json"), restoreSession: false)
        XCTAssertEqual(store.resolver.webView.bounds.width, 960)
        XCTAssertEqual(store.resolver.webView.bounds.height, 640)
        XCTAssertTrue(store.resolver.silent)
        let stream = ResolvedStream(label: "video", url: URL(string: "https://fixture.test/video.mp4")!, referer: "", headers: [:])
        defer { store.pauseAll(); try? FileManager.default.removeItem(at: folder); try? FileManager.default.removeItem(at: DownloadStore.directory.appendingPathComponent(SubtitleFiles.key(anime.id + "#1"))) }
        store.download(item, stream: stream)
        XCTAssertEqual(store.entries.count, 1); XCTAssertEqual(store.entries.first?.status, "준비 중")
        XCTAssertTrue(store.notice?.contains("시작") == true)
        store.download(item, stream: stream)
        XCTAssertEqual(store.entries.count, 1); XCTAssertTrue(store.notice?.contains("이미") == true)
    }
    func testManualSubtitleImportNormalizesAndCopiesIntoAppStorage() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("내 자막.srt")
        try "1\n00:00:00,000 --> 00:00:01,000\n사용자가 선택한 자막".write(to: file, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: directory); try? FileManager.default.removeItem(at: SubtitleFiles.root.appendingPathComponent(SubtitleFiles.key(file.absoluteString))) }
        let imported = try await SubtitleFiles.prepare(file)
        let copied = try XCTUnwrap(imported.first)
        try FileManager.default.removeItem(at: file)
        XCTAssertTrue(copied.path.hasPrefix(SubtitleFiles.root.path + "/"))
        XCTAssertTrue(try SubtitleFiles.text(copied).contains("사용자가 선택한 자막"))
    }
    @MainActor func testMissingCloudLinesRetryWithoutDiscardingGoodDialogue() async throws {
        let lines = (0..<50).map { "source \($0)" }
        let cues = lines.indices.map { SubtitleCue(startSeconds: Double($0), endSeconds: Double($0 + 1), text: lines[$0]) }
        var kept: [String: String] = [:]
        var requests: [[String]] = []
        let service = IosServices()
        try await CloudSubtitleScheduler.translate(lines: lines, cues: cues, provider: "gemini",
            config: TranslationConfig(provider: "gemini", key: "fixture", model: "", region: "international", terminology: ""), service: service,
            position: { 0 }, cached: { kept }, request: { batch in
                requests.append(batch)
                return batch.map { line in line == "source 0" && requests.count == 1 ? "" : "번역 " + line }
            }, save: { additions in kept.merge(additions) { old, _ in old } })
        XCTAssertEqual(kept.count, 50)
        XCTAssertEqual(kept["source 0"], "번역 source 0")
        XCTAssertEqual(requests.flatMap { $0 }.filter { $0 == "source 1" }.count, 1)
        XCTAssertEqual(requests.flatMap { $0 }.filter { $0 == "source 0" }.count, 2)
    }
    @MainActor func testUntranslatableLineDoesNotStopRemainingBatches() async {
        let lines = (0..<90).map { "source \($0)" }
        let cues = lines.indices.map { SubtitleCue(startSeconds: Double($0), endSeconds: Double($0 + 1), text: lines[$0]) }
        var kept: [String: String] = [:]
        let service = IosServices()
        do {
            try await CloudSubtitleScheduler.translate(lines: lines, cues: cues, provider: "deepl",
                config: TranslationConfig(provider: "deepl", key: "fixture", model: "", region: "international", terminology: ""), service: service,
                position: { 0 }, cached: { kept }, request: { batch in batch.map { $0 == "source 0" ? "" : "번역 " + $0 } },
                save: { additions in kept.merge(additions) { old, _ in old } })
            XCTFail("A missing line must remain retryable by the next provider")
        } catch { XCTAssertTrue(error.localizedDescription.contains("누락")) }
        XCTAssertEqual(kept.count, 89); XCTAssertNil(kept["source 0"])
    }
    func testApplicationContainsInstalledPhoneAndTabletIcons() throws {
        let icons = try XCTUnwrap(Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any])
        XCTAssertNotNil(icons["CFBundlePrimaryIcon"])
        XCTAssertNotNil(Bundle.main.url(forResource: "AppIcon60x60@2x", withExtension: "png"))
        XCTAssertNotNil(Bundle.main.infoDictionary?["CFBundleIcons~ipad"])
    }
}
