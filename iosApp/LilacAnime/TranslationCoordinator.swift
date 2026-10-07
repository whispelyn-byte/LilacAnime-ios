import Foundation
import SwiftUI
import LilacShared

@MainActor
final class TranslationCoordinator: ObservableObject {
    @Published var running = false
    @Published var progress = 0.0
    @Published var error: String?
    private let service = IosServices()
    private let local = LocalInference()
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private var workingFiles: Set<URL> = []
    func translate(_ file: URL, preferences: AppPreferences, completion: @escaping (URL) -> Void) {
        cancel(); running = true; error = nil; progress = 0
        let token = generation
        task = Task {
            do {
                let content = try SubtitleFiles.text(file)
                let ext = file.pathExtension
                let credential = SecureKeys.load(preferences.translationProvider)
                let configuration = try JSONEncoder().encode(preferences)
                let cacheID = SubtitleFiles.key(content + String(decoding: configuration, as: UTF8.self) + SubtitleFiles.key(credential))
                try FileManager.default.createDirectory(at: SubtitleFiles.translations, withIntermediateDirectories: true)
                let resultFile = SubtitleFiles.translations.appendingPathComponent(cacheID + "." + ext)
                if FileManager.default.fileExists(atPath: resultFile.path) {
                    guard token == generation else { return }
                    completion(resultFile); running = false; progress = 1; return
                }
                let working = SubtitleFiles.translations.appendingPathComponent("working-" + token.uuidString + "." + ext)
                workingFiles.insert(working)
                func publish(_ output: String) throws {
                    guard token == generation else { return }
                    try output.write(to: working, atomically: true, encoding: .utf8)
                    completion(working)
                }
                let output: String
                if preferences.translationProvider == "local" {
                    guard !preferences.selectedGGUF.isEmpty else { throw SubtitleFiles.failure("설정에서 GGUF 모델을 선택하세요.") }
                    let lines = SubtitleTools.shared.lines(content: content, extension: ext)
                    guard !lines.isEmpty else { throw SubtitleFiles.failure("번역할 자막이 없습니다.") }
                    var translated: [String] = []
                    for index in lines.indices {
                        try Task.checkCancellation()
                        let before = lines[max(0, index - preferences.contextCues)..<index].joined(separator: "\n")
                        let after = lines.dropFirst(index + 1).prefix(preferences.prefetchAhead).joined(separator: "\n")
                        let prompt = LocalTranslationPrompt.shared.build(modelName: preferences.selectedGGUF, template: preferences.prompt,
                            text: lines[index], before: before, after: after, thinking: preferences.thinking)
                        let response = try await local.generate(prompt, preferences: preferences)
                        try Task.checkCancellation()
                        let cleaned = LocalTranslationPrompt.shared.clean(text: response)
                        guard !cleaned.isEmpty else { throw SubtitleFiles.failure("로컬 모델이 빈 번역을 반환했습니다.") }
                        translated.append(cleaned)
                        if index == 0 || (index + 1) % 4 == 0 {
                            try publish(SubtitleTools.shared.replace(content: content, extension: ext,
                                lines: translated + Array(lines.dropFirst(translated.count))))
                        }
                        if token == generation { progress = Double(index + 1) / Double(lines.count) }
                    }
                    output = SubtitleTools.shared.replace(content: content, extension: ext, lines: translated)
                } else {
                    let config = TranslationConfig(provider: preferences.translationProvider, key: credential,
                        model: preferences.translationModel, region: preferences.qwenRegion)
                    output = try await withCheckedThrowingContinuation { continuation in
                        service.translate(content: content, extension: ext, config: config, progress: { [weak self] done, total in
                            if token == self?.generation { self?.progress = Double(done) / Double(max(total, 1)) }
                        }, partial: { [weak self] value in
                            guard token == self?.generation else { return }
                            do { try publish(value) } catch { self?.error = error.localizedDescription }
                        }, completion: { value, error in
                            if let value { continuation.resume(returning: value) }
                            else { continuation.resume(throwing: SubtitleFiles.failure(error ?? "번역 실패")) }
                        })
                    }
                }
                try Task.checkCancellation()
                guard token == generation else { return }
                try output.write(to: resultFile, atomically: true, encoding: .utf8)
                completion(resultFile); progress = 1; running = false
            } catch is CancellationError { if token == generation { running = false } }
            catch { if token == generation { self.error = error.localizedDescription; running = false } }
        }
    }
    // Let cloud completion resume its continuation; generation prevents stale UI changes.
    func cancel() { local.cancel(); generation = UUID(); task?.cancel(); task = nil; running = false }
    func shutdown() { cancel(); workingFiles.forEach { try? FileManager.default.removeItem(at: $0) }; workingFiles.removeAll(); Task { await local.unload() } }
}
