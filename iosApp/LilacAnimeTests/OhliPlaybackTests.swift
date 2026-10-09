import XCTest
@testable import LilacAnime

final class OhliPlaybackTests: XCTestCase {
    private let media = "#EXTM3U\n#EXT-X-TARGETDURATION:20\n#EXTINF:20,\nhttps://cdn.invalid/1080p_000.html\n#EXTINF:20,\nhttps://cdn.invalid/1080p_001.html\n#EXT-X-ENDLIST"
    private func capturedStream() -> ResolvedStream {
        ResolvedStream(label: "HLS", url: URL(string: "https://cdndania.invalid/m3/session-token")!, referer: "", headers: [:], hlsManifest: media)
    }
    func testSessionOnlyPlaylistSurvivesNativeFetchAndPersistence() async throws {
        let stream = capturedStream()
        let restored = try JSONDecoder().decode(ResolvedStream.self, from: JSONEncoder().encode(stream))
        let (body, response) = try await HLSData.fetch(restored.url, stream: restored)
        XCTAssertEqual(String(decoding: body, as: UTF8.self), media)
        XCTAssertEqual(response.mimeType, "application/vnd.apple.mpegurl")
        let legacy = #"{"label":"MP4","url":"https://cdn.invalid/video.mp4","referer":"","headers":{},"subtitles":[]}"#
        XCTAssertNil(try JSONDecoder().decode(ResolvedStream.self, from: Data(legacy.utf8)).hlsManifest)
    }
    func testNativeProxyServesExtensionlessManifestWithoutReopeningPlayerSession() async throws {
        let proxy = HLSProxy(); defer { proxy.stop() }
        let proxied = try await proxy.start(capturedStream())
        XCTAssertEqual(proxied.url.pathExtension, "m3u8")
        XCTAssertNil(proxied.hlsManifest)
        let (data, response) = try await URLSession.shared.data(from: proxied.url)
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertTrue(text.contains("http://127.0.0.1:"))
        XCTAssertFalse(text.contains("cdn.invalid"))
        XCTAssertEqual(HLSData.references(text, base: proxied.url).map(\.pathExtension), ["ts", "ts"])
        XCTAssertTrue(OhliPlayback.isEpisodePlaylist(text))
    }
    func testDownloadPlanRelocatesHTMLSegmentsAsTransportStreamFiles() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let (root, parts) = try await HLSPlanBuilder(stream: capturedStream(), folder: folder, quality: "Auto").build()
        XCTAssertEqual(parts.count, 2)
        XCTAssertTrue(parts.allSatisfy { $0.name.hasSuffix(".ts") && $0.url.pathExtension == "html" })
        let text = try String(contentsOf: folder.appendingPathComponent(root), encoding: .utf8)
        XCTAssertTrue(parts.allSatisfy { text.contains($0.name) })
        XCTAssertFalse(text.contains("https://"))
    }
    func testHTMLMediaTypeStillRequiresRealMediaBytes() throws {
        var ts = Data(repeating: 0, count: 188 * 3)
        for offset in [0, 188, 376] { ts[offset] = 71 }
        XCTAssertEqual(try HLSData.validatedFragment(ts), ts)
        XCTAssertThrowsError(try HLSData.validatedFragment(Data("<html>advertisement</html>".utf8)))
    }
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
