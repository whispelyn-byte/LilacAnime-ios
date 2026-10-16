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
    /// When it went into 내 목록 (기기 동기화 orders and compares by it); nil for items saved before.
    var savedAt: Date? = nil
    private var decoded = DecodedAnime()
    private enum CodingKeys: String, CodingKey { case id, title, poster, source, snapshot, savedAt }
    init(_ anime: Anime, source: String) {
        id = source + ":" + anime.id; title = anime.title; poster = anime.poster
        self.source = source; snapshot = AnimeSnapshot.shared.encode(anime: anime)
        decoded.store(anime, for: snapshot)
    }
    /// Decoded once per snapshot: views read this on every redraw (the player many times a second), and decoding a
    /// long series' snapshot with all its episodes each time kept the main thread busy.
    var anime: Anime { decoded.anime(for: snapshot) }
}
private final class DecodedAnime: @unchecked Sendable {
    private let lock = NSLock()
    private var snapshot = ""
    private var value: Anime?
    func anime(for snapshot: String) -> Anime {
        lock.lock(); defer { lock.unlock() }
        if let value, self.snapshot == snapshot { return value }
        let anime = AnimeSnapshot.shared.decode(content: snapshot)
        self.snapshot = snapshot; value = anime
        return anime
    }
    func store(_ anime: Anime, for snapshot: String) { lock.lock(); self.snapshot = snapshot; value = anime; lock.unlock() }
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
    /// How far it was watched (0–100) when only that is known: an entry from the desktop app through 기기 동기화.
    var progressPercent: Double? = nil
}
struct SubtitleChoice: Codable { var relativeFile: String?; var offset: Double }
struct AppPreferences: Codable, Equatable {
    var playerFit: String? = "contain"
    var playerAspect: String? = nil
    var anime4k: String? = "off"
    var theme = "dark"
    var titleLanguage: String? = "ko"
    var desktopWorkspace: Bool? = false
    var preferredServer: String? = nil
    var preferredServers: [String: String]? = nil
    var preferredStream: String? = nil
    var modelSampling: Bool? = true
    var localGPU: Bool? = true
    var pretranslateNext: Bool? = true
    var prepareNextCloud: Bool? = false
    var vttStyle: Bool? = true
    var downloadSubtitles: Bool? = true
    var translateDownloads: Bool? = true
    var source = "reanime"
    var quality = "1080p"
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
    var autoSkip = false
    var showSkipButton: Bool? = true
    var offlineAnalysis = true
    var backgroundAudio = true
    var subtitleProvider: String? = "auto"
    var translationFallback: Bool? = true
    var translationGlossary: String? = ""
    var autoTranslation = true
    var translationProvider = "gemini"
    var translationModel = ""
    var translationModels: [String: String]? = nil
    var cloudFallback: Bool? = true
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
    @Published private(set) var aiSubtitleSeries: [String: Bool] = [:]
    @Published var persistenceError: String?
    private let file: URL
    private var ready = false
    private struct State: Codable { var preferences: AppPreferences; var favorites: [SavedAnime]; var history: [WatchEntry]; var subtitles: [String: SubtitleChoice]?; var aiSubtitleSeries: [String: Bool]? }
    private var loading: Task<Void, Never>?
    init(file: URL? = nil, deferLoading: Bool = false) {
        self.file = file ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("library.json")
        if deferLoading { return }
        restore(Self.read(self.file))
    }
    private nonisolated static func read(_ file: URL) -> Result<State?, Error> {
        if FileManager.default.fileExists(atPath: file.path) {
            do {
                let state = try JSONDecoder().decode(State.self, from: Data(contentsOf: file))
                for item in state.favorites { _ = item.anime }
                for item in state.history { _ = item.anime.anime }
                return .success(state)
            } catch { return .failure(error) }
        }
        return .success(nil)
    }
    private func restore(_ result: Result<State?, Error>) {
        switch result {
        case .success(let state):
            if let state {
                preferences = state.preferences; favorites = state.favorites; history = state.history; subtitleChoices = state.subtitles ?? [:]
                aiSubtitleSeries = state.aiSubtitleSeries ?? [:]
                // Existing custom prompts and sampling values remain active after upgrading.
                if preferences.modelSampling == nil { preferences.modelSampling = false }
            }
        case .failure(let error): persistenceError = "저장된 보관함을 읽지 못했습니다: " + error.localizedDescription
        }
        ready = true
    }
    func load() async {
        if ready { return }
        if let loading { await loading.value; return }
        let file = file
        let task = Task { self.restore(await Task.detached(priority: .userInitiated) { Self.read(file) }.value) }
        loading = task
        await task.value
    }
    func subtitleChoice(animeID: String, episodeID: String) -> SubtitleChoice? { subtitleChoices[animeID + "#" + episodeID] }
    func preferAI(_ value: Bool, animeID: String) { aiSubtitleSeries[animeID] = value ? true : nil; persist() }
    func saveSubtitle(animeID: String, episodeID: String, file: URL?, offset: Double) {
        let prefix = SubtitleFiles.root.path + "/"
        let relative = file.flatMap { $0.path.hasPrefix(prefix) ? String($0.path.dropFirst(prefix.count)) : nil }
        subtitleChoices[animeID + "#" + episodeID] = SubtitleChoice(relativeFile: relative, offset: offset)
        persist()
    }
    func clearSubtitleFiles() { for key in subtitleChoices.keys { subtitleChoices[key]?.relativeFile = nil }; persist() }
    func clearSubtitleFiles(matching saved: [String: SubtitleChoice]) {
        for (key, choice) in saved where subtitleChoices[key]?.relativeFile == choice.relativeFile { subtitleChoices[key]?.relativeFile = nil }
        persist()
    }
    func contains(_ anime: Anime, source: String) -> Bool { favorites.contains { $0.id == source + ":" + anime.id } }
    func toggle(_ anime: Anime, source: String) {
        var saved = SavedAnime(anime, source: source)
        if favorites.contains(where: { $0.id == saved.id }) { favorites.removeAll { $0.id == saved.id } }
        else { saved.savedAt = Date(); favorites.append(saved) }
        persist()
    }
    func progress(anime: SavedAnime, episodeID: String, title: String, number: Int, watchURL: String,
                  directURL: String?, position: Double, duration: Double) {
        guard position.isFinite, duration.isFinite, position >= 0 else { return }
        let id = anime.id + "#" + episodeID
        history.removeAll { $0.id == id }
        // Only the newest entry of a series keeps its episode list: the same long list in every watched episode's entry
        // made library.json grow without end. An older entry asks for the list again when it is played.
        for index in history.indices where history[index].anime.id == anime.id && !history[index].anime.snapshot.contains("\"episodes\":[]") {
            let old = history[index].anime
            var trimmed = SavedAnime(AnimeSnapshot.shared.withEpisodes(anime: old.anime, episodes: []), source: old.source)
            trimmed.savedAt = old.savedAt
            history[index].anime = trimmed
        }
        history.insert(WatchEntry(id: id, anime: anime, episodeID: episodeID, episodeTitle: title, number: number,
                                  watchURL: watchURL, directURL: directURL, position: position, duration: duration, updatedAt: Date()), at: 0)
        history = Array(history.prefix(500)); persist()
    }
    func resume(_ anime: SavedAnime, episodeID: String) -> Double {
        history.first { $0.anime.id == anime.id && $0.episodeID == episodeID }.map { $0.duration > 0 && $0.position / $0.duration > 0.95 ? 0 : $0.position } ?? 0
    }
    /// An entry from another device that knows only the percentage watched.
    func resumeFraction(_ anime: SavedAnime, episodeID: String) -> Double? {
        history.first { $0.anime.id == anime.id && $0.episodeID == episodeID }.flatMap { $0.duration <= 0 && $0.position <= 0 ? $0.progressPercent.map { $0 / 100 } : nil }
    }
    /// 기기 동기화 writes both lists at once.
    func replaceFromSync(favorites: [SavedAnime], history: [WatchEntry]) {
        self.favorites = favorites; self.history = Array(history.prefix(500)); persist()
    }
    func deleteHistory(_ offsets: IndexSet) { history.remove(atOffsets: offsets); persist() }
    func clearHistory() { history.removeAll(); persist() }
    func deleteHistory(ids: Set<String>) { history.removeAll { ids.contains($0.id) }; persist() }
    /// Written off the main thread: the history carries each series' snapshot and is saved every few seconds while playing.
    private func persist() {
        guard ready else { return }
        let state = State(preferences: preferences, favorites: favorites, history: history, subtitles: subtitleChoices, aiSubtitleSeries: aiSubtitleSeries)
        BackgroundWriter.shared.write(file, after: 0.5, failed: { [weak self] message in self?.persistenceError = "보관함 저장 실패: " + message }) { try JSONEncoder().encode(state) }
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

