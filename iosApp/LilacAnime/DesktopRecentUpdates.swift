import Foundation
import LilacShared

@MainActor
final class DesktopRecentUpdates: ObservableObject {
    static let shared = DesktopRecentUpdates()
    @Published private(set) var home: [Anime] = []
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
    // --- 내 목록 (desktop 19110b2, GitHub issue #28) ---
    // A saved work was the card of the day it was saved: its episode count stood still (a show at 7화 still said 3화), and
    // 회차 업데이트순 knew a date only for RE:Anime's and Miruro's works that were in their recent lists, so the other
    // sources' works followed in an order of their own. What each saved work has now is kept apart from the saved card
    // (libraryFacts in UserDefaults, so the list synced with the other devices is not rewritten): its counts and the day
    // of its latest episode, from the sources' recent lists and the work's own page (LibraryFacts), read again every few
    // hours for a show still airing and weekly for a finished one. 링크애니 dates no episode: its works are dated by when a
    // new episode was first seen here.
    struct Fact: Codable { var available = -1; var total = -1; var status = ""; var updatedAt = ""; var seenAt: Date?; var checkedAt: Date? }
    @Published private(set) var facts: [String: Fact] = {
        guard let data = UserDefaults.standard.data(forKey: "libraryFacts") else { return [:] }
        return (try? JSONDecoder().decode([String: Fact].self, from: data)) ?? [:]
    }()
    private var feedRank: [String: Int] = [:]
    private var refreshing = false
    private func keepFacts(_ favorites: [SavedAnime]) {
        let saved = Set(favorites.map(\.id)); facts = facts.filter { saved.contains($0.key) }
        if let data = try? JSONEncoder().encode(facts) { UserDefaults.standard.set(data, forKey: "libraryFacts") }
    }
    private static func uploaded(_ anime: Anime) -> Int { anime.availableEpisodes?.intValue ?? 0 }
    /// What a list or a page says of a saved work now. A value it does not give leaves the known one; the later day stays.
    private func learn(_ saved: SavedAnime, available: Int, total: Int, status: String, updatedAt: String, checked: Bool) {
        let before = Self.uploaded(shown(saved))
        var fact = facts[saved.id] ?? Fact()
        if available >= 0 { fact.available = available }
        if total >= 0 { fact.total = total }
        if !status.isEmpty { fact.status = status }
        let day = LibraryFacts.shared.day(value: updatedAt)
        if !day.isEmpty && day > fact.updatedAt { fact.updatedAt = day }
        if fact.checkedAt != nil && fact.available > before { fact.seenAt = Date() }
        if checked { fact.checkedAt = Date() }
        facts[saved.id] = fact
    }
    /// The card as it is now (both orders show it).
    func shown(_ saved: SavedAnime) -> Anime {
        guard let fact = facts[saved.id] else { return saved.anime }
        return LibraryFacts.shared.shown(anime: saved.anime, fact: LibraryFact(availableEpisodes: Int32(fact.available), totalEpisodes: Int32(fact.total), episodes: -1, status: fact.status, updatedAt: fact.updatedAt))
    }
    /// The saved works whose own page is due, those never looked at first, 40 a visit, 3 at a time.
    private func refreshDue(_ favorites: [SavedAnime]) async {
        guard !refreshing else { return }
        let now = Date()
        let due = favorites.filter { LibraryFacts.shared.sources.contains($0.source) }.filter { saved in
            guard let checked = facts[saved.id]?.checkedAt else { return true }
            return now.timeIntervalSince(checked) * 1000 > Double(LibraryFacts.shared.dueAfterMillis(anime: shown(saved)))
        }.sorted { (facts[$0.id]?.checkedAt ?? .distantPast) < (facts[$1.id]?.checkedAt ?? .distantPast) }.prefix(40)
        guard !due.isEmpty else { return }
        refreshing = true; defer { refreshing = false }
        let service = self.service, works = Array(due)
        for at in stride(from: 0, to: works.count, by: 3) {
            let chunk = Array(works[at..<min(at + 3, works.count)])
            // The service answers on the main thread, one callback each.
            let found: [String: LibraryFact] = await withCheckedContinuation { continuation in
                var collected: [String: LibraryFact] = [:], left = chunk.count
                for saved in chunk {
                    service.libraryFact(anime: saved.anime, sourceKey: saved.source) { value, _ in
                        if let value { collected[saved.id] = value }
                        left -= 1
                        if left == 0 { continuation.resume(returning: collected) }
                    }
                }
            }
            for saved in chunk {
                if let fact = found[saved.id] {
                    learn(saved, available: Int(fact.availableEpisodes), total: Int(fact.totalEpisodes), status: fact.status, updatedAt: fact.updatedAt, checked: true)
                } else {
                    // A page that could not be read is tried again in half an hour.
                    var kept = facts[saved.id] ?? Fact(); kept.checkedAt = Date().addingTimeInterval(-3 * 3600 + 1800); facts[saved.id] = kept
                }
            }
        }
        keepFacts(favorites)
    }
    func loadLibrary(_ favorites: [SavedAnime], order: String = "updated") async {
        libraryRequest = UUID(); let request = libraryRequest
        guard order == "updated" else { await refreshDue(favorites); return }
        libraryNote = "저장한 작품의 최근 회차 목록을 확인하는 중…"
        let sources = Array(Set(favorites.map(\.source))).sorted()
        let pending = sources.filter(Self.supported.contains).map { ($0, recent($0, pages: 8)) }
        var failures: [String] = []
        let saved = Dictionary(favorites.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var ranks: [String: Int] = [:]
        for (source, task) in pending {
            do {
                for (rank, anime) in try await task.value.enumerated() {
                    let id = source + ":" + anime.id
                    guard let work = saved[id] else { continue }
                    ranks[id] = rank
                    let before = Self.uploaded(shown(work))
                    // A list that dates the work settles it; one that does not (Animenosub, 애니24) leaves its page to be read
                    // when the work has no day yet or shows more episodes than before. 링크애니 has no dates anywhere.
                    let settled = !LibraryFacts.shared.day(value: anime.updatedAt).isEmpty || !LibraryFacts.shared.dated.contains(source)
                    learn(work, available: Self.uploaded(anime) > 0 ? Self.uploaded(anime) : -1, total: anime.totalEpisodes?.intValue ?? -1,
                          status: anime.status, updatedAt: anime.updatedAt, checked: settled)
                    if !settled, facts[id]?.updatedAt.isEmpty != false || Self.uploaded(shown(work)) > before { facts[id]?.checkedAt = nil }
                }
            } catch { failures.append(ContentSources.name(source)); cache[source + ":8"] = nil }
        }
        guard request == libraryRequest else { return }
        feedRank = ranks; keepFacts(favorites)
        let unsupported = sources.filter { !Self.supported.contains($0) }.map(ContentSources.name)
        let note = "최신 회차가 나온 날짜순입니다. 날짜를 알 수 없는 작품은 뒤에 둡니다." + (unsupported.isEmpty ? "" : " \(unsupported.joined(separator: "·")): 업데이트순 미지원.") + (failures.isEmpty ? "" : " \(failures.joined(separator: "·")): 목록을 불러오지 못했습니다.")
        libraryNote = note + " 저장한 작품의 최신 회차를 확인하는 중…"
        await refreshDue(favorites)
        if request == libraryRequest { libraryNote = note }
    }
    /// The latest episode's day, newest first, whatever the source; works without a day follow, those in their source's
    /// recent list first (in its order), then the rest as saved.
    func ordered(_ favorites: [SavedAnime]) -> [SavedAnime] {
        func time(_ saved: SavedAnime) -> String {
            let fact = facts[saved.id], day = fact?.updatedAt ?? ""
            if !day.isEmpty { return day }
            return fact?.seenAt.map { String(Self.plainDate.string(from: $0).prefix(10)) } ?? ""
        }
        return favorites.enumerated().sorted { lhs, rhs in
            let a = time(lhs.element), b = time(rhs.element)
            if a.isEmpty != b.isEmpty { return !a.isEmpty }
            if !a.isEmpty { return a == b ? lhs.offset < rhs.offset : a > b }
            let ra = feedRank[lhs.element.id], rb = feedRank[rhs.element.id]
            if (ra != nil) != (rb != nil) { return ra != nil }
            guard let ra, let rb else { return lhs.offset < rhs.offset }
            if lhs.element.source != rhs.element.source { return lhs.element.source < rhs.element.source }
            return ra == rb ? lhs.offset < rhs.offset : ra < rb
        }.map(\.element)
    }
    private static let plainDate = ISO8601DateFormatter()
}
