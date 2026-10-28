import XCTest
import LilacShared
@testable import LilacAnime

/// desktop 0.5.55: the 회차 panel and 계속 시청하기's episodes, and the Android app's history in 기기 동기화.
final class EpisodePickerTests: XCTestCase {
    private func anime(_ episodes: Int, id: String = "animenosub:bleach", source: String = "animenosub") -> SavedAnime {
        let list = (1...episodes).map { #"{"id":"\#(id)_ep_\#($0)","number":\#($0),"title":"\#($0)화","videoUrl":"https://animenosub.to/bleach-tybw-episode-\#($0)/"}"# }.joined(separator: ",")
        return SavedAnime(AnimeSnapshot.shared.decode(content: #"{"id":"\#(id)","title":"Bleach","source":"\#(source)","detailUrl":"https://animenosub.to/anime/bleach/","episodes":[\#(list)]}"#), source: source)
    }

    func testAnEpisodeInASeriesStepsThroughTheOnesAroundIt() {
        let saved = anime(5)
        let item = PlaybackItem.inSeries(saved, episodes: saved.anime.episodes, at: 2)
        XCTAssertEqual(item.number, 3)
        XCTAssertEqual(item.preceding.map(\.number), [1, 2])
        XCTAssertEqual(item.next.map(\.number), [4, 5])
        XCTAssertEqual(EpisodeMatch.index(saved.anime.episodes, id: "x", label: "4", number: 0), 3)
        XCTAssertEqual(EpisodeMatch.index(saved.anime.episodes, id: "x", label: "x", number: 5), 4)
        XCTAssertEqual(EpisodeMatch.title("12"), "12화"); XCTAssertEqual(EpisodeMatch.title("OVA 1"), "OVA 1")
    }

    @MainActor func testEachSourcesEpisodeHasTheAndroidAppsKey() {
        let saved = anime(5)
        let entry = WatchEntry(id: saved.id + "#animenosub:bleach_ep_5", anime: saved, episodeID: "animenosub:bleach_ep_5", episodeTitle: "5화", number: 5,
                               watchURL: "https://animenosub.to/bleach-tybw-episode-5/", directURL: nil, position: 60, duration: 1200, updatedAt: Date(timeIntervalSince1970: 2))
        let item = try! XCTUnwrap(DeviceSyncMapping.historyItem(entry))
        XCTAssertEqual(DeviceSyncMapping.androidEpisodeKey(item), "animenosub:bleach:animenosub:bleach_ep_5")
        var dub = item; dub["episode"] = (item["episode"] as! [String: Any]).merging(["id": "animenosub:bleach_dub_ep_5"]) { _, new in new }
        XCTAssertEqual(DeviceSyncMapping.androidEpisodeKey(dub), "animenosub:bleach:animenosub:bleach_dub_ep_5")
        func key(_ provider: String, _ id: Any, _ episode: [String: Any]) -> String {
            DeviceSyncMapping.androidEpisodeKey(["anime": ["provider": provider, "id": id], "episode": episode])
        }
        XCTAssertEqual(key("reanime", "kaguya", ["number": 2, "name": "2", "url": "x"]), "reanime:kaguya:reanime:kaguya:ep-2")
        XCTAssertEqual(key("miruro", 123, ["number": 4, "name": "4", "url": "x"]), "miruro:123:miruro:123:4")
        XCTAssertEqual(key("linkani", "19848", ["number": 3, "name": "3", "url": "https://linkani.tv/watch/19848/a1/k3/"]), "19848:19848_ep_3")
        XCTAssertEqual(key("ohli24", "슬라임", ["number": 5, "name": "5", "url": "https://ani.ohli24.com/e/" + "슬라임 5화".addingPercentEncoding(withAllowedCharacters: .alphanumerics)!]),
                       "ohli24:슬라임:ohli24:슬라임:슬라임 5화")
        XCTAssertEqual(key("linkkf", "1", ["number": 1, "name": "1", "url": "x"]), "")
    }

    private func fromPhone(_ number: Int, progress: Double, updated: Double) -> [String: Any] {
        ["collection": "history", "id": "animenosub:bleach:animenosub:bleach_ep_\(number)", "updated": updated,
         "data": ["key": "animenosub:bleach:animenosub:bleach_ep_\(number)", "src": "", "name": "Bleach · \(number)화",
                  "anime": ["provider": "animenosub", "id": "bleach", "mal_id": "animenosub:bleach", "url": "https://animenosub.to/anime/bleach/", "title": "Bleach"],
                  "episode": ["provider": "animenosub", "number": number, "name": String(number), "key": "animenosub:bleach_ep_\(number)"],
                  "progress": progress, "updated": updated, "androidAnimeId": "animenosub:bleach", "androidEpisodeKey": "animenosub:bleach_ep_\(number)", "episodeNumber": number] as [String: Any]]
    }

    @MainActor func testAnEpisodeWatchedOnThePhoneIsThisDevicesEntryForIt() throws {
        let saved = anime(6)
        let entry = WatchEntry(id: saved.id + "#animenosub:bleach_ep_5", anime: saved, episodeID: "animenosub:bleach_ep_5", episodeTitle: "5화", number: 5,
                               watchURL: "https://animenosub.to/bleach-tybw-episode-5/", directURL: nil, position: 240, duration: 1200, updatedAt: Date(timeIntervalSince1970: 2))
        let mineID = try XCTUnwrap(DeviceSyncMapping.historyID(entry))
        let history = [mineID: try XCTUnwrap(DeviceSyncMapping.historyItem(entry))]
        let folded = DeviceSyncMapping.fold([fromPhone(5, progress: 70, updated: 5000)], history: history)
        XCTAssertEqual(folded.first?["id"] as? String, mineID)
        let back = try XCTUnwrap(DeviceSyncMapping.watchEntry(try XCTUnwrap(folded.first?["data"] as? [String: Any])))
        XCTAssertEqual(back.id, entry.id)
        XCTAssertEqual(back.watchURL, entry.watchURL)
        XCTAssertEqual(back.position, 840, accuracy: 0.001, "70% of its length")
        XCTAssertFalse(back.byNumber)
        // Its removal on the phone removes it here.
        let removal = DeviceSyncMapping.fold([["collection": "history", "id": "animenosub:bleach:animenosub:bleach_ep_5", "updated": 7000.0, "deleted": true]], history: history)
        XCTAssertEqual(removal.first?["id"] as? String, mineID)
    }

    @MainActor func testAnEpisodeOnlyThePhoneWatchedComesAsItIsToPlayByItsNumber() throws {
        let saved = anime(6)
        let entry = WatchEntry(id: saved.id + "#animenosub:bleach_ep_5", anime: saved, episodeID: "animenosub:bleach_ep_5", episodeTitle: "5화", number: 5,
                               watchURL: "https://animenosub.to/bleach-tybw-episode-5/", directURL: nil, position: 240, duration: 1200, updatedAt: Date(timeIntervalSince1970: 2))
        let history = [try XCTUnwrap(DeviceSyncMapping.historyID(entry)): try XCTUnwrap(DeviceSyncMapping.historyItem(entry))]
        let incoming = fromPhone(6, progress: 30, updated: 3000)
        let folded = DeviceSyncMapping.fold([incoming], history: history)
        XCTAssertEqual(folded.first?["id"] as? String, "animenosub:bleach:animenosub:bleach_ep_6")
        let six = try XCTUnwrap(DeviceSyncMapping.watchEntry(try XCTUnwrap(folded.first?["data"] as? [String: Any])))
        XCTAssertTrue(six.byNumber); XCTAssertTrue(PlaybackItem(entry: six).byNumber)
        XCTAssertEqual(six.number, 6); XCTAssertEqual(six.progressPercent, 30); XCTAssertEqual(six.anime.id, saved.id)
        XCTAssertEqual(DeviceSyncMapping.historyID(six), "animenosub:bleach:animenosub:bleach_ep_6", "sent back under the phone's id")
        XCTAssertEqual(DeviceSyncMapping.historyItem(six)?["androidEpisodeKey"] as? String, "animenosub:bleach_ep_6")

        // Played here, the episode's own entry takes its place.
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".json")
        defer { try? FileManager.default.removeItem(at: file) }
        let library = LibraryStore(file: file)
        library.replaceFromSync(favorites: [], history: [six])
        library.progress(anime: saved, episodeID: "animenosub:bleach_ep_6", title: "6화", number: 6, watchURL: "https://animenosub.to/bleach-tybw-episode-6/",
                         directURL: nil, position: 30, duration: 1200, displayNumber: "6")
        XCTAssertEqual(library.history.map(\.episodeID), ["animenosub:bleach_ep_6"])
        XCTAssertFalse(library.history[0].byNumber)
    }
}
