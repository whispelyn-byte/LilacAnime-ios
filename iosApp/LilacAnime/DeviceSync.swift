import Foundation
import SwiftUI
import Combine
import LilacShared

/// 기기 동기화 (desktop electron/device-sync.cjs + src/device-sync.js): 내 목록 and 시청 기록 kept in step with the desktop
/// app through the owner's own LilacAnime server (lilacanimeserver on Vercel). Items travel in the desktop's shape so
/// both apps read each other's; an item made here also carries its iOS form ("ios") so it comes back whole.
enum DeviceSyncAddress {
    /// "my-server.vercel.app", "https://my-server.vercel.app/" -> https://my-server.vercel.app (http only for this device).
    static func normalize(_ value: String) throws -> String {
        var text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw SubtitleFiles.failure("서버 주소를 입력하세요.") }
        if text.range(of: "^https?://", options: [.regularExpression, .caseInsensitive]) == nil {
            text = (text.range(of: "^(?:localhost|127\\.0\\.0\\.1)(?::|$)", options: [.regularExpression, .caseInsensitive]) != nil ? "http://" : "https://") + text
        }
        guard let url = URL(string: text), let scheme = url.scheme?.lowercased(), let host = url.host, !host.isEmpty else { throw SubtitleFiles.failure("서버 주소가 올바르지 않습니다.") }
        if scheme == "http" && !["localhost", "127.0.0.1"].contains(host.lowercased()) { throw SubtitleFiles.failure("서버 주소는 https://로 시작해야 합니다.") }
        return scheme + "://" + host + (url.port.map { ":\($0)" } ?? "")
    }
    /// What went wrong, said so the owner can act on it (device-sync.cjs serverError).
    static func serverError(_ data: [String: Any]?, status: Int) -> String {
        if let message = data?["error"] as? String { return message }
        let nested = data?["error"] as? [String: Any]
        let text = String(describing: data?["message"] ?? "") + " " + String(describing: nested?["message"] ?? "")
        if data?["protection"] != nil || text.range(of: "Protected (?:by Vercel|deployment)", options: [.regularExpression, .caseInsensitive]) != nil {
            return "Vercel 보호 기능이 이 주소를 막고 있습니다. Vercel 프로젝트의 Domains에 있는 고정 주소(Production)를 넣거나, Settings > Deployment Protection에서 Vercel Authentication을 끄세요."
        }
        let code = nested?["code"] as? String ?? ""
        if code == "NOT_FOUND" || status == 404 { return "이 주소에 LilacAnime 서버가 없습니다. Vercel에 배포한 주소가 맞는지, 배포가 끝났는지 확인하세요." }
        if code.range(of: "FUNCTION_INVOCATION|INTERNAL|DEPLOYMENT", options: .regularExpression) != nil || status >= 500 {
            return "서버가 요청을 처리하지 못했습니다 (" + (code.isEmpty ? "HTTP \(status)" : code) + "). Vercel 프로젝트의 Logs에서 오류를 확인하세요."
        }
        if let message = nested?["message"] as? String { return "서버 오류: " + message }
        return "서버 오류 (HTTP \(status))"
    }
}

/// The comparison and merge rules of src/device-sync.js, on the desktop-shaped items.
enum DeviceSyncPlan {
    static let collections = ["library", "history"]
    typealias Item = [String: Any]
    typealias Lists = [String: [String: Item]]
    /// FNV-1a of the item's JSON (keys sorted): whether it changed, without keeping a copy.
    static func hash(_ item: Any) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: item, options: [.sortedKeys, .fragmentsAllowed])) ?? Data()
        var value: UInt32 = 0x811c9dc5
        for byte in data { value ^= UInt32(byte); value = value &* 0x01000193 }
        return String(value, radix: 36)
    }
    static func hashes(_ lists: Lists) -> [String: [String: String]] {
        Dictionary(uniqueKeysWithValues: collections.map { collection in (collection, (lists[collection] ?? [:]).mapValues { hash($0) }) })
    }
    /// Items added or changed since the snapshot, and removals. A history entry carries its own time of change; an anime
    /// in 내 목록 is changed now, unless it was never synced (then it is as old as when it was saved).
    static func plan(_ lists: Lists, snapshot: [String: [String: String]], now: Double) -> [Item] {
        var items: [Item] = []
        for collection in collections {
            let before = snapshot[collection] ?? [:], current = lists[collection] ?? [:]
            for id in current.keys.sorted() {
                guard let item = current[id], before[id] != hash(item) else { continue }
                let updated = collection == "history" ? (number(item["updated"]) ?? now) : before[id] != nil ? now : (number(item["savedAt"]).flatMap { $0 > 0 ? $0 : nil } ?? 1)
                items.append(["collection": collection, "id": id, "updated": updated, "data": item])
            }
            for id in before.keys.sorted() where current[id] == nil { items.append(["collection": collection, "id": id, "updated": now, "deleted": true]) }
        }
        return items
    }
    static func number(_ value: Any?) -> Double? {
        if let value = value as? NSNumber { return value.doubleValue }
        if let value = value as? String { return Double(value) }
        return nil
    }
}

@MainActor
final class DeviceSync: ObservableObject {
    static let shared = DeviceSync(deferLoading: true)
    @Published private(set) var server = ""
    @Published private(set) var connected = false
    @Published private(set) var state = "연결된 서버가 없습니다."
    @Published private(set) var running = false
    private weak var library: LibraryStore?
    private var observers: Set<AnyCancellable> = []
    private var timer: Task<Void, Never>?
    private var due = Date.distantFuture
    private var lastRun = Date.distantPast
    private var again = false
    private var applying = false
    private let file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("device-sync.json")
    private struct Saved: Codable { var server = ""; var rev = 0; var snapshot: [String: [String: String]] = [:]; var at: Date? = nil }
    private var saved = Saved()
    private let tokenName = "device-sync-token"

    private var loaded = false
    private var loading: Task<Void, Never>?
    init(deferLoading: Bool = false) {
        if deferLoading { return }
        restore(Self.read(file))
    }
    private nonisolated static func read(_ file: URL) -> Saved {
        guard let data = try? Data(contentsOf: file), let value = try? JSONDecoder().decode(Saved.self, from: data) else { return Saved() }
        return value
    }
    private func restore(_ value: Saved) {
        saved = value; loaded = true
        server = saved.server; connected = !server.isEmpty && !SecureKeys.load(tokenName).isEmpty
        state = connected ? server + "에 연결됨" : server.isEmpty ? "연결된 서버가 없습니다." : "연결이 끊겨 있습니다. 비밀번호를 넣고 연결하세요."
    }
    func load() async {
        if loaded { return }
        if let loading { await loading.value; return }
        let file = file
        let task = Task { self.restore(await Task.detached(priority: .userInitiated) { Self.read(file) }.value) }
        loading = task
        await task.value
    }

    /// Runs when the app opens, when it comes back to the front, every 5 minutes, and a little after either list changes.
    func start(library: LibraryStore) {
        guard self.library == nil else { return }
        self.library = library
        library.$favorites.dropFirst().sink { [weak self] _ in self?.changed(after: 3) }.store(in: &observers)
        library.$history.dropFirst().sink { [weak self] _ in self?.changed(after: 30) }.store(in: &observers)
        Timer.publish(every: 300, on: .main, in: .common).autoconnect().sink { [weak self] _ in self?.soon(0) }.store(in: &observers)
        soon(3)
    }
    func becameActive() { if Date().timeIntervalSince(lastRun) > 60 { soon(1) } }
    private func changed(after seconds: Double) { if !applying { soon(seconds) } }
    /// A later request never pushes a sooner one back.
    private func soon(_ seconds: Double) {
        guard connected else { return }
        let at = Date().addingTimeInterval(seconds)
        if timer != nil && due <= at { return }
        timer?.cancel(); due = at
        timer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.run()
        }
    }

    // MARK: Connection

    func connect(address: String, password: String) async throws {
        let base = try DeviceSyncAddress.normalize(address)
        let status = try await call(base, "/api/status")
        guard status["app"] as? String == "lilacanime-server" else { throw SubtitleFiles.failure("LilacAnime 서버가 아닌 것 같습니다. 주소를 확인하세요.") }
        guard status["ready"] as? Bool == true else {
            throw SubtitleFiles.failure("서버 설정이 끝나지 않았습니다. " + ((status["problems"] as? [String]) ?? []).joined(separator: " "))
        }
        guard !password.isEmpty else { throw SubtitleFiles.failure("비밀번호를 입력하세요.") }
        let login = try await call(base, "/api/login", method: "POST", body: ["password": password])
        guard let token = login["token"] as? String, !token.isEmpty else { throw SubtitleFiles.failure("서버가 토큰을 주지 않았습니다.") }
        try SecureKeys.save(token, name: tokenName)
        // What was synced with another server says nothing about this one: a new address starts over.
        if saved.server != base { saved = Saved(server: base) }
        persist(); server = base; connected = true; state = base + "에 연결됨"
        await run()
    }
    func disconnect(message: String = "연결을 끊었습니다.") {
        try? SecureKeys.save("", name: tokenName)
        timer?.cancel(); timer = nil; due = .distantFuture
        connected = false; state = message
    }

    private func call(_ base: String, _ path: String, method: String = "GET", body: Any? = nil, token: String? = nil) async throws -> [String: Any] {
        guard let url = URL(string: base + path) else { throw SubtitleFiles.failure("서버 주소가 올바르지 않습니다.") }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        let (data, response): (Data, URLResponse)
        do { (data, response) = try await URLSession.shared.data(for: request) }
        catch { throw SubtitleFiles.failure((error as? URLError)?.code == .timedOut ? "서버가 응답하지 않습니다." : "서버에 연결하지 못했습니다. 주소와 인터넷 연결을 확인하세요.") }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard (200..<300).contains(status) else {
            let failure = SubtitleFiles.failure(DeviceSyncAddress.serverError(object, status: status))
            throw NSError(domain: "DeviceSync", code: status, userInfo: [NSLocalizedDescriptionKey: failure.localizedDescription])
        }
        guard let object else { throw SubtitleFiles.failure("LilacAnime 서버가 아닌 것 같습니다. 주소를 확인하세요.") }
        return object
    }
    /// A token the server no longer takes (the password was changed) signs this device out.
    private func request(_ method: String, _ path: String, body: Any? = nil) async throws -> [String: Any] {
        let token = SecureKeys.load(tokenName)
        guard connected, !token.isEmpty else { throw SubtitleFiles.failure("기기 동기화가 연결되어 있지 않습니다.") }
        do { return try await call(server, path, method: method, body: body, token: token) }
        catch let error as NSError where error.domain == "DeviceSync" && error.code == 401 {
            disconnect(message: "서버가 이 기기를 받지 않습니다. 비밀번호가 바뀌었다면 새 비밀번호로 다시 연결하세요.")
            throw error
        }
    }

    // MARK: Sync

    func run() async {
        guard connected, let library else { return }
        if running { again = true; return }
        running = true; lastRun = Date(); timer = nil; due = .distantFuture; state = "동기화하는 중..."
        defer { running = false; if again { again = false; soon(0) } }
        do {
            let now = Date().timeIntervalSince1970 * 1000
            let local = DeviceSyncMapping.lists(library), planned = DeviceSyncPlan.hashes(local)
            let outgoing = DeviceSyncPlan.plan(local, snapshot: saved.snapshot, now: now)
            var incoming: [DeviceSyncPlan.Item] = []
            for start in stride(from: 0, to: outgoing.count, by: 500) {
                let reply = try await request("POST", "/api/sync", body: ["items": Array(outgoing[start..<min(outgoing.count, start + 500)])])
                incoming += reply["current"] as? [DeviceSyncPlan.Item] ?? []
            }
            var since = saved.rev, more = true
            while more {
                let page = try await request("GET", "/api/sync?since=\(since)")
                incoming += page["items"] as? [DeviceSyncPlan.Item] ?? []
                since = Int(DeviceSyncPlan.number(page["rev"]) ?? Double(since)); more = page["more"] as? Bool ?? false
            }
            // What was sent stands for the snapshot, so the server's own echo of it changes nothing here.
            var sent = saved.snapshot
            for item in outgoing {
                guard let collection = item["collection"] as? String, let id = item["id"] as? String else { continue }
                if item["deleted"] as? Bool == true { sent[collection]?[id] = nil } else { sent[collection, default: [:]][id] = planned[collection]?[id] }
            }
            saved.snapshot = apply(incoming, sent: sent, library: library)
            saved.rev = since; saved.at = Date(); persist()
            state = server + "에 연결됨 · 마지막 동기화 " + Date().formatted(date: .omitted, time: .shortened)
        } catch {
            if connected { state = "동기화하지 못했습니다: " + error.localizedDescription }
        }
    }
    /// Takes the server's items in (device-sync.js merge). One changed here while the sync ran is kept, unless the
    /// server's is a newer history entry, and goes out with the next sync. Returns the new snapshot.
    private func apply(_ incoming: [DeviceSyncPlan.Item], sent: [String: [String: String]], library: LibraryStore) -> [String: [String: String]] {
        var next = sent
        let fresh = DeviceSyncMapping.lists(library)
        var working = fresh
        var changes: [String: [String: DeviceSyncPlan.Item?]] = [:]
        for item in incoming {
            guard let collection = item["collection"] as? String, DeviceSyncPlan.collections.contains(collection), let id = item["id"] as? String else { continue }
            let mine = working[collection]?[id]
            let changedHere = mine.map { DeviceSyncPlan.hash($0) } != next[collection]?[id]
            let newer = collection == "history" && mine != nil && (DeviceSyncPlan.number(mine?["updated"]) ?? 0) < (DeviceSyncPlan.number(item["updated"]) ?? 0)
            if changedHere && !newer { continue }
            if item["deleted"] as? Bool == true {
                working[collection]?[id] = nil; next[collection]?[id] = nil; changes[collection, default: [:]][id] = .some(nil)
            } else if let data = item["data"] as? DeviceSyncPlan.Item {
                working[collection, default: [:]][id] = data; changes[collection, default: [:]][id] = .some(data)
            }
        }
        guard !changes.isEmpty else { return next }
        applying = true
        defer { applying = false }
        DeviceSyncMapping.apply(changes, to: library)
        // The lists as this app keeps them now: their items are what the next sync compares against.
        let written = DeviceSyncMapping.lists(library)
        for (collection, ids) in changes {
            for id in ids.keys {
                if let item = written[collection]?[id] { next[collection, default: [:]][id] = DeviceSyncPlan.hash(item) } else { next[collection]?[id] = nil }
            }
        }
        return next
    }
    private func persist() {
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(saved) { try? data.write(to: file, options: .atomic) }
    }
}

/// iOS 내 목록 / 시청 기록 <-> the desktop's item shape (README of lilacanimeserver, "데이터 모양").
@MainActor
enum DeviceSyncMapping {
    /// The site's own id: iOS keeps RE:Anime / Animenosub ids with their source in front.
    static func desktopID(_ anime: Anime, source: String) -> String {
        for prefix in [source + ":", source + "_"] where anime.id.hasPrefix(prefix) { return String(anime.id.dropFirst(prefix.count)) }
        return anime.id
    }
    static func iosID(_ id: String, source: String) -> String { ["reanime", "animenosub"].contains(source) && !id.hasPrefix(source + ":") ? source + ":" + id : id }
    private static func trimmed(_ saved: SavedAnime) -> (Anime, String) {
        let anime = AnimeSnapshot.shared.withEpisodes(anime: saved.anime, episodes: [])
        return (anime, AnimeSnapshot.shared.encode(anime: anime))
    }
    static func animeItem(_ anime: Anime, source: String) -> DeviceSyncPlan.Item {
        let id = desktopID(anime, source: source)
        var item: DeviceSyncPlan.Item = ["provider": source, "id": id, "mal_id": source + ":" + id, "title": anime.title,
            "title_english": anime.english, "title_japanese": anime.native, "images": ["webp": ["large_image_url": anime.poster]],
            "year": anime.year, "type": anime.format, "status": anime.status, "url": anime.detailUrl,
            "genres": anime.genres.map { ["name": $0] }]
        if anime.score > 0 { item["score"] = anime.score }
        if let total = anime.totalEpisodes?.intValue { item["episodes"] = total; item["totalEpisodes"] = total }
        if let anilist = anime.anilistId?.intValue { item["anilistId"] = anilist }
        if let mal = anime.malId?.intValue { item["malId"] = mal }
        return item
    }
    static func libraryID(_ saved: SavedAnime) -> String { saved.source + ":" + desktopID(saved.anime, source: saved.source) }
    static func libraryItem(_ saved: SavedAnime) -> DeviceSyncPlan.Item {
        let (anime, snapshot) = trimmed(saved)
        var item = animeItem(anime, source: saved.source)
        item["savedAt"] = saved.savedAt.map { ($0.timeIntervalSince1970 * 1000).rounded() } ?? 0
        item["ios"] = ["snapshot": snapshot]
        return item
    }
    static func historyID(_ entry: WatchEntry) -> String? {
        guard entry.anime.source != "local", let url = URL(string: entry.watchURL), ["http", "https"].contains(url.scheme ?? "") else { return nil }
        return entry.anime.source + ":" + entry.watchURL
    }
    static func historyItem(_ entry: WatchEntry) -> DeviceSyncPlan.Item? {
        guard let key = historyID(entry) else { return nil }
        let (anime, snapshot) = trimmed(entry.anime)
        let progress = entry.duration > 0 ? (min(1, max(0, entry.position / entry.duration)) * 100).rounded() : (entry.progressPercent ?? 0)
        return ["key": key, "src": "", "name": anime.title + " · " + entry.episodeTitle, "subtitleTitle": anime.title, "image": anime.poster,
                "episode": ["provider": entry.anime.source, "url": entry.watchURL, "id": entry.episodeID, "number": entry.number, "name": String(entry.number)],
                "anime": animeItem(anime, source: entry.anime.source), "resolveKind": entry.anime.source == "linkkf" ? "linkkf" as Any : NSNull(),
                "progress": progress, "updated": (entry.updatedAt.timeIntervalSince1970 * 1000).rounded(),
                "ios": ["animeID": entry.anime.id, "episodeID": entry.episodeID, "title": entry.episodeTitle, "position": entry.position, "duration": entry.duration, "snapshot": snapshot]]
    }
    static func lists(_ library: LibraryStore) -> DeviceSyncPlan.Lists {
        var favorites: [String: DeviceSyncPlan.Item] = [:], history: [String: DeviceSyncPlan.Item] = [:]
        for saved in library.favorites { favorites[libraryID(saved)] = libraryItem(saved) }
        for entry in library.history { if let id = historyID(entry), history[id] == nil, let item = historyItem(entry) { history[id] = item } }
        return ["library": favorites, "history": history]
    }

    /// A desktop item as this app's saved anime: its iOS form when it was made here, else built from its fields.
    static func savedAnime(_ data: DeviceSyncPlan.Item, savedAt: Date? = nil) -> SavedAnime? {
        let ios = data["ios"] as? [String: Any]
        var source = data["provider"] as? String ?? ""
        if source.isEmpty, let malID = data["mal_id"] as? String, let colon = malID.firstIndex(of: ":") { source = String(malID[..<colon]) }
        guard !source.isEmpty else { return nil }
        let anime: Anime
        if let snapshot = ios?["snapshot"] as? String { anime = AnimeSnapshot.shared.decode(content: snapshot) }
        else {
            var id = data["id"].map { String(describing: $0) } ?? ""
            if id.isEmpty, let malID = data["mal_id"] as? String { id = String(malID.drop(while: { $0 != ":" }).dropFirst()) }
            guard !id.isEmpty else { return nil }
            let images = (data["images"] as? [String: Any])?["webp"] as? [String: Any]
            let genres = (data["genres"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }
            let total = DeviceSyncPlan.number(data["totalEpisodes"]) ?? DeviceSyncPlan.number(data["episodes"])
            var fields: [String: Any] = ["id": iosID(id, source: source), "title": data["title"] as? String ?? id, "score": DeviceSyncPlan.number(data["score"]) ?? 0,
                "poster": images?["large_image_url"] as? String ?? "", "genres": genres, "description": data["synopsis"] as? String ?? "",
                "airedDate": data["aired"] as? String ?? "", "year": data["year"].map { String(describing: $0) } ?? "", "season": data["season"] as? String ?? "",
                "format": data["type"] as? String ?? "", "status": data["status"] as? String ?? "", "source": source,
                "english": data["title_english"] as? String ?? "", "native": data["title_japanese"] as? String ?? "", "detailUrl": data["url"] as? String ?? ""]
            if let value = DeviceSyncPlan.number(data["anilistId"]) { fields["anilistId"] = Int(value) }
            if let value = DeviceSyncPlan.number(data["malId"]) { fields["malId"] = Int(value) }
            if let value = total { fields["totalEpisodes"] = Int(value) }
            guard let json = try? JSONSerialization.data(withJSONObject: fields), let text = String(data: json, encoding: .utf8) else { return nil }
            anime = AnimeSnapshot.shared.decode(content: text)
        }
        var saved = SavedAnime(anime, source: source)
        saved.savedAt = savedAt
        return saved
    }
    static func watchEntry(_ data: DeviceSyncPlan.Item) -> WatchEntry? {
        guard let key = data["key"] as? String, let animeData = data["anime"] as? DeviceSyncPlan.Item else { return nil }
        let ios = data["ios"] as? [String: Any]
        var withProvider = animeData
        if withProvider["provider"] == nil, let colon = key.firstIndex(of: ":") { withProvider["provider"] = String(key[..<colon]) }
        if let snapshot = ios?["snapshot"] as? String { withProvider["ios"] = ["snapshot": snapshot] }
        guard let anime = savedAnime(withProvider) else { return nil }
        let episode = data["episode"] as? [String: Any] ?? [:]
        let url = episode["url"] as? String ?? String(key.drop(while: { $0 != ":" }).dropFirst())
        let episodeID = ios?["episodeID"] as? String ?? (episode["id"].map { String(describing: $0) }.flatMap { $0.isEmpty ? nil : $0 } ?? url)
        let number = Int(DeviceSyncPlan.number(episode["number"]) ?? 1)
        let name = episode["name"].map { String(describing: $0) } ?? String(number)
        let title = ios?["title"] as? String ?? (name.range(of: "\\d$", options: .regularExpression) != nil ? name + "화" : name)
        let updated = Date(timeIntervalSince1970: (DeviceSyncPlan.number(data["updated"]) ?? 0) / 1000)
        var entry = WatchEntry(id: anime.id + "#" + episodeID, anime: anime, episodeID: episodeID, episodeTitle: title, number: number,
            watchURL: url, directURL: nil, position: DeviceSyncPlan.number(ios?["position"]) ?? 0, duration: DeviceSyncPlan.number(ios?["duration"]) ?? 0, updatedAt: updated)
        if ios == nil { entry.progressPercent = DeviceSyncPlan.number(data["progress"]) }
        return entry
    }

    /// Writes the merged items into the lists: changed ones in place, new ones first, removals out.
    static func apply(_ changes: [String: [String: DeviceSyncPlan.Item?]], to library: LibraryStore) {
        var favorites = library.favorites, history = library.history
        for (id, change) in changes["library"] ?? [:] {
            let index = favorites.firstIndex { libraryID($0) == id }
            guard let data = change else { if let index { favorites.remove(at: index) }; continue }
            let savedAt = DeviceSyncPlan.number(data["savedAt"]).flatMap { $0 > 0 ? Date(timeIntervalSince1970: $0 / 1000) : nil }
            guard let saved = savedAnime(data, savedAt: savedAt) else { continue }
            if let index { favorites[index] = saved } else { favorites.removeAll { $0.id == saved.id }; favorites.insert(saved, at: 0) }
        }
        for (id, change) in changes["history"] ?? [:] {
            history.removeAll { historyID($0) == id }
            guard let data = change, let entry = watchEntry(data) else { continue }
            history.removeAll { $0.id == entry.id }
            history.append(entry)
        }
        library.replaceFromSync(favorites: favorites, history: history.sorted { $0.updatedAt > $1.updatedAt })
    }
}

struct DeviceSyncView: View {
    @ObservedObject private var sync = DeviceSync.shared
    @State private var address = DeviceSync.shared.server
    @State private var password = ""
    @State private var working = false
    @State private var message: String?
    var body: some View {
        Form {
            Section {
                Text("직접 만든 LilacAnime 서버(Vercel)를 거쳐 내 목록과 시청 기록을 데스크탑 앱과 맞춥니다. 서버는 lilacanimeserver를 Vercel에 배포하고 Upstash Redis와 비밀번호(LILAC_PASSWORD)를 설정해 만듭니다.")
                    .font(.caption).foregroundStyle(.secondary)
                Link("서버 만드는 방법", destination: URL(string: "https://github.com/whispelyn-byte/LilacAnime-server")!)
            }
            Section("서버") {
                TextField("서버 주소 (https://내-서버.vercel.app)", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                SecureField("비밀번호", text: $password).textInputAutocapitalization(.never).autocorrectionDisabled().onSubmit(connect)
                Button(sync.connected ? "다시 연결" : "연결", action: connect).disabled(working)
                if working { ProgressView("서버에 연결하는 중...") }
                if let message { Text(message).font(.caption).foregroundStyle(.red) }
            }
            Section("상태") {
                Text(sync.state).font(.caption)
                if sync.connected {
                    Button("지금 동기화") { Task { await sync.run() } }.disabled(sync.running)
                    Button("연결 끊기", role: .destructive) { sync.disconnect() }
                }
                Text("비밀번호는 연결할 때 한 번만 보내고, 서버가 준 토큰만 이 기기의 Keychain에 둡니다. 이 기기의 파일로 본 기록은 보내지 않습니다. 같은 항목을 두 기기에서 바꾸면 나중에 바꾼 쪽이 남습니다.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }.navigationTitle("기기 동기화")
    }
    private func connect() {
        working = true; message = nil
        Task {
            do { try await sync.connect(address: address, password: password); password = "" }
            catch { message = error.localizedDescription }
            working = false
        }
    }
}
