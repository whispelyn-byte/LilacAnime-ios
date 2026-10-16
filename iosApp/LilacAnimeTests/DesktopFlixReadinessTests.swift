import XCTest
@testable import LilacAnime

final class DesktopFlixReadinessTests: XCTestCase {
    private func stream(_ path: String, key: String? = nil) -> ResolvedStream {
        ResolvedStream(label: "HD-2", url: URL(string: "https://fixture.test/" + path)!, referer: "https://flixcloud.cc/e/test", headers: [:], manifestKey: key)
    }

    func testMasterWaitsForKeyAndWinsOverFirstCapturedAudio() throws {
        var readiness = DesktopFlixReadiness()
        let candidates = [stream("audio/index.m3u8"), stream("master.m3u8")]
        XCTAssertNil(try readiness.select(candidates, now: 0))
        var keyed = candidates
        keyed[0].manifestKey = "late-key"
        let selected = try XCTUnwrap(readiness.select(keyed, now: 2))
        XCTAssertEqual(selected.url.lastPathComponent, "master.m3u8")
        XCTAssertEqual(selected.manifestKey, "late-key")
    }

    func testSeparateVideoAndAudioAreCombinedLikeDesktopProxy() throws {
        var readiness = DesktopFlixReadiness()
        let selected = try XCTUnwrap(readiness.select([stream("audio.m3u8", key: "key"), stream("video/index.m3u8")], now: 0))
        let manifest = try XCTUnwrap(selected.hlsManifest)
        XCTAssertTrue(manifest.contains("TYPE=AUDIO"))
        XCTAssertTrue(manifest.contains("URI=\"https://fixture.test/audio.m3u8\""))
        XCTAssertTrue(manifest.contains("AUDIO=\"audio\"\nhttps://fixture.test/video/index.m3u8"))
        XCTAssertEqual(selected.manifestKey, "key")
    }

    func testUnknownPlaylistWaitsUntilAddressesStableForOneAndHalfSeconds() throws {
        var readiness = DesktopFlixReadiness()
        let first = [stream("first.m3u8", key: "key")]
        XCTAssertNil(try readiness.select(first, now: 0))
        XCTAssertNil(try readiness.select(first, now: 1.4))
        let updated = first + [stream("next.m3u8")]
        XCTAssertNil(try readiness.select(updated, now: 1.4))
        XCTAssertNil(try readiness.select(updated, now: 2.8))
        XCTAssertEqual(try readiness.select(updated, now: 3)?.url, first[0].url)
    }

    func testCollectionHasTenSecondLimitEvenIfNewUrlsKeepArriving() throws {
        var readiness = DesktopFlixReadiness()
        let first = [stream("first.m3u8", key: "key")]
        XCTAssertNil(try readiness.select(first, now: 0))
        for second in 1..<10 {
            XCTAssertNil(try readiness.select(first + (1...second).map { stream("\($0).m3u8") }, now: Double(second)))
        }
        XCTAssertNotNil(try readiness.select(first + (1...10).map { stream("\($0).m3u8") }, now: 10))
    }

    func testMissingKeyFailsAfterCollectionPlusFifteenSeconds() throws {
        var readiness = DesktopFlixReadiness()
        let candidates = [stream("master.m3u8")]
        XCTAssertNil(try readiness.select(candidates, now: 0))
        XCTAssertNil(try readiness.select(candidates, now: 10))
        XCTAssertNil(try readiness.select(candidates, now: 24.9))
        XCTAssertThrowsError(try readiness.select(candidates, now: 25))
    }

    func testKeyArrivingDuringFinalWaitIsAcceptedWithoutFetchingMedia() throws {
        var readiness = DesktopFlixReadiness()
        XCTAssertNil(try readiness.select([stream("episode.m3u8")], now: 0))
        XCTAssertNil(try readiness.select([stream("episode.m3u8")], now: 10))
        XCTAssertNotNil(try readiness.select([stream("episode.m3u8", key: "key")], now: 24))
    }
    func testLateKeyDoesNotChangeTheCollectedPlaylistSelection() throws {
        var readiness = DesktopFlixReadiness()
        let original = stream("original.m3u8")
        XCTAssertNil(try readiness.select([original], now: 0))
        XCTAssertNil(try readiness.select([original], now: 10))
        let selected = try XCTUnwrap(readiness.select([original, stream("master.m3u8", key: "late")], now: 12))
        XCTAssertEqual(selected.url, original.url)
        XCTAssertEqual(selected.manifestKey, "late")
    }
    func testDesktopRecognisesMasterSuffixWithoutSlashPrefix() throws {
        var readiness = DesktopFlixReadiness()
        let selected = try XCTUnwrap(readiness.select([stream("audio.m3u8"), stream("hd-master.m3u8", key: "key")], now: 0))
        XCTAssertEqual(selected.url.lastPathComponent, "hd-master.m3u8")
    }
}
