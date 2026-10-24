import Foundation
import LilacShared

/// Translate only newly recognized lines. Gemini waits for 40 lines except at the end of an episode.
@MainActor final class WhisperLiveTranslation {
    private let translator = TranslationCoordinator()
    private var task: Task<Void, Never>?
    private var lines: [WhisperLine] = []
    private var translated: [Double: String] = [:]
    private var sent: Set<Double> = []
    private var finished = false
    private var generation = UUID()
    private var failures = 0
    func cancel() { generation = UUID(); task?.cancel(); task = nil; translator.cancel(); lines = []; translated = [:]; sent = []; finished = false; failures = 0 }
    func update(_ lines: [WhisperLine], complete: Bool, original: URL, preferences: AppPreferences, anime: SavedAnime,
                receive: @escaping (URL) -> Void) {
        self.lines = lines; finished = complete
        guard task == nil, failures < 2 else { return }
        let token = generation
        task = Task {
            defer { if token == generation { task = nil } }
            while !Task.isCancelled && token == generation {
                let pending = self.lines.filter { !sent.contains($0.start) }
                let threshold = preferences.translationProvider == "gemini" ? 40 : 1
                guard !pending.isEmpty, finished || pending.count >= threshold else { return }
                let batch = Array(pending.prefix(40))
                let input = FileManager.default.temporaryDirectory.appendingPathComponent("whisper-batch-\(UUID().uuidString).vtt")
                do { try Data(WhisperSubtitleFile.vtt(batch).utf8).write(to: input, options: .atomic) }
                catch { return }
                var succeeded = false
                translator.translate(input, preferences: preferences, anime: anime) { [weak self] output in
                    guard let self, token == self.generation,
                          let content = try? SubtitleFiles.text(output) else { return }
                    for cue in SubtitleTools.shared.cues(content: content, extension: "vtt") { self.translated[cue.startSeconds] = cue.text }
                    let all = self.lines.map { WhisperLine(start: $0.start, end: $0.end, text: self.translated[$0.start] ?? $0.text) }
                    let result = original.deletingPathExtension().appendingPathExtension("ko.vtt")
                    if (try? Data(WhisperSubtitleFile.vtt(all).utf8).write(to: result, options: .atomic)) != nil { receive(result) }
                    if !output.lastPathComponent.hasPrefix("working-") { succeeded = true }
                }
                while translator.running && !Task.isCancelled { try? await Task.sleep(nanoseconds: 100_000_000) }
                try? FileManager.default.removeItem(at: input)
                guard token == generation, !Task.isCancelled else { return }
                if succeeded { sent.formUnion(batch.map(\.start)); failures = 0 }
                else { failures += 1; if failures >= 2 { return } }
            }
        }
    }
}
