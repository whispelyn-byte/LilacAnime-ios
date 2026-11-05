import XCTest
import LilacShared
@testable import LilacAnime

/// Desktop 77e3d7e: every site's episode lists RE:Anime's and Miruro's tracks of the same episode.
final class CrossSiteTracksTests: XCTestCase {
    private func track(_ label: String, _ language: String, origin: String? = nil) -> RemoteSubtitle {
        RemoteSubtitle(label: label, url: URL(string: "https://fixture.test/\(origin ?? "own")/\(language)-\(label.count).vtt")!, language: language, origin: origin)
    }

    func testTheOtherSitesTracksAreAllListedWhenTheSiteHasNoneAndOnlyAKoreanOneBesideItsOwn() {
        let other = [track("Japanese", "ja", origin: "reanime"), track("Korean", "ko", origin: "miruro"), track("Spanish", "es", origin: "miruro")]
        XCTAssertEqual(CrossSiteTrackPolicy.merged(own: [], other: other).map(\.label), ["Japanese", "Korean", "Spanish"])
        let own = [track("English", "en")]
        XCTAssertEqual(CrossSiteTrackPolicy.merged(own: own, other: other).map(\.label), ["English", "Korean"])
        // A site with its own Korean track lists only its own.
        XCTAssertEqual(CrossSiteTrackPolicy.merged(own: [track("Korean", "ko")], other: other).map(\.origin), [nil])
        // A downloaded episode keeps the other sites' tracks in its own list, still told apart, never twice.
        let downloaded = [track("Japanese", "ja", origin: "reanime")]
        XCTAssertEqual(CrossSiteTrackPolicy.merged(own: downloaded, other: []).map(\.origin), ["reanime"])
        XCTAssertEqual(CrossSiteTrackPolicy.merged(own: downloaded, other: downloaded).count, 1)
    }

    func testAKoreanSitesOwnSubtitleIsNoTrackSoTheOtherSitesTracksAreAllListed() {
        let other = [track("Japanese", "ja", origin: "reanime"), track("Korean", "ko", origin: "miruro")]
        let linkkf = [track("Linkkf 한국어", "ko")]
        for source in ["linkkf", "ohli24", "linkani", "animenosub"] {
            let listed = CrossSiteTrackPolicy.listed(own: linkkf, source: source)
            XCTAssertEqual(CrossSiteTrackPolicy.merged(own: listed, other: other).map(\.label), ["Japanese", "Korean"], source)
        }
        // RE:Anime / Miruro tracks are a track list: their own Korean track keeps the others out.
        XCTAssertEqual(CrossSiteTrackPolicy.merged(own: CrossSiteTrackPolicy.listed(own: [track("Korean", "ko")], source: "miruro"), other: other).count, 1)
        // A download keeps the other sites' tracks in its list on any site.
        XCTAssertEqual(CrossSiteTrackPolicy.listed(own: linkkf + [track("Japanese", "ja", origin: "reanime")], source: "linkkf").map(\.origin), ["reanime"])
    }

    func testTheKoreanSitesFilesJoinEveryListBesideASitesOwnKoreanTrackToo() {
        let korean = [track("한국어", "ko", origin: "ohli24"), track("한국어", "ko", origin: "linkkf")]
        let fast = [track("Korean", "ko", origin: "miruro"), track("Japanese", "ja", origin: "miruro")]
        // RE:Anime's own Korean track keeps Miruro's out, never 애니24's or Linkkf's file.
        XCTAssertEqual(CrossSiteTrackPolicy.merged(own: [track("Korean", "ko")], other: fast + korean).map(\.origin), [nil, "ohli24", "linkkf"])
        XCTAssertEqual(CrossSiteTrackPolicy.merged(own: [], other: fast + korean).count, 4)
        XCTAssertEqual(CrossSiteTrackPolicy.sourceLabel(playing: "animenosub", tracks: korean), "애니24·Linkkf")
        XCTAssertEqual(CrossSiteTrackPolicy.label(korean[0], playing: "reanime"), "애니24")
    }

    func testTheListIsNamedForTheSitesItsTracksComeFrom() {
        XCTAssertEqual(CrossSiteTrackPolicy.sourceLabel(playing: "ohli24", tracks: [track("Japanese", "ja", origin: "reanime"), track("Korean", "ko", origin: "miruro")]), "Re:Anime·Miruro")
        XCTAssertEqual(CrossSiteTrackPolicy.sourceLabel(playing: "miruro", tracks: [track("English", "en"), track("Korean", "ko", origin: "reanime")]), "Miruro·Re:Anime")
        XCTAssertEqual(CrossSiteTrackPolicy.sourceLabel(playing: "reanime", tracks: [track("English", "en")]), "Re:Anime")
        XCTAssertEqual(CrossSiteTrackPolicy.label(track("Korean", "ko", origin: "miruro"), playing: "linkani"), "Miruro")
    }

    func testTranslationTakesTheSitesJapaneseThenAnotherSitesThenEnglish() {
        let tracks = [track("English (Dialogue)", "en"), track("English", "en", origin: "miruro"), track("Japanese", "ja", origin: "reanime")]
        XCTAssertEqual(CrossSiteTrackPolicy.translationOrder(tracks).map(\.label), ["Japanese", "English (Dialogue)", "English"])
        XCTAssertEqual(CrossSiteTrackPolicy.translationOrder(tracks + [track("Japanese", "ja")]).first?.origin, nil)
    }

    func testAnotherSitesTrackIsNeverASyncReference() {
        let tracks = [track("English (Full)", "en", origin: "reanime"), track("English", "en")]
        XCTAssertEqual(SubtitleAutoSync.referenceTracks(tracks).map(\.origin), [nil])
    }

    @MainActor func testHalfEpisodesSpecialsAndDubsAreNotAsked() {
        func item(_ display: String, url: String = "https://example.test/watch/x?ep=3") -> PlaybackItem {
            let anime = SavedAnime(AnimeSnapshot.shared.decode(content: #"{"id":"cross","title":"test","episodes":[{"id":"e","number":3,"title":"3화","displayNumber":"\#(display)","videoUrl":"\#(url)"}]}"#), source: "linkani")
            return PlaybackItem(anime: anime, episode: anime.anime.episodes[0])
        }
        XCTAssertEqual(CrossSiteTrackPolicy.number(item("3")).number, 3); XCTAssertFalse(CrossSiteTrackPolicy.number(item("3")).special)
        XCTAssertTrue(CrossSiteTrackPolicy.number(item("3.5")).special)
        XCTAssertTrue(CrossSiteTrackPolicy.number(item("4a")).special)
        XCTAssertTrue(CrossSiteTrackPolicy.number(item("OVA 1화")).special)
        XCTAssertFalse(CrossSiteTrackPolicy.number(item("80000대장경")).special)
        XCTAssertTrue(CrossSiteTrackPolicy.dub(item("3", url: "https://animenosub.to/show-episode-3-english-dub/")))
        XCTAssertFalse(CrossSiteTrackPolicy.dub(item("3", url: "https://example.test/dubai-story/3")))
    }

    func testDownloadedTracksKeepWhoseTheyAre() throws {
        let saved = DownloadSubtitleTrack(file: "01.vtt", label: "Korean", language: "ko", provider: "reanime", origin: "miruro")
        let restored = try JSONDecoder().decode(DownloadSubtitleTrack.self, from: JSONEncoder().encode(saved))
        XCTAssertEqual(restored.origin, "miruro")
        let old = try JSONDecoder().decode(DownloadSubtitleTrack.self, from: Data(#"{"file":"01.vtt","label":"Korean","language":"ko","provider":"reanime"}"#.utf8))
        XCTAssertNil(old.origin)
    }
}
