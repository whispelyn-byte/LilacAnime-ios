import Foundation
import AVFoundation
import LilacShared

struct OfflineChapter: Codable, Identifiable {
    var id: String { type + String(start) }
    var type: String
    var start: Double
    var end: Double
    var score: Double
}
enum OfflineAnalyzer {
    static let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Chapters")
    static func clearCache() { try? FileManager.default.removeItem(at: directory) }
    static func chapters(animeID: String, episodeID: String) -> [OfflineChapter] {
        let file = directory.appendingPathComponent(SubtitleFiles.key(animeID + "#" + episodeID) + ".json")
        guard let data = try? Data(contentsOf: file) else { return [] }
        return (try? JSONDecoder().decode([OfflineChapter].self, from: data)) ?? []
    }
    static func analyze(_ entries: [DownloadEntry]) async throws -> Int {
        let work = Task.detached(priority: .utility) {
            var count = 0
            for group in Dictionary(grouping: entries, by: { $0.anime.id }).values where group.count >= 2 {
                var fingerprints: [(DownloadEntry, KotlinFloatArray)] = []
                for item in group {
                    try Task.checkCancellation()
                    guard let filename = item.localFile else { continue }
                    let url = DownloadStore.directory.appendingPathComponent(item.id).appendingPathComponent(filename)
                    let data = try await pcm(url)
                    let kotlin = KotlinFloatArray(size: Int32(data.count))
                    for index in data.indices { kotlin.set(index: Int32(index), value: data[index]) }
                    fingerprints.append((item, AudioFingerprint.shared.compute(samples: kotlin, sampleRate: 8000)))
                }
                for (item, fingerprint) in fingerprints {
                    try Task.checkCancellation()
                    var candidates: [[AudioMatch]] = []
                    for (other, otherFingerprint) in fingerprints where other.id != item.id {
                        candidates.append(AudioFingerprint.shared.repeated(a: fingerprint, b: otherFingerprint))
                    }
                    let duration = Double(fingerprint.size) / 32 * 0.1
                    var selected: [OfflineChapter] = []
                    for type in ["op","ed"] {
                        let matches = candidates.flatMap { $0 }.filter { type == "op" ? $0.startSeconds < duration / 2 : $0.startSeconds >= duration / 2 }
                        guard let best = matches.max(by: { $0.score < $1.score }) else { continue }
                        let supporting = candidates.filter { list in list.contains { abs($0.startSeconds - best.startSeconds) <= 60 } }.count
                        guard supporting >= max(1, Int(ceil(Double(candidates.count) * 0.6))) else { continue }
                        selected.append(OfflineChapter(type: type, start: best.startSeconds, end: best.endSeconds, score: best.score))
                    }
                    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                    let file = directory.appendingPathComponent(SubtitleFiles.key(item.anime.id + "#" + item.episodeID) + ".json")
                    try JSONEncoder().encode(selected).write(to: file, options: .atomic); count += 1
                }
            }
            return count
        }
        return try await withTaskCancellationHandler(operation: { try await work.value }, onCancel: { work.cancel() })
    }
    private static func pcm(_ url: URL) async throws -> [Float] {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let identity = url.path + String(describing: attributes[.modificationDate])
        let file = directory.appendingPathComponent(SubtitleFiles.key(identity) + ".f32")
        if !FileManager.default.fileExists(atPath: file.path) {
            var message: UnsafeMutablePointer<CChar>?
            let cancelled: @convention(c) (UnsafeMutableRawPointer?) -> Bool = { _ in Task<Never, Never>.isCancelled }
            let result = LilacDecodeAudio(url.path, file.path, cancelled, nil, &message)
            defer { if let message { LilacAudioFreeError(message) } }
            guard result == 0 else {
                if Task.isCancelled { throw CancellationError() }
                throw SubtitleFiles.failure(message.map { String(cString: $0) } ?? "오디오 디코딩 실패")
            }
        }
        let bytes = try Data(contentsOf: file, options: .mappedIfSafe)
        guard bytes.count % 4 == 0 else { throw SubtitleFiles.failure("오디오 캐시 형식이 올바르지 않습니다.") }
        return bytes.withUnsafeBytes { buffer in
            stride(from: 0, to: bytes.count, by: 4).map { buffer.loadUnaligned(fromByteOffset: $0, as: Float.self) }
        }
    }
}
