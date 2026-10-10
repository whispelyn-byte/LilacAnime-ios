import XCTest
@testable import LilacAnime

final class SubtitleNameTests: XCTestCase {
    func testUTF8HeaderAndExistingSavedNameAreReadable() throws {
        let name = "체인소맨 2기 6화.smi"
        let broken = try XCTUnwrap(String(data: Data(name.utf8), encoding: .isoLatin1))
        XCTAssertNotEqual(broken, name)
        XCTAssertEqual(SubtitleNames.downloadFilename("attachment; filename=\"\(broken)\"", fallback: "download"), name)
        XCTAssertEqual(SubtitleNames.label(broken), name)
    }
    func testExtendedFilenameWinsAndDoesNotIncludeOtherParameters() {
        XCTAssertEqual(SubtitleNames.downloadFilename("attachment; filename=plain.smi; FILENAME*=UTF-8'ko'%ED%95%9C%EA%B8%80.smi; size=100", fallback: "download"), "한글.smi")
        XCTAssertEqual(SubtitleNames.downloadFilename("attachment; filename=\"episode; 6.srt\"; size=100", fallback: "download"), "episode; 6.srt")
        XCTAssertEqual(SubtitleNames.downloadFilename("attachment; filename=episode.srt; size=100", fallback: "download"), "episode.srt")
    }
    func testMalformedExtendedHeaderFallsBackToOrdinaryName() {
        XCTAssertEqual(SubtitleNames.downloadFilename("attachment; filename*=UTF-8''%ZZ.srt; filename=episode.srt", fallback: "source.srt"), "episode.srt")
        XCTAssertEqual(SubtitleNames.downloadFilename("attachment; filename*=unknown''abc", fallback: "source.srt"), "source.srt")
    }
    func testLegitimateAccentedAndUnicodeNamesArePreserved() {
        for name in ["Français épisode 6.srt", "日本語 06.ass", "한글 6화.smi", "[NanakoRaws] 01.ass"] {
            XCTAssertEqual(SubtitleNames.readable(name), name)
        }
    }
    func testBlankSearchResultsAlwaysHaveAnActionLabel() {
        XCTAssertEqual(SubtitleNames.label("   ", url: "https://fixture.test/subs/%ED%95%9C%EA%B8%80.srt"), "한글.srt")
        XCTAssertEqual(SubtitleNames.label("...", url: "https://drive.test/download?id=123"), "자막 파일")
        XCTAssertEqual(SubtitleNames.label("…", fallback: "원본 자막 게시물"), "원본 자막 게시물")
    }
}
