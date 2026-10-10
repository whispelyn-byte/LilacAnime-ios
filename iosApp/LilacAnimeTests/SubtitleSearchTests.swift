import XCTest
import LilacShared
@testable import LilacAnime

final class SubtitleSearchTests: XCTestCase {
    @MainActor func testJimakuRefreshKeepsResultsOnLoadingAndFailure() {
        var replies: [([SubtitleAsset]?, String?) -> Void] = []
        let model = makeModel { _, reply in replies.append(reply) }
        defer { model.shutdown(library: LibraryStore()) }
        let file = asset("episode-01.ass", source: "jimaku")
        model.search("jimaku"); replies[0]([file], nil)
        model.search("jimaku")
        XCTAssertTrue(model.searching); XCTAssertEqual(model.assets.map(\.url), [file.url])
        replies[1](nil, "연결 실패")
        XCTAssertFalse(model.searching); XCTAssertEqual(model.assets.map(\.url), [file.url])
        XCTAssertEqual(model.subtitleSearchError, "연결 실패")
        model.search("jimaku"); replies[2]([], nil)
        XCTAssertTrue(model.assets.isEmpty); XCTAssertNil(model.subtitleSearchError)
    }

    @MainActor func testReturningToJimakuRestoresItsOwnListAndRejectsOldReply() {
        var replies: [([SubtitleAsset]?, String?) -> Void] = []
        let model = makeModel { _, reply in replies.append(reply) }
        defer { model.shutdown(library: LibraryStore()) }
        let japanese = asset("episode-01.ass", source: "jimaku")
        let korean = asset("한국어.smi", source: "kairan")
        model.search("jimaku"); replies[0]([japanese], nil)
        model.search("kairan"); replies[1]([korean], nil)
        model.search("kairan") // This refresh remains pending.
        model.search("jimaku")
        XCTAssertEqual(model.assets.map(\.url), [japanese.url])
        XCTAssertEqual(model.subtitleResultsProvider, "jimaku")
        replies[2]([korean], nil)
        XCTAssertTrue(model.searching); XCTAssertEqual(model.assets.map(\.url), [japanese.url])
        replies[3]([japanese], nil)
        XCTAssertFalse(model.searching); XCTAssertEqual(model.assets.map(\.url), [japanese.url])
    }

    @MainActor func testRepeatedFileURLsDoNotCreateDuplicateRows() {
        let first = asset("episode-01.ass", source: "jimaku")
        let second = asset("episode-01.srt", source: "jimaku")
        let model = makeModel { _, reply in reply([first, second, first], nil) }
        defer { model.shutdown(library: LibraryStore()) }
        model.search("jimaku")
        XCTAssertEqual(model.assets.map(\.url), [first.url, second.url])
    }

    @MainActor private func makeModel(_ lookup: @escaping (String, @escaping ([SubtitleAsset]?, String?) -> Void) -> Void) -> EpisodePlayerModel {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: "{\"id\":\"fixture\",\"title\":\"테스트\",\"episodes\":[{\"id\":\"1\",\"number\":1,\"title\":\"1화\"}]}"), source: "reanime")
        return EpisodePlayerModel(item: PlaybackItem(anime: anime, episode: anime.anime.episodes[0]), subtitleLookup: lookup)
    }
    private func asset(_ name: String, source: String) -> SubtitleAsset {
        SubtitleAsset(name: name, url: "https://fixture.test/" + SubtitleFiles.key(name), source: source, score: 1, episode: nil, strict: false, bundle: false, postURL: "", matchedEpisode: nil)
    }
}
