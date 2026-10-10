import XCTest
import LilacShared
@testable import LilacAnime

final class SubtitleSearchTests: XCTestCase {
    @MainActor func testDesktopListingLoadsOnceIncludingEmptyResults() async {
        var calls = 0
        let catalog = DesktopJimakuCatalog { _ in calls += 1; return [] }
        defer { catalog.cancel() }
        let episode = item()
        _ = await catalog.load(episode); _ = await catalog.load(episode, retryFailure: true)
        XCTAssertEqual(calls, 1); XCTAssertEqual(catalog.files(episode)?.count, 0)
        XCTAssertNil(catalog.error(episode)); XCTAssertFalse(catalog.loading(episode))
    }
    @MainActor func testDesktopConcurrentConsumersSharePendingListing() async throws {
        var calls = 0
        var reply: CheckedContinuation<[SubtitleAsset], Error>?
        let catalog = DesktopJimakuCatalog { _ in
            calls += 1
            return try await withCheckedThrowingContinuation { reply = $0 }
        }
        defer { catalog.cancel() }
        let episode = item(), file = asset()
        let first = Task { await catalog.load(episode) }
        let second = Task { await catalog.load(episode) }
        for _ in 0..<100 { if reply != nil { break }; await Task.yield() }
        XCTAssertTrue(catalog.loading(episode)); XCTAssertEqual(calls, 1)
        let continuation = try XCTUnwrap(reply)
        continuation.resume(returning: [file])
        let a = await first.value, b = await second.value
        XCTAssertEqual(a.map(\.url), [file.url]); XCTAssertEqual(b.map(\.url), [file.url])
        XCTAssertFalse(catalog.loading(episode))
    }
    @MainActor func testDesktopFailureRetriesOnlyWhenUserReopensIt() async {
        var calls = 0
        let file = asset()
        let catalog = DesktopJimakuCatalog { _ in
            calls += 1
            if calls == 1 { throw SubtitleFiles.failure("연결 실패") }
            return [file]
        }
        defer { catalog.cancel() }
        let episode = item()
        _ = await catalog.load(episode); _ = await catalog.load(episode)
        XCTAssertEqual(calls, 1); XCTAssertEqual(catalog.error(episode), "연결 실패")
        _ = await catalog.load(episode, retryFailure: true)
        XCTAssertEqual(calls, 2); XCTAssertEqual(catalog.files(episode)?.map(\.url), [file.url])
        XCTAssertNil(catalog.error(episode))
    }
    @MainActor func testDesktopServerSwitchReusesEpisodeAndNextEpisodeLoadsSeparately() async {
        var calls = 0
        let file = asset()
        let catalog = DesktopJimakuCatalog { _ in calls += 1; return [file] }
        defer { catalog.cancel() }
        let first = item()
        _ = await catalog.load(first)
        let switched = PlaybackItem(entry: WatchEntry(id: "switch", anime: first.anime, episodeID: first.episodeID, episodeTitle: first.title, number: first.number, watchURL: "https://fixture.test/other-server", directURL: nil, position: 0, duration: 0, updatedAt: Date()))
        _ = await catalog.load(switched)
        XCTAssertEqual(calls, 1)
        _ = await catalog.load(item(episode: "2"))
        XCTAssertEqual(calls, 2)
    }
    @MainActor func testSwitchingToCommunityDoesNotDiscardPendingJimakuResult() async throws {
        var callbacks: [(String, ([SubtitleAsset]?, String?) -> Void)] = []
        let model = EpisodePlayerModel(item: item(), subtitleLookup: { source, reply in callbacks.append((source, reply)) })
        defer { model.shutdown(library: LibraryStore()) }
        model.search("jimaku")
        for _ in 0..<100 { if !callbacks.isEmpty { break }; await Task.yield() }
        let jimakuReply = try XCTUnwrap(callbacks.first?.1)
        model.search("kairan")
        jimakuReply([asset()], nil)
        for _ in 0..<100 { if model.jimaku.files(model.item) != nil { break }; await Task.yield() }
        XCTAssertEqual(model.subtitleResultsProvider, "kairan")
        model.search("jimaku")
        XCTAssertEqual(model.displayedSubtitleAssets.map(\.name), ["episode-01.ass"])
        for _ in 0..<10 { await Task.yield() }
        XCTAssertEqual(callbacks.filter { $0.0 == "jimaku" }.count, 1)
    }
    @MainActor func testJimakuChipOpensItsListWithoutBecomingTheDefaultSource() async throws {
        var callbacks: [String] = []
        let library = LibraryStore()
        let previous = library.preferences.subtitleProvider
        library.preferences.subtitleProvider = "csora"
        defer { library.preferences.subtitleProvider = previous }
        let model = EpisodePlayerModel(item: item(), subtitleLookup: { source, reply in callbacks.append(source); reply([], nil) })
        defer { model.shutdown(library: library) }
        model.chooseSource("jimaku", library: library)
        for _ in 0..<100 { if model.jimaku.files(model.item) != nil { break }; await Task.yield() }
        XCTAssertEqual(model.openSheet, "jimaku")
        XCTAssertEqual(model.activeSubtitleSource, "jimaku")
        XCTAssertEqual(callbacks, ["jimaku"])
        XCTAssertEqual(library.preferences.subtitleProvider, "csora")
    }
    @MainActor func testPickingAJimakuFileKeepsTheCommunityDefault() throws {
        let library = LibraryStore()
        let previous = library.preferences.subtitleProvider
        library.preferences.subtitleProvider = "kairan"
        defer { library.preferences.subtitleProvider = previous }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".srt")
        try Data("1\n00:00:00,000 --> 00:00:01,000\nこんにちは\n".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let model = EpisodePlayerModel(item: item(), subtitleLookup: { _, reply in reply([], nil) })
        defer { model.shutdown(library: library) }
        model.selectSubtitle(file, library: library, translate: false, provider: "jimaku")
        XCTAssertEqual(library.preferences.subtitleProvider, "kairan")
        XCTAssertEqual(model.activeSubtitleSource, "jimaku")
        XCTAssertEqual(model.subtitleLabel, "Jimaku 자막")
        XCTAssertTrue(model.translatable)
        for record in EpisodeSubtitleStore.shared.list(model.item) { EpisodeSubtitleStore.shared.remove(record.id) }
    }
    func testSiteTracksListKoreanEnglishJapaneseFirstWithLanguageOnTop() {
        let track = { (label: String, language: String) in RemoteSubtitle(label: label, url: URL(string: "https://fixture.test/" + language + ".vtt")!, language: language) }
        let tracks = [track("Chinese (Chinese (Han, Simplified) - Full Subtitles)", "zh"), track("Japanese", "ja"), track("English (Dialogue)", "en"), track("Korean", "ko")]
        XCTAssertEqual(SiteTrackOrder.sorted(tracks).map(\.language), ["ko", "en", "ja", "zh"])
        XCTAssertEqual(SiteTrackOrder.name(tracks[0]), "Chinese")
        XCTAssertEqual(SiteTrackOrder.detail(tracks[0]), "(Han, Simplified) - Full Subtitles")
        XCTAssertEqual(SiteTrackOrder.name(tracks[2]), "English"); XCTAssertEqual(SiteTrackOrder.detail(tracks[2]), "Dialogue")
        XCTAssertEqual(SiteTrackOrder.name(tracks[1]), "Japanese"); XCTAssertEqual(SiteTrackOrder.detail(tracks[1]), "")
    }
    func testSavedSubtitlesAreNamedBySourceLikeTheDesktop() {
        XCTAssertEqual(SubtitleLabels.label(provider: "kairan", translated: false, name: "ìë§.smi"), "Kairan 자막")
        XCTAssertEqual(SubtitleLabels.label(provider: "anissia", translated: false, name: "x.ass", maker: "제작자"), "Anissia · 제작자 자막")
        XCTAssertEqual(SubtitleLabels.label(provider: "gemini", translated: true, name: "x.srt"), "Gemini 번역")
        XCTAssertEqual(SubtitleLabels.label(provider: "local", translated: true, name: "x.srt"), "로컬 AI 번역")
        XCTAssertEqual(SubtitleLabels.label(provider: "user", translated: false, name: "내 파일.srt"), "내 파일.srt")
    }
    func testJimakuShiftJISNormalizesToUTF8AndUsesCatalogFilename() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let content = Data("1\n00:00:00,000 --> 00:00:01,000\n".utf8) + Data([0x82,0xa0,0x82,0xa2,0x82,0xa4])
        try content.write(to: file)
        defer { try? FileManager.default.removeItem(at: file); try? FileManager.default.removeItem(at: SubtitleFiles.root.appendingPathComponent(SubtitleFiles.key(file.absoluteString))) }
        let copied = try await SubtitleFiles.prepare(file, japanese: true, filename: "日本語.srt")
        let output = try XCTUnwrap(copied.first)
        XCTAssertEqual(output.lastPathComponent, "日本語.srt")
        XCTAssertTrue(try String(contentsOf: output, encoding: .utf8).contains("あいう"))
    }
    @MainActor private func item(episode: String = "1") -> PlaybackItem {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: "{\"id\":\"fixture\",\"title\":\"테스트\",\"episodes\":[{\"id\":\"\(episode)\",\"number\":1,\"title\":\"1화\"}]}"), source: "reanime")
        return PlaybackItem(anime: anime, episode: anime.anime.episodes[0])
    }
    private func asset() -> SubtitleAsset {
        SubtitleAsset(name: "episode-01.ass", url: "https://jimaku.cc/entry/123/download/1", source: "jimaku", score: 1, episode: nil, strict: false, bundle: false, postURL: "", matchedEpisode: nil, size: 0)
    }
}
