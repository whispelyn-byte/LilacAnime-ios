import XCTest
@testable import LilacAnime

final class DesktopParityTests: XCTestCase {
    private func cases(_ key: String) throws -> [[String: Any]] {
        let file = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "desktop-oracle", withExtension: "json"))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        return try XCTUnwrap(root[key] as? [[String: Any]])
    }
    private func track(_ raw: [String: Any]) -> RemoteSubtitle {
        RemoteSubtitle(label: raw["label"] as? String ?? "", url: URL(string: raw["url"] as? String ?? "https://fixture.test/sub.ass")!, language: raw["language"] as? String ?? "")
    }
    func testTranslationTrackMatchesDesktopJapaneseAndEnglishDialoguePriority() throws {
        for test in try cases("subtitleTracks") {
            let tracks = (test["tracks"] as? [[String: Any]] ?? []).map(track)
            XCTAssertEqual(DesktopSubtitlePolicy.translationTrack(tracks)?.label, test["expected"] as? String)
        }
    }
    func testKoreanDetectionMatchesDesktop() throws {
        for test in try cases("korean") { XCTAssertEqual(DesktopSubtitlePolicy.isKorean(track(test["input"] as! [String: Any])), test["expected"] as? Bool) }
    }
    func testSavedPreferredMatchesDesktop() throws {
        for test in try cases("saved") {
            let records = (test["saved"] as! [[String: String]]).enumerated().map { index, raw in
                SavedSubtitle(id: "\(index)", episodeKey: "a#1", name: "sub.ass", relativeFile: "fixture.ass", provider: raw["source"]!, translated: raw["source"] == "gemini", date: Date())
            }
            XCTAssertEqual(DesktopSubtitlePolicy.preferredSaved(records, preferred: test["preferred"] as? String)?.provider, test["expected"] as? String)
        }
    }
    func testCacheAgeAndDefaultOptionsMatchDesktop() {
        XCTAssertFalse(DesktopSubtitlePolicy.cacheRemovable(age: 3599, all: false, protected: false))
        XCTAssertTrue(DesktopSubtitlePolicy.cacheRemovable(age: 3601, all: false, protected: false))
        XCTAssertFalse(DesktopSubtitlePolicy.cacheRemovable(age: 7200, all: false, protected: true))
        XCTAssertFalse(DesktopSubtitlePolicy.cacheRemovable(age: 599, all: true, protected: true))
        XCTAssertTrue(DesktopSubtitlePolicy.cacheRemovable(age: 601, all: true, protected: true))
        let preferences = AppPreferences()
        XCTAssertTrue(preferences.autoTranslation); XCTAssertFalse(preferences.autoSkip); XCTAssertEqual(preferences.prepareNextCloud, false)
        XCTAssertEqual(preferences.translationProvider, "gemini"); XCTAssertEqual(preferences.showSkipButton, true)
    }
    func testOwnKoreanTracksSuppressAlongsideAndCloudPrefetchIsOptIn() {
        XCTAssertFalse(DesktopSubtitlePolicy.shouldTranslateAlongside(source: "kairan", siteKorean: true))
        XCTAssertTrue(DesktopSubtitlePolicy.shouldTranslateAlongside(source: "kairan", siteKorean: false))
        XCTAssertFalse(DesktopSubtitlePolicy.shouldPrefetch(provider: "gemini", cloud: false, siteKorean: false, prefersAI: false))
        XCTAssertTrue(DesktopSubtitlePolicy.shouldPrefetch(provider: "local", cloud: false, siteKorean: false, prefersAI: false))
        XCTAssertFalse(DesktopSubtitlePolicy.shouldPrefetch(provider: "local", cloud: false, siteKorean: true, prefersAI: false))
        XCTAssertTrue(DesktopSubtitlePolicy.shouldPrefetch(provider: "local", cloud: false, siteKorean: true, prefersAI: true))
    }
    func testMiruroKoreanSoftThenSelectedAndRawSubPreferences() {
        func stream(_ label: String, _ language: String? = nil) -> ResolvedStream {
            ResolvedStream(label: label, url: URL(string: "https://fixture.test/\(SubtitleFiles.key(label)).m3u8")!, referer: "", headers: [:], subtitles: language.map { [RemoteSubtitle(label: $0, url: URL(string: "https://fixture.test/sub.ass")!, language: $0)] } ?? [])
        }
        let values = [stream("SUB - anikoto"), stream("RAW - kickassanime"), stream("SOFT - icarus", "en"), stream("SOFT - aniwaves", "ko")]
        XCTAssertEqual(DesktopStreamPolicy.ordered(values, preferRaw: true, preferred: nil, workingProvider: nil).first?.label, "SOFT - aniwaves")
        XCTAssertEqual(DesktopStreamPolicy.ordered(values, preferRaw: false, preferred: "SUB - anikoto", workingProvider: nil).first?.label, "SUB - anikoto")
        XCTAssertEqual(DesktopStreamPolicy.ordered(Array(values.prefix(3)), preferRaw: false, preferred: nil, workingProvider: nil).first?.label, "SOFT - icarus")
        XCTAssertEqual(DesktopStreamPolicy.ordered(Array(values.prefix(2)), preferRaw: true, preferred: nil, workingProvider: nil).first?.label, "RAW - kickassanime")
    }
    func testWrappedTsValidationFindsUnknownHeaderAndRejectsHtml() throws {
        var ts = Data(repeating: 0, count: 600); ts[0] = 71; ts[188] = 71; ts[376] = 71
        XCTAssertEqual(try HLSData.validatedFragment(Data("fake-image-header".utf8) + ts), ts)
        XCTAssertThrowsError(try HLSData.validatedFragment(Data("<html>challenge</html>".utf8)))
        XCTAssertTrue(HLSData.isMedia(Data([0,0,0,20]) + Data("moof".utf8)))
        let order = HLSData.references("#EXTM3U\n10.png\n2.png\n3.png\n", base: URL(string: "https://fixture.test/index.m3u8")!)
        XCTAssertEqual(order.map(\.lastPathComponent), ["10.png", "2.png", "3.png"])
    }
    @MainActor
    func testWinPngUsesPostsConverterAndReadsConvertedBlob() async throws {
        let html = """
        <article><img onclick="convert()"></article><script>
        window.downloadZip=function(){};
        function convert(){
          const a=document.createElement('a'); a.download='Anime - 03.ass';
          const file=URL.createObjectURL(new Blob(['[Script Info]\\nTitle: Converted 03'],{type:'text/plain'}));
          a.setAttribute('data-href',file); a.setAttribute('data-ass',file); document.body.appendChild(a);
        }
        </script>
        """
        let rows = try await WinPNGReader().read(URL(string: "https://fixture.invalid/post")!, html: html)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?["name"], "Anime - 03.ass")
        XCTAssertEqual(rows.first?["ass"], "[Script Info]\nTitle: Converted 03")
    }
    @MainActor
    func testWinPngCancellationStopsPendingPage() async {
        let task = Task { try await WinPNGReader().read(URL(string: "https://fixture.invalid/post")!, html: "<html></html>") }
        await Task.yield()
        task.cancel()
        do { _ = try await task.value; XCTFail("Expected cancellation") }
        catch is CancellationError { }
        catch { XCTFail("Unexpected error: \(error)") }
    }
}
