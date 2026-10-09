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
    static let shared = EpisodeSubtitleStore()
    @Published private(set) var records: [SavedSubtitle] = []
    @Published var error: String?
    private let index = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("episode-subtitles.json")
    init() { if let data = try? Data(contentsOf: index), let saved = try? JSONDecoder().decode([SavedSubtitle].self, from: data) { records = saved } }
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
            let relative = String(target.path.dropFirst(prefix.count))
            let id = SubtitleFiles.key(episodeKey + relative)
            let originalRelative = original.flatMap { value -> String? in
                if value.path.hasPrefix(prefix) { return String(value.path.dropFirst(prefix.count)) }
                return records.first { $0.episodeKey == episodeKey && !$0.translated && $0.name == value.lastPathComponent }?.relativeFile
            }
            records.removeAll { $0.id == id || (translated && $0.episodeKey == episodeKey && $0.translated && $0.provider == provider) }
            records.append(SavedSubtitle(id: id, episodeKey: episodeKey, name: file.lastPathComponent, relativeFile: relative, provider: provider, translated: translated, date: Date(), behind: behind, originalFile: originalRelative))
            let keep = Set(list(item).prefix(20).map(\.id))
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
    private func persist() { do { try JSONEncoder().encode(records).write(to: index, options: .atomic) } catch { self.error = error.localizedDescription } }
    func protectedFiles(library: LibraryStore) -> Set<URL> {
        Set(records.compactMap(\.file) + records.compactMap(\.original) + library.subtitleChoices.values.compactMap { $0.relativeFile.map { SubtitleFiles.root.appendingPathComponent($0).standardizedFileURL } })
    }
}
enum SubtitleCache {
    static func files() -> [URL] {
        (FileManager.default.enumerator(at: SubtitleFiles.root, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey])?.allObjects as? [URL] ?? [])
            .filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
    }
    static var bytes: Int64 { files().reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) } }
    @MainActor static func clean(library: LibraryStore, all: Bool) throws {
        let protected = all ? Set<URL>() : EpisodeSubtitleStore.shared.protectedFiles(library: library)
        for file in files() {
            if file.path.hasPrefix(SubtitleFiles.fontDirectory.path + "/") || file.lastPathComponent.hasPrefix("working-") { continue }
            if protected.contains(file.standardizedFileURL) { continue }
            let date = (try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date.distantPast
            if DesktopSubtitlePolicy.cacheRemovable(age: Date().timeIntervalSince(date), all: all, protected: protected.contains(file.standardizedFileURL)) { try FileManager.default.removeItem(at: file) }
        }
        if all { EpisodeSubtitleStore.shared.clear(); library.clearSubtitleFiles() }
    }
}
struct SubtitleStorageView: View {
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var saved = EpisodeSubtitleStore.shared
    @State private var size = SubtitleCache.bytes
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
            }
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
            .confirmationDialog("저장한 원본·번역 자막도 삭제합니다. 글꼴과 다운로드 폴더에 복사한 자막은 보존됩니다.", isPresented: $confirm, titleVisibility: .visible) {
                Button("자막 캐시 전체 삭제", role: .destructive) { clean(true) }
            }
    }
    private func clean(_ all: Bool) { do { try SubtitleCache.clean(library: library, all: all); size = SubtitleCache.bytes } catch { self.error = error.localizedDescription } }
}
