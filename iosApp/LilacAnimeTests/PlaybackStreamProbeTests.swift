import XCTest
@testable import LilacAnime

final class PlaybackStreamProbeTests: XCTestCase {
    private func stream(key: String? = nil) -> ResolvedStream {
        ResolvedStream(label: "HD-1", url: URL(string: "https://fixture.test/master.m3u8")!, referer: "https://player.test/e/episode", headers: [:], manifestKey: key)
    }
    private var fragment: Data {
        var data = Data(repeating: 0, count: 188 * 3)
        for offset in [0, 188, 376] { data[offset] = 71 }
        return data
    }
    func testMasterVariantAndFirstMediaBytesMustAllBeAvailable() async throws {
        var requests: [URL] = []
        try await PlaybackStreamProbe.validate(stream()) { url, original, range in
            requests.append(url)
            XCTAssertEqual(original.referer, "https://player.test/e/episode")
            if url.lastPathComponent == "master.m3u8" { return Data("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nmedia/index.m3u8".utf8) }
            if url.lastPathComponent == "index.m3u8" { return Data("#EXTM3U\n#EXTINF:6,\n../one.png\n#EXT-X-ENDLIST".utf8) }
            XCTAssertEqual(range, "bytes=0-131071")
            return Data([137,80,78,71,13,10,26,10]) + self.fragment
        }
        XCTAssertEqual(requests.map(\.absoluteString), ["https://fixture.test/master.m3u8", "https://fixture.test/media/index.m3u8", "https://fixture.test/one.png"])
    }
    func testEncryptedPlaylistCannotWinBeforeItsKeyArrives() async throws {
        let key: [UInt8] = [1,2,3,4]
        let plain = Array("#EXTM3U\n#EXTINF:6,\none.ts\n#EXT-X-ENDLIST".utf8)
        let encoded = Data(plain.enumerated().map { $0.element ^ key[$0.offset % key.count] }).base64EncodedData()
        let fetch: PlaybackStreamProbe.Fetch = { url, _, _ in url.pathExtension == "m3u8" ? encoded : self.fragment }
        do { try await PlaybackStreamProbe.validate(stream(), fetch: fetch); XCTFail("A keyless encrypted playlist is not ready") } catch { }
        try await PlaybackStreamProbe.validate(stream(key: Data(key).base64EncodedString()), fetch: fetch)
    }
    func testFastInvalidServerDoesNotWinTheRace() async throws {
        let candidates = [stream(), ResolvedStream(label: "HD-2", url: URL(string: "https://fixture.test/good.m3u8")!, referer: "", headers: [:])]
        let winner = try await StaggeredResolver.first(candidates, delay: 10_000_000) { candidate in
            try await PlaybackStreamProbe.validate(candidate) { url, _, _ in
                if url.lastPathComponent == "master.m3u8" { return Data("<html>unavailable</html>".utf8) }
                if url.pathExtension == "m3u8" { return Data("#EXTM3U\n#EXTINF:6,\none.ts".utf8) }
                return self.fragment
            }
            return candidate.label
        }
        XCTAssertEqual(winner, "HD-2")
    }
    func testEmptySegmentsAndPlaylistLoopsAreRejected() async {
        for loop in [false, true] {
            do {
                try await PlaybackStreamProbe.validate(stream()) { url, _, _ in
                    if loop { return Data("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmaster.m3u8".utf8) }
                    return url.pathExtension == "m3u8" ? Data("#EXTM3U\n#EXTINF:6,\none.ts".utf8) : Data()
                }
                XCTFail("An unusable server must fail validation")
            } catch { }
        }
    }
    func testStandardAesSegmentsAreLeftEncryptedForMpv() async throws {
        try await PlaybackStreamProbe.validate(stream()) { url, _, _ in
            if url.pathExtension == "m3u8" { return Data("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6,\none.ts".utf8) }
            return Data(repeating: 123, count: 64)
        }
    }
    func testCancelledProbeDoesNotContinueToTheSegment() async {
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            do { try await PlaybackStreamProbe.validate(stream()) { _, _, _ in XCTFail("Cancelled probe must not fetch"); return Data() } }
            catch { XCTAssertTrue(error is CancellationError) }
        }
        await task.value
    }
}
