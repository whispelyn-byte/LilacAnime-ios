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

    @MainActor func testSkipLookupRetriesTransientFailureAndKeepsOfflineChapters() async {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: """
        {"id":"automation-fixture","title":"Fixture","malId":1,"episodes":[{"id":"1","number":1,"title":"1"}]}
        """), source: "local")
        let first = expectation(description: "first request fails")
        let retried = expectation(description: "retry succeeds")
        var calls = 0
        let model = EpisodePlayerModel(item: PlaybackItem(anime: anime, episode: anime.anime.episodes[0]), skipLookup: { _, mal, episode, duration, completion in
            calls += 1
            XCTAssertEqual(mal, 1); XCTAssertEqual(episode, 1); XCTAssertEqual(duration, 1440)
            if calls == 1 { completion(nil, "temporary network error"); first.fulfill() }
            else { completion([SkipSegment(type: "op", start: 10, end: 90)], nil); retried.fulfill() }
        })
        model.chapters = [OfflineChapter(type: "ed", start: 1300, end: 1400, score: 1)]
        let now = Date()
        model.requestSkipSegments(duration: 1440, now: now)
        await fulfillment(of: [first], timeout: 2)
        XCTAssertEqual(model.chapters.first?.type, "ed")
        model.requestSkipSegments(duration: 1440, now: now.addingTimeInterval(1))
        XCTAssertEqual(calls, 1, "Progress ticks must respect the retry delay")
        model.requestSkipSegments(duration: 1440, now: now.addingTimeInterval(11))
        await fulfillment(of: [retried], timeout: 2)
        XCTAssertEqual(model.chapters.first?.type, "op")
        model.requestSkipSegments(duration: 1440, now: now.addingTimeInterval(30))
        XCTAssertEqual(calls, 2, "Successful lookups must not be repeated every tick")
    }
}
