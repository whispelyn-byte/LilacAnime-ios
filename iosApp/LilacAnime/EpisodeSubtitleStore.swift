import Foundation
import SwiftUI

struct SavedSubtitle: Codable, Identifiable {
    var id: String; var episodeKey: String; var name: String; var relativeFile: String; var provider: String; var translated: Bool; var date: Date
    var behind: Bool? = nil
    var originalFile: String? = nil
    var file: URL? {
        let result = SubtitleFiles.root.appendingPathComponent(relativeFile).standardizedFileURL
        return result.path.hasPrefix(SubtitleFiles.root.path + "/") && FileManager.default.fileExists(atPath: result.path) ? result : nil
    }
    var original: URL? {
        guard let originalFile else { return nil }
        let result = SubtitleFiles.root.appendingPathComponent(originalFile).standardizedFileURL
        return result.path.hasPrefix(SubtitleFiles.root.path + "/") && FileManager.default.fileExists(atPath: result.path) ? result : nil
    }
}
@MainActor
final class EpisodeSubtitleStore: ObservableObject {
    static let shared = EpisodeSubtitleStore(deferLoading: true)
    @Published private(set) var records: [SavedSubtitle] = []
    @Published var error: String?
    private let index: URL
    private var loaded = false
    private var loading: Task<Void, Never>?
    init(index: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("episode-subtitles.json"), deferLoading: Bool = false) {
        self.index = index
        if deferLoading { return }
        records = Self.read(index); loaded = true
    }
    private nonisolated static func read(_ index: URL) -> [SavedSubtitle] {
        guard let data = try? Data(contentsOf: index), let saved = try? JSONDecoder().decode([SavedSubtitle].self, from: data) else { return [] }
        return saved
    }
    func load() async {
        if loaded { return }
        if let loading { await loading.value; return }
        let index = index
        let task = Task {
            records = await Task.detached(priority: .userInitiated) { Self.read(index) }.value
            loaded = true
        }
        loading = task
        await task.value
    }
    func list(_ item: PlaybackItem) -> [SavedSubtitle] { records.filter { $0.episodeKey == item.anime.id + "#" + item.episodeID && $0.file != nil }.sorted {
        if ($0.behind == true) != ($1.behind == true) { return $0.behind != true }
        return $0.behind == true ? $0.date < $1.date : $0.date > $1.date
    } }
    func save(_ file: URL, item: PlaybackItem, provider: String, translated: Bool, behind: Bool = false, original: URL? = nil) {
        guard !file.lastPathComponent.hasPrefix("working-") else { return }
        do {
            let prefix = SubtitleFiles.root.path + "/"
            var target = file
            if !file.standardizedFileURL.path.hasPrefix(prefix) {
                let folder = SubtitleFiles.root.appendingPathComponent("Saved")
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                target = folder.appendingPathComponent(SubtitleFiles.key(file.path) + "." + file.pathExtension)
                if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: file, to: target) }
            }
            let episodeKey = item.anime.id + "#" + item.episodeID
            try SubtitleFiles.associateFonts(SubtitleFiles.fonts(for: file) + (original.map { SubtitleFiles.fonts(for: $0) } ?? []), with: target)
            let relative = String(target.path.dropFirst(prefix.count))
            let id = SubtitleFiles.key(episodeKey + relative)
            let originalRelative = original.flatMap { value -> String? in
                if value.path.hasPrefix(prefix) { return String(value.path.dropFirst(prefix.count)) }
                return records.first { $0.episodeKey == episodeKey && !$0.translated && $0.name == value.lastPathComponent }?.relativeFile
            }
            records.removeAll { $0.id == id || (translated && $0.episodeKey == episodeKey && $0.translated && $0.provider == provider) }
            records.append(SavedSubtitle(id: id, episodeKey: episodeKey, name: file.lastPathComponent, relativeFile: relative, provider: provider, translated: translated, date: Date(), behind: behind, originalFile: originalRelative))
            let previous = list(item).filter { $0.id != id }
            let keep = Set(([id] + previous.prefix(19).map(\.id)))
            records.removeAll { $0.episodeKey == episodeKey && !keep.contains($0.id) }
            persist()
        } catch { self.error = error.localizedDescription }
    }
    func remove(_ id: String) {
        let file = records.first { $0.id == id }?.file
        records.removeAll { $0.id == id }; persist()
        if let file, !records.contains(where: { $0.file == file || $0.original == file }) { try? FileManager.default.removeItem(at: file) }
    }
    func clear() { records.removeAll(); persist() }
    func clear(before date: Date) { records.removeAll { $0.date <= date }; persist() }
    /// Saved at every translated batch while a subtitle is translated: written off the main thread, the latest list.
    private func persist() {
        let records = records
        BackgroundWriter.shared.write(index, after: 0.5, failed: { [weak self] message in self?.error = message }) { try JSONEncoder().encode(records) }
    }
    func protectedFiles(library: LibraryStore) async -> Set<URL> {
        let records = records, choices = library.subtitleChoices
        return await Task.detached(priority: .utility) {
            let subtitles = records.compactMap(\.file) + records.compactMap(\.original) + choices.values.compactMap { $0.relativeFile.map { SubtitleFiles.root.appendingPathComponent($0).standardizedFileURL } }
            return Set(subtitles + subtitles.map { $0.appendingPathExtension("fonts.json") } + subtitles.flatMap { SubtitleFiles.fonts(for: $0) })
        }.value
    }
}
enum SubtitleCache {
    static func files(in root: URL = SubtitleFiles.root) -> [URL] {
        (FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey, .creationDateKey])?.allObjects as? [URL] ?? [])
            .filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
    }
    static func bytes() async -> Int64 {
        await Task.detached(priority: .utility) { files().reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) } }.value
    }
    @MainActor private static var cleaning = false
    @MainActor static func clean(library: LibraryStore, all: Bool) async throws {
        guard !cleaning else { throw SubtitleFiles.failure("자막 캐시를 정리하고 있습니다.") }
        cleaning = true
        defer { cleaning = false }
        await EpisodeSubtitleStore.shared.load()
        let started = Date(), choices = library.subtitleChoices
        let protected = all ? Set<URL>() : await EpisodeSubtitleStore.shared.protectedFiles(library: library)
        try await Task.detached(priority: .utility) {
            try removeFiles(in: SubtitleFiles.root, fontDirectory: SubtitleFiles.fontDirectory, protected: protected, all: all, before: started)
        }.value
        if all { EpisodeSubtitleStore.shared.clear(before: started); library.clearSubtitleFiles(matching: choices) }
    }
    static func removeFiles(in root: URL, fontDirectory: URL, protected: Set<URL>, all: Bool, before started: Date) throws {
        for file in files(in: root) {
            if file.path.hasPrefix(fontDirectory.path + "/") || file.lastPathComponent.hasPrefix("working-") { continue }
            if protected.contains(file.standardizedFileURL) { continue }
            let date = (try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date.distantPast
            // Files created or updated during cleanup belong to ongoing playback/translation.
            let created = (try? file.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? Date.distantPast
            if date > started || created > started { continue }
            if DesktopSubtitlePolicy.cacheRemovable(age: Date().timeIntervalSince(date), all: all, protected: protected.contains(file.standardizedFileURL)) { try FileManager.default.removeItem(at: file) }
        }
    }
}
struct SubtitleStorageView: View {
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var saved = EpisodeSubtitleStore.shared
    @State private var size: Int64 = 0
    @State private var cleaning = false
    @State private var error: String?
    @State private var confirm = false
    var body: some View {
        List {
            Section("자막 캐시") {
                Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                Text("정리는 저장한 자막·현재 선택한 자막·글꼴을 보존하고 1시간 이상 지난 임시 파일을 지웁니다.").font(.caption)
                Button("사용하지 않는 오래된 캐시 정리") { clean(false) }
                Button("모든 자막·번역 캐시 삭제", role: .destructive) { confirm = true }
                if let error { Text(error).foregroundStyle(.red) }
                if cleaning { ProgressView("캐시 정리 중…") }
            }.disabled(cleaning)
            Section("회차별 저장 자막") {
                ForEach(saved.records.sorted { $0.date > $1.date }) { record in
                    VStack(alignment: .leading) {
                        Text(record.name); Text(record.episodeKey).font(.caption).foregroundStyle(.secondary)
                        Text(record.translated ? "번역 자막 · " + record.provider : "원본 자막 · " + record.provider).font(.caption)
                        if let file = record.file { ShareLink(item: file) { Label("내보내기", systemImage: "square.and.arrow.up") } }
                    }.swipeActions { Button("목록에서 삭제", role: .destructive) { saved.remove(record.id) } }
                }
            }
        }.navigationTitle("저장 자막·캐시")
            .task { size = await SubtitleCache.bytes() }
            .confirmationDialog("저장한 원본·번역 자막도 삭제합니다. 글꼴과 다운로드 폴더에 복사한 자막은 보존됩니다.", isPresented: $confirm, titleVisibility: .visible) {
                Button("자막 캐시 전체 삭제", role: .destructive) { clean(true) }
            }
    }
    private func clean(_ all: Bool) {
        guard !cleaning else { return }
        cleaning = true
        Task {
            defer { cleaning = false }
            do { try await SubtitleCache.clean(library: library, all: all); size = await SubtitleCache.bytes() }
            catch { self.error = error.localizedDescription }
        }
    }
}
