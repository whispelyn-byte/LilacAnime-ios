import XCTest
@testable import LilacAnime

final class LANMediaRelayTests: XCTestCase {
    func testRangesAndUnregisteredPathsOverHTTP() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appendingPathComponent("video.mp4")
        try Data([0, 1, 2, 3, 4, 5]).write(to: file)
        let relay = LANMediaRelay(addressProvider: { "127.0.0.1" })
        defer { relay.stop() }
        let stream = ResolvedStream(label: "local", url: file, referer: "", headers: [:], manifestKey: nil)
        let url = try await relay.start(stream)
        var request = URLRequest(url: url)
        request.setValue("bytes=2-4", forHTTPHeaderField: "Range")
        let (body, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual(body, Data([2, 3, 4]))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 206)
        XCTAssertEqual((response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Range"), "bytes 2-4/6")
        request.httpMethod = "HEAD"
        let (head, headResponse) = try await URLSession.shared.data(for: request)
        XCTAssertTrue(head.isEmpty)
        XCTAssertEqual((headResponse as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Length"), "3")
        let unknown = url.deletingLastPathComponent().appendingPathComponent("unknown.mp4")
        let (_, denied) = try await URLSession.shared.data(from: unknown)
        XCTAssertEqual((denied as? HTTPURLResponse)?.statusCode, 404)
        request.httpMethod = "GET"; request.setValue("bytes=100-", forHTTPHeaderField: "Range")
        let (_, invalid) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((invalid as? HTTPURLResponse)?.statusCode, 416)
    }
    func testManifestRewritesOnlyFilesInsideSelectedDownload() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let manifest = folder.appendingPathComponent("index.m3u8")
        try "#EXTM3U\n#EXTINF:2,\n1.ts\n#EXT-X-ENDLIST".write(to: manifest, atomically: true, encoding: .utf8)
        try Data([71, 1, 2]).write(to: folder.appendingPathComponent("1.ts"))
        let relay = LANMediaRelay(addressProvider: { "127.0.0.1" }); defer { relay.stop() }
        let url = try await relay.start(ResolvedStream(label: "HLS", url: manifest, referer: "", headers: [:], manifestKey: nil))
        let (body, _) = try await URLSession.shared.data(from: url)
        let segment = String(decoding: body, as: UTF8.self).components(separatedBy: "\n").first { $0.hasPrefix("http://") }!
        let (data, _) = try await URLSession.shared.data(from: URL(string: segment)!)
        XCTAssertEqual(data, Data([71, 1, 2]))
        try "#EXTM3U\n../outside.ts\n#EXT-X-ENDLIST".write(to: manifest, atomically: true, encoding: .utf8)
        let (_, denied) = try await URLSession.shared.data(from: url)
        XCTAssertEqual((denied as? HTTPURLResponse)?.statusCode, 502)
    }
    func testSuffixRangeAndSymlinkEscapeAreRejected() throws {
        XCTAssertEqual(try LANMediaRelay.byteRange("bytes=-3", size: 10), .init(start: 7, end: 9))
        XCTAssertThrowsError(try LANMediaRelay.byteRange("bytes=9-2", size: 10))
        XCTAssertThrowsError(try LANMediaRelay.byteRange("bytes=0-1,5-6", size: 10))
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let link = folder.appendingPathComponent("outside")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: folder.deletingLastPathComponent())
        XCTAssertFalse(LANMediaRelay.allowedLocal(link.appendingPathComponent("missing"), root: folder))
        let outside = folder.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
        try Data([1]).write(to: outside)
        defer { try? FileManager.default.removeItem(at: outside) }
        XCTAssertFalse(LANMediaRelay.allowedLocal(link.appendingPathComponent(outside.lastPathComponent), root: folder))
        let inside = folder.appendingPathComponent("allowed.ts")
        try Data([71]).write(to: inside)
        XCTAssertTrue(LANMediaRelay.allowedLocal(inside, root: folder))
    }
}
