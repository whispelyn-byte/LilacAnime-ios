import XCTest
@testable import LilacAnime
final class HLSDataTests: XCTestCase {
    func testCancelledProxyStartFinishesInsteadOfLeavingTheCallerWaiting() async {
        let proxy = HLSProxy()
        let finished = expectation(description: "Cancelled proxy start returns")
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            do {
                let stream = ResolvedStream(label: "test", url: URL(string: "https://example.test/video.m3u8")!, referer: "", headers: [:])
                _ = try await proxy.start(stream)
                XCTFail("Cancelled start should fail")
            } catch { XCTAssertTrue(error is CancellationError) }
            finished.fulfill()
        }
        await fulfillment(of: [finished], timeout: 5)
        task.cancel(); proxy.stop()
    }
    func testRelativeKeysMapsAndSegmentsAreRewritten() {
        let manifest = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-MAP:URI=\"../init.mp4\",BYTERANGE=\"20@0\"\nsegments/1.ts\n#EXT-X-ENDLIST"
        let base = URL(string: "https://example.test/watch/index.m3u8")!
        let urls = HLSData.references(manifest, base: base)
        XCTAssertEqual(Set(urls.map(\.absoluteString)), ["https://example.test/watch/key.bin","https://example.test/init.mp4","https://example.test/watch/segments/1.ts"])
        let local = HLSData.rewrite(manifest, base: base) { $0.lastPathComponent }
        XCTAssertTrue(local.contains("URI=\"key.bin\""))
        XCTAssertTrue(local.contains("BYTERANGE=\"20@0\""))
        XCTAssertTrue(local.contains("\n1.ts\n"))
    }
    func testPreferredHeightKeepsSeparateAudioAndChoosesOneRendition() {
        let text = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=854x480\n480.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000,RESOLUTION=1280x720\n720.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4000,RESOLUTION=1920x1080\n1080.m3u8"
        let selected = HLSData.selectVariant(text, quality: "720p")
        XCTAssertTrue(selected.contains("audio.m3u8"))
        XCTAssertTrue(selected.contains("720.m3u8"))
        XCTAssertFalse(selected.contains("480.m3u8"))
        XCTAssertFalse(selected.contains("1080.m3u8"))
    }
    func testEncryptedManifestAndWrappedFragment() throws {
        let plain = Array("#EXTM3U\nvideo.ts\n#EXT-X-ENDLIST".utf8)
        let key: [UInt8] = [1,2,3,4]
        let encrypted = Data(plain.enumerated().map { $0.element ^ key[$0.offset % key.count] }).base64EncodedData()
        XCTAssertEqual(try HLSData.manifest(encrypted, key: Data(key).base64EncodedString()), String(bytes: plain, encoding: .utf8))
        XCTAssertThrowsError(try HLSData.manifest(encrypted, key: Data([7,8]).base64EncodedString()))
        let media = Data([71,0,20,30])
        XCTAssertEqual(HLSData.fragment(Data([137,80,78,71,13,10,26,10]) + media), media)
    }
    func testAudioAndVideoKeepIndependentPrefetchOrder() {
        let video = (1...7).map { URL(string: "https://fixture.test/video/\($0).png")! }
        let audio = (1...7).map { URL(string: "https://fixture.test/audio/\($0).png")! }
        var order = HLSMediaOrder()
        order.insert(video); order.insert(audio)
        XCTAssertEqual(order.following(video[0]), Array(video[1...5]))
        XCTAssertEqual(order.following(audio[0]), Array(audio[1...5]))
        XCTAssertTrue(order.following(video.last!).isEmpty)
        order.insert(Array(video.dropFirst()))
        XCTAssertEqual(order.following(audio[1]), Array(audio[2...6]))
    }
    /// FlixCloud's CDN now names its segments .png/.webp; FFmpeg refuses those extensions for HLS segments, so mpv
    /// never started the episode (it timed out). The proxy and the downloaded files name them .ts.
    private func flixStream() -> ResolvedStream {
        let media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:4.6,\nhttps://vault.invalid/_v7/id/seg-0-f1-v1-a0.png\n#EXTINF:4.1,\nhttps://vault.invalid/_v7/id/seg-1-f1-v1-a0.webp\n#EXT-X-ENDLIST"
        return ResolvedStream(label: "HD-2", url: URL(string: "https://fetch.invalid/_v7/id/video.m3u8")!, referer: "https://flixcloud.cc/e/test",
            headers: [:], manifestKey: Data("key".utf8).base64EncodedString(), hlsManifest: media)
    }
    func testFlixImageSegmentsAreProxiedAsTransportStream() async throws {
        let proxy = HLSProxy(); defer { proxy.stop() }
        let proxied = try await proxy.start(flixStream())
        let (data, _) = try await URLSession.shared.data(from: proxied.url)
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertEqual(HLSData.references(text, base: proxied.url).map(\.pathExtension), ["ts", "ts"])
    }
    func testFlixImageSegmentsAreDownloadedAsTransportStreamFiles() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let (_, parts, _) = try await HLSPlanBuilder(stream: flixStream(), folder: folder, quality: "Auto").build()
        XCTAssertEqual(parts.map(\.url.pathExtension).sorted(), ["png", "webp"])
        XCTAssertTrue(parts.allSatisfy { $0.name.hasSuffix(".ts") })
    }
}
