import XCTest
@testable import LilacAnime

final class DesktopLatestPortTests: XCTestCase {
    func testEncryptionScopeEndsAtMethodNoneAndDoesNotIncludeKey() {
        let base = URL(string: "https://example.com/video/list.m3u8")!
        let manifest = """
        #EXTM3U
        #EXT-X-KEY:METHOD=AES-128,URI="secret.key"
        #EXTINF:10,
        first.png
        #EXT-X-KEY:METHOD=NONE
        #EXTINF:10,
        second.png
        """
        XCTAssertEqual(HLSData.encryptedSegments(manifest, base: base), [URL(string: "https://example.com/video/first.png")!])
    }
    func testWhisperStartsAtViewerPositionAndReprioritizesAfterSeek() {
        XCTAssertEqual(WhisperSubtitleFile.nextChunk(duration: 120, position: 65, completed: []), 2)
        XCTAssertEqual(WhisperSubtitleFile.nextChunk(duration: 120, position: 5, completed: [2]), 0)
        XCTAssertEqual(WhisperSubtitleFile.nextChunk(duration: 120, position: 110, completed: [2, 3]), 0)
        XCTAssertNil(WhisperSubtitleFile.nextChunk(duration: 120, position: 0, completed: [0, 1, 2, 3]))
        XCTAssertNil(WhisperSubtitleFile.nextChunk(duration: .nan, position: 0, completed: []))
    }
    func testWhisperVttEscapesTextAndSortsOutOfOrderRecognition() {
        let output = WhisperSubtitleFile.vtt([WhisperLine(start: 31, end: 32, text: "<声>&"), WhisperLine(start: 1, end: 2, text: "先")])
        XCTAssertTrue(output.contains("&lt;声&gt;&amp;"))
        XCTAssertLessThan(output.range(of: "00:00:01.000")!.lowerBound, output.range(of: "00:00:31.000")!.lowerBound)
        // Binary floating-point must not turn 1001 ms into 1000 and lose the live translation's cue key.
        let precise = WhisperSubtitleFile.vtt([WhisperLine(start: 1.001, end: 2.001, text: "声")])
        XCTAssertTrue(precise.contains("00:00:01.001 --> 00:00:02.001"))
    }
    func testMiruroRequestsStayWithinAllowedAPIOrigins() {
        XCTAssertTrue(MiruroBrowser.allowed(URL(string: "https://www.miruro.to/api/v1/anime/1")!))
        XCTAssertFalse(MiruroBrowser.allowed(URL(string: "https://www.miruro.to.attacker.test/api/v1/anime/1")!))
        XCTAssertFalse(MiruroBrowser.allowed(URL(string: "https://www.miruro.to:1234/api/v1/anime/1")!))
    }
    func testCleanupRejectsBadMasksAndKeepsDisabledSettingsEmpty() throws {
        XCTAssertFalse(WatermarkMask(rgba: [0], width: 1, height: 1, origin: [0, 0], extent: [1, 1]).valid)
        XCTAssertFalse(WatermarkMask(rgba: [0, 0, 0, 0], width: 1, height: 1, origin: [.nan, 0], extent: [1, 1]).valid)
        XCTAssertEqual(try VideoCleanup.paths(VideoCleanupSettings()), [])
        XCTAssertEqual(SubtitleLineSpacing.points(nil), 0)
        XCTAssertEqual(SubtitleLineSpacing.points(150), 18)
    }
    func testBundledSubtitleCleanupResolvesAllThreePasses() throws {
        var settings = VideoCleanupSettings()
        settings.subtitles = true
        settings.subtitleRegions = [CleanupRegion(x: 0.1, y: 0.75, width: 0.8, height: 0.2)]
        let path = try XCTUnwrap(VideoCleanup.paths(settings).first)
        let source = try String(contentsOfFile: path, encoding: .utf8)
        XCTAssertTrue(source.contains("//!SAVE LilacText"))
        XCTAssertTrue(source.contains("//!SAVE LilacWide"))
        XCTAssertTrue(source.contains("//!DESC Lilac subtitle fill"))
        XCTAssertFalse(source.contains("// REGION_CONSTANTS"))
    }
}
