import Foundation
import LilacShared

@MainActor
enum CloudSubtitleScheduler {
    private enum Event {
        case tick
        case result(UUID, [Int], [String]?, String?)
    }
    static func translate(lines: [String], cues: [SubtitleCue], provider: String, config: TranslationConfig,
                          service: IosServices, position: @escaping () -> Double, cached: @escaping () -> [String: String],
                          save: @escaping ([String: String]) throws -> Void) async throws {
        try await withThrowingTaskGroup(of: Event.self) { group in
            var active: [UUID: [Int]] = [:]
            var lastPosition = position()
            var first = true
            func pending(rush: Bool) -> [Int] {
                let reserved = Set(active.values.flatMap { $0 }.map { lines[$0] })
                var translated = cached()
                if !rush { for line in reserved { translated[line] = "" } }
                let limit = rush ? 16 : first ? 40 : provider == "gemini" ? 250 : provider == "deepl" ? 50 : 100
                let candidates = TranslationPriority.indices(starts: cues.map(\.startSeconds), ends: cues.map(\.endSeconds),
                    lines: lines, translated: translated, position: position(), limit: limit)
                var count = 0
                return Array(candidates.prefix { index in count += lines[index].count; return count <= (provider == "gemini" || provider == "deepl" ? 20000 : 6000) || count == lines[index].count })
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
            while lines.contains(where: { cached()[$0] == nil }) {
                try Task.checkCancellation()
                while active.count < 2 {
                    let next = pending(rush: false); if next.isEmpty { break }; launch(next)
                }
                guard let event = try await group.next() else { break }
                switch event {
                case .tick:
                    let current = position()
                    if abs(current - lastPosition) >= 20 && active.count < 3 { launch(pending(rush: true)); lastPosition = current }
                    tick()
                case let .result(id, indices, output, failure):
                    active[id] = nil
                    guard let output, output.count == indices.count, output.allSatisfy({ !$0.isEmpty }) else {
                        group.cancelAll(); service.cancel()
                        throw SubtitleFiles.failure(failure ?? "번역 줄 수가 일치하지 않습니다.")
                    }
                    let existing = cached()
                    var additions: [String: String] = [:]
                    for (index, value) in output.enumerated() where existing[lines[indices[index]]] == nil { additions[lines[indices[index]]] = value }
                    try save(additions)
                }
            }
            group.cancelAll(); service.cancel()
        }
    }
}
