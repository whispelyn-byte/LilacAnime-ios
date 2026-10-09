import XCTest
@testable import LilacAnime

final class OhliPlaybackTests: XCTestCase {
    func testSixSecondAdCannotBecomeEpisode() {
        XCTAssertFalse(OhliPlayback.isEpisodePlaylist("#EXTM3U\n#EXTINF:6.0,\nad.ts\n#EXT-X-ENDLIST"))
        XCTAssertFalse(OhliPlayback.isEpisodePlaylist("#EXTM3U\n#EXTINF:6.0,\nad.ts"))
        XCTAssertFalse(OhliPlayback.isEpisodePlaylist("<html>challenge</html>"))
        XCTAssertFalse(OhliPlayback.isEpisodePlaylist("#EXTM3U\n#EXTINF:nan,\nad.ts"))
        XCTAssertTrue(OhliPlayback.isEpisodePlaylist("#EXTM3U\n#EXTINF:20,\n1.ts\n#EXTINF:20,\n2.ts\n#EXT-X-ENDLIST"))
    }
    func testMasterHasToBeResolvedToMediaPlaylist() {
        let master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n480/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000\n# comment\n1080/index.m3u8"
        XCTAssertFalse(OhliPlayback.isEpisodePlaylist(master))
        XCTAssertEqual(OhliPlayback.variants(master, base: URL(string: "https://cdn.test/episode/master.txt?token=x")!).map(\.absoluteString), ["https://cdn.test/episode/1080/index.m3u8", "https://cdn.test/episode/480/index.m3u8"])
    }
    func testOnlySelectedPlayerContentCanBeAccepted() {
        let player = URL(string: "https://cdndania.com/video/episode")!
        XCTAssertFalse(OhliPlayback.accepts(kind: "video", frame: player, selected: player))
        XCTAssertFalse(OhliPlayback.accepts(kind: "ohliVariant", frame: URL(string: "https://ad.test/frame"), selected: player))
        XCTAssertFalse(OhliPlayback.accepts(kind: "ohliVariant", frame: URL(string: "https://cdndania.com/video/previous"), selected: player))
        XCTAssertFalse(OhliPlayback.accepts(kind: "ohliMedia", frame: player, selected: nil))
        XCTAssertTrue(OhliPlayback.accepts(kind: "ohliVariant", frame: player, selected: player))
        XCTAssertTrue(OhliPlayback.accepts(kind: "ohliMedia", frame: player, selected: player))
    }
}
