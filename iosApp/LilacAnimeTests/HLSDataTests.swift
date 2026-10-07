import XCTest
@testable import LilacAnime
final class HLSDataTests: XCTestCase {
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
}
