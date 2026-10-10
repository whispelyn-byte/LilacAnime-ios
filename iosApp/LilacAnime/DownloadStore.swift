import Foundation
import SwiftUI
import UIKit
import LilacShared

struct DownloadPart: Codable {
    var url: URL
    var name: String
    var done = false
}
struct DownloadSubtitleTrack: Codable, Equatable {
    var file: String
    var label: String
    var language: String
    var provider: String
    var translatedFile: String? = nil
}
struct DownloadEntry: Codable, Identifiable {
    var id: String
    var anime: SavedAnime
    var episodeID: String
    var title: String
    var number: Int
    var watchURL: String
    var stream: ResolvedStream
    var localFile: String?
    var subtitleFiles: [String]?
    var rootFile: String?
    var parts: [DownloadPart]?
    var quality: String?
    var fontFiles: [String]?
    var chapters: [OfflineChapter]?
    var subtitleTracks: [DownloadSubtitleTrack]?
    var primarySubtitle: String?
    var siteKorean: Bool?
    var subtitlesPrepared: Bool?
    var posterFile: String?
    var retries: Int?
    var attemptID: String? = nil
    var planIdentity: String? = nil
    var bytes: Int64?
    var status = "대기"
    var completed = 0
    var total = 0
    var error: String?
    var date = Date()
    @MainActor var playback: PlaybackItem {
        var item = PlaybackItem(entry: WatchEntry(id: id, anime: anime, episodeID: episodeID, episodeTitle: title, number: number,
            watchURL: watchURL, directURL: localFile.map { DownloadStore.directory.appendingPathComponent(id).appendingPathComponent($0).absoluteString },
            position: 0, duration: 0, updatedAt: date))
        item.localSubtitles = orderedSubtitles.map { DownloadStore.directory.appendingPathComponent(id).appendingPathComponent($0) }
        item.burnedKorean = stream.burnedKorean; item.localSubtitleTracks = localTracks
        item.next = following(DownloadStore.shared.entries)
        item.preceding = preceding(DownloadStore.shared.entries)
        return item
    }
    func following(_ entries: [DownloadEntry]) -> [PlaybackItem] {
        let available = ordered(entries)
        guard let current = available.firstIndex(where: { $0.id == id }) else { return [] }
        return available.dropFirst(current + 1).map { $0.singlePlayback }
    }
    func preceding(_ entries: [DownloadEntry]) -> [PlaybackItem] {
        let available = ordered(entries)
        guard let current = available.firstIndex(where: { $0.id == id }) else { return [] }
        return available.prefix(current).map { $0.singlePlayback }
    }
    private func ordered(_ entries: [DownloadEntry]) -> [DownloadEntry] {
        let order = Dictionary(anime.anime.episodes.enumerated().map { ($0.element.id, $0.offset) }, uniquingKeysWith: { first, _ in first })
        let available = entries.filter { $0.anime.id == anime.id && $0.localFile != nil }.sorted {
            if let left = order[$0.episodeID], let right = order[$1.episodeID] { return left < right }
            if $0.number != $1.number { return $0.number < $1.number }
            return $0.episodeID.localizedStandardCompare($1.episodeID) == .orderedAscending
        }
        return available
    }
    private var singlePlayback: PlaybackItem {
        var item = PlaybackItem(entry: WatchEntry(id: id, anime: anime, episodeID: episodeID, episodeTitle: title, number: number, watchURL: watchURL,
            directURL: localFile.map { DownloadStore.directory.appendingPathComponent(id).appendingPathComponent($0).absoluteString }, position: 0, duration: 0, updatedAt: date))
        item.localSubtitles = orderedSubtitles.map { DownloadStore.directory.appendingPathComponent(id).appendingPathComponent($0) }; item.burnedKorean = stream.burnedKorean; item.localSubtitleTracks = localTracks; return item
    }
    private var orderedSubtitles: [String] { primarySubtitle.map { [$0] + (subtitleFiles ?? []).filter { $0 != primarySubtitle } } ?? (subtitleFiles ?? []) }
    private var localTracks: [RemoteSubtitle] {
        (subtitleTracks ?? []).map { track in RemoteSubtitle(label: track.label, url: DownloadStore.directory.appendingPathComponent(id).appendingPathComponent(track.translatedFile ?? track.file), language: track.translatedFile == nil ? track.language : "ko") }
    }
}
@MainActor
final class DownloadStore: ObservableObject {
    nonisolated static let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads")
    static let shared = DownloadStore()
    @Published private(set) var entries: [DownloadEntry] = []
    private var savedManifests: [String: Data] = [:]
    weak var library: LibraryStore? { didSet { prepareCompletedDownloads(); analyzeCompletedDownloads() } }
    @Published var translationStatus: String?
    private let downloadTranslator = TranslationCoordinator()
    private var subtitleTask: Task<Void, Never>?
    private var translatedDownloads: Set<String> = []
    private var preparationTask: Task<Void, Never>?
    private var retryTasks: [String: Task<Void, Never>] = [:]
    @Published var analysisStatus: String?
    @Published private(set) var clearing = false
    private var analysisTask: Task<Void, Never>?
    private var analyzedGroups: Set<String> = []
    @Published var byteProgress: [String: Double] = [:]
    @Published var transferRate: [String: Double] = [:]
    private var samples: [String: (Date, Int64)] = [:]
    private var rates: [String: Double] = [:]
    private let subtitlePreparer = DesktopSubtitlePreparer()
    @Published var error: String?
    @Published var notice: String?
    @Published private(set) var pendingResolution = 0
    @Published private(set) var resolvingTitle = ""
    private var resolutionTask: Task<Void, Never>?
    private var resolutionQueue: [(PlaybackItem, String)] = []
    let resolver: PlaybackResolver = {
        let resolver = PlaybackResolver()
        resolver.webView.frame = CGRect(x: 0, y: 0, width: 960, height: 640)
        resolver.silent = true
        return resolver
    }()
    private var tasks: [String: Task<Void, Never>] = [:]
    private var backgroundTasks: [String: URLSessionDownloadTask] = [:]
    private let delegate = BackgroundDownloadDelegate()
    private var restoring = true
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: "com.lilac.downloads")
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        configuration.httpMaximumConnectionsPerHost = 4
        configuration.timeoutIntervalForRequest = 180
        return URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }()
    private let indexFile: URL?
    private var file: URL { indexFile ?? Self.directory.appendingPathComponent("index.json") }
    init(index: URL? = nil, restoreSession: Bool = true) {
        indexFile = index
        var continuing: Set<String> = []
        if let data = try? Data(contentsOf: file), let saved = try? JSONDecoder().decode([DownloadEntry].self, from: data) {
            continuing = Set(saved.filter { $0.status == "다운로드 중" }.map(\.id))
            entries = saved.map { entry in var item = entry; if restoreSession && (item.status == "다운로드 중" || item.status == "준비 중") { item.status = "중단됨" }; return item }
        }
        delegate.owner = self
        guard restoreSession else { restoring = false; return }
        let resumeIDs = continuing
        session.getAllTasks { [weak self] found in
            Task { @MainActor in
                guard let self else { return }
                for task in found {
                    guard let task = task as? URLSessionDownloadTask, let description = task.taskDescription else { continue }
                    let id = description.components(separatedBy: "|")[0]
                    guard resumeIDs.contains(id), let entry = self.entries.first(where: { $0.id == id }), DownloadIdentity.accepts(description, attempt: entry.attemptID) else { task.cancel(); continue }
                    self.backgroundTasks[description] = task
                    self.update(id) { $0.status = "다운로드 중" }
                }
                self.restoring = false
                self.pump()
            }
        }
    }
    func download(_ item: PlaybackItem, stream: ResolvedStream, quality: String = "Auto", announce: Bool = true) {
        let id = SubtitleFiles.key(item.anime.id + "#" + item.episodeID)
        guard !restoring else { enqueue([item], quality: quality); return }
        guard !busy(id) else { notice = "이미 다운로드 중인 회차입니다. 내 목록 → 다운로드에서 확인하세요."; return }
        if let existing = entries.first(where: { $0.id == id }), let local = existing.localFile,
           FileManager.default.fileExists(atPath: Self.directory.appendingPathComponent(id).appendingPathComponent(local).path) { notice = "이미 저장한 회차입니다. 내 목록 → 다운로드에서 재생하세요."; prepareCompletedDownloads(); return }
        if !entries.contains(where: { $0.id == id }) {
            entries.insert(DownloadEntry(id: id, anime: item.anime, episodeID: item.episodeID, title: item.title,
                number: item.number, watchURL: item.watchURL.absoluteString, stream: stream, quality: quality), at: 0)
        } else {
            update(id) { entry in
                // Rebuild requests with fresh tokens; start() checks selected-list identity before reusing data.
                if entry.planIdentity == nil && (DownloadIdentity.resource(entry.stream.url) != DownloadIdentity.resource(stream.url) || entry.quality != quality) { entry.planIdentity = "legacy-source-changed" }
                entry.stream = stream; entry.quality = quality
            }
        }
        if announce { notice = item.title + " 다운로드를 시작합니다. 내 목록 → 다운로드에서 진행 상황을 확인하세요." }
        start(id)
    }
    func enqueue(_ items: [PlaybackItem], quality: String) {
        for item in items where !resolutionQueue.contains(where: { $0.0.anime.id == item.anime.id && $0.0.episodeID == item.episodeID }) { resolutionQueue.append((item, quality)); pendingResolution += 1 }
        if !items.isEmpty { notice = "\(items.count)개 회차를 다운로드 대기에 추가했습니다. 내 목록 → 다운로드에서 확인하세요." }
        guard resolutionTask == nil else { return }
        error = nil
        resolutionTask = Task {
            defer {
                resolver.cancel(); resolver.webView.configuration.userContentController.removeAllUserScripts()
                resolver.webView.loadHTMLString("", baseURL: nil); resolutionTask = nil; pendingResolution = resolutionQueue.count; resolvingTitle = ""
                // An enqueue immediately after pause/cancel must survive the old worker's cleanup.
                if !resolutionQueue.isEmpty { enqueue([], quality: quality) }
            }
            while !resolutionQueue.isEmpty {
                while restoring && !Task.isCancelled { do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return } }
                let (item, quality) = resolutionQueue.removeFirst()
                if Task.isCancelled { return }
                resolvingTitle = item.anime.title + " · " + item.title
                let id = SubtitleFiles.key(item.anime.id + "#" + item.episodeID)
                if entries.contains(where: { $0.id == id && ($0.localFile != nil || busy(id)) }) { pendingResolution -= 1; continue }
                let preferRaw = await subtitlePreparer.preferRaw(item, preferences: library?.preferences ?? AppPreferences(), downloading: true)
                if Task.isCancelled { return }
                resolver.resolve(item, preferRaw: preferRaw, preferred: library?.preferences.preferredServers?[item.anime.source])
                while resolver.loading && resolver.streams.isEmpty && !Task.isCancelled {
                    do { try await Task.sleep(nanoseconds: 200_000_000) } catch { return }
                }
                if Task.isCancelled { return }
                let ordered = DesktopStreamPolicy.ordered(resolver.streams, preferRaw: preferRaw, preferred: library?.preferences.preferredServers?[item.anime.source], workingProvider: UserDefaults.standard.string(forKey: "miruro.working-provider"))
                let chosen = item.anime.source == "miruro" ? await DownloadStreamSelector.choose(ordered, quality: quality) : ordered.first
                if Task.isCancelled { return }
                if let stream = chosen { download(item, stream: stream, quality: quality, announce: false) }
                else { error = item.title + ": " + (resolver.error ?? "영상 주소를 찾지 못했습니다.") }
                pendingResolution -= 1
            }
        }
    }
    func stopResolving() { resolutionQueue.removeAll(); pendingResolution = 0; resolutionTask?.cancel(); resolver.cancel() }
    /// An episode's download as the desktop episode button shows it (app.js episodeDownloadMarkup).
    enum EpisodeState { case idle, active, stopped, completed }
    func state(_ item: PlaybackItem) -> EpisodeState {
        let id = SubtitleFiles.key(item.anime.id + "#" + item.episodeID)
        if resolutionQueue.contains(where: { $0.0.anime.id == item.anime.id && $0.0.episodeID == item.episodeID }) { return .active }
        guard let entry = entries.first(where: { $0.id == id }) else { return .idle }
        if entry.localFile != nil { return .completed }
        return ["대기", "준비 중", "다운로드 중"].contains(entry.status) || busy(id) ? .active : .stopped
    }
    // MARK: Series groups (app.js downloadGroupCard / downloads:group)
    func entries(group animeID: String) -> [DownloadEntry] { entries.filter { $0.anime.id == animeID }.sorted { $0.number < $1.number } }
    func stopGroup(_ animeID: String) {
        let queued = resolutionQueue.count
        resolutionQueue.removeAll { $0.0.anime.id == animeID }
        pendingResolution = max(0, pendingResolution - (queued - resolutionQueue.count))
        for entry in entries(group: animeID) where entry.localFile == nil && (["대기", "준비 중", "다운로드 중"].contains(entry.status) || busy(entry.id)) { cancel(entry.id) }
    }
    func resumeGroup(_ animeID: String) {
        for entry in entries(group: animeID) where entry.localFile == nil && ["중단됨", "실패"].contains(entry.status) && !busy(entry.id) { retry(entry) }
    }
    func removeGroup(_ animeID: String) async {
        stopGroup(animeID)
        var waited = 0
        while waited < 50 && entries(group: animeID).contains(where: { busy($0.id) }) { try? await Task.sleep(nanoseconds: 100_000_000); waited += 1 }
        for entry in entries(group: animeID) { delete(entry.id) }
    }
    /// "1~3, 5, 7화" (app.js episodeRanges).
    nonisolated static func episodeRanges(_ numbers: [Int]) -> String {
        let sorted = Array(Set(numbers)).sorted()
        var parts: [String] = [], index = 0
        while index < sorted.count {
            var end = index
            while end + 1 < sorted.count && sorted[end + 1] == sorted[end] + 1 { end += 1 }
            parts.append(end > index + 1 ? "\(sorted[index])~\(sorted[end])" : end > index ? "\(sorted[index]), \(sorted[end])" : "\(sorted[index])")
            index = end + 1
        }
        return parts.joined(separator: ", ")
    }
    /// The episode button: delete a saved one, stop one in progress, resume a stopped one, else queue it.
    func toggle(_ item: PlaybackItem, quality: String) {
        let id = SubtitleFiles.key(item.anime.id + "#" + item.episodeID)
        switch state(item) {
        case .completed: delete(id); notice = "\(item.title) 다운로드를 삭제했습니다."
        case .active:
            let queued = resolutionQueue.count
            resolutionQueue.removeAll { $0.0.anime.id == item.anime.id && $0.0.episodeID == item.episodeID }
            pendingResolution = max(0, pendingResolution - (queued - resolutionQueue.count))
            if entries.contains(where: { $0.id == id }) { cancel(id) }
            notice = "\(item.title) 다운로드를 중지했습니다."
        case .stopped: if let entry = entries.first(where: { $0.id == id }) { retry(entry); notice = "\(item.title) 다운로드를 다시 시작합니다." }
        case .idle: enqueue([item], quality: quality)
        }
    }
    private func busy(_ id: String) -> Bool {
        restoring || tasks[id] != nil || backgroundTasks.keys.contains { $0.hasPrefix(id + "|") }
    }
    func retry(_ entry: DownloadEntry) { retryTasks[entry.id]?.cancel(); retryTasks[entry.id] = nil; update(entry.id) { $0.retries = 0 }; if entry.status == "실패" { enqueue([entry.playback], quality: entry.quality ?? "Auto") } else { start(entry.id) } }
    private func start(_ id: String) {
        guard !busy(id), let entry = entries.first(where: { $0.id == id }), entry.localFile == nil else { return }
        let attempt = UUID().uuidString
        resetProgress(id)
        update(id) { $0.status = "준비 중"; $0.error = nil; $0.attemptID = attempt }
        tasks[id] = Task {
            let background = UIApplication.shared.beginBackgroundTask(withName: "LilacManifestPlan") { [weak self] in Task { @MainActor in self?.cancel(id) } }
            defer { if background != .invalid { UIApplication.shared.endBackgroundTask(background) }; tasks[id] = nil }
            do {
                let folder = Self.directory.appendingPathComponent(id)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let root: String
                var parts: [DownloadPart]
                var identity: String
                if ["mp4", "mkv", "webm", "m4v"].contains(entry.stream.url.pathExtension.lowercased()) {
                    root = "video." + entry.stream.url.pathExtension.lowercased()
                    parts = [DownloadPart(url: entry.stream.url, name: root)]
                    identity = DownloadIdentity.resource(entry.stream.url)
                } else {
                    let planner = HLSPlanBuilder(stream: entry.stream, folder: folder, quality: entry.quality ?? "Auto")
                    let plan = try await planner.build()
                    root = plan.0; parts = plan.1; identity = plan.2
                }
                try Task.checkCancellation()
                let oldParts = entry.parts ?? []
                let compatible = entry.planIdentity == identity || (entry.planIdentity == nil && oldParts.count == parts.count &&
                    Set(oldParts.map { DownloadIdentity.resource($0.url) }) == Set(parts.map { DownloadIdentity.resource($0.url) }))
                if !compatible {
                    for old in oldParts {
                        try? FileManager.default.removeItem(at: folder.appendingPathComponent(old.name))
                        try? FileManager.default.removeItem(at: folder.appendingPathComponent(old.name + ".resume"))
                    }
                } else {
                    // Upgrade pre-identity downloads without discarding their completed pieces.
                    for part in parts {
                        guard let old = oldParts.first(where: { DownloadIdentity.resource($0.url) == DownloadIdentity.resource(part.url) }) else { continue }
                        let source = folder.appendingPathComponent(old.name), target = folder.appendingPathComponent(part.name)
                        if source != target, FileManager.default.fileExists(atPath: source.path), !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: source, to: target) }
                        if old.url != part.url { try? FileManager.default.removeItem(at: folder.appendingPathComponent(old.name + ".resume")) }
                    }
                }
                for index in parts.indices {
                    parts[index].done = FileManager.default.fileExists(atPath: folder.appendingPathComponent(parts[index].name).path)
                }
                try Task.checkCancellation()
                let bytes = parts.filter(\.done).reduce(Int64(0)) { $0 + Int64((try? folder.appendingPathComponent($1.name).resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
                update(id) { $0.planIdentity = identity; $0.rootFile = root; $0.parts = parts; $0.completed = parts.filter(\.done).count; $0.total = parts.count; $0.bytes = bytes; $0.status = "다운로드 중" }
                if parts.allSatisfy(\.done) { update(id) { $0.localFile = root; $0.status = "완료"; $0.retries = 0 }; prepareCompletedDownloads(); analyzeCompletedDownloads(); return }
                pump()
            } catch is CancellationError { update(id) { $0.status = "중단됨" } }
            catch { failed(id, error: error) }
        }
    }
    func cancel(_ id: String) {
        retryTasks[id]?.cancel(); retryTasks[id] = nil
        tasks[id]?.cancel()
        let attempt = entries.first { $0.id == id }?.attemptID
        for (description, task) in backgroundTasks where description.hasPrefix(id + "|") {
            task.cancel { data in
                Task { @MainActor [weak self] in
                    guard let entry = self?.entries.first(where: { $0.id == id }), entry.attemptID == attempt, entry.status == "중단됨" else { return }
                    if let data, let destination = BackgroundDownloadDelegate.destination(description, suffix: ".resume") { try? data.write(to: destination, options: .atomic) }
                }
            }
        }
        update(id) { $0.status = "중단됨" }
    }
    func delete(_ id: String) {
        retryTasks[id]?.cancel(); retryTasks[id] = nil
        guard !busy(id) else { cancel(id); error = "다운로드를 중단한 뒤 삭제하세요."; return }
        do {
            let folder = Self.directory.appendingPathComponent(id)
            if FileManager.default.fileExists(atPath: folder.path) { try FileManager.default.removeItem(at: folder) }
            entries.removeAll { $0.id == id }; persist()
        } catch { self.error = error.localizedDescription }
    }
    func backgroundFinished(_ description: String, staged: URL, mime: String) {
        let fields = description.components(separatedBy: "|")
        guard fields.count >= 2, let index = entries.firstIndex(where: { $0.id == fields[0] }),
              DownloadIdentity.accepts(description, attempt: entries[index].attemptID),
              let partIndex = entries[index].parts?.firstIndex(where: { $0.name == fields[1] }),
              let target = BackgroundDownloadDelegate.destination(description) else { try? FileManager.default.removeItem(at: staged); return }
        guard entries[index].status == "다운로드 중" else { try? FileManager.default.removeItem(at: staged); return }
        do {
            if entries[index].stream.hlsManifest != nil && entries[index].parts?[partIndex].url.pathExtension.lowercased() == "html" {
                let decoded = try HLSData.validatedFragment(Data(contentsOf: staged))
                try decoded.write(to: target, options: .atomic); try FileManager.default.removeItem(at: staged)
            } else if entries[index].stream.manifestKey != nil && (fields[1].hasSuffix(".ts") || mime.hasPrefix("image/")) {
                let decoded = HLSData.transportStream(try Data(contentsOf: staged))
                try decoded.write(to: target, options: .atomic); try FileManager.default.removeItem(at: staged)
            } else if fields[1].hasSuffix(".ts") {
                let decoded = HLSData.transportStream(try Data(contentsOf: staged))
                try decoded.write(to: target, options: .atomic); try FileManager.default.removeItem(at: staged)
            } else {
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
                try FileManager.default.moveItem(at: staged, to: target)
            }
            try? FileManager.default.removeItem(at: target.appendingPathExtension("resume"))
            entries[index].bytes = (entries[index].bytes ?? 0) + Int64((try? target.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            entries[index].parts?[partIndex].done = true
            entries[index].completed = entries[index].parts?.filter(\.done).count ?? 0
            if entries[index].parts?.allSatisfy(\.done) == true {
                entries[index].localFile = entries[index].rootFile; entries[index].status = "완료"; entries[index].error = nil; entries[index].retries = 0
                prepareCompletedDownloads(); analyzeCompletedDownloads()
            }
            persist()
        } catch { backgroundFailed(description, error: error) }
    }
    func backgroundFailed(_ description: String, error: Error) {
        let id = description.components(separatedBy: "|")[0]
        guard let entry = entries.first(where: { $0.id == id }), entry.status == "다운로드 중",
              DownloadIdentity.accepts(description, attempt: entry.attemptID) else { return }
        let ns = error as NSError
        if let resume = ns.userInfo["NSURLSessionDownloadTaskResumeData"] as? Data,
           let path = BackgroundDownloadDelegate.destination(description, suffix: ".resume") { try? resume.write(to: path, options: .atomic) }
        if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled { update(id) { $0.status = "중단됨" } }
        else { failed(id, error: error) }
    }
    func backgroundCompleted(_ description: String) {
        backgroundTasks.removeValue(forKey: description); samples[description] = nil; rates[description] = nil
        let id = description.components(separatedBy: "|")[0]
        transferRate[id] = rates.filter { $0.key.hasPrefix(id + "|") }.values.reduce(0, +)
        pump()
    }
    private func pump() {
        guard !restoring else { return }
        var active = Set(backgroundTasks.keys.map { $0.components(separatedBy: "|")[0] })
        for entry in entries.reversed() where entry.status == "다운로드 중" && entry.localFile == nil {
            if !active.contains(entry.id) && active.count >= 2 { continue }
            active.insert(entry.id)
            for part in entry.parts ?? [] where !part.done {
                guard backgroundTasks.count < 12 else { return }
                if backgroundTasks.keys.filter({ $0.hasPrefix(entry.id + "|") }).count >= 6 { break }
                let description = entry.id + "|" + part.name + (entry.attemptID.map { "|" + $0 } ?? "")
                guard backgroundTasks[description] == nil else { continue }
                let resumed = Self.directory.appendingPathComponent(entry.id).appendingPathComponent(part.name + ".resume")
                let task: URLSessionDownloadTask
                if let data = try? Data(contentsOf: resumed) { task = session.downloadTask(withResumeData: data) }
                else {
                    var request = URLRequest(url: part.url)
                    for (key, value) in entry.stream.headers where key.lowercased() != "cookie" || part.url.host == entry.stream.url.host { request.setValue(value, forHTTPHeaderField: key) }
                    request.setValue(entry.stream.referer, forHTTPHeaderField: "Referer")
                    task = session.downloadTask(with: request)
                }
                task.taskDescription = description; backgroundTasks[description] = task; task.resume()
            }
        }
    }
    func progress(_ description: String, written: Int64, expected: Int64) {
        let id = description.components(separatedBy: "|")[0]
        guard let entry = entries.first(where: { $0.id == id }), entry.status == "다운로드 중",
              DownloadIdentity.accepts(description, attempt: entry.attemptID) else { return }
        byteProgress[id] = expected > 0 ? Double(written) / Double(expected) : 0
        if let sample = samples[description], Date().timeIntervalSince(sample.0) >= 1 {
            rates[description] = Double(max(0, written - sample.1)) / Date().timeIntervalSince(sample.0)
            transferRate[id] = rates.filter { $0.key.hasPrefix(id + "|") }.values.reduce(0, +)
            samples[description] = (Date(), written)
        } else if samples[description] == nil { samples[description] = (Date(), written) }
    }
    private func resetProgress(_ id: String) {
        samples = samples.filter { !$0.key.hasPrefix(id + "|") }; rates = rates.filter { !$0.key.hasPrefix(id + "|") }
        byteProgress[id] = nil; transferRate[id] = nil
    }
    func pauseAll() { analysisTask?.cancel(); subtitleTask?.cancel(); preparationTask?.cancel(); downloadTranslator.cancel(); stopResolving(); for entry in entries where entry.localFile == nil { cancel(entry.id) } }
    func resumeAll() { enqueue(entries.filter { $0.localFile == nil }.map(\.playback), quality: library?.preferences.quality ?? "Auto") }
    func clearCompleted() { for entry in entries where entry.localFile != nil { delete(entry.id) } }
    func clearList() {
        let removable = Set(entries.filter { !["대기", "준비 중", "다운로드 중"].contains($0.status) && !busy($0.id) }.map(\.id))
        // Older versions only kept the central index; keep recovery metadata before hiding those entries.
        guard persist() else { return }
        entries.removeAll { removable.contains($0.id) }
        persist()
    }
    func restoreList() {
        do {
            let folders = try FileManager.default.contentsOfDirectory(at: Self.directory, includingPropertiesForKeys: nil)
            let values = folders.compactMap { folder -> DownloadEntry? in
                guard let data = try? Data(contentsOf: folder.appendingPathComponent("download-entry.json")),
                    let entry = try? JSONDecoder().decode(DownloadEntry.self, from: data), DownloadTransfer.valid(entry),
                    folder.lastPathComponent == entry.id, let file = entry.localFile,
                    FileManager.default.fileExists(atPath: folder.appendingPathComponent(file).path) else { return nil }
                return entry
            }
            importCompleted(values)
        } catch { self.error = error.localizedDescription }
    }
    func clearAll() async {
        guard !clearing else { return }
        clearing = true
        defer { clearing = false }
        pauseAll()
        for _ in 0..<100 {
            if tasks.isEmpty && backgroundTasks.isEmpty && resolutionTask == nil && subtitleTask == nil && preparationTask == nil && analysisTask == nil && !restoring { break }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        guard tasks.isEmpty && backgroundTasks.isEmpty && resolutionTask == nil && subtitleTask == nil && preparationTask == nil && analysisTask == nil && !restoring else {
            error = "다운로드 중단이 끝나면 다시 삭제하세요."; return
        }
        for entry in entries { delete(entry.id) }
    }
    func analyzeCompletedDownloads() {
        guard analysisTask == nil, library?.preferences.offlineAnalysis == true, !clearing else { return }
        let groups = Dictionary(grouping: entries.filter { $0.localFile != nil }, by: { $0.anime.id })
        let pending = groups.values.filter { group in group.count >= 2 && !analyzedGroups.contains(group.map(\.id).sorted().joined(separator: "|")) &&
            group.contains { !FileManager.default.fileExists(atPath: OfflineAnalyzer.directory.appendingPathComponent(SubtitleFiles.key($0.anime.id + "#" + $0.episodeID) + ".json").path) } }
        guard !pending.isEmpty else { return }
        analysisTask = Task(priority: .utility) {
            defer { analysisTask = nil; analysisStatus = nil }
            for group in pending {
                if Task.isCancelled { return }
                analysisStatus = group[0].anime.title + " · OP/ED 분석"
                do {
                    _ = try await OfflineAnalyzer.analyze(group)
                    try Task.checkCancellation()
                    analyzedGroups.insert(group.map(\.id).sorted().joined(separator: "|"))
                } catch is CancellationError { return }
                catch { analyzedGroups.insert(group.map(\.id).sorted().joined(separator: "|")); self.error = "OP/ED 분석: " + error.localizedDescription }
            }
        }
    }
    private func failed(_ id: String, error: Error) {
        guard entries.contains(where: { $0.id == id && ["준비 중", "다운로드 중"].contains($0.status) }) else { return }
        let attempt = (entries.first { $0.id == id }?.retries ?? 0) + 1
        update(id) { $0.status = "실패"; $0.retries = attempt; $0.error = error.localizedDescription + (attempt <= 2 ? (attempt == 1 ? " · 30초 뒤 다시 시도" : " · 2분 뒤 다시 시도") : "") }
        for (description, task) in backgroundTasks where description.hasPrefix(id + "|") { task.cancel() }
        guard attempt <= 2 else { return }
        retryTasks[id]?.cancel()
        retryTasks[id] = Task {
            do { try await Task.sleep(nanoseconds: attempt == 1 ? 30_000_000_000 : 120_000_000_000) } catch { return }
            while backgroundTasks.keys.contains(where: { $0.hasPrefix(id + "|") }) {
                do { try await Task.sleep(nanoseconds: 200_000_000) } catch { return }
            }
            guard let entry = entries.first(where: { $0.id == id && $0.status == "실패" }) else { return }
            // Keep existing segments, but resolve a fresh expiring media URL.
            enqueue([entry.playback], quality: entry.quality ?? "Auto")
        }
    }
    /// Subtitles, fonts, poster and skip metadata run after the media transfer, outside its slots.
    func prepareCompletedDownloads() {
        guard preparationTask == nil, library != nil, !clearing else { return }
        preparationTask = Task(priority: .utility) {
            defer { preparationTask = nil; if !Task.isCancelled { translateSavedDownloads() } }
            for entry in entries.reversed() where entry.localFile != nil && entry.subtitlesPrepared != true {
                if Task.isCancelled { return }
                do { try await prepareAssets(entry) }
                catch is CancellationError { return }
                catch { self.error = entry.title + ": " + error.localizedDescription }
            }
        }
    }
    private func prepareAssets(_ entry: DownloadEntry) async throws {
        guard let library else { return }
        let folder = Self.directory.appendingPathComponent(entry.id)
        guard entries.contains(where: { $0.id == entry.id }), FileManager.default.fileExists(atPath: folder.path) else { return }
        let preferences = library.preferences
        var files = entry.subtitleFiles ?? []
        var tracks = entry.subtitleTracks ?? []
        var primary = entry.primarySubtitle
        var scopedFonts: Set<URL> = []
        let siteKorean = DesktopSubtitlePolicy.siteKorean(source: entry.anime.source, tracks: entry.stream.subtitles, stream: entry.stream)
        func copy(_ file: URL, label: String, language: String, provider: String) throws -> String {
            scopedFonts.formUnion(SubtitleFiles.fonts(for: file))
            let name = "subtitle-" + String(SubtitleFiles.key(file.path).prefix(16)) + "." + file.pathExtension
            let target = folder.appendingPathComponent(name)
            if file.standardizedFileURL != target.standardizedFileURL && !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: file, to: target) }
            if !files.contains(name) { files.append(name) }
            if !tracks.contains(where: { $0.file == name }) { tracks.append(DownloadSubtitleTrack(file: name, label: label, language: language, provider: provider)) }
            return name
        }
        if preferences.downloadSubtitles != false && !DesktopSubtitlePolicy.burnedKorean(source: entry.anime.source, stream: entry.stream) {
            if let prepared = try await subtitlePreparer.prepare(entry.playback, tracks: entry.stream.subtitles, preferences: preferences, stream: entry.stream) {
                primary = try copy(prepared.0, label: prepared.1, language: SubtitleFiles.isKorean(prepared.0) ? "ko" : prepared.1 == "jimaku" ? "ja" : "", provider: prepared.1)
                EpisodeSubtitleStore.shared.save(prepared.0, item: entry.playback, provider: prepared.1, translated: EpisodeSubtitleStore.shared.list(entry.playback).contains { $0.file == prepared.0 && $0.translated })
            }
            for track in entry.stream.subtitles {
                try Task.checkCancellation()
                if let prepared = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]) {
                    for file in prepared { _ = try copy(file, label: track.label, language: track.language, provider: "reanime") }
                }
            }
            // Keep a separate translation source beside fansubs, never beside the site's own Korean track.
            if !siteKorean, preferences.translationPreferences(automatic: true) != nil,
               let source = try await subtitlePreparer.translationSource(entry.playback, tracks: entry.stream.subtitles, preferences: preferences) {
                let name = try copy(source.0, label: source.1, language: source.1 == "jimaku" ? "ja" : "", provider: source.1)
                EpisodeSubtitleStore.shared.save(source.0, item: entry.playback, provider: source.1, translated: false, behind: primary != nil)
                if primary == nil && !entry.stream.label.hasPrefix("SUB") { primary = name }
            }
            var fonts = entry.fontFiles ?? []
            for file in scopedFonts.sorted(by: { $0.path < $1.path }) {
                let name = "font-" + String(SubtitleFiles.key(file.path).prefix(12)) + "-" + file.lastPathComponent
                let target = folder.appendingPathComponent(name)
                if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: file, to: target) }
                if !fonts.contains(name) { fonts.append(name) }
            }
            update(entry.id) { $0.subtitleFiles = files; $0.subtitleTracks = tracks; $0.primarySubtitle = primary; $0.siteKorean = siteKorean; $0.fontFiles = fonts }
        }
        if entry.posterFile == nil, let url = URL(string: entry.anime.poster), ["http", "https"].contains(url.scheme ?? "") {
            if let response = try? await URLSession.shared.data(from: url), let http = response.1 as? HTTPURLResponse,
               (200..<300).contains(http.statusCode), http.mimeType?.hasPrefix("image/") == true, response.0.count <= 16_000_000 {
                let name = "poster." + (http.mimeType == "image/png" ? "png" : http.mimeType == "image/webp" ? "webp" : "jpg")
                try response.0.write(to: folder.appendingPathComponent(name), options: .atomic)
                update(entry.id) { $0.posterFile = name }
            }
        }
        if (entry.chapters ?? []).isEmpty {
            let service = IosServices()
            defer { service.close() }
            for attempt in 0..<2 {
                let segments: [SkipSegment] = await withCheckedContinuation { continuation in
                    service.skipSegments(anilistId: entry.anime.anime.anilistId?.int32Value ?? 0, malId: entry.anime.anime.malId?.int32Value ?? 0, episode: Int32(entry.number), duration: 0) { values, _ in continuation.resume(returning: values ?? []) }
                }
                if !segments.isEmpty {
                    let chapters = segments.map { OfflineChapter(type: $0.type, start: $0.start, end: $0.end, score: 1) }
                    update(entry.id) { $0.chapters = chapters }
                    try FileManager.default.createDirectory(at: OfflineAnalyzer.directory, withIntermediateDirectories: true)
                    try JSONEncoder().encode(chapters).write(to: OfflineAnalyzer.directory.appendingPathComponent(SubtitleFiles.key(entry.anime.id + "#" + entry.episodeID) + ".json"), options: .atomic)
                    break
                }
                if attempt == 0 { try await Task.sleep(nanoseconds: 500_000_000) }
            }
        }
        try Task.checkCancellation()
        update(entry.id) { $0.subtitlesPrepared = true }
    }
    func translateSavedDownloads() {
        guard subtitleTask == nil, let library, library.preferences.translateDownloads != false,
              library.preferences.translationPreferences() != nil, library.preferences.downloadSubtitles != false else { return }
        subtitleTask = Task(priority: .utility) {
            defer { subtitleTask = nil; translationStatus = nil }
            while let entry = entries.first(where: { $0.localFile != nil && !translatedDownloads.contains($0.id) }) {
                if Task.isCancelled { return }
                translatedDownloads.insert(entry.id)
                let folder = Self.directory.appendingPathComponent(entry.id)
                let files = (entry.subtitleFiles ?? []).map { folder.appendingPathComponent($0) }
                guard !DesktopSubtitlePolicy.burnedKorean(source: entry.anime.source, stream: entry.stream), entry.siteKorean != true,
                      !files.contains(where: { $0.lastPathComponent.hasPrefix("translated-") }),
                      let preferences = library.preferences.translationPreferences() else { continue }
                let tracks = entry.subtitleTracks ?? []
                let jimaku = library.preferences.autoTranslation ? tracks.first { $0.provider == "jimaku" } : nil
                let remote = tracks.map { RemoteSubtitle(label: $0.label, url: folder.appendingPathComponent($0.file), language: $0.language) }
                guard let original = jimaku.map({ folder.appendingPathComponent($0.file) }) ?? DesktopSubtitlePolicy.translationTrack(remote)?.url ?? (tracks.isEmpty ? files.first(where: { !SubtitleFiles.isKorean($0) }) : nil) else { continue }
                let item = entry.playback
                translationStatus = entry.anime.title + " · " + entry.title + " 자막 번역"
                EpisodeSubtitleStore.shared.save(original, item: item, provider: "download", translated: false)
                downloadTranslator.translate(original, preferences: preferences, anime: entry.anime, background: true) { [weak self] output in
                    guard let self, !output.lastPathComponent.hasPrefix("working-") else { return }
                    do {
                        let name = "translated-" + output.lastPathComponent
                        let target = folder.appendingPathComponent(name)
                        if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: output, to: target) }
                        self.update(entry.id) {
                            if !($0.subtitleFiles ?? []).contains(name) { $0.subtitleFiles = ($0.subtitleFiles ?? []) + [name] }
                            if let index = $0.subtitleTracks?.firstIndex(where: { folder.appendingPathComponent($0.file) == original }) { $0.subtitleTracks?[index].translatedFile = name }
                            if $0.primarySubtitle == original.lastPathComponent { $0.primarySubtitle = name }
                        }
                        EpisodeSubtitleStore.shared.save(output, item: item, provider: preferences.translationProvider, translated: true, behind: entry.primarySubtitle != original.lastPathComponent, original: original)
                    } catch { self.error = error.localizedDescription }
                }
                while downloadTranslator.running && !Task.isCancelled {
                    do { try await Task.sleep(nanoseconds: 500_000_000) } catch { downloadTranslator.cancel(); return }
                }
                if downloadTranslator.error != nil { translatedDownloads.remove(entry.id); error = downloadTranslator.error; return }
            }
        }
    }
    func portableEntries() -> [DownloadEntry] {
        entries.filter { $0.localFile != nil }.map { entry in var copy = entry; copy.stream.headers = [:]; copy.stream.referer = ""; copy.stream.manifestKey = nil; copy.stream.hlsManifest = nil; copy.stream.subtitles = []; copy.chapters = OfflineAnalyzer.chapters(animeID: entry.anime.id, episodeID: entry.episodeID); return copy }
    }
    func importCompleted(_ values: [DownloadEntry]) {
        for var entry in values where entry.localFile != nil {
            guard !entries.contains(where: { $0.id == entry.id }) else { continue }
            entry.status = "완료"; entry.error = nil; entries.append(entry)
            for name in entry.fontFiles ?? [] { _ = try? SubtitleFiles.importFont(Self.directory.appendingPathComponent(entry.id).appendingPathComponent(name)) }
            if let chapters = entry.chapters {
                try? FileManager.default.createDirectory(at: OfflineAnalyzer.directory, withIntermediateDirectories: true)
                try? JSONEncoder().encode(chapters).write(to: OfflineAnalyzer.directory.appendingPathComponent(SubtitleFiles.key(entry.anime.id + "#" + entry.episodeID) + ".json"), options: .atomic)
            }
        }
        persist()
    }
    private func update(_ id: String, change: (inout DownloadEntry) -> Void) {
        guard let index = entries.firstIndex(where: { $0.id == id }) else { return }
        change(&entries[index]); persist()
    }
    @discardableResult private func persist() -> Bool {
        do {
            let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
            try FileManager.default.createDirectory(at: Self.directory, withIntermediateDirectories: true)
            try encoder.encode(entries).write(to: file, options: .atomic)
            for entry in entries where entry.localFile != nil {
                let folder = Self.directory.appendingPathComponent(entry.id)
                let data = try encoder.encode(entry)
                if savedManifests[entry.id] != data && FileManager.default.fileExists(atPath: folder.path) {
                    try data.write(to: folder.appendingPathComponent("download-entry.json"), options: .atomic)
                    savedManifests[entry.id] = data
                }
            }
            return true
        } catch { self.error = error.localizedDescription; return false }
    }
}
struct DownloadsView: View {
    @EnvironmentObject private var downloads: DownloadStore
    @State private var confirmClear = false
    @State private var analyzing = false
    @State private var analysisMessage: String?
    @State private var collapsed: Set<String> = []
    @State private var removingGroup: String?
    private var groups: [String] { Dictionary(grouping: downloads.entries, by: { $0.anime.id }).keys.sorted { left, right in
        (downloads.entries.filter { $0.anime.id == left }.map(\.date).max() ?? .distantPast) > (downloads.entries.filter { $0.anime.id == right }.map(\.date).max() ?? .distantPast)
    } }
    var body: some View {
        NavigationStack {
            List {
                if downloads.pendingResolution > 0 {
                    VStack(alignment: .leading, spacing: 8) {
                        ProgressView("영상 준비 · \(downloads.pendingResolution)개 남음")
                        Text(downloads.resolvingTitle).font(.caption).foregroundStyle(.secondary)
                        Button("준비 중단") { downloads.stopResolving() }
                    }
                }
                Section("다운로드 관리") {
                    HStack { Button("모두 일시 중지") { downloads.pauseAll() }; Button("모두 이어받기") { downloads.resumeAll() } }.buttonStyle(.borderless)
                    NavigationLink("다운로드 폴더 내보내기·가져오기") { DownloadTransferView() }
                    Button("목록 비우기 · 파일 보존") { downloads.clearList() }
                    Button("보존한 다운로드 다시 찾기") { downloads.restoreList() }
                    Button("다운로드 전체 삭제", role: .destructive) { confirmClear = true }.disabled(downloads.clearing)
                    if downloads.clearing { ProgressView("중단 후 삭제 중") }
                }
                ForEach(groups, id: \.self) { key in
                  DisclosureGroup(isExpanded: Binding(get: { !collapsed.contains(key) }, set: { if $0 { collapsed.remove(key) } else { collapsed.insert(key) } })) {
                    ForEach(downloads.entries.filter { $0.anime.id == key }.sorted { $0.number < $1.number }) { entry in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(entry.anime.title).font(.headline); Text(entry.title)
                        Text(entry.status).foregroundStyle(.secondary)
                        if let bytes = entry.bytes { Text(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)).font(.caption) }
                        if entry.status == "다운로드 중", let rate = downloads.transferRate[entry.id] {
                            Text(ByteCountFormatter.string(fromByteCount: Int64(rate), countStyle: .file) + "/s").font(.caption)
                        }
                        if entry.total > 0 && entry.status == "다운로드 중" { ProgressView(value: Double(entry.completed), total: Double(max(entry.total, 1))) }
                        if let error = entry.error { Text(error).foregroundStyle(.red) }
                        HStack {
                            if entry.localFile != nil { PlaybackButton(item: entry.playback) { Text("재생") } }
                            if entry.status == "다운로드 중" { Button("중단") { downloads.cancel(entry.id) } }
                            else if entry.localFile == nil { Button("다시 시도") { downloads.retry(entry) } }
                            Button("삭제", role: .destructive) { downloads.delete(entry.id) }
                        }.buttonStyle(.borderless)
                    }
                }
                  } label: { groupHeader(key) }
                }
                if let status = downloads.analysisStatus { ProgressView(status) }
                if let status = downloads.translationStatus { ProgressView(status) }
                if let error = downloads.error { Text(error).foregroundStyle(.red) }
                if let analysisMessage { Text(analysisMessage) }
                Button("다운로드 회차 OP/ED 분석") {
                    analyzing = true
                    let entries = downloads.entries.filter { $0.localFile != nil }
                    Task {
                        do { let count = try await OfflineAnalyzer.analyze(entries); analysisMessage = "\(count)개 회차 분석 완료" }
                        catch { analysisMessage = error.localizedDescription }
                        analyzing = false
                    }
                }.disabled(analyzing)
                if analyzing { ProgressView() }
            }.navigationTitle("다운로드")
                .confirmationDialog("다운로드한 영상·자막과 대기 목록을 모두 삭제할까요?", isPresented: $confirmClear, titleVisibility: .visible) {
                    Button("전체 삭제", role: .destructive) { Task { await downloads.clearAll() } }
                }
                .confirmationDialog(removingGroup.map { (downloads.entries(group: $0).first?.anime.title ?? "") + "의 다운로드 \(downloads.entries(group: $0).count)개를 모두 삭제할까요? 저장한 영상과 자막도 함께 삭제됩니다." } ?? "",
                    isPresented: Binding(get: { removingGroup != nil }, set: { if !$0 { removingGroup = nil } }), titleVisibility: .visible) {
                    Button("전체 삭제", role: .destructive) { if let key = removingGroup { Task { await downloads.removeGroup(key) } }; removingGroup = nil }
                }
        }
    }
    /// One series (app.js downloadGroupCard): its episodes as ranges, what is saved, running or stopped, and actions for all of them.
    private func groupHeader(_ key: String) -> some View {
        let jobs = downloads.entries(group: key)
        let done = jobs.filter { $0.localFile != nil }.count
        let active = jobs.filter { $0.localFile == nil && ["대기", "준비 중", "다운로드 중"].contains($0.status) }.count
        let stopped = jobs.filter { $0.localFile == nil && ["중단됨", "실패"].contains($0.status) }.count
        let summary = [DownloadStore.episodeRanges(jobs.map(\.number)) + "화", done > 0 ? "\(done)개 저장됨" : "", active > 0 ? "\(active)개 받는 중" : "", stopped > 0 ? "\(stopped)개 멈춤" : ""].filter { !$0.isEmpty }.joined(separator: " · ")
        let progress = jobs.isEmpty ? 0 : jobs.reduce(0.0) { $0 + ($1.localFile != nil ? 1 : $1.total > 0 ? Double($1.completed) / Double($1.total) : 0) } / Double(jobs.count)
        return VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(jobs.first?.anime.title ?? key).font(.headline).lineLimit(2)
                Text("\(jobs.count)개 회차").font(.caption).foregroundStyle(.secondary)
            }
            Text(summary).font(.caption).foregroundStyle(.secondary)
            ProgressView(value: progress)
            HStack(spacing: 14) {
                if active > 0 { Button("전체 중지") { downloads.stopGroup(key) } }
                if stopped > 0 { Button("이어 받기") { downloads.resumeGroup(key) } }
                Button("전체 삭제", role: .destructive) { removingGroup = key }
            }.font(.caption).buttonStyle(.borderless)
        }.padding(.vertical, 4)
    }
}
