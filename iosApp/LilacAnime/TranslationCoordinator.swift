import Foundation
import SwiftUI
import LilacShared

@MainActor
final class TranslationCoordinator: ObservableObject {
    @Published var running = false
    @Published var progress = 0.0
    @Published var error: String?
    @Published var status: String?
    private let service = IosServices()
    private let local = LocalInference()
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private var workingFiles: Set<URL> = []
    func translate(_ file: URL, preferences: AppPreferences, position: @escaping () -> Double = { 0 }, completion: @escaping (URL) -> Void) {
        cancel(); running = true; error = nil; status = nil; progress = 0
        let token = generation
        task = Task {
            do {
                let content = try SubtitleFiles.text(file)
                let ext = file.pathExtension
                let lines = SubtitleTools.shared.lines(content: content, extension: ext)
                let cues = SubtitleTools.shared.cues(content: content, extension: ext)
                guard !lines.isEmpty else { throw SubtitleFiles.failure("번역할 자막이 없습니다.") }
                let credential = SecureKeys.load(preferences.translationProvider)
                let settings: [String: Any] = [
                    "provider": preferences.translationProvider, "model": preferences.translationModel,
                    "region": preferences.qwenRegion, "local": preferences.selectedGGUF,
                    "context": preferences.contextSize, "maxTokens": preferences.maxTokens,
                    "temperature": preferences.temperature, "topP": preferences.topP, "topK": preferences.topK,
                    "repetition": preferences.repetitionPenalty, "before": preferences.contextCues,
                    "after": preferences.prefetchAhead, "thinking": preferences.thinking, "prompt": preferences.prompt,
                    "glossary": preferences.translationGlossary ?? "", "fallback": preferences.translationFallback ?? true
                ]
                let configuration = try JSONSerialization.data(withJSONObject: settings, options: [.sortedKeys])
                let cacheID = SubtitleFiles.key(content + String(decoding: configuration, as: UTF8.self) + SubtitleFiles.key(credential))
                try FileManager.default.createDirectory(at: SubtitleFiles.translations, withIntermediateDirectories: true)
                let resultFile = SubtitleFiles.translations.appendingPathComponent(cacheID + "." + ext)
                let partialFile = SubtitleFiles.translations.appendingPathComponent(cacheID + ".lines.json")
                if FileManager.default.fileExists(atPath: resultFile.path) {
                    guard token == generation else { return }
                    completion(resultFile); running = false; progress = 1; status = "저장된 번역을 불러왔습니다."; return
                }
                var kept: [String: String] = [:]
                if let data = try? Data(contentsOf: partialFile), let saved = try? JSONDecoder().decode([String: String].self, from: data) {
                    kept = saved.filter { !$0.value.isEmpty }
                }
                let working = SubtitleFiles.translations.appendingPathComponent("working-" + token.uuidString + "." + ext)
                workingFiles.insert(working)
                @MainActor func publish() throws {
                    guard token == generation else { return }
                    let translated = lines.map { kept[$0] ?? $0 }
                    let output = SubtitleTools.shared.replace(content: content, extension: ext, lines: translated)
                    try output.write(to: working, atomically: true, encoding: .utf8)
                    try JSONEncoder().encode(kept).write(to: partialFile, options: .atomic)
                    progress = Double(lines.filter { kept[$0] != nil }.count) / Double(lines.count)
                    completion(working)
                }
                if !kept.isEmpty { try publish(); status = "중단된 번역을 이어서 진행합니다." }
                var provider = preferences.translationProvider
                while lines.contains(where: { kept[$0] == nil }) {
                    try Task.checkCancellation()
                    guard token == generation else { return }
                    let pending = TranslationPriority.indices(starts: cues.map(\.startSeconds), ends: cues.map(\.endSeconds),
                        lines: lines, translated: kept, position: position(), limit: provider == "local" ? 1 : 16)
                    guard let first = pending.first else { break }
                    do {
                        if provider == "local" {
                            guard !preferences.selectedGGUF.isEmpty else { throw SubtitleFiles.failure("설정에서 GGUF 모델을 선택하세요.") }
                            let before = lines[max(0, first - preferences.contextCues)..<first].joined(separator: "\n")
                            let after = lines.dropFirst(first + 1).prefix(preferences.prefetchAhead).joined(separator: "\n")
                            let terms = TranslationTerminology.shared.hints(text: lines[first], custom: preferences.translationGlossary ?? "")
                            let prompt = LocalTranslationPrompt.shared.build(modelName: preferences.selectedGGUF, template: preferences.prompt,
                                text: lines[first], before: before, after: after, thinking: preferences.thinking) + (terms.isEmpty ? "" : "\n표기 기준:\n" + terms)
                            let response = try await local.generate(prompt, preferences: preferences)
                            try Task.checkCancellation()
                            let cleaned = LocalTranslationPrompt.shared.clean(text: response)
                            guard !cleaned.isEmpty else { throw SubtitleFiles.failure("로컬 모델이 빈 번역을 반환했습니다.") }
                            kept[lines[first]] = cleaned
                        } else {
                            let config = TranslationConfig(provider: provider, key: SecureKeys.load(provider),
                                model: preferences.translationModel, region: preferences.qwenRegion, terminology: TranslationTerminology.shared.hints(text: pending.map { lines[$0] }.joined(separator: "\n"), custom: preferences.translationGlossary ?? ""))
                            let batch = pending.map { lines[$0] }
                            let output: [String] = try await withCheckedThrowingContinuation { continuation in
                                service.translateLines(lines: batch, config: config) { result, failure in
                                    if let result { continuation.resume(returning: result) }
                                    else { continuation.resume(throwing: SubtitleFiles.failure(failure ?? "번역 실패")) }
                                }
                            }
                            try Task.checkCancellation()
                            guard output.count == batch.count else { throw SubtitleFiles.failure("번역 줄 수가 일치하지 않습니다.") }
                            for (index, translated) in output.enumerated() { kept[batch[index]] = translated }
                        }
                        try publish()
                    } catch is CancellationError { throw CancellationError() }
                    catch {
                        if provider != "local", preferences.translationFallback != false, !preferences.selectedGGUF.isEmpty {
                            status = provider + " 번역을 계속할 수 없어 로컬 AI로 이어서 번역합니다."
                            provider = "local"
                        } else { throw error }
                    }
                }
                try Task.checkCancellation()
                guard token == generation else { return }
                let output = SubtitleTools.shared.replace(content: content, extension: ext, lines: lines.map { kept[$0] ?? $0 })
                try output.write(to: resultFile, atomically: true, encoding: .utf8)
                try? FileManager.default.removeItem(at: partialFile)
                completion(resultFile); progress = 1; running = false
            } catch is CancellationError { if token == generation { running = false } }
            catch { if token == generation { self.error = error.localizedDescription; running = false } }
        }
    }
    func cancel() { local.cancel(); generation = UUID(); task?.cancel(); task = nil; running = false }
    func shutdown() { cancel(); workingFiles.forEach { try? FileManager.default.removeItem(at: $0) }; workingFiles.removeAll(); Task { await local.unload() } }
}

enum TranslationPriority {
    static func indices(starts: [Double], ends: [Double], lines: [String], translated: [String: String], position: Double, limit: Int) -> [Int] {
        let candidates = lines.indices.filter { translated[lines[$0]] == nil }
        let ordered = candidates.sorted { lhs, rhs in
            func score(_ index: Int) -> Double {
                let start = starts.indices.contains(index) ? starts[index] : Double(index)
                let end = ends.indices.contains(index) ? ends[index] : start
                return (end < position ? 1_000_000 : 0) + abs(start - position)
            }
            let a = score(lhs), b = score(rhs)
            return a == b ? lhs < rhs : a < b
        }
        var seen: Set<String> = []
        return Array(ordered.filter { seen.insert(lines[$0]).inserted }.prefix(limit))
    }
}
