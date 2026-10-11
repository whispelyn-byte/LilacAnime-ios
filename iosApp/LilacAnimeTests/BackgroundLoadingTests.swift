import XCTest
import LilacShared
@testable import LilacAnime

final class BackgroundLoadingTests: XCTestCase {
    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }
    @MainActor func testDeferredLibraryRestoresOnceAndKeepsSubsequentChanges() async throws {
        let file = try folder().appendingPathComponent("library.json")
        struct Saved: Encodable { let preferences: AppPreferences; let favorites: [SavedAnime]; let history: [WatchEntry] }
        var preferences = AppPreferences(); preferences.subtitleFont = "사용자 글꼴"; preferences.theme = "light"
        try JSONEncoder().encode(Saved(preferences: preferences, favorites: [], history: [])).write(to: file)
        let store = LibraryStore(file: file, deferLoading: true)
        XCTAssertEqual(store.preferences.theme, "dark")
        async let first: Void = store.load()
        async let second: Void = store.load()
        _ = await (first, second)
        XCTAssertEqual(store.preferences.theme, "light")
        XCTAssertEqual(store.preferences.subtitleFont, "사용자 글꼴")
        store.preferences.theme = "system"
        await store.load()
        XCTAssertEqual(store.preferences.theme, "system")
        BackgroundWriter.shared.waitUntilWritten()
    }
    @MainActor func testDeferredSubtitleIndexKeepsRecordsAddedAfterRestore() async throws {
        let file = try folder().appendingPathComponent("subtitles.json")
        let record = SavedSubtitle(id: "saved", episodeKey: "series#1", name: "이전 자막", relativeFile: "old.srt", provider: "user", translated: false, date: Date())
        try JSONEncoder().encode([record]).write(to: file)
        let store = EpisodeSubtitleStore(index: file, deferLoading: true)
        XCTAssertTrue(store.records.isEmpty)
        await store.load()
        XCTAssertEqual(store.records.map(\.id), ["saved"])
        store.remove("saved")
        await store.load()
        XCTAssertTrue(store.records.isEmpty)
        BackgroundWriter.shared.waitUntilWritten()
    }
    @MainActor func testSubtitleSavedBeforeTheIndexIsReadKeepsTheSavedOnes() async throws {
        let folder = try folder(), file = folder.appendingPathComponent("subtitles.json")
        let record = SavedSubtitle(id: "saved", episodeKey: "series#1", name: "이전 자막", relativeFile: "old.srt", provider: "user", translated: false, date: Date())
        try JSONEncoder().encode([record]).write(to: file)
        let store = EpisodeSubtitleStore(index: file, deferLoading: true)
        let subtitle = folder.appendingPathComponent("new.srt")
        try "1\n00:00:01,000 --> 00:00:02,000\n새 자막\n".write(to: subtitle, atomically: true, encoding: .utf8)
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: #"{"id":"early","title":"검증","episodes":[{"id":"1","number":1,"title":"1화"}]}"#), source: "reanime")
        store.save(subtitle, item: PlaybackItem(anime: anime, episode: anime.anime.episodes[0]), provider: "user", translated: false)
        BackgroundWriter.shared.waitUntilWritten()
        XCTAssertEqual(try JSONDecoder().decode([SavedSubtitle].self, from: Data(contentsOf: file)).map(\.id), ["saved"], "nothing is written before the index is read")
        await store.load()
        XCTAssertEqual(Set(store.records.map(\.episodeKey)), ["series#1", "reanime:early#1"])
        BackgroundWriter.shared.waitUntilWritten()
        XCTAssertEqual(try JSONDecoder().decode([SavedSubtitle].self, from: Data(contentsOf: file)).count, 2)
        if let saved = store.records.first(where: { $0.episodeKey == "reanime:early#1" }) { store.remove(saved.id) }
        BackgroundWriter.shared.waitUntilWritten()
    }
    @MainActor func testDeferredDownloadIndexRestoresCompletedEntry() async throws {
        let file = try folder().appendingPathComponent("downloads.json")
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: "{\"id\":\"fixture\",\"title\":\"테스트\"}"), source: "linkkf")
        let stream = ResolvedStream(label: "fixture", url: URL(string: "https://fixture.test/video.mp4")!, referer: "", headers: [:])
        var entry = DownloadEntry(id: "fixture", anime: anime, episodeID: "1", title: "1화", number: 1, watchURL: stream.url.absoluteString, stream: stream)
        entry.status = "완료"; entry.localFile = "video.mp4"
        try JSONEncoder().encode([entry]).write(to: file)
        let store = DownloadStore(index: file, restoreSession: false, deferLoading: true)
        XCTAssertTrue(store.entries.isEmpty)
        await store.load()
        XCTAssertEqual(store.entries.first?.status, "완료")
        XCTAssertEqual(store.entries.first?.localFile, "video.mp4")
        store.pauseAll()
    }
    @MainActor func testKoreanDetectionRunsAsynchronouslyAndPreservesLanguageRules() async throws {
        let file = try folder().appendingPathComponent("subtitle.srt")
        try "1\n00:00:01,000 --> 00:00:03,000\n안녕하세요\n".write(to: file, atomically: true, encoding: .utf8)
        let korean = await SubtitleFiles.isKorean(file)
        XCTAssertTrue(korean)
        try "1\n00:00:01,000 --> 00:00:03,000\nこんにちは\n".write(to: file, atomically: true, encoding: .utf8)
        let japanese = await SubtitleFiles.isKorean(file)
        XCTAssertFalse(japanese)
    }
    func testTranslatedLegacyAssUsesDefaultStyleAndCustomFontSurvives() {
        XCTAssertEqual(SubtitleAppearance.assOverride(extension: "ass", translated: true, effects: true, keepStyle: true), "strip")
        XCTAssertEqual(SubtitleAppearance.assOverride(extension: "ass", translated: false, effects: true, keepStyle: true), "no")
        XCTAssertEqual(SubtitleAppearance.font(""), "Apple SD Gothic Neo")
        XCTAssertEqual(SubtitleAppearance.font("사용자 글꼴"), "사용자 글꼴")
    }
    @MainActor func testDeferredCatalogSearchKeepsAliasesAndSourceIsolation() async throws {
        let root = try folder()
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: "{\"id\":\"fixture\",\"title\":\"Fixture Original\"}"), source: "reanime")
        let name = CatalogName(korean: "테스트 제목", english: "Fixture Original", overview: "소개", aliases: ["다른 별칭"], cast: [], anilist: 0, mal: 0, updated: Date(), credential: "")
        try JSONEncoder().encode([anime]).write(to: root.appendingPathComponent("reanime.json"))
        try JSONEncoder().encode([anime.id: name]).write(to: root.appendingPathComponent("names.json"))
        let catalog = DesktopCatalog(directory: root, deferLoading: true)
        let matches = await catalog.search("별칭", source: "reanime")
        XCTAssertEqual(matches.map(\.id), ["fixture"])
        let unrelated = await catalog.search("별칭", source: "linkkf")
        XCTAssertTrue(unrelated.isEmpty)
        let variants = await catalog.variants("테스트")
        XCTAssertEqual(variants, ["Fixture Original"])
    }
    @MainActor func testGgufImportCopiesAndRejectsDuplicateWithoutReplacingIt() async throws {
        let source = try folder().appendingPathComponent(UUID().uuidString + ".gguf")
        let target = LocalModelFiles.directory.appendingPathComponent(source.lastPathComponent)
        addTeardownBlock { try? FileManager.default.removeItem(at: target) }
        let data = Data("GGUF".utf8) + Data([3]) + Data(repeating: 0, count: 19)
        try data.write(to: source)
        let imported = try await LocalModelFiles.importModel(source)
        XCTAssertEqual(imported, target)
        XCTAssertEqual(try Data(contentsOf: imported), data)
        do { _ = try await LocalModelFiles.importModel(source); XCTFail("Duplicate model should be rejected") }
        catch { XCTAssertEqual(try Data(contentsOf: imported), data) }
    }
    func testCacheCleanupPreservesSavedFontsWorkingAndRecentlyChangedFiles() throws {
        let root = try folder(), fonts = root.appendingPathComponent("Fonts")
        try FileManager.default.createDirectory(at: fonts, withIntermediateDirectories: true)
        let names = ["old.srt", "saved.srt", "working-active.vtt", "new.vtt", "Fonts/font.ttf"]
        let files = names.map { root.appendingPathComponent($0) }
        for file in files {
            try Data([1]).write(to: file)
            try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-7200)], ofItemAtPath: file.path)
        }
        let started = Date()
        try FileManager.default.setAttributes([.modificationDate: started.addingTimeInterval(60)], ofItemAtPath: files[3].path)
        try SubtitleCache.removeFiles(in: root, fontDirectory: fonts, protected: [files[1].standardizedFileURL], all: false, before: started)
        XCTAssertFalse(FileManager.default.fileExists(atPath: files[0].path))
        for file in files.dropFirst() { XCTAssertTrue(FileManager.default.fileExists(atPath: file.path), file.lastPathComponent) }
    }
}
