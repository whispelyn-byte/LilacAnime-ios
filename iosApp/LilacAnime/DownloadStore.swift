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
    var status = "대기"
    var completed = 0
    var total = 0
    var error: String?
    var date = Date()
    var playback: PlaybackItem {
        var item = PlaybackItem(entry: WatchEntry(id: id, anime: anime, episodeID: episodeID, episodeTitle: title, number: number,
            watchURL: watchURL, directURL: localFile.map { DownloadStore.directory.appendingPathComponent(id).appendingPathComponent($0).absoluteString },
            position: 0, duration: 0, updatedAt: date))
        item.localSubtitles = (subtitleFiles ?? []).map { DownloadStore.directory.appendingPathComponent(id).appendingPathComponent($0) }
        return item
    }
}
@MainActor
final class DownloadStore: ObservableObject {
    nonisolated static let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads")
    static let shared = DownloadStore()
    @Published private(set) var entries: [DownloadEntry] = []
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
                if let stream = resolver.streams.first { download(item, stream: stream, quality: quality) }
                else { error = item.title + ": " + (resolver.error ?? "영상 주소를 찾지 못했습니다.") }
                pendingResolution -= 1
            }
        }
    }
    func stopResolving() { resolutionTask?.cancel(); resolver.cancel() }
    private func busy(_ id: String) -> Bool {
        restoring || tasks[id] != nil || backgroundTasks.keys.contains { $0.hasPrefix(id + "|") }
    }
    func retry(_ entry: DownloadEntry) { start(entry.id) }
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
                if subtitles.isEmpty {
                    for (index, track) in entry.stream.subtitles.enumerated() {
                        do {
                            let prepared = try await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:])
                            for (part, file) in prepared.enumerated() {
                                let name = "subtitle-\(index)-\(part)." + file.pathExtension
                                let destination = folder.appendingPathComponent(name)
                                if !FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.copyItem(at: file, to: destination) }
                                subtitles.append(name)
                            }
                        } catch { /* Keep video download available when a subtitle host fails. */ }
                    }
                    update(id) { $0.subtitleFiles = subtitles }
                }
                try Task.checkCancellation()
                update(id) { $0.rootFile = root; $0.parts = parts; $0.completed = parts.filter(\.done).count; $0.total = parts.count; $0.status = "다운로드 중" }
                if parts.allSatisfy(\.done) { update(id) { $0.localFile = root; $0.status = "완료" }; return }
                for part in parts where !part.done {
                    let description = id + "|" + part.name
                    let resumed = folder.appendingPathComponent(part.name + ".resume")
                    let task: URLSessionDownloadTask
                    if let data = try? Data(contentsOf: resumed) {
                        task = session.downloadTask(withResumeData: data)
                    } else {
                        var request = URLRequest(url: part.url)
                        for (key, value) in entry.stream.headers where key.lowercased() != "cookie" || part.url.host == entry.stream.url.host { request.setValue(value, forHTTPHeaderField: key) }
                        request.setValue(entry.stream.referer, forHTTPHeaderField: "Referer")
                        task = session.downloadTask(with: request)
                    }
                    task.taskDescription = description; backgroundTasks[description] = task; task.resume()
                }
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
            entries[index].parts?[partIndex].done = true
            entries[index].completed = entries[index].parts?.filter(\.done).count ?? 0
            if entries[index].parts?.allSatisfy(\.done) == true {
                entries[index].localFile = entries[index].rootFile; entries[index].status = "완료"; entries[index].error = nil
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
    func backgroundCompleted(_ description: String) { backgroundTasks.removeValue(forKey: description) }
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
                ForEach(downloads.entries) { entry in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(entry.anime.title).font(.headline); Text(entry.title)
                        Text(entry.status).foregroundStyle(.secondary)
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
        }
    }
}
