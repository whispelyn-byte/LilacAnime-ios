import XCTest
@testable import LilacAnime

final class MiruroPlaybackTests: XCTestCase {
    private func stream(_ label: String) -> ResolvedStream { ResolvedStream(label: label, url: URL(string: "https://fixture.test/" + label.replacingOccurrences(of: " ", with: "_") + ".m3u8")!, referer: "", headers: [:], subtitles: []) }
    func testAutomaticOriginalAudioNeverSelectsDubAndSlowestGroupsFallThrough() async throws {
        let original = stream("RAW - one"), dub = stream("DUB - fast"), sub = stream("SUB - two")
        let selected = try await MiruroPlayback.choose([original,dub,sub], preferRaw: true, preferred: nil) { item in
            MiruroProbeResult(speed: item.label == original.label ? 100 : 400000, requiredSpeed: 300000)
        }
        XCTAssertEqual(selected.label, sub.label)
    }
    func testManualSlowChoiceAndExcludedDubRemainDubbed() async throws {
        let one = stream("DUB - one"), two = stream("DUB - two"), raw = stream("RAW - other")
        let manual = try await MiruroPlayback.choose([one,raw], preferRaw: true, preferred: one.label) { _ in MiruroProbeResult(speed: 1, requiredSpeed: 500000) }
        XCTAssertEqual(manual.label, one.label)
        let recovered = try await MiruroPlayback.choose([one,two,raw], preferRaw: true, preferred: one.label, excluded: [one.label]) { _ in MiruroProbeResult(speed: 500000, requiredSpeed: 1) }
        XCTAssertEqual(recovered.label, two.label)
    }
    func testRecoveryStopsWithoutCompatibleAlternativesOrAfterTwoFailures() {
        let raw = stream("RAW - current"), next = stream("SOFT - next"), dub = stream("DUB - other")
        XCTAssertFalse(MiruroPlayback.canRecover([raw,dub], current: raw, failed: []))
        XCTAssertTrue(MiruroPlayback.canRecover([raw,next,dub], current: raw, failed: []))
        XCTAssertFalse(MiruroPlayback.canRecover([raw,next,dub], current: raw, failed: ["one","two"]))
    }
    func testAllBrokenServersFailRatherThanReturningUnvalidatedFirst() async {
        do { _ = try await MiruroPlayback.choose([stream("RAW - broken")], preferRaw: true, preferred: nil) { _ in throw NSError(domain: "offline", code: 1) }; XCTFail("Must reject broken media") } catch { }
    }
    func testProbeFollowsManifestRedirectAndReadsTwoRealSegments() async throws {
        let item = stream("RAW - probe")
        let result = try await MiruroPlayback.inspect(item) { url in
            let body: Data; let final: URL
            if url == item.url {
                body = Data("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1920x1080\nmedia/list.m3u8\n".utf8)
                final = URL(string: "https://redirect.test/master.m3u8")!
            } else if url.pathExtension == "m3u8" {
                XCTAssertEqual(url.absoluteString, "https://redirect.test/media/list.m3u8")
                body = Data("#EXTM3U\n#EXTINF:5,\none.ts\n#EXTINF:5,\ntwo.ts\n".utf8); final = url
            } else {
                XCTAssertTrue(["one.ts","two.ts"].contains(url.lastPathComponent))
                body = Data(repeating: 1, count: 10000); final = url
            }
            return (body, HTTPURLResponse(url: final, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        XCTAssertEqual(result.requiredSpeed, 150000)
    }
    func testProbeHasAnOverallDeadlineEvenWhileTransportIsWaiting() async {
        do {
            _ = try await MiruroPlayback.inspect(stream("RAW - timeout"), timeout: 0.02) { _ in
                try await Task.sleep(nanoseconds: 1_000_000_000)
                return nil
            }
            XCTFail("Probe must time out")
        } catch { XCTAssertTrue(error.localizedDescription.contains("시간 초과")) }
    }
    func testStallIgnoresPauseAndSeekAndRequiresFifteenSeconds() {
        var monitor = MiruroStallMonitor()
        func tick(_ time: Double, _ position: Double = 118, _ seek: UInt64 = 0, _ eligible: Bool = true) -> Bool {
            monitor.update(context: "same", position: position, seek: seek, eligible: eligible, buffering: true, now: time)
        }
        XCTAssertFalse(tick(0)); XCTAssertFalse(tick(14.9)); XCTAssertTrue(tick(15))
        XCTAssertFalse(tick(30,118,0,false)); XCTAssertFalse(tick(31,118,1)); XCTAssertFalse(tick(45,118,1)); XCTAssertTrue(tick(46,118,1))
        XCTAssertFalse(tick(50,119,1)); XCTAssertFalse(tick(64,119,1))
    }
    func testTranslationTracksExcludeSignsAndRankJapaneseDialogue() {
        func track(_ label: String, _ language: String) -> RemoteSubtitle { RemoteSubtitle(label: label, url: URL(string:"https://fixture.test/sub.ass")!, language: language) }
        let values = [track("Japanese Signs", "ja"),track("Japanese (AI)","jpn"),track("Japanese Full","ja-JP"),track("English Full","eng")]
        XCTAssertEqual(DesktopSubtitlePolicy.translationTrack(values, language: "ja")?.label,"Japanese Full")
        XCTAssertEqual(DesktopSubtitlePolicy.translationTrack(values, language: "en")?.label,"English Full")
        XCTAssertNil(DesktopSubtitlePolicy.translationTrack([track("Songs & Signs","ja")]))
        XCTAssertFalse(DesktopSubtitlePolicy.shouldTranslateAlongside(source: "anissia", siteKorean: false))
    }
}
