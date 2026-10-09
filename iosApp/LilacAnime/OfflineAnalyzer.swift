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
                var fingerprints: [(DownloadEntry, KotlinFloatArray, KotlinFloatArray, Double)] = []
                for item in group.sorted(by: { $0.number < $1.number }) {
                    try Task.checkCancellation()
                    guard let filename = item.localFile else { continue }
                    let url = DownloadStore.directory.appendingPathComponent(item.id).appendingPathComponent(filename)
                    let data = try await pcm(url)
                    guard data.count >= 120 * 8000 else { continue }
                    func fingerprint(_ samples: ArraySlice<Float>) -> KotlinFloatArray {
                        let kotlin = KotlinFloatArray(size: Int32(samples.count))
                        for (index, value) in samples.enumerated() { kotlin.set(index: Int32(index), value: value) }
                        return AudioFingerprint.shared.compute(samples: kotlin, sampleRate: 8000)
                    }
                    fingerprints.append((item, fingerprint(data.prefix(300 * 8000)), fingerprint(data.suffix(300 * 8000)), Double(data.count) / 8000))
                }
                for (item, front, back, duration) in fingerprints {
                    try Task.checkCancellation()
                    var opening: [AudioMatch] = [], ending: [AudioMatch] = []
                    for (_, otherFront, otherBack, _) in fingerprints.filter({ $0.0.id != item.id }).prefix(5) {
                        if let match = DesktopAudioFingerprint.shared.region(a: front, b: otherFront) { opening.append(match) }
                        if let match = DesktopAudioFingerprint.shared.region(a: back, b: otherBack) { ending.append(match) }
                    }
                    var selected: [OfflineChapter] = []
                    for (type, matches, offset) in [("op", opening, 0.0), ("ed", ending, max(0, duration - 300))] {
                        guard let match = DesktopAudioFingerprint.shared.consensus(matches: matches, offset: offset) else { continue }
                        selected.append(OfflineChapter(type: type, start: match.startSeconds, end: min(duration, match.endSeconds), score: match.score))
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
