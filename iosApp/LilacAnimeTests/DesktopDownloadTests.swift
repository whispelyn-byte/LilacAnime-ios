import XCTest
import LilacShared
@testable import LilacAnime

final class DesktopDownloadTests: XCTestCase {
    func testSeriesGroupSummarisesEpisodeRangesLikeTheDesktop() {
        XCTAssertEqual(DownloadStore.episodeRanges([1, 2, 3, 5, 7, 8]), "1~3, 5, 7, 8")
        XCTAssertEqual(DownloadStore.episodeRanges([4, 2, 2, 1]), "1, 2, 4")
        XCTAssertEqual(DownloadStore.episodeRanges([12]), "12")
    }
    func testServerChoicesGroupByKindWithoutTheKindPrefix() {
        let stream = { (label: String) in ResolvedStream(label: label, url: URL(string: "https://fixture.test/" + label.replacingOccurrences(of: " ", with: "-") + ".m3u8")!, referer: "", headers: [:]) }
        let choices = ServerChoice.all(streams: [stream("RAW - anikoto HD-2"), stream("SUB - animepahe animepahe"), stream("DUB - Moon")], servers: [])
        XCTAssertEqual(ServerChoice.kinds(choices), ["sub", "raw", "dub"])
        XCTAssertEqual(ServerChoice.name("SUB - animepahe animepahe"), "animepahe")
        XCTAssertEqual(ServerChoice.name("RAW - anikoto HD-2"), "anikoto HD-2")
        XCTAssertEqual(ServerChoice.title("soft"), "SOFT · 자막 따로")
    }
    func testWindowsManifestRelocatesVideoSubtitlesFontsAndChapters() throws {
        let id = "test-" + UUID().uuidString
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(id)
        let series = root.appendingPathComponent("작품")
        try FileManager.default.createDirectory(at: series, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        for name in ["01.mp4", "01.ko.ass", "font.ttf"] { try Data(name.utf8).write(to: series.appendingPathComponent(name)) }
        let job: [String: Any] = ["status": "completed", "anime": ["provider": "reanime", "id": id, "title": "보존된 작품"],
            "episode": ["id": "4a", "name": "4a화"], "episodeNumber": 4, "filePath": "C:\\Users\\admin\\Videos\\LilacAnime\\작품\\01.mp4",
            "subtitleAssPath": "C:\\Users\\admin\\Videos\\LilacAnime\\작품\\01.ko.ass",
            "subtitleFonts": ["C:\\Users\\admin\\Videos\\LilacAnime\\작품\\font.ttf"],
            "skipSegments": [["type": "OP", "start": 5.0, "end": 95.0, "score": 0.9]]]
        try JSONSerialization.data(withJSONObject: [job]).write(to: root.appendingPathComponent("downloads.json"))
        let values = try DesktopDownloads.load(root, skipping: [])
        let entry = try XCTUnwrap(values.first)
        let target = DownloadStore.directory.appendingPathComponent(entry.id)
        defer { try? FileManager.default.removeItem(at: target) }
        XCTAssertEqual(entry.anime.title, "보존된 작품"); XCTAssertEqual(entry.episodeID, "4a")
        XCTAssertEqual(entry.subtitleFiles?.count, 1); XCTAssertEqual(entry.fontFiles?.count, 1)
        XCTAssertEqual(entry.chapters?.first?.end, 95)
        XCTAssertEqual(try Data(contentsOf: target.appendingPathComponent(try XCTUnwrap(entry.localFile))), Data("01.mp4".utf8))
        XCTAssertTrue(DownloadTransfer.valid(entry))
        XCTAssertEqual(try DesktopDownloads.load(root, skipping: [entry.id]).count, 0)
    }
    func testDesktopManifestCannotEscapeSelectedFolder() throws {
        XCTAssertThrowsError(try DesktopDownloads.locate("../outside.mp4", root: FileManager.default.temporaryDirectory))
        XCTAssertThrowsError(try DesktopDownloads.locate("C:\\Users\\..\\outside.mp4", root: FileManager.default.temporaryDirectory))
    }
}
