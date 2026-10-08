import Foundation
import SwiftUI
import UniformTypeIdentifiers

enum DownloadTransfer {
    static func export(_ entries: [DownloadEntry], to destination: URL) async throws -> URL {
        try await Task.detached(priority: .utility) {
            let access = destination.startAccessingSecurityScopedResource()
            defer { if access { destination.stopAccessingSecurityScopedResource() } }
            let folder = destination.appendingPathComponent("LilacDownloads-" + String(Int(Date().timeIntervalSince1970)))
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            do {
                for entry in entries {
                    try Task.checkCancellation()
                    guard valid(entry) else { throw SubtitleFiles.failure("다운로드 목록에 잘못된 파일 경로가 있습니다.") }
                    let source = DownloadStore.directory.appendingPathComponent(entry.id)
                    let target = folder.appendingPathComponent(entry.id)
                    try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
                    let resources = try files(entry, in: source)
                    for name in resources {
                        let file = source.appendingPathComponent(name)
                        let attributes = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
                        guard attributes.isRegularFile == true, attributes.isSymbolicLink != true else { throw SubtitleFiles.failure("다운로드 파일을 읽을 수 없습니다.") }
                        try FileManager.default.copyItem(at: file, to: target.appendingPathComponent(name))
                    }
                }
                try JSONEncoder().encode(entries).write(to: folder.appendingPathComponent("index.json"), options: .atomic)
                return folder
            } catch {
                try? FileManager.default.removeItem(at: folder)
                throw error
            }
        }.value
    }
    static func load(from folder: URL, skipping existing: Set<String>) async throws -> [DownloadEntry] {
        try await Task.detached(priority: .utility) {
            let access = folder.startAccessingSecurityScopedResource()
            defer { if access { folder.stopAccessingSecurityScopedResource() } }
            let index = folder.appendingPathComponent("index.json")
            guard (try index.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 16_000_000 else { throw SubtitleFiles.failure("다운로드 목록이 너무 큽니다.") }
            let entries = try JSONDecoder().decode([DownloadEntry].self, from: Data(contentsOf: index))
            guard entries.count <= 10000 else { throw SubtitleFiles.failure("다운로드 회차가 너무 많습니다.") }
            var imported: [DownloadEntry] = []
            for entry in entries where !existing.contains(entry.id) {
                try Task.checkCancellation()
                guard valid(entry), let root = entry.localFile else { throw SubtitleFiles.failure("다운로드 목록에 잘못된 파일 경로가 있습니다.") }
                let source = folder.appendingPathComponent(entry.id)
                guard try source.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { throw SubtitleFiles.failure("다운로드 폴더가 심볼릭 링크입니다.") }
                let resources = try FileManager.default.contentsOfDirectory(at: source, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey])
                guard resources.count <= 11000 else { throw SubtitleFiles.failure("한 회차의 파일이 너무 많습니다.") }
                for file in resources {
                    let attributes = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
                    guard attributes.isRegularFile == true, attributes.isSymbolicLink != true, safeName(file.lastPathComponent) else { throw SubtitleFiles.failure("다운로드 폴더에는 일반 파일만 넣을 수 있습니다.") }
                }
                guard FileManager.default.fileExists(atPath: source.appendingPathComponent(root).path),
                      ((entry.subtitleFiles ?? []) + (entry.fontFiles ?? [])).allSatisfy({ FileManager.default.fileExists(atPath: source.appendingPathComponent($0).path) }) else { throw SubtitleFiles.failure("영상 또는 자막 파일이 빠져 있습니다.") }
                for manifest in resources where manifest.pathExtension == "m3u8" {
                    let content = try String(contentsOf: manifest, encoding: .utf8)
                    let lines = content.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && !$0.hasPrefix("#") }
                    let regex = try NSRegularExpression(pattern: "URI=\"([^\"]+)\"")
                    let uri = regex.matches(in: content, range: NSRange(content.startIndex..., in: content)).compactMap { Range($0.range(at: 1), in: content).map { String(content[$0]) } }
                    guard (lines + uri).allSatisfy({ safeName($0) && FileManager.default.fileExists(atPath: source.appendingPathComponent($0).path) }) else { throw SubtitleFiles.failure("오프라인 HLS에 잘못된 조각 경로가 있습니다.") }
                }
                try FileManager.default.createDirectory(at: DownloadStore.directory, withIntermediateDirectories: true)
                let target = DownloadStore.directory.appendingPathComponent(entry.id)
                guard !FileManager.default.fileExists(atPath: target.path) else { continue }
                let staging = DownloadStore.directory.appendingPathComponent("import-" + UUID().uuidString)
                do { try FileManager.default.copyItem(at: source, to: staging); try FileManager.default.moveItem(at: staging, to: target) }
                catch { try? FileManager.default.removeItem(at: staging); throw error }
                imported.append(entry)
            }
            return imported
        }.value
    }
    static func safeName(_ name: String) -> Bool {
        !name.isEmpty && name != "." && name != ".." && !name.contains("/") && !name.contains("\\") && !name.contains(":") && !name.contains("\0")
    }
    static func files(_ entry: DownloadEntry, in source: URL) throws -> Set<String> {
        var result: Set<String> = []
        if let file = entry.localFile { result.insert(file) }
        if let root = entry.rootFile { result.insert(root) }
        for part in entry.parts ?? [] { result.insert(part.name) }
        result.formUnion(entry.subtitleFiles ?? [])
        result.formUnion(entry.fontFiles ?? [])
        var pending: [String] = Array(result.filter { $0.hasSuffix(".m3u8") })
        var visited: Set<String> = []
        let regex = try NSRegularExpression(pattern: "URI=\"([^\"]+)\"", options: .caseInsensitive)
        while let name = pending.popLast() {
            guard safeName(name), visited.insert(name).inserted else { continue }
            let content = try String(contentsOf: source.appendingPathComponent(name), encoding: .utf8)
            let rawLines = content.components(separatedBy: CharacterSet.newlines)
            let lines: [String] = rawLines.map { $0.trimmingCharacters(in: CharacterSet.whitespaces) }.filter { !$0.isEmpty && !$0.hasPrefix("#") }
            let uri: [String] = regex.matches(in: content, range: NSRange(content.startIndex..., in: content)).compactMap { Range($0.range(at: 1), in: content).map { String(content[$0]) } }
            for resource in lines + uri {
                guard safeName(resource) else { throw SubtitleFiles.failure("오프라인 HLS 파일 경로가 잘못되었습니다.") }
                result.insert(resource)
                if resource.hasSuffix(".m3u8") && !visited.contains(resource) { pending.append(resource) }
            }
            guard result.count <= 11000 else { throw SubtitleFiles.failure("한 회차의 파일이 너무 많습니다.") }
        }
        return result
    }
    static func valid(_ entry: DownloadEntry) -> Bool {
        entry.id == SubtitleFiles.key(entry.anime.id + "#" + entry.episodeID) && entry.localFile.map(safeName) == true &&
        (entry.rootFile == nil || entry.rootFile.map(safeName) == true) && ((entry.subtitleFiles ?? []) + (entry.fontFiles ?? [])).allSatisfy(safeName) && (entry.parts ?? []).allSatisfy { safeName($0.name) }
    }
}
struct DownloadTransferView: View {
    @EnvironmentObject private var downloads: DownloadStore
    @State private var picker = false
    @State private var importing = false
    @State private var busy = false
    @State private var message: String?
    var body: some View {
        List {
            Text("영상·자막·OP/ED 정보와 회차 목록을 폴더로 내보내고, 다른 설치에서 다시 가져옵니다. 앱 내부 다운로드도 그대로 남습니다.")
            Button("완료한 다운로드를 폴더로 내보내기") { importing = false; picker = true }.disabled(busy)
            Button("내보낸 다운로드 폴더 가져오기") { importing = true; picker = true }.disabled(busy)
            if busy { ProgressView() }
            if let message { Text(message) }
        }.navigationTitle("다운로드 폴더")
            .fileImporter(isPresented: $picker, allowedContentTypes: [.folder]) { result in
                do {
                    let folder = try result.get(); busy = true
                    Task {
                        do {
                            if importing {
                                let entries = try await DownloadTransfer.load(from: folder, skipping: Set(downloads.entries.map(\.id)))
                                downloads.importCompleted(entries); message = "\(entries.count)개 회차를 가져왔습니다."
                            } else {
                                let target = try await DownloadTransfer.export(downloads.portableEntries(), to: folder)
                                message = "내보냈습니다: " + target.lastPathComponent
                            }
                        } catch { message = error.localizedDescription }
                        busy = false
                    }
                } catch { message = error.localizedDescription }
            }
    }
}
