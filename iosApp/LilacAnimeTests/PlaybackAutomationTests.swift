import XCTest
import LilacShared
@testable import LilacAnime

final class PlaybackAutomationTests: XCTestCase {
    @MainActor func testPausedAndBufferingTimeCannotTriggerAutomaticSeek() {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: """
        {"id":"paused-skip-fixture","title":"Fixture","episodes":[{"id":"1","number":1,"title":"1"}]}
        """), source: "local")
        let model = EpisodePlayerModel(item: PlaybackItem(anime: anime, episode: anime.anime.episodes[0]))
        defer { model.shutdown(library: LibraryStore(file: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))) }
        model.chapters = [OfflineChapter(type: "op", start: 10, end: 90, score: 1)]
        let start = Date(timeIntervalSince1970: 1000)
        model.engine.play()
        model.updateAutoSkip(position: 20, enabled: true, now: start)
        model.engine.pause()
        // The final one-second playback sample can arrive after the pause flag.
        model.updateAutoSkip(position: 21, enabled: true, now: start.addingTimeInterval(1))
        model.updateAutoSkip(position: 21, enabled: true, now: start.addingTimeInterval(10))
        XCTAssertEqual(model.engine.seekRevision, 0)
        model.engine.play()
        model.updateAutoSkip(position: 21, enabled: true, now: start.addingTimeInterval(11))
        XCTAssertEqual(model.engine.seekRevision, 0, "Resume retains one elapsed second")
        model.updateAutoSkip(position: 21.5, enabled: true, now: start.addingTimeInterval(11.5))
        model.engine.buffering = true
        model.updateAutoSkip(position: 21.5, enabled: true, now: start.addingTimeInterval(20))
        XCTAssertEqual(model.engine.seekRevision, 0)
        model.engine.buffering = false
        model.updateAutoSkip(position: 21.5, enabled: true, now: start.addingTimeInterval(21))
        model.updateAutoSkip(position: 22.25, enabled: true, now: start.addingTimeInterval(21.75))
        XCTAssertEqual(model.engine.seekRevision, 0)
        model.updateAutoSkip(position: 22.5, enabled: true, now: start.addingTimeInterval(22))
        XCTAssertEqual(model.engine.seekRevision, 1)
        model.updateAutoSkip(position: 22.5, enabled: true, now: start.addingTimeInterval(25))
        XCTAssertEqual(model.engine.seekRevision, 1, "An asynchronous seek is not sent twice")
    }

    func testSkipCountdownUsesPlaybackSpeedAndDiscardsSeekJumps() {
        var countdown = PlaybackSkipCountdown(position: 20, speed: 2, revision: 0)
        countdown.update(position: 22, speed: 2, revision: 0)
        XCTAssertEqual(countdown.elapsed, 1)
        countdown.update(position: 22, speed: 2, revision: 0)
        XCTAssertEqual(countdown.elapsed, 1, "Paused/buffering samples preserve elapsed time")
        countdown.update(position: 25, speed: 1, revision: 0)
        XCTAssertEqual(countdown.elapsed, 2.5)
        countdown.update(position: 40, speed: 1, revision: 1)
        XCTAssertEqual(countdown.elapsed, 0, "Seeking is not time spent watching")
        countdown.update(position: 41, speed: 1, revision: 1)
        XCTAssertEqual(countdown.elapsed, 1)
        countdown.update(position: 30, speed: 1, revision: 1)
        XCTAssertEqual(countdown.elapsed, 0, "A backwards timestamp starts a fresh sample")
    }

    func testCompletionSignalsAdvanceOnlyOnceAndAllowReplay() {
        var completion = PlaybackCompletion()
        XCTAssertTrue(completion.reachedEnd()) // eof-reached with keep-open
        XCTAssertFalse(completion.reachedEnd()) // repeated property / END_FILE
        completion.resume() // seek back and replay the same episode
        XCTAssertTrue(completion.reachedEnd())
        completion.reset() // load the next episode
        XCTAssertTrue(completion.reachedEnd())
    }

    /// desktop tests/player-interactions: manual seeks suppress only the destination skip segment.
    @MainActor func testViewerSeekWatchesOnlyTheSegmentItLandsIn() {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: """
        {"id":"skip-fixture","title":"Fixture","episodes":[{"id":"1","number":1,"title":"1"}]}
        """), source: "local")
        let model = EpisodePlayerModel(item: PlaybackItem(anime: anime, episode: anime.anime.episodes[0]))
        defer { model.shutdown(library: LibraryStore(file: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))) }
        let op = OfflineChapter(type: "op", start: 10, end: 90, score: 1), ed = OfflineChapter(type: "ed", start: 200, end: 260, score: 1)
        XCTAssertFalse(model.watchedByChoice(op), "normal playback skips")
        model.engine.seekByViewer(20)
        XCTAssertTrue(model.watchedByChoice(op), "seeking into an OP allows watching it")
        model.engine.seekByViewer(30)
        XCTAssertTrue(model.watchedByChoice(op), "seeking within the segment keeps the exemption")
        XCTAssertFalse(model.watchedByChoice(nil), "the position before the seek is reported first")
        XCTAssertNotNil(model.engine.viewerSeek, "and does not end the exemption")
        XCTAssertFalse(model.watchedByChoice(ed, now: Date().addingTimeInterval(2)), "a later segment still skips")
        XCTAssertNil(model.engine.viewerSeek, "once played elsewhere the exemption ends")
        model.engine.seekByViewer(5)
        XCTAssertFalse(model.watchedByChoice(op), "seeking before the segment allows normal auto skip")
        model.engine.seekByViewer(20); model.engine.clearViewerSeek()
        XCTAssertFalse(model.watchedByChoice(op), "new playback clears the exemption")
    }

    @MainActor func testSubtitleSyncButtonsStepAndResetToZero() {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: """
        {"id":"sync-fixture","title":"Fixture","episodes":[{"id":"1","number":1,"title":"1"}]}
        """), source: "local")
        let library = LibraryStore(file: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let model = EpisodePlayerModel(item: PlaybackItem(anime: anime, episode: anime.anime.episodes[0]))
        defer { model.shutdown(library: library) }
        for _ in 0..<5 { model.shiftSubtitle(0.25, library: library) } // 0.25초 늦게 ×5
        XCTAssertEqual(model.subtitleOffset, 1.25)
        model.setSubtitleOffset(0, library: library) // 원래대로
        XCTAssertEqual(model.subtitleOffset, 0)
        model.setSubtitleOffset(0, library: library) // pressed again
        XCTAssertEqual(model.subtitleOffset, 0)
        model.shiftSubtitle(-0.25, library: library) // 0.25초 빠르게
        XCTAssertEqual(model.subtitleOffset, -0.25)
        model.setSubtitleOffset(0.1234, library: library) // typed 123.4 ms
        XCTAssertEqual(model.subtitleOffset, 0.123)
        XCTAssertEqual(library.subtitleChoice(animeID: anime.id, episodeID: model.item.episodeID)?.offset, 0.123)
    }
}
