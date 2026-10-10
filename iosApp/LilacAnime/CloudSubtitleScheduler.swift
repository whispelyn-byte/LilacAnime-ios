import Foundation
import LilacShared

@MainActor
enum CloudSubtitleScheduler {
    private enum Event {
        case tick
        case result(UUID, [Int], [String]?, String?)
    }
    static func translate(lines: [String], cues: [SubtitleCue], provider: String, config: TranslationConfig,
                          service: IosServices, position: @escaping () -> Double, seekRevision: @escaping () -> UInt64 = { 0 }, cached: @escaping () -> [String: String],
                          save: @escaping ([String: String]) throws -> Void) async throws {
        try await withThrowingTaskGroup(of: Event.self) { group in
            var active: [UUID: [Int]] = [:]
            var lastSeek = seekRevision()
            var first = true
            var missingAttempts: [String: Int] = [:]
            let batchLines = provider == "gemini" ? 600 : provider == "openai" ? 150 : provider == "deepl" ? 50 : 100
            let batchCharacters = provider == "gemini" ? 30000 : provider == "openai" ? 9000 : provider == "deepl" ? 20000 : 6000
            let parallel = provider == "gemini" || provider == "deepl" ? 2 : 4
            func pending(rush: Bool) -> [Int] {
                let reserved = Set(active.values.flatMap { $0 }.map { lines[$0] })
                var translated = cached()
                for (line, attempts) in missingAttempts where attempts >= 2 { translated[line] = "" }
                if !rush { for line in reserved { translated[line] = "" } }
                let limit = rush || first ? 40 : batchLines
                let candidates = TranslationPriority.indices(starts: cues.map(\.startSeconds), ends: cues.map(\.endSeconds),
                    lines: lines, translated: translated, position: position(), limit: limit)
                var count = 0
                return Array(candidates.prefix { index in count += lines[index].count; return count <= batchCharacters || count == lines[index].count })
            }
            func launch(_ indices: [Int]) {
                guard !indices.isEmpty else { return }
                let id = UUID(); active[id] = indices
                let texts = indices.map { lines[$0] }
                group.addTask {
                    let value: ([String]?, String?) = await withCheckedContinuation { continuation in
                        service.translateLines(lines: texts, config: config) { values, failure in continuation.resume(returning: (values, failure)) }
                    }
                    return .result(id, indices, value.0, value.1)
                }
                first = false
            }
            func tick() { group.addTask { try await Task.sleep(nanoseconds: 250_000_000); return .tick } }
            tick()
            while lines.contains(where: { cached()[$0] == nil && missingAttempts[$0, default: 0] < 2 }) {
                try Task.checkCancellation()
                while active.count < parallel {
                    let next = pending(rush: false); if next.isEmpty { break }; launch(next)
                }
                guard let event = try await group.next() else { break }
                switch event {
                case .tick:
                    let current = position()
                    if seekRevision() != lastSeek && active.count <= parallel {
                        let missing = pending(rush: true)
                        if let next = missing.first, cues[next].startSeconds <= current + 60,
                           !active.values.contains(where: { $0.contains(next) && $0.count <= 40 }) { launch(missing) }
                        lastSeek = seekRevision()
                    }
                    tick()
                case let .result(id, indices, output, failure):
                    active[id] = nil
                    guard let output, output.count == indices.count else {
                        group.cancelAll(); service.cancel()
                        throw SubtitleFiles.failure(failure ?? "번역 줄 수가 일치하지 않습니다.")
                    }
                    let existing = cached()
                    var additions: [String: String] = [:]
                    for (index, value) in output.enumerated() where existing[lines[indices[index]]] == nil {
                        let line = lines[indices[index]]
                        if value.isEmpty { missingAttempts[line, default: 0] += 1 }
                        else { additions[line] = value }
                    }
                    try save(additions)
                }
            }
            group.cancelAll(); service.cancel()
            if missingAttempts.contains(where: { $0.value >= 2 && cached()[$0.key] == nil }) {
                throw SubtitleFiles.failure("API 응답에서 일부 자막 번역이 누락되었습니다.")
            }
        }
    }
}
