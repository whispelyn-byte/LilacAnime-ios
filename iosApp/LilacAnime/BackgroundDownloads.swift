import Foundation
import UIKit

@MainActor
enum BackgroundEvents {
    static var completions: [String: () -> Void] = [:]
}
final class BackgroundDownloadDelegate: NSObject, URLSessionDownloadDelegate {
    weak var owner: DownloadStore?
    static func destination(_ description: String, suffix: String = "") -> URL? {
        guard description.range(of: "^[a-f0-9]{64}\\|(video|[a-f0-9]{64})\\.[A-Za-z0-9]{1,10}$", options: .regularExpression) != nil else { return nil }
        let parts = description.components(separatedBy: "|")
        return DownloadStore.directory.appendingPathComponent(parts[0]).appendingPathComponent(parts[1] + suffix)
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let description = downloadTask.taskDescription,
              let target = Self.destination(description, suffix: ".staging-" + UUID().uuidString) else { return }
        do {
            guard let response = downloadTask.response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else {
                throw SubtitleFiles.failure("영상 다운로드 서버가 오류를 반환했습니다.")
            }
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try FileManager.default.moveItem(at: location, to: target)
            let mime = response.mimeType ?? ""
            Task { @MainActor [weak self] in self?.owner?.backgroundFinished(description, staged: target, mime: mime) }
        } catch {
            Task { @MainActor [weak self] in self?.owner?.backgroundFailed(description, error: error) }
        }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let description = task.taskDescription else { return }
        Task { @MainActor [weak self] in
            if let error { self?.owner?.backgroundFailed(description, error: error) }
            self?.owner?.backgroundCompleted(description)
        }
    }
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            if let id = session.configuration.identifier { BackgroundEvents.completions.removeValue(forKey: id)?() }
        }
    }
}

private struct HLSPlan {
    var root: String
    var parts: [DownloadPart]
}
actor HLSPlanBuilder {
    private let stream: ResolvedStream
    private let folder: URL
    private let quality: String
    private var files: [URL: String] = [:]
    private var parts: [URL: DownloadPart] = [:]
    private var active: Set<URL> = []
    init(stream: ResolvedStream, folder: URL, quality: String) { self.stream = stream; self.folder = folder; self.quality = quality }
    func build() async throws -> (String, [DownloadPart]) {
        let root = try await playlist(stream.url, depth: 0)
        return (root, parts.values.sorted { $0.name < $1.name })
    }
    private func filename(_ url: URL, manifest: Bool = false) -> String {
        let ext = manifest ? "m3u8" : url.pathExtension.lowercased()
        let suffix = ext.range(of: "^[a-z0-9]{1,10}$", options: .regularExpression) == nil ? "bin" : ext
        return SubtitleFiles.key(url.absoluteString) + "." + suffix
    }
    private func playlist(_ url: URL, depth: Int) async throws -> String {
        try Task.checkCancellation()
        guard depth < 8, !active.contains(url), files.count < 10000 else { throw SubtitleFiles.failure("재생목록 구조가 잘못되었습니다.") }
        if let existing = files[url] { return existing }
        active.insert(url); defer { active.remove(url) }
        let (body, _) = try await HLSData.fetch(url, stream: stream)
        var text = try HLSData.manifest(body, key: stream.manifestKey)
        text = HLSData.selectVariant(text, quality: quality)
        let name = filename(url, manifest: true); files[url] = name
        let lines = text.components(separatedBy: .newlines)
        let master = lines.contains { $0.hasPrefix("#EXT-X-STREAM-INF") }
        guard master || text.contains("#EXT-X-ENDLIST") else { throw SubtitleFiles.failure("방송 중인 HLS는 완료된 회차로 저장할 수 없습니다.") }
        var childManifests = Set<URL>()
        let regex = try! NSRegularExpression(pattern: "URI=\"([^\"]+)\"", options: .caseInsensitive)
        for index in lines.indices {
            let line = lines[index]
            if line.hasPrefix("#EXT-X-STREAM-INF"), index + 1 < lines.count,
               let child = URL(string: lines[index + 1].trimmingCharacters(in: .whitespaces), relativeTo: url)?.absoluteURL { childManifests.insert(child) }
            if line.hasPrefix("#EXT-X-MEDIA:") || line.hasPrefix("#EXT-X-I-FRAME-STREAM-INF:") {
                for match in regex.matches(in: line, range: NSRange(line.startIndex..., in: line)) {
                    if let range = Range(match.range(at: 1), in: line), let child = URL(string: String(line[range]), relativeTo: url)?.absoluteURL { childManifests.insert(child) }
                }
            }
        }
        let resources = HLSData.references(text, base: url)
        guard files.count + resources.count <= 10000 else { throw SubtitleFiles.failure("재생목록 조각이 너무 많습니다.") }
        for resource in resources {
            if childManifests.contains(resource) || resource.pathExtension.lowercased() == "m3u8" {
                _ = try await playlist(resource, depth: depth + 1)
            } else {
                let file = filename(resource); files[resource] = file
                parts[resource] = DownloadPart(url: resource, name: file)
            }
        }
        let rewritten = HLSData.rewrite(text, base: url) { files[$0] ?? "" }
        try Data(rewritten.utf8).write(to: folder.appendingPathComponent(name), options: .atomic)
        return name
    }

}
