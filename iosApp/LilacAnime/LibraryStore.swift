import Foundation
import SwiftUI
import Security
import LilacShared

struct SavedAnime: Codable, Identifiable {
    let id: String
    var title: String
    var poster: String
    var source: String
    var snapshot: String
    init(_ anime: Anime, source: String) {
        id = source + ":" + anime.id; title = anime.title; poster = anime.poster
        self.source = source; snapshot = AnimeSnapshot.shared.encode(anime: anime)
    }
    var anime: Anime { AnimeSnapshot.shared.decode(content: snapshot) }
}
struct WatchEntry: Codable, Identifiable {
    var id: String
    var anime: SavedAnime
    var episodeID: String
    var episodeTitle: String
    var number: Int
    var watchURL: String
    var directURL: String?
    var position: Double
    var duration: Double
    var updatedAt: Date
}
struct SubtitleChoice: Codable { var relativeFile: String?; var offset: Double }
struct AppPreferences: Codable {
    var theme = "system"
    var source = "linkkf"
    var quality = "Auto"
    var speed = 1.0
    var seekSeconds = 10.0
    var subtitleSize = 100.0
    var subtitleFont = ""
    var subtitleColor = "#FFFFFF"
    var outlineColor = "#000000"
    var outlineWidth = 2.0
    var subtitleBold = true
    var subtitlePadding = 12.0
    var subtitleOffset = 0.0
    var assEffects = true
    var autoPlay = true
    var autoSkip = true
    var offlineAnalysis = true
    var backgroundAudio = true
    var autoTranslation = false
    var translationProvider = "local"
    var translationModel = ""
    var qwenRegion = "international"
    var selectedGGUF = ""
    var contextSize = 4096
    var threads = 0
    var maxTokens = 512
    var temperature = 0.25
    var topP = 0.85
    var topK = 40
    var repetitionPenalty = 1.05
    var contextCues = 6
    var prefetchAhead = 10
    var thinking = "off"
    var prompt = "이전 맥락\n{context}\n이후 맥락\n{future_context}\n\n{source_text}"
}

@MainActor
final class LibraryStore: ObservableObject {
    @Published var preferences = AppPreferences() { didSet { persist() } }
    @Published private(set) var favorites: [SavedAnime] = []
    @Published private(set) var history: [WatchEntry] = []
    @Published private(set) var subtitleChoices: [String: SubtitleChoice] = [:]
    @Published var persistenceError: String?
    private let file: URL
    private var ready = false
    private struct State: Codable { var preferences: AppPreferences; var favorites: [SavedAnime]; var history: [WatchEntry]; var subtitles: [String: SubtitleChoice]? }
    init() {
        file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("library.json")
        if FileManager.default.fileExists(atPath: file.path) {
            do {
                let state = try JSONDecoder().decode(State.self, from: Data(contentsOf: file))
                preferences = state.preferences; favorites = state.favorites; history = state.history; subtitleChoices = state.subtitles ?? [:]
            } catch { persistenceError = "저장된 보관함을 읽지 못했습니다: " + error.localizedDescription }
        }
        ready = true
    }
    func subtitleChoice(animeID: String, episodeID: String) -> SubtitleChoice? { subtitleChoices[animeID + "#" + episodeID] }
    func saveSubtitle(animeID: String, episodeID: String, file: URL?, offset: Double) {
        let prefix = SubtitleFiles.root.path + "/"
        let relative = file.flatMap { $0.path.hasPrefix(prefix) ? String($0.path.dropFirst(prefix.count)) : nil }
        subtitleChoices[animeID + "#" + episodeID] = SubtitleChoice(relativeFile: relative, offset: offset)
        persist()
    }
    func contains(_ anime: Anime, source: String) -> Bool { favorites.contains { $0.id == source + ":" + anime.id } }
    func toggle(_ anime: Anime, source: String) {
        let saved = SavedAnime(anime, source: source)
        if favorites.contains(where: { $0.id == saved.id }) { favorites.removeAll { $0.id == saved.id } }
        else { favorites.append(saved) }
        persist()
    }
    func progress(anime: SavedAnime, episodeID: String, title: String, number: Int, watchURL: String,
                  directURL: String?, position: Double, duration: Double) {
        guard position.isFinite, duration.isFinite, position >= 0 else { return }
        let id = anime.id + "#" + episodeID
        history.removeAll { $0.id == id }
        history.insert(WatchEntry(id: id, anime: anime, episodeID: episodeID, episodeTitle: title, number: number,
                                  watchURL: watchURL, directURL: directURL, position: position, duration: duration, updatedAt: Date()), at: 0)
        history = Array(history.prefix(500)); persist()
    }
    func resume(_ anime: SavedAnime, episodeID: String) -> Double {
        history.first { $0.anime.id == anime.id && $0.episodeID == episodeID }.map { $0.duration > 0 && $0.position / $0.duration > 0.95 ? 0 : $0.position } ?? 0
    }
    func deleteHistory(_ offsets: IndexSet) { history.remove(atOffsets: offsets); persist() }
    func clearHistory() { history.removeAll(); persist() }
    private func persist() {
        guard ready else { return }
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(State(preferences: preferences, favorites: favorites, history: history, subtitles: subtitleChoices)).write(to: file, options: .atomic)
        } catch { persistenceError = "보관함 저장 실패: " + error.localizedDescription }
    }
}

enum SecureKeys {
    private static let service = "com.lilac.anime.ios.keys"
    static func load(_ name: String) -> String {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: name, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }
    static func save(_ value: String, name: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: name]
        if value.isEmpty {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw error(status) }
            return
        }
        let data = Data(value.utf8)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query
            insert[kSecValueData as String] = data
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let result = SecItemAdd(insert as CFDictionary, nil)
            guard result == errSecSuccess else { throw error(result) }
        } else if status != errSecSuccess { throw error(status) }
    }
    private static func error(_ status: OSStatus) -> NSError {
        NSError(domain: NSOSStatusErrorDomain, code: Int(status), userInfo: [NSLocalizedDescriptionKey: "보안 키 저장 실패 (\(status))"])
    }
}

struct LibraryView: View {
    @EnvironmentObject private var store: LibraryStore
    var body: some View {
        NavigationStack {
            List {
                Section("즐겨찾기") {
                    ForEach(store.favorites) { item in
                        NavigationLink(item.title) { DetailView(summary: item.anime, source: item.source) }
                    }
                }
                Section("이어 보기") {
                    ForEach(store.history) { entry in
                        NavigationLink {
                            EpisodePlayerView(item: PlaybackItem(entry: entry))
                        } label: {
                            VStack(alignment: .leading) {
                                Text(entry.anime.title); Text(entry.episodeTitle).foregroundStyle(.secondary)
                                if entry.duration > 0 { ProgressView(value: min(entry.position / entry.duration, 1)) }
                            }
                        }
                    }.onDelete(perform: store.deleteHistory)
                }
                if store.favorites.isEmpty && store.history.isEmpty { Text("즐겨찾기와 시청 기록이 여기에 표시됩니다.") }
            }.navigationTitle("보관함")
        }
    }
}
