import XCTest
import LilacShared
@testable import LilacAnime

final class PortRegressionTests: XCTestCase {
    func testVideoAndAudioPlaylistsKeepSharedKeysReachable() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",DEFAULT=YES,URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"audio\"\nvideo.m3u8\n"
        let manifests = ["/master.m3u8": master,
            "/video.m3u8": "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"shared.key\"\n#EXTINF:5,\nv.ts\n#EXT-X-ENDLIST",
            "/audio.m3u8": "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"shared.key\"\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST"]
        let stream = ResolvedStream(label: "영상", url: URL(string: "https://fixture.test/master.m3u8")!, referer: "", headers: [:])
        let (root, parts, _) = try await HLSPlanBuilder(stream: stream, folder: folder, quality: "Auto", fetch: { url, _ in
            (Data(manifests[url.path]!.utf8), HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }).build()
        XCTAssertEqual(parts.count, 3)
        let key = try XCTUnwrap(parts.first { $0.url.path == "/shared.key" })
        let rootText = try String(contentsOf: folder.appendingPathComponent(root), encoding: .utf8)
        for child in HLSData.references(rootText, base: folder.appendingPathComponent(root)) {
            let text = try String(contentsOf: child, encoding: .utf8)
            XCTAssertTrue(text.contains(key.name))
            let references = HLSData.references(text, base: folder.appendingPathComponent(root))
            XCTAssertTrue(references.allSatisfy { resource in parts.contains { $0.name == resource.lastPathComponent } })
        }
    }
    func testKoreanSubtitleDecodingDoesNotMistakeLegacyBytesForUTF16() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appendingPathComponent("fixture.srt")
        try Data([0xbe, 0xc8, 0xb3, 0xe7]).write(to: file) // EUC-KR 안녕
        XCTAssertEqual(try SubtitleFiles.text(file), "안녕")
        try (Data([0xff, 0xfe]) + "안녕".data(using: .utf16LittleEndian)!).write(to: file)
        XCTAssertEqual(try SubtitleFiles.text(file), "안녕")
        try Data("\u{FEFF}안녕".utf8).write(to: file)
        XCTAssertEqual(try SubtitleFiles.text(file), "안녕")
    }
    func testDownloadedModelCannotAcceptHTMLDisguisedAsGGUF() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        try Data("<html>not a model, despite its download filename</html>".utf8).write(to: file)
        XCTAssertThrowsError(try LocalModelFiles.validate(file, requireExtension: false))
        try (Data([71,71,85,70,3]) + Data(repeating: 0, count: 19)).write(to: file)
        XCTAssertNoThrow(try LocalModelFiles.validate(file, requireExtension: false))
        XCTAssertThrowsError(try LocalModelFiles.validate(file))
    }
    func testPartialTranslationKeepsUntranslatedLinesRetryableAndCountsUniqueDialogue() {
        let outcome = TranslationOutcome(lines: ["一行", "一行", "二行", "123", "中文"], kept: ["一行": "첫 줄", "123": "123", "中文": ""])
        XCTAssertEqual(outcome.translated, 1); XCTAssertEqual(outcome.failed, 1)
        XCTAssertEqual(TranslationOutcome(lines: ["一行"], kept: [:]).translated, 0)
        XCTAssertEqual(TranslationOutcome(lines: ["一行"], kept: ["一行": "첫 줄"]).failed, 0)
    }
    func testMiruroForcedEnglishTrackDoesNotOutrankSubServer() {
        let url = URL(string: "https://fixture.test/index.m3u8")!
        let forced = ResolvedStream(label: "SOFT - anikoto one", url: url, referer: "", headers: [:], subtitles: [RemoteSubtitle(label: "English Signs", url: url, language: "en")])
        let sub = ResolvedStream(label: "SUB - anikoto two", url: url, referer: "", headers: [:])
        XCTAssertEqual(DesktopStreamPolicy.ordered([forced, sub], preferRaw: false, preferred: nil, workingProvider: nil).first?.label, sub.label)
    }
    func testExplicitLocalTranslationCannotSelectCloudBeforeOrAfterFailure() {
        var preferences = AppPreferences(); preferences.translationProvider = "local"
        XCTAssertNil(preferences.translationPreferences(localOnly: true, models: [], hasKey: { _ in true }))
        let model = URL(fileURLWithPath: "/fixture.gguf")
        XCTAssertEqual(preferences.translationPreferences(localOnly: true, models: [model], hasKey: { _ in true })?.translationProvider, "local")
        XCTAssertNil(TranslationFallbackPolicy.cloudAfterLocal(localOnly: true, enabled: true, tried: ["local"], hasKey: { _ in true }))
        XCTAssertEqual(TranslationFallbackPolicy.cloudAfterLocal(localOnly: false, enabled: true, tried: ["local", "gemini"], hasKey: { _ in true }), "openai")
    }
    @MainActor func testCommunityMissIsRetriedAndSuccessfulSearchIsDeduplicated() async {
        let cache = SubtitleSearchCache()
        var requests = 0
        let missing = cache.task("episode") { requests += 1; return nil }
        let duplicate = cache.task("episode") { requests += 1; return nil }
        let first = await missing.value, second = await duplicate.value
        XCTAssertNil(first); XCTAssertNil(second); XCTAssertEqual(requests, 1)
        let recovered = await cache.task("episode") { requests += 1; return (URL(fileURLWithPath: "/subtitle.ass"), "kairan") }.value
        XCTAssertEqual(recovered?.1, "kairan"); XCTAssertEqual(requests, 2)
        let cached = await cache.task("episode") { requests += 1; return nil }.value
        XCTAssertEqual(cached?.1, "kairan"); XCTAssertEqual(requests, 2)
        cache.cancel()
    }
    private func item() -> PlaybackItem {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: "{\"id\":\"test-\(UUID().uuidString)\",\"title\":\"검증\",\"episodes\":[{\"id\":\"1\",\"number\":1,\"title\":\"1화\"}]}"), source: "reanime")
        return PlaybackItem(anime: anime, episode: anime.anime.episodes[0])
    }
    @MainActor func testOneDownloadAttemptConsumesOnlyOneRetryAndRejectsLateSuccess() throws {
        let item = item(), id = SubtitleFiles.key(item.anime.id + "#1"), attempt = UUID().uuidString
        let part = String(repeating: "a", count: 64) + ".ts"
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let index = folder.appendingPathComponent("index.json")
        let stream = ResolvedStream(label: "영상", url: URL(string: "https://fixture.test/index.m3u8")!, referer: "", headers: [:])
        let entry = DownloadEntry(id: id, anime: item.anime, episodeID: "1", title: "1화", number: 1, watchURL: stream.url.absoluteString, stream: stream,
            rootFile: "root.m3u8", parts: [DownloadPart(url: URL(string: "https://fixture.test/1.ts")!, name: part)], retries: 0, attemptID: attempt, status: "다운로드 중")
        try JSONEncoder().encode([entry]).write(to: index)
        let store = DownloadStore(index: index, restoreSession: false)
        defer { store.pauseAll() }
        let description = id + "|" + part + "|" + attempt
        for _ in 0..<6 { store.backgroundFailed(description, error: URLError(.networkConnectionLost)) }
        XCTAssertEqual(store.entries[0].retries, 1); XCTAssertEqual(store.entries[0].status, "실패")
        store.backgroundFailed(description, error: URLError(.cancelled))
        XCTAssertEqual(store.entries[0].status, "실패")
        let staged = folder.appendingPathComponent("staged")
        try Data([71]).write(to: staged)
        store.backgroundFinished(description, staged: staged, mime: "video/mp2t")
        XCTAssertNil(store.entries[0].localFile); XCTAssertFalse(FileManager.default.fileExists(atPath: staged.path))
        XCTAssertFalse(DownloadIdentity.accepts(id + "|" + part + "|" + UUID().uuidString, attempt: attempt))
        XCTAssertNotNil(BackgroundDownloadDelegate.destination(description))
        store.progress(description, written: 90, expected: 100)
        XCTAssertNil(store.byteProgress[id])
    }
    @MainActor func testSavingTwentyFirstBackgroundSubtitleKeepsNewOne() throws {
        let item = item()
        let folder = SubtitleFiles.root.appendingPathComponent("test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = EpisodeSubtitleStore(index: folder.appendingPathComponent("index.json"))
        for number in 0..<21 {
            let file = folder.appendingPathComponent("\(number).srt")
            try "1\n00:00:00,000 --> 00:00:01,000\n대사".write(to: file, atomically: true, encoding: .utf8)
            store.save(file, item: item, provider: "test", translated: false, behind: true)
        }
        let saved = store.list(item)
        XCTAssertEqual(saved.count, 20); XCTAssertTrue(saved.contains { $0.name == "20.srt" })
        XCTAssertFalse(saved.contains { $0.name == "19.srt" })
        let reloaded = EpisodeSubtitleStore(index: folder.appendingPathComponent("index.json"))
        XCTAssertEqual(reloaded.list(item).map(\.id), saved.map(\.id))
    }
    func testFontAssociationIncludesOnlyCurrentArchiveAndSurvivesReload() throws {
        let folder = SubtitleFiles.root.appendingPathComponent("test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let first = folder.appendingPathComponent("one.ass"), second = folder.appendingPathComponent("two.ass")
        let font = folder.appendingPathComponent("one.ttf"), unrelated = folder.appendingPathComponent("two.ttf")
        try Data([1]).write(to: font); try Data([2]).write(to: unrelated)
        try SubtitleFiles.associateFonts([font], with: first); try SubtitleFiles.associateFonts([unrelated], with: second)
        XCTAssertEqual(SubtitleFiles.fonts(for: first), [font]); XCTAssertEqual(SubtitleFiles.fonts(for: second), [unrelated])
        XCTAssertFalse(SubtitleFiles.fonts(for: first).contains(unrelated))
    }
    func testLinkaniLegacyAndExplicitBurnedSubtitleState() {
        let url = URL(string: "https://fixture.test/index.m3u8")!
        var stream = ResolvedStream(label: "링크애니", url: url, referer: "", headers: [:])
        XCTAssertTrue(DesktopSubtitlePolicy.burnedKorean(source: "linkani", stream: stream))
        stream.subtitles = [RemoteSubtitle(label: "한국어", url: url, language: "ko")]
        XCTAssertFalse(DesktopSubtitlePolicy.burnedKorean(source: "linkani", stream: stream))
        stream.burnedKorean = true
        XCTAssertTrue(DesktopSubtitlePolicy.burnedKorean(source: "linkani", stream: stream))
        XCTAssertTrue(DesktopSubtitlePolicy.burnedKorean(source: "ohli24", stream: stream))
    }
    @MainActor func testSeekRevisionChangesForSmallJumpsAndNotNaturalProgress() {
        let engine = MPVEngine(), first = engine.seekRevision
        engine.seek(10); XCTAssertEqual(engine.seekRevision, first + 1)
        engine.skip(-5); XCTAssertEqual(engine.seekRevision, first + 2)
    }
    func testPaddedTransportStreamCoversDesktop64KiBWindowAndLastOffset() throws {
        for padding in [1, 4096, 60000, 65535] {
            var bytes = Data(repeating: 0, count: padding + 377)
            bytes[padding] = 71; bytes[padding + 188] = 71; bytes[padding + 376] = 71
            XCTAssertEqual(try HLSData.validatedFragment(bytes).count, 377)
            if padding >= 8 {
                bytes.replaceSubrange(0..<8, with: [137,80,78,71,13,10,26,10])
                XCTAssertEqual(try HLSData.validatedFragment(bytes).count, 377)
                XCTAssertEqual(HLSData.transportStream(bytes).count, 377)
            }
        }
        let opaque = Data(repeating: 0xA5, count: 512)
        XCTAssertEqual(HLSData.transportStream(opaque), opaque)
        XCTAssertThrowsError(try HLSData.validatedFragment(opaque))
    }
    func testCatalogRetryMatchesAuthTransientAndServerCooldownRules() {
        XCTAssertEqual(CatalogTitleRetry.delay(auth: true, retryAfter: 0), 1800)
        XCTAssertEqual(CatalogTitleRetry.delay(auth: false, retryAfter: 0), 60)
        XCTAssertEqual(CatalogTitleRetry.delay(auth: false, retryAfter: 120), 120)
    }
    func testResourceIdentityIgnoresCredentialsButPreservesSelectedServerAndVariant() {
        let url = URL(string: "https://fixture.test/720p/index.m3u8?token=old")!
        XCTAssertEqual(DownloadIdentity.resource(url), DownloadIdentity.resource(URL(string: "https://fixture.test/720p/index.m3u8?token=new")!))
        XCTAssertNotEqual(DownloadIdentity.resource(url), DownloadIdentity.resource(URL(string: "https://fixture.test/1080p/index.m3u8")!))
        XCTAssertNotEqual(DownloadIdentity.resource(url), DownloadIdentity.resource(URL(string: "https://other.test/720p/index.m3u8")!))
    }
    func testHLSPlanReusesTokenRefreshedPiecesButChangesIdentityForAnotherRendition() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        func plan(_ token: String, variant: String) async throws -> (String, [DownloadPart], String) {
            let stream = ResolvedStream(label: "영상", url: URL(string: "https://fixture.test/\(variant)/index.m3u8?token=\(token)")!, referer: "", headers: [:],
                hlsManifest: "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:5,\n1.ts?token=\(token)\n#EXTINF:5,\n2.ts?token=\(token)\n#EXT-X-ENDLIST")
            return try await HLSPlanBuilder(stream: stream, folder: folder, quality: "Auto").build()
        }
        let old = try await plan("old", variant: "720p"), fresh = try await plan("new", variant: "720p")
        XCTAssertEqual(old.0, fresh.0); XCTAssertEqual(old.1.map(\.name), fresh.1.map(\.name)); XCTAssertEqual(old.2, fresh.2)
        XCTAssertNotEqual(old.1.map(\.url), fresh.1.map(\.url))
        let different = try await plan("new", variant: "1080p")
        XCTAssertNotEqual(fresh.2, different.2); XCTAssertNotEqual(fresh.1.map(\.name), different.1.map(\.name))
    }
}
