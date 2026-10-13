import XCTest
import LilacShared
@testable import LilacAnime

final class PlaybackAutomationTests: XCTestCase {
    func testCompletionSignalsAdvanceOnlyOnceAndAllowReplay() {
        var completion = PlaybackCompletion()
        XCTAssertTrue(completion.reachedEnd()) // eof-reached with keep-open
        XCTAssertFalse(completion.reachedEnd()) // repeated property / END_FILE
        completion.resume() // seek back and replay the same episode
        XCTAssertTrue(completion.reachedEnd())
        completion.reset() // load the next episode
        XCTAssertTrue(completion.reachedEnd())
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
