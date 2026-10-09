import Foundation
import LilacShared

@MainActor
final class DesktopRecentUpdates: ObservableObject {
    struct Update { var anime: Anime; var rank: Int }
    static let shared = DesktopRecentUpdates()
    @Published private(set) var home: [Anime] = []
    @Published private(set) var library: [String: Update] = [:]
    @Published private(set) var homeNote = ""
    @Published private(set) var libraryNote = ""
    private let service = IosServices()
    private var cache: [String: (Date, Task<[Anime], Error>)] = [:]
    private var homeRequest = UUID()
    private var libraryRequest = UUID()
    static let supported = ["reanime", "miruro", "animenosub", "ohli24", "linkani"]
    func recent(_ source: String, pages: Int) -> Task<[Anime], Error> {
        let key = source + ":" + String(pages)
        if let previous = cache[key], Date().timeIntervalSince(previous.0) < 300 { return previous.1 }
        let task = Task { () throws -> [Anime] in
            var items: [Anime] = []; var seen: Set<String> = []
            for page in 1...pages {
                let result: [Anime] = try await withCheckedThrowingContinuation { continuation in
                    service.sourceUpdates(sourceKey: source, page: Int32(page)) { values, error in
                        if let error { continuation.resume(throwing: SubtitleFiles.failure(error)) }
                        else { continuation.resume(returning: values ?? []) }
                    }
                }
                let added = result.filter { seen.insert($0.id).inserted }
                if added.isEmpty { break }
                items += added
            }
            return items
        }
        cache[key] = (Date(), task)
        return task
    }
    func loadHome(_ source: String) async {
        homeRequest = UUID(); let request = homeRequest; home = []; homeNote = "새 회차 목록을 불러오는 중…"
        do {
            let items = try await recent(source, pages: 1).value
            guard request == homeRequest else { return }
            home = Array(items.prefix(30)); homeNote = source == "miruro" ? "최근 2주간 방영된 회차 기준입니다." : items.isEmpty ? "최근 업데이트된 회차가 없습니다." : "소스의 최근 회차 업데이트 목록입니다."
        } catch { if request == homeRequest { homeNote = error.localizedDescription; cache[source + ":1"] = nil } }
    }
    func loadLibrary(_ favorites: [SavedAnime]) async {
        libraryRequest = UUID(); let request = libraryRequest
        libraryNote = "저장한 작품의 최근 회차 목록을 확인하는 중…"
        let sources = Array(Set(favorites.map(\.source))).sorted()
        let pending = sources.filter(Self.supported.contains).map { ($0, recent($0, pages: 8)) }
        var found: [String: Update] = [:]; var failures: [String] = []
        for (source, task) in pending {
            do { for (rank, anime) in try await task.value.enumerated() { found[source + ":" + anime.id] = Update(anime: anime, rank: rank) } }
            catch { failures.append(ContentSources.name(source)); cache[source + ":8"] = nil }
        }
        guard request == libraryRequest else { return }
        library = found
        let unsupported = sources.filter { !Self.supported.contains($0) }.map(ContentSources.name)
        libraryNote = "소스별 최근 회차 목록에 있는 작품을 먼저 표시합니다. 목록 밖 작품은 저장순을 유지합니다." + (unsupported.isEmpty ? "" : " \(unsupported.joined(separator: "·")): 업데이트순 미지원.") + (failures.isEmpty ? "" : " \(failures.joined(separator: "·")): 목록을 불러오지 못했습니다.")
    }
    func ordered(_ favorites: [SavedAnime]) -> [SavedAnime] {
        favorites.enumerated().sorted { lhs, rhs in
            let a = library[lhs.element.id], b = library[rhs.element.id]
            if (a != nil) != (b != nil) { return a != nil }
            guard let a, let b else { return lhs.offset < rhs.offset }
            let ad = Self.timestamp(a.anime.updatedAt), bd = Self.timestamp(b.anime.updatedAt)
            if (ad != nil) != (bd != nil) { return ad != nil }
            if let ad, let bd { return ad == bd ? lhs.offset < rhs.offset : ad > bd }
            if lhs.element.source != rhs.element.source { return lhs.element.source < rhs.element.source }
            return a.rank == b.rank ? lhs.offset < rhs.offset : a.rank < b.rank
        }.map { entry in library[entry.element.id].map { SavedAnime($0.anime, source: entry.element.source) } ?? entry.element }
    }
    private static func timestamp(_ text: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        if let date = formatter.date(from: text) { return date }
        formatter.formatOptions.insert(.withFractionalSeconds); return formatter.date(from: text)
    }
}
