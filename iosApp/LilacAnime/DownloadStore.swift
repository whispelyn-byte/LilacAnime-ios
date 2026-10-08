import Foundation
import SwiftUI
import UIKit

struct DownloadPart: Codable {
    var url: URL
    var name: String
    var done = false
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
        item.localSubtitles = (subtitleFiles ?? []).map { DownloadStore.directory.appendingPathComponent(id).appendingPathComponent($0) }
        item.next = following(DownloadStore.shared.entries)
        return item
    }
    func following(_ entries: [DownloadEntry]) -> [PlaybackItem] {
        let order = Dictionary(anime.anime.episodes.enumerated().map { ($0.element.id, $0.offset) }, uniquingKeysWith: { first, _ in first })
        let available = entries.filter { $0.anime.id == anime.id && $0.localFile != nil }.sorted {
            if let left = order[$0.episodeID], let right = order[$1.episodeID] { return left < right }
            if $0.number != $1.number { return $0.number < $1.number }
            return $0.episodeID.localizedStandardCompare($1.episodeID) == .orderedAscending
        }
        guard let current = available.firstIndex(where: { $0.id == id }) else { return [] }
        return available.dropFirst(current + 1).map { $0.singlePlayback }
    }
    private var singlePlayback: PlaybackItem {
        var item = PlaybackItem(entry: WatchEntry(id: id, anime: anime, episodeID: episodeID, episodeTitle: title, number: number, watchURL: watchURL,
            directURL: localFile.map { DownloadStore.directory.appendingPathComponent(id).appendingPathComponent($0).absoluteString }, position: 0, duration: 0, updatedAt: date))
        item.localSubtitles = (subtitleFiles ?? []).map { DownloadStore.directory.appendingPathComponent(id).appendingPathComponent($0) }; return item
    }
}
@MainActor
final class DownloadStore: ObservableObject {
    nonisolated static let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads")
    static let shared = DownloadStore()
    @Published private(set) var entries: [DownloadEntry] = []
    weak var library: LibraryStore? { didSet { translateSavedDownloads(); analyzeCompletedDownloads() } }
    @Published var translationStatus: String?
    private let downloadTranslator = TranslationCoordinator()
    private var subtitleTask: Task<Void, Never>?
    private var translatedDownloads: Set<String> = []
    @Published var analysisStatus: String?
    @Published private(set) var clearing = false
    private var analysisTask: Task<Void, Never>?
    private var analyzedGroups: Set<String> = []
    @Published var byteProgress: [String: Double] = [:]
    @Published var transferRate: [String: Double] = [:]
    private var samples: [String: (Date, Int64)] = [:]
    private let subtitlePreparer = DesktopSubtitlePreparer()
    @Published var error: String?
    @Published private(set) var pendingResolution = 0
    @Published private(set) var resolvingTitle = ""
    private var resolutionTask: Task<Void, Never>?
    private let resolver = PlaybackResolver()
    private var tasks: [String: Task<Void, Never>] = [:]
    private var backgroundTasks: [String: URLSessionDownloadTask] = [:]
    private let delegate = BackgroundDownloadDelegate()
    private var restoring = true
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: "com.lilac.downloads")
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        configuration.httpMaximumConnectionsPerHost = 4
        return URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }()
    private var file: URL { Self.directory.appendingPathComponent("index.json") }
    init() {
        if let data = try? Data(contentsOf: file), let saved = try? JSONDecoder().decode([DownloadEntry].self, from: data) {
            entries = saved.map { entry in var item = entry; if item.status == "다운로드 중" || item.status == "준비 중" { item.status = "중단됨" }; return item }
        }
        delegate.owner = self
        session.getAllTasks { [weak self] found in
            Task { @MainActor in
                guard let self else { return }
                for task in found {
                    guard let task = task as? URLSessionDownloadTask, let description = task.taskDescription else { continue }
                    self.backgroundTasks[description] = task
                    let id = description.components(separatedBy: "|")[0]
                    self.update(id) { $0.status = "다운로드 중" }
                }
                self.restoring = false
            }
        }
    }
    func download(_ item: PlaybackItem, stream: ResolvedStream, quality: String = "Auto") {
        let id = SubtitleFiles.key(item.anime.id + "#" + item.episodeID)
        guard !busy(id) else { return }
        if !entries.contains(where: { $0.id == id }) {
            entries.insert(DownloadEntry(id: id, anime: item.anime, episodeID: item.episodeID, title: item.title,
                number: item.number, watchURL: item.watchURL.absoluteString, stream: stream, quality: quality), at: 0)
        } else {
            update(id) { entry in
                if entry.stream.url != stream.url || entry.stream.manifestKey != stream.manifestKey {
                    entry.parts = nil; entry.rootFile = nil
                }
                entry.stream = stream; entry.quality = quality
            }
        }
        start(id)
    }
    func enqueue(_ items: [PlaybackItem], quality: String) {
        guard resolutionTask == nil else { error = "다른 회차의 영상 주소를 준비 중입니다."; return }
        pendingResolution = items.count; error = nil
        resolutionTask = Task {
            defer { resolver.cancel(); resolutionTask = nil; pendingResolution = 0; resolvingTitle = "" }
            for item in items {
                if Task.isCancelled { return }
                resolvingTitle = item.anime.title + " · " + item.title
                let id = SubtitleFiles.key(item.anime.id + "#" + item.episodeID)
                if entries.contains(where: { $0.id == id && ($0.localFile != nil || busy(id)) }) { pendingResolution -= 1; continue }
                resolver.resolve(item)
                while resolver.loading && resolver.streams.isEmpty && !Task.isCancelled {
                    do { try await Task.sleep(nanoseconds: 200_000_000) } catch { return }
                }
                if Task.isCancelled { return }
                let chosen = item.anime.source == "miruro" ? await DownloadStreamSelector.choose(resolver.streams, quality: quality) : resolver.streams.first
                if Task.isCancelled { return }
                if let stream = chosen { download(item, stream: stream, quality: quality) }
                else { error = item.title + ": " + (resolver.error ?? "영상 주소를 찾지 못했습니다.") }
                pendingResolution -= 1
            }
        }
    }
    func stopResolving() { resolutionTask?.cancel(); resolver.cancel() }
    private func busy(_ id: String) -> Bool {
        restoring || tasks[id] != nil || backgroundTasks.keys.contains { $0.hasPrefix(id + "|") }
    }
    func retry(_ entry: DownloadEntry) { if entry.status == "실패" { enqueue([entry.playback], quality: entry.quality ?? "Auto") } else { start(entry.id) } }
    private func start(_ id: String) {
        guard !busy(id), let entry = entries.first(where: { $0.id == id }), entry.localFile == nil else { return }
        update(id) { $0.status = "준비 중"; $0.error = nil }
        tasks[id] = Task {
            let background = UIApplication.shared.beginBackgroundTask(withName: "LilacManifestPlan") { [weak self] in Task { @MainActor in self?.cancel(id) } }
            defer { if background != .invalid { UIApplication.shared.endBackgroundTask(background) }; tasks[id] = nil }
            do {
                let folder = Self.directory.appendingPathComponent(id)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let root: String
                var parts: [DownloadPart]
                if let saved = entry.parts, let file = entry.rootFile {
                    root = file; parts = saved
                } else if ["mp4", "mkv", "webm", "m4v"].contains(entry.stream.url.pathExtension.lowercased()) {
                    root = "video." + entry.stream.url.pathExtension.lowercased()
                    parts = [DownloadPart(url: entry.stream.url, name: root)]
                } else {
                    let planner = HLSPlanBuilder(stream: entry.stream, folder: folder, quality: entry.quality ?? "Auto")
                    let plan = try await planner.build()
                    root = plan.0; parts = plan.1
                }
                try Task.checkCancellation()
                for index in parts.indices {
                    parts[index].done = FileManager.default.fileExists(atPath: folder.appendingPathComponent(parts[index].name).path)
                }
                var subtitles = entry.subtitleFiles ?? []
                if subtitles.isEmpty && library?.preferences.downloadSubtitles != false {
                    let tracks = entry.stream.subtitles.sorted { lhs, rhs in
                        func rank(_ track: RemoteSubtitle) -> Int {
                            let name = track.label.lowercased()
                            return track.language.lowercased().hasPrefix("ko") || name.contains("한국") || name.contains("korean") ? 0 : 1
                        }
                        return rank(lhs) < rank(rhs)
                    }
                    for (index, track) in tracks.enumerated() {
                        do {
                            let prepared = try await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:])
                            for (part, file) in prepared.enumerated() {
                                let label = track.label.replacingOccurrences(of: "[^\\p{L}\\p{N}_-]", with: "_", options: .regularExpression)
                                let name = "subtitle-\(index)-\(part)-" + String(label.prefix(60)) + "." + file.pathExtension
                                let destination = folder.appendingPathComponent(name)
                                if !FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.copyItem(at: file, to: destination) }
                                subtitles.append(name)
                            }
                        } catch { /* Keep video download available when a subtitle host fails. */ }
                    }
                    let preferences = library?.preferences ?? AppPreferences()
                    var extra = EpisodeSubtitleStore.shared.list(entry.playback).compactMap(\.file)
                    if extra.isEmpty, let prepared = try? await subtitlePreparer.prepare(entry.playback, tracks: entry.stream.subtitles, preferences: preferences) { extra.append(prepared.0) }
                    for file in extra {
                        let name = "saved-" + file.lastPathComponent
                        let target = folder.appendingPathComponent(name)
                        if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: file, to: target) }
                        if !subtitles.contains(name) { subtitles.append(name) }
                    }
                    update(id) { $0.subtitleFiles = subtitles }
                }
                if library?.preferences.downloadSubtitles != false {
                    var fonts: [String] = []
                    for file in (try? FileManager.default.contentsOfDirectory(at: SubtitleFiles.fontDirectory, includingPropertiesForKeys: nil)) ?? [] {
                        guard ["ttf","otf","ttc"].contains(file.pathExtension.lowercased()) else { continue }
                        let name = "font-" + String(SubtitleFiles.key(file.lastPathComponent).prefix(12)) + "-" + file.lastPathComponent
                        let target = folder.appendingPathComponent(name)
                        if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: file, to: target) }
                        fonts.append(name)
                    }
                    update(id) { $0.fontFiles = fonts }
                }
                try Task.checkCancellation()
                update(id) { $0.rootFile = root; $0.parts = parts; $0.completed = parts.filter(\.done).count; $0.total = parts.count; $0.status = "다운로드 중" }
                if parts.allSatisfy(\.done) { update(id) { $0.localFile = root; $0.status = "완료" }; translateSavedDownloads(); analyzeCompletedDownloads(); return }
                pump()
            } catch is CancellationError { update(id) { $0.status = "중단됨" } }
            catch { update(id) { $0.status = "실패"; $0.error = error.localizedDescription } }
        }
    }
    func cancel(_ id: String) {
        tasks[id]?.cancel()
        for (description, task) in backgroundTasks where description.hasPrefix(id + "|") {
            task.cancel { data in
                if let data, let destination = BackgroundDownloadDelegate.destination(description, suffix: ".resume") { try? data.write(to: destination, options: .atomic) }
            }
        }
        update(id) { $0.status = "중단됨" }
    }
    func delete(_ id: String) {
        guard !busy(id) else { cancel(id); error = "다운로드를 중단한 뒤 삭제하세요."; return }
        do {
            let folder = Self.directory.appendingPathComponent(id)
            if FileManager.default.fileExists(atPath: folder.path) { try FileManager.default.removeItem(at: folder) }
            entries.removeAll { $0.id == id }; persist()
        } catch { self.error = error.localizedDescription }
    }
    func backgroundFinished(_ description: String, staged: URL, mime: String) {
        let fields = description.components(separatedBy: "|")
        guard fields.count == 2, let index = entries.firstIndex(where: { $0.id == fields[0] }),
              let partIndex = entries[index].parts?.firstIndex(where: { $0.name == fields[1] }),
              let target = BackgroundDownloadDelegate.destination(description) else { try? FileManager.default.removeItem(at: staged); return }
        do {
            if entries[index].stream.manifestKey != nil && (fields[1].hasSuffix(".ts") || mime.hasPrefix("image/")) {
                let decoded = HLSData.fragment(try Data(contentsOf: staged))
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
                entries[index].localFile = entries[index].rootFile; entries[index].status = "완료"; entries[index].error = nil
                translateSavedDownloads(); analyzeCompletedDownloads()
            }
            persist()
        } catch { backgroundFailed(description, error: error) }
    }
    func backgroundFailed(_ description: String, error: Error) {
        let id = description.components(separatedBy: "|")[0]
        let ns = error as NSError
        if let resume = ns.userInfo["NSURLSessionDownloadTaskResumeData"] as? Data,
           let path = BackgroundDownloadDelegate.destination(description, suffix: ".resume") { try? resume.write(to: path, options: .atomic) }
        if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled { update(id) { if $0.status != "완료" { $0.status = "중단됨" } } }
        else { update(id) { $0.status = "실패"; $0.error = error.localizedDescription } }
    }
    func backgroundCompleted(_ description: String) { backgroundTasks.removeValue(forKey: description); pump() }
    private func pump() {
        guard !restoring else { return }
        for entry in entries where entry.status == "다운로드 중" && entry.localFile == nil {
            for part in entry.parts ?? [] where !part.done {
                guard backgroundTasks.count < 6 else { return }
                let description = entry.id + "|" + part.name
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
        byteProgress[id] = expected > 0 ? Double(written) / Double(expected) : 0
        if let sample = samples[description], Date().timeIntervalSince(sample.0) >= 1 {
            transferRate[id] = Double(max(0, written - sample.1)) / Date().timeIntervalSince(sample.0)
            samples[description] = (Date(), written)
        } else if samples[description] == nil { samples[description] = (Date(), written) }
    }
    func pauseAll() { analysisTask?.cancel(); subtitleTask?.cancel(); downloadTranslator.cancel(); stopResolving(); for entry in entries where entry.localFile == nil { cancel(entry.id) } }
    func resumeAll() { enqueue(entries.filter { $0.localFile == nil }.map(\.playback), quality: library?.preferences.quality ?? "Auto") }
    func clearCompleted() { for entry in entries where entry.localFile != nil { delete(entry.id) } }
    func clearAll() async {
        guard !clearing else { return }
        clearing = true
        defer { clearing = false }
        pauseAll()
        for _ in 0..<100 {
            if tasks.isEmpty && backgroundTasks.isEmpty && resolutionTask == nil && subtitleTask == nil && analysisTask == nil && !restoring { break }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        guard tasks.isEmpty && backgroundTasks.isEmpty && resolutionTask == nil && subtitleTask == nil && analysisTask == nil && !restoring else {
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
    func translateSavedDownloads() {
        guard subtitleTask == nil, let library, library.preferences.translateDownloads != false,
              library.preferences.autoTranslation, library.preferences.downloadSubtitles != false else { return }
        subtitleTask = Task(priority: .utility) {
            defer { subtitleTask = nil; translationStatus = nil }
            while let entry = entries.first(where: { $0.localFile != nil && !translatedDownloads.contains($0.id) }) {
                if Task.isCancelled { return }
                translatedDownloads.insert(entry.id)
                let folder = Self.directory.appendingPathComponent(entry.id)
                let files = (entry.subtitleFiles ?? []).map { folder.appendingPathComponent($0) }
                guard !files.contains(where: { $0.lastPathComponent.hasPrefix("translated-") }),
                      let original = files.first(where: { !SubtitleFiles.isKorean($0) }) else { continue }
                let item = entry.playback
                translationStatus = entry.anime.title + " · " + entry.title + " 자막 번역"
                EpisodeSubtitleStore.shared.save(original, item: item, provider: "다운로드", translated: false)
                downloadTranslator.translate(original, preferences: library.preferences, anime: entry.anime, background: true) { [weak self] output in
                    guard let self, !output.lastPathComponent.hasPrefix("working-") else { return }
                    do {
                        let name = "translated-" + output.lastPathComponent
                        let target = folder.appendingPathComponent(name)
                        if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: output, to: target) }
                        self.update(entry.id) { if !($0.subtitleFiles ?? []).contains(name) { $0.subtitleFiles = ($0.subtitleFiles ?? []) + [name] } }
                        EpisodeSubtitleStore.shared.save(output, item: item, provider: library.preferences.translationProvider, translated: true)
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
        entries.filter { $0.localFile != nil }.map { entry in var copy = entry; copy.stream.headers = [:]; copy.stream.referer = ""; copy.stream.manifestKey = nil; copy.stream.subtitles = []; copy.chapters = OfflineAnalyzer.chapters(animeID: entry.anime.id, episodeID: entry.episodeID); return copy }
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
    private func persist() {
        do {
            try FileManager.default.createDirectory(at: Self.directory, withIntermediateDirectories: true)
            try JSONEncoder().encode(entries).write(to: file, options: .atomic)
        } catch { self.error = error.localizedDescription }
    }
}
struct DownloadsView: View {
    @EnvironmentObject private var downloads: DownloadStore
    @State private var confirmClear = false
    @State private var analyzing = false
    @State private var analysisMessage: String?
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
                    HStack { Button("모두 일시 중지") { downloads.pauseAll() }; Button("모두 이어받기") { downloads.resumeAll() } }
                    NavigationLink("다운로드 폴더 내보내기·가져오기") { DownloadTransferView() }
                    Button("다운로드 전체 삭제", role: .destructive) { confirmClear = true }.disabled(downloads.clearing)
                    if downloads.clearing { ProgressView("중단 후 삭제 중") }
                }
                ForEach(Array(Dictionary(grouping: downloads.entries, by: { $0.anime.id }).keys).sorted(), id: \.self) { key in
                  Section(downloads.entries.first { $0.anime.id == key }?.anime.title ?? key) {
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
                            if entry.localFile != nil { NavigationLink("재생") { EpisodePlayerView(item: entry.playback) } }
                            if entry.status == "다운로드 중" { Button("중단") { downloads.cancel(entry.id) } }
                            else if entry.localFile == nil { Button("다시 시도") { downloads.retry(entry) } }
                            Button("삭제", role: .destructive) { downloads.delete(entry.id) }
                        }
                    }
                }
                  }
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
        }
    }
}
