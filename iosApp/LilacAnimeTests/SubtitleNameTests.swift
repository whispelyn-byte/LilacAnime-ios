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
    func testAlreadyKoreanPrefixAndMixedHeaderBytesAreRepaired() {
        // 인 contains the undefined Windows-1252 byte 0x9d; 화 uses its
        // printable substitutes ™ and ”. A whole-string re-encode cannot
        // handle this together with the already-correct Korean prefix.
        let broken = "한국어 - ì\u{009d}¸ 6í™”.smi"
        let expected = "한국어 - 인 6화.smi"
        XCTAssertEqual(SubtitleNames.readable(broken), expected)
        XCTAssertEqual(SubtitleNames.label(broken), expected)
        XCTAssertEqual(SubtitleNames.downloadFilename("attachment; filename=\"\(broken)\"", fallback: "source.smi"), expected)
        XCTAssertEqual(SubtitleLabels.label(provider: "user", translated: false, name: broken), expected)
    }
    func testTwiceMisdecodedSavedNameIsRepairedWithoutChangingItsPath() {
        func latinBytes(_ value: String) -> String {
            String(String.UnicodeScalarView(value.utf8.map { Unicode.Scalar($0) }))
        }
        let expected = "체인소맨 2기 6화.smi"
        let broken = latinBytes(latinBytes(expected))
        let file = URL(fileURLWithPath: "/saved/" + broken)
        XCTAssertEqual(SubtitleNames.label(file.lastPathComponent), expected)
        XCTAssertEqual(file.lastPathComponent, broken)
    }
    func testDecomposedFilesystemNamesAreRepaired() {
        let broken = "내 자막 6í™”.srt".decomposedStringWithCanonicalMapping
        XCTAssertEqual(SubtitleNames.readable(broken), "내 자막 6화.srt")
        XCTAssertEqual(SubtitleNames.label(broken), "내 자막 6화.srt")
        let legitimate = "한국어 Français épisode 6.srt".decomposedStringWithCanonicalMapping
        XCTAssertEqual(Array(SubtitleNames.readable(legitimate).unicodeScalars), Array(legitimate.unicodeScalars))
    }
    func testMixedLegitimateAccentsRemainUntouched() {
        for name in ["한국어 Français épisode 6.srt", "日本語 café.ass", "ì è é à.smi", "Ã©.srt"] {
            XCTAssertEqual(SubtitleNames.readable(name), name)
        }
    }
    func testLostBytesUseReadableFallbackInsteadOfReplacementCharacters() {
        XCTAssertEqual(SubtitleNames.label("ì�¸ 6í™”.smi"), "자막 파일")
        XCTAssertEqual(SubtitleNames.label("ì�¸.smi", url: "https://fixture.test/%ED%95%9C%EA%B8%80.smi"), "한글.smi")
    }
    func testImportedFilenameIsRepairedBeforeItIsSaved() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("내 자막 6í™”.srt")
        try "1\n00:00:00,000 --> 00:00:01,000\n한국어 자막".write(to: file, atomically: true, encoding: .utf8)
        defer {
            try? FileManager.default.removeItem(at: directory)
            try? FileManager.default.removeItem(at: SubtitleFiles.root.appendingPathComponent(SubtitleFiles.key(file.absoluteString)))
        }
        let prepared = try await SubtitleFiles.prepare(file)
        let copied = try XCTUnwrap(prepared.first)
        XCTAssertEqual(copied.lastPathComponent, "내 자막 6화.srt")
        XCTAssertTrue(try SubtitleFiles.text(copied).contains("한국어 자막"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
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
