import Foundation
import SwiftUI
import LilacShared

struct CastCharacter: Codable {
    var name: String; var native: String; var first: String; var last: String; var gender: String
    var shared: AnimeCharacter { AnimeCharacter(name: name, native: native, first: first, last: last, gender: gender) }
}
struct CatalogName: Codable {
    var korean: String; var english: String; var overview: String; var aliases: [String]; var cast: [CastCharacter]
    var anilist: Int; var mal: Int; var updated: Date; var credential: String
    var titleLookupRevision: Int? = nil
    var castPrepared: Bool? = nil
    func isFresh(credential: String, cast: Bool, bulk: Bool = false, now: Date = Date()) -> Bool {
        self.credential == credential && (!korean.isEmpty || titleLookupRevision == 1) &&
            now.timeIntervalSince(updated) < (bulk ? 30 : 7) * 86400 && (!cast || castPrepared == true || !self.cast.isEmpty)
    }
}
@MainActor
final class DesktopCatalog: ObservableObject {
    static let shared = DesktopCatalog()
    @Published private(set) var names: [String: CatalogName] = [:]
    @Published private(set) var catalogs: [String: [SavedAnime]] = [:]
    @Published private(set) var status = "대기"
    @Published private(set) var source = ""
    @Published private(set) var running = false
    @Published var error: String?
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private var lookups: [String: Task<Bool, Never>] = [:]
    private var titleRetry: Task<Void, Never>?
    private var nextLookup = Date.distantPast
    private var titleFailures: [String: (code: String, retry: Double)] = [:]
    private let service = IosServices()
    private let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("DesktopCatalog")
    init() {
        if let data = try? Data(contentsOf: directory.appendingPathComponent("names.json")), let saved = try? JSONDecoder().decode([String: CatalogName].self, from: data) { names = saved }
        for key in ContentSources.keys {
            if let data = try? Data(contentsOf: directory.appendingPathComponent(key + ".json")), let saved = try? JSONDecoder().decode([SavedAnime].self, from: data) { catalogs[key] = saved }
        }
    }
    func title(_ anime: Anime, source: String, language: String?) -> String {
        let cached = names[source + ":" + anime.id]
        if language == "ko", let title = cached?.korean, !title.isEmpty { return title }
        if language == "en" { return cached?.english.isEmpty == false ? cached!.english : (anime.english.isEmpty ? anime.title : anime.english) }
        return anime.title
    }
    func record(_ anime: SavedAnime) -> CatalogName? { names[anime.id] }
    @discardableResult
    func enrich(_ anime: Anime, source: String, cast: Bool = false, force: Bool = false, bulk: Bool = false) async -> Bool {
        let key = source + ":" + anime.id
        let credential = SubtitleFiles.key(SecureKeys.load("tmdb"))
        if !force, let name = names[key], name.isFresh(credential: credential, cast: cast, bulk: bulk) { return true }
        if let pending = lookups[key] {
            let success = await pending.value
            if !success || !cast || names[key]?.castPrepared == true || !(names[key]?.cast.isEmpty ?? true) { return success }
        }
        let delay = bulk ? 0 : max(0, nextLookup.timeIntervalSinceNow)
        if !bulk { nextLookup = Date().addingTimeInterval(delay + 1) }
        let pending = Task { [weak self] () -> Bool in
            guard let self else { return false }
            defer { lookups[key] = nil }
            if delay > 0 { try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
            guard !Task.isCancelled else { return false }
            let result: DesktopMetadata? = await withCheckedContinuation { continuation in
                service.desktopMetadata(anime: anime, credential: SecureKeys.load("tmdb"), includeCast: cast) { value, _ in continuation.resume(returning: value) }
            }
            guard let result, !Task.isCancelled else { return false }
            if !result.titleLookupFailure.isEmpty && result.korean.isEmpty {
                titleFailures[key] = (result.titleFailureCode, Double(result.titleRetryAfterMs) / 1000)
                error = result.titleLookupFailure; return false
            }
            titleFailures[key] = nil
            let old = names[key]
            names[key] = CatalogName(korean: result.korean.isEmpty ? (old?.korean ?? "") : result.korean, english: result.english, overview: result.overview.isEmpty ? (old?.overview ?? "") : result.overview, aliases: result.aliases,
                cast: result.characters.isEmpty ? (old?.cast ?? []) : result.characters.map { CastCharacter(name: $0.name, native: $0.native, first: $0.first, last: $0.last, gender: $0.gender) },
                anilist: Int(result.anilistId), mal: Int(result.malId), updated: Date(), credential: credential,
                titleLookupRevision: 1, castPrepared: cast || old?.castPrepared == true)
            saveNames()
            return true
        }
        lookups[key] = pending; return await pending.value
    }
    func search(_ query: String, source: String) -> [Anime] {
        let wanted = DesktopTitleRules.shared.key(title: query)
        guard !wanted.isEmpty else { return [] }
        return (catalogs[source] ?? []).filter { saved in
            let name = names[saved.id]
            return ([saved.title, saved.anime.native, saved.anime.english, saved.anime.romaji, name?.korean ?? "", name?.english ?? ""] + (name?.aliases ?? []))
                .contains { DesktopTitleRules.shared.key(title: $0).contains(wanted) }
        }.map(\.anime)
    }
    var known: Int { (catalogs[source] ?? []).filter { names[$0.id]?.korean.isEmpty == false || TitleCandidates.shared.isKorean(title: $0.title) }.count }
    func needsRefresh(_ source: String) -> Bool { UserDefaults.standard.integer(forKey: "catalog-metadata-revision:" + source) < 2 }
    func start(_ source: String, refresh: Bool = false, titlesOnly: Bool = false) {
        stop(); self.source = source; running = true; error = nil
        let token = generation
        task = Task {
            defer { if token == generation { running = false } }
            do {
                var gathered = refresh ? [] : catalogs[source] ?? []
                var page: Int32 = 1
                var seenPages: Set<String> = []
                while !titlesOnly, !Task.isCancelled, token == generation {
                    status = "전체 목록 수집 · \(gathered.count)개"
                    let result: [Anime] = try await withCheckedThrowingContinuation { continuation in
                        service.browse(sourceKey: source, query: "", page: page, filter: AnimeSnapshot.shared.filter(genre: "", year: "", format: "", status: "")) { value, failure in
                            if let value { continuation.resume(returning: value) } else { continuation.resume(throwing: SubtitleFiles.failure(failure ?? "목록 요청 실패")) }
                        }
                    }
                    try Task.checkCancellation()
                    guard token == generation else { return }
                    guard !result.isEmpty, seenPages.insert(result.map(\.id).joined(separator: "|")).inserted else { break }
                    gathered = CatalogIndexMerge.merge(gathered, incoming: result.map { SavedAnime($0, source: source) }); catalogs[source] = gathered
                    try persist(gathered, name: source + ".json")
                    page += 1
                    try await Task.sleep(nanoseconds: 400_000_000)
                }
                try Task.checkCancellation()
                guard token == generation else { return }
                if !seenPages.isEmpty { UserDefaults.standard.set(2, forKey: "catalog-metadata-revision:" + source) }
                status = "Wikidata 한국어 제목 일괄 적용"
                let bulk: String? = await withCheckedContinuation { continuation in
                    service.catalogKoreanIndex { value, _ in continuation.resume(returning: value) }
                }
                if let data = bulk?.data(using: .utf8), let titles = try? JSONDecoder().decode([String: String].self, from: data) {
                    let credential = SubtitleFiles.key(SecureKeys.load("tmdb"))
                    for item in gathered where names[item.id]?.korean.isEmpty != false {
                        guard let id = item.anime.anilistId, let korean = titles[String(id.intValue)] else { continue }
                        names[item.id] = CatalogName(korean: DesktopTitleRules.shared.seasonal(title: korean, original: item.title, format: item.anime.format), english: item.anime.english,
                            overview: "", aliases: [korean], cast: [], anilist: id.intValue, mal: item.anime.malId?.intValue ?? 0, updated: Date(), credential: credential)
                    }
                    saveNames()
                }
                guard !SecureKeys.load("tmdb").isEmpty else {
                    status = "\(knownCount(gathered))/\(gathered.count)개 한국어 제목 · 나머지는 TMDB 키를 설정하면 찾습니다."
                    return
                }
                let pending = gathered.filter { !TitleCandidates.shared.isKorean(title: $0.title) && names[$0.id]?.korean.isEmpty != false }
                for start in stride(from: 0, to: pending.count, by: 6) {
                    try Task.checkCancellation()
                    guard token == generation else { return }
                    status = "한국어 제목 · \(knownCount(gathered))/\(gathered.count)"
                    let batch = Array(pending[start..<min(start + 6, pending.count)])
                    let success = await withTaskGroup(of: Bool.self, returning: Bool.self) { group in
                        for item in batch { group.addTask { @MainActor in await self.enrich(item.anime, source: source, bulk: true) } }
                        var complete = true
                        for await result in group { if !result { complete = false } }
                        return complete
                    }
                    if !success {
                        guard token == generation, !Task.isCancelled else { return }
                        let failures = batch.compactMap { titleFailures[$0.id] }
                        let delay = CatalogTitleRetry.delay(auth: failures.contains { $0.code == "auth" }, retryAfter: failures.map(\.retry).max() ?? 0)
                        status = "\(knownCount(gathered))/\(gathered.count)개 한국어 제목 · \(error ?? "제목 조회 실패"), \(Int(ceil(delay / 60)))분 뒤 재시도"
                        titleRetry = Task { [weak self] in
                            do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) } catch { return }
                            guard let self, generation == token, !SecureKeys.load("tmdb").isEmpty else { return }
                            self.start(source, titlesOnly: true)
                        }
                        return
                    }
                    try await Task.sleep(nanoseconds: 200_000_000)
                }
                if token == generation { status = "완료 · \(knownCount(gathered))/\(gathered.count)개 한국어 제목" }
            } catch is CancellationError { }
            catch { if token == generation { self.error = error.localizedDescription; status = "중단 · 다시 시작하면 저장된 목록에서 이어집니다." } }
        }
    }
    private func knownCount(_ values: [SavedAnime]) -> Int { values.filter { names[$0.id]?.korean.isEmpty == false || TitleCandidates.shared.isKorean(title: $0.title) }.count }
    func stop() { generation = UUID(); task?.cancel(); task = nil; titleRetry?.cancel(); titleRetry = nil; running = false; status = "일시 중지" }
    func clear() { stop(); lookups.values.forEach { $0.cancel() }; lookups = [:]; nextLookup = .distantPast; names = [:]; catalogs = [:]; try? FileManager.default.removeItem(at: directory) }
    private func persist<T: Encodable>(_ value: T, name: String) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try JSONEncoder().encode(value).write(to: directory.appendingPathComponent(name), options: .atomic)
    }
    private func saveNames() { do { try persist(names, name: "names.json") } catch { self.error = error.localizedDescription } }
}
enum CatalogTitleRetry {
    static func delay(auth: Bool, retryAfter: Double) -> Double { max(auth ? 1800 : 60, retryAfter) }
}
enum CatalogIndexMerge {
    static func merge(_ existing: [SavedAnime], incoming: [SavedAnime]) -> [SavedAnime] {
        var values = existing
        var indices = Dictionary(existing.enumerated().map { ($0.element.id, $0.offset) }, uniquingKeysWith: { first, _ in first })
        for item in incoming {
            if let index = indices[item.id] { values[index] = item }
            else { indices[item.id] = values.count; values.append(item) }
        }
        return values
    }
}
struct AnimeDisplayTitle: View {
    let anime: Anime; var source: String? = nil
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var catalog = DesktopCatalog.shared
    var body: some View {
        Text(catalog.title(anime, source: source ?? (anime.source.isEmpty ? library.preferences.source : anime.source), language: library.preferences.titleLanguage))
            .task(id: anime.id) {
                if library.preferences.titleLanguage != nil && library.preferences.titleLanguage != "original" && !UIShowcase.enabled {
                    await catalog.enrich(anime, source: source ?? (anime.source.isEmpty ? library.preferences.source : anime.source))
                }
            }
    }
}
struct CatalogIndexView: View {
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var catalog = DesktopCatalog.shared
    var body: some View {
        List {
            Section("한국어 전체 카탈로그") {
                Picker("소스", selection: $library.preferences.source) { ForEach(ContentSources.keys, id: \.self) { Text(ContentSources.name($0)).tag($0) } }
                Text(catalog.status)
                if let error = catalog.error { Text(error).foregroundStyle(.red) }
                Button(catalog.running ? "일시 중지" : "목록·한국어 제목 이어서 수집") { if catalog.running { catalog.stop() } else { catalog.start(library.preferences.source) } }
                Button("전체 목록 새로 수집") { catalog.start(library.preferences.source, refresh: true) }
                Button("제목 인덱스 지우기", role: .destructive) { catalog.clear() }
                Text("TMDB → AniList → Wikidata 순서로 찾습니다. 수집 중에도 검색할 수 있습니다. 앱을 다시 열면 저장된 목록을 사용합니다.").font(.footnote)
            }
        }.navigationTitle("한국어 제목 인덱스")
    }
}
