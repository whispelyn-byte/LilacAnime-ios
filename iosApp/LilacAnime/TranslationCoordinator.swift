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
    private let local = LocalInference.shared
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private var workingFiles: Set<URL> = []
    func translate(_ file: URL, preferences: AppPreferences, position: @escaping () -> Double = { 0 }, seekRevision: @escaping () -> UInt64 = { 0 }, anime: SavedAnime? = nil, fresh: Bool = false, background: Bool = false, localOnly: Bool = false, completion: @escaping (URL) -> Void) {
        cancel(); if fresh { service.clearTranslationCache() }; running = true; error = nil; status = nil; progress = 0
        let token = generation
        task = Task(priority: background ? .utility : .userInitiated) {
            do {
                var characters: [AnimeCharacter] = []
                if let anime {
                    await DesktopCatalog.shared.enrich(anime.anime, source: anime.source, cast: true)
                    characters = DesktopCatalog.shared.record(anime)?.cast.map(\.shared) ?? []
                }
                try Task.checkCancellation()
                let content = try SubtitleFiles.text(file)
                let ext = file.pathExtension
                let lines = SubtitleTools.shared.lines(content: content, extension: ext)
                let cues = SubtitleTools.shared.cues(content: content, extension: ext)
                guard !lines.isEmpty else { throw SubtitleFiles.failure("번역할 자막이 없습니다.") }
                let settings: [String: Any] = [
                    "desktopPrompt": "prompt-3/local-5/bilingual-1/requests-2", "localOnly": localOnly,
                    "provider": preferences.translationProvider, "model": preferences.translationModel,
                    "models": preferences.translationModels ?? [:], "cloudFallback": preferences.cloudFallback ?? true,
                    "credentials": ["gemini", "openai", "qwen", "deepl"].map { SubtitleFiles.key(SecureKeys.load($0)) },
                    "region": preferences.qwenRegion, "local": preferences.selectedGGUF,
                    "context": preferences.contextSize, "maxTokens": preferences.maxTokens,
                    "temperature": preferences.temperature, "topP": preferences.topP, "topK": preferences.topK,
                    "repetition": preferences.repetitionPenalty, "before": preferences.contextCues,
                    "modelSampling": preferences.modelSampling ?? true, "after": preferences.prefetchAhead, "thinking": preferences.thinking, "prompt": preferences.prompt,
                    "glossary": (preferences.translationGlossary ?? "") + AnimeGlossary.shared.characterTerms(characters: characters) + characters.map { $0.name + $0.gender }.joined(), "fallback": preferences.translationFallback ?? true
                ]
                let configuration = try JSONSerialization.data(withJSONObject: settings, options: [.sortedKeys])
                let cacheID = SubtitleFiles.key(content + String(decoding: configuration, as: UTF8.self))
                try FileManager.default.createDirectory(at: SubtitleFiles.translations, withIntermediateDirectories: true)
                let resultFile = SubtitleFiles.translations.appendingPathComponent(cacheID + "." + ext)
                let partialFile = SubtitleFiles.translations.appendingPathComponent(cacheID + ".lines.json")
                if fresh { try? FileManager.default.removeItem(at: resultFile); try? FileManager.default.removeItem(at: partialFile) }
                if FileManager.default.fileExists(atPath: resultFile.path) {
                    guard token == generation else { return }
                    completion(resultFile); running = false; progress = 1; status = "저장된 번역을 불러왔습니다."; return
                }
                var kept: [String: String] = [:]
                if let data = try? Data(contentsOf: partialFile), let saved = try? JSONDecoder().decode([String: String].self, from: data) {
                    kept = saved.filter { !$0.value.isEmpty }
                }
                for index in SubtitleTools.shared.translationBlanks(content: content, extension: ext) {
                    let value = index.intValue; if lines.indices.contains(value) { kept[lines[value]] = "" }
                }
                for line in lines where line.rangeOfCharacter(from: .letters) == nil { kept[line] = line }
                let working = SubtitleFiles.translations.appendingPathComponent("working-" + token.uuidString + "." + ext)
                try SubtitleFiles.associateFonts(SubtitleFiles.fonts(for: file), with: working)
                try SubtitleFiles.associateFonts(SubtitleFiles.fonts(for: file), with: resultFile)
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
                var triedProviders: Set<String> = [provider]
                var localLines: [String] = []
                var localKind: String?
                var attemptedLocal: Set<String> = []
                var localFailure: Error?
                var terminalFailure: Error?
                while lines.contains(where: { kept[$0] == nil }) {
                    try Task.checkCancellation()
                    guard token == generation else { return }
                    let batchLimit = provider == "local" ? 1 : (kept.isEmpty ? 40 : (provider == "gemini" ? 250 : provider == "deepl" ? 50 : 100))
                    let candidates = TranslationPriority.indices(starts: cues.map(\.startSeconds), ends: cues.map(\.endSeconds),
                        lines: lines, translated: kept, position: position(), limit: batchLimit)
                    var charactersInBatch = 0
                    let pending = candidates.prefix { index in
                        charactersInBatch += lines[index].count
                        return charactersInBatch <= (provider == "gemini" || provider == "deepl" ? 20000 : 6000) || charactersInBatch == lines[index].count
                    }
                    guard let first = pending.first else { break }
                    do {
                        if provider == "local" {
                            guard !preferences.selectedGGUF.isEmpty else { throw SubtitleFiles.failure("설정에서 GGUF 모델을 선택하세요.") }
                            if localKind == nil {
                                var seen: Set<String> = []
                                localLines = lines.filter { kept[$0] == nil && seen.insert($0).inserted }
                                let template = try await local.chatTemplate(preferences: preferences)
                                localKind = DesktopTranslationPrompt.shared.kind(modelName: preferences.selectedGGUF, template: template)
                            }
                            let available = TranslationPriority.indices(starts: cues.map(\.startSeconds), ends: cues.map(\.endSeconds), lines: lines,
                                translated: kept.merging(Dictionary(uniqueKeysWithValues: attemptedLocal.map { ($0, "") })) { old, _ in old }, position: position(), limit: 1)
                            guard let first = available.first else { throw localFailure ?? SubtitleFiles.failure("로컬 모델이 일부 자막을 번역하지 못했습니다.") }
                            let source = lines[first]
                            let index = localLines.firstIndex(of: source) ?? 0
                            let before = preferences.modelSampling == false ? lines[max(0, first - preferences.contextCues)..<first].joined(separator: "\n") : localLines[max(0, index - 2)..<index].joined(separator: "\n")
                            let after = lines.dropFirst(first + 1).prefix(preferences.prefetchAhead).joined(separator: "\n")
                            let terms = AnimeGlossary.shared.hints(text: lines[first], characters: characters, custom: preferences.translationGlossary ?? "")
                            let prompt = LocalTranslationPrompt.shared.build(modelName: preferences.selectedGGUF, template: preferences.prompt,
                                text: lines[first], before: before, after: after, thinking: preferences.thinking) + (terms.isEmpty ? "" : "\n표기 기준:\n" + terms)
                            let desktop = DesktopTranslationPrompt.shared.request(kind: localKind ?? "chat", text: source, before: before,
                                terms: AnimeGlossary.shared.localTerms(text: source, characters: characters, custom: preferences.translationGlossary ?? ""),
                                cast: AnimeGlossary.shared.localCast(characters: characters))
                            var settings = preferences
                            if preferences.modelSampling != false {
                                settings.temperature = desktop.temperature; settings.topP = desktop.topP; settings.topK = Int(desktop.topK); settings.repetitionPenalty = desktop.repetition
                                settings.maxTokens = Int(DesktopTranslationPrompt.shared.maxTokens(source: source))
                                settings.thinking = "off"
                            }
                            attemptedLocal.insert(source)
                            do {
                                var cleaned = ""
                                for attempt in 0..<(preferences.modelSampling == false ? 1 : 3) {
                                    if attempt > 0 && localKind == "jako" { settings.temperature = 0.5 }
                                    let response = try await local.generate(preferences.modelSampling == false ? prompt : desktop.prompt, preferences: settings, requestID: token)
                                    try Task.checkCancellation()
                                    cleaned = preferences.modelSampling == false ? LocalTranslationPrompt.shared.clean(text: response) : DesktopTranslationPrompt.shared.clean(output: response, original: source)
                                    if !DesktopTranslationPrompt.shared.needsRetry(text: cleaned) { break }
                                }
                                if !cleaned.isEmpty { kept[source] = cleaned }
                                else { localFailure = SubtitleFiles.failure("로컬 모델이 빈 번역을 반환했습니다.") }
                            } catch is CancellationError { throw CancellationError() }
                            catch { localFailure = error }
                        } else {
                            let metadata = anime.flatMap { DesktopCatalog.shared.record($0) }
                            var contextParts: [String] = []
                            if let anime {
                                contextParts.append("Anime: " + anime.title + " / " + anime.anime.native)
                                contextParts.append("Genres: " + anime.anime.genres.joined(separator: ", "))
                            }
                            if let metadata { contextParts.append("Story: " + metadata.overview) }
                            if !characters.isEmpty {
                                let cast = characters.prefix(30).map { "- " + $0.native + " / " + $0.name + " (" + $0.gender + ")" }
                                contextParts.append("Main characters (original / romanized, gender):\n" + cast.joined(separator: "\n"))
                            }
                            contextParts.append(AnimeGlossary.shared.hints(text: lines.joined(separator: "\n"), characters: characters, custom: preferences.translationGlossary ?? ""))
                            let context = contextParts.filter { !$0.isEmpty }.joined(separator: "\n")
                            let config = TranslationConfig(provider: provider, key: SecureKeys.load(provider),
                                model: preferences.translationModels?[provider] ?? (provider == preferences.translationProvider ? preferences.translationModel : ""), region: preferences.qwenRegion, terminology: context)
                            try await CloudSubtitleScheduler.translate(lines: lines, cues: cues, provider: provider, config: config,
                                service: service, position: position, seekRevision: seekRevision, cached: { kept }) { additions in
                                    kept.merge(additions) { old, _ in old }
                                    try publish()
                                }
                            try Task.checkCancellation()

                        }
                        try publish()
                    } catch is CancellationError { throw CancellationError() }
                    catch {
                        if provider != "local", preferences.cloudFallback != false,
                           let next = ["gemini", "openai", "deepl", "qwen"].first(where: { !triedProviders.contains($0) && !SecureKeys.load($0).isEmpty }) {
                            status = provider + " 번역 실패 · " + next + "로 이어서 번역합니다."
                            provider = next; triedProviders.insert(next)
                        } else if provider != "local", !triedProviders.contains("local"), preferences.translationFallback != false, !preferences.selectedGGUF.isEmpty {
                            status = provider + " 번역을 계속할 수 없어 로컬 AI로 이어서 번역합니다."
                            provider = "local"; triedProviders.insert("local")
                        } else if provider == "local",
                                  let next = TranslationFallbackPolicy.cloudAfterLocal(localOnly: localOnly, enabled: preferences.translationFallback != false, tried: triedProviders, hasKey: { !SecureKeys.load($0).isEmpty }) {
                            status = "로컬 AI 번역 실패 · " + next + "로 이어서 번역합니다."
                            provider = next; triedProviders.insert(next)
                        } else { terminalFailure = error; break }
                    }
                }
                try Task.checkCancellation()
                guard token == generation else { return }
                let outcome = TranslationOutcome(lines: lines, kept: kept)
                guard outcome.translated > 0 else { throw terminalFailure ?? SubtitleFiles.failure("번역 결과를 받지 못했습니다.") }
                let output = SubtitleTools.shared.replace(content: content, extension: ext, lines: lines.map { kept[$0] ?? $0 })
                // A partial result is usable, but must never masquerade as a finished cache hit.
                let destination = outcome.failed == 0 ? resultFile : SubtitleFiles.translations.appendingPathComponent(cacheID + "-partial-" + token.uuidString + "." + ext)
                try output.write(to: destination, atomically: true, encoding: .utf8)
                try SubtitleFiles.associateFonts(SubtitleFiles.fonts(for: file), with: destination)
                if outcome.failed == 0 { try? FileManager.default.removeItem(at: partialFile) }
                completion(destination); progress = 1; running = false
                if outcome.failed > 0 { status = "\(outcome.failed)줄은 번역하지 못해 원문으로 남겼습니다." }
            } catch is CancellationError { if token == generation { running = false } }
            catch { if token == generation { self.error = error.localizedDescription; running = false } }
        }
    }
    func cancel() { if running { local.cancel(requestID: generation); service.cancel() }; generation = UUID(); task?.cancel(); task = nil; running = false }
    func shutdown() { cancel(); workingFiles.forEach { try? FileManager.default.removeItem(at: $0) }; workingFiles.removeAll(); Task { await local.unload() } }
}

struct TranslationOutcome {
    let translated: Int
    let failed: Int
    init(lines: [String], kept: [String: String]) {
        let unique = Set(lines.filter { $0.rangeOfCharacter(from: .letters) != nil && kept[$0] != "" })
        translated = unique.filter { kept[$0] != nil }.count
        failed = unique.count - translated
    }
}

enum TranslationPriority {
    static func indices(starts: [Double], ends: [Double], lines: [String], translated: [String: String], position: Double, limit: Int) -> [Int] {
        var seen: Set<String> = []
        let candidates = lines.indices.filter { seen.insert(lines[$0]).inserted && translated[lines[$0]] == nil }
        let ordered = candidates.sorted { lhs, rhs in
            func score(_ index: Int) -> Double {
                let start = starts.indices.contains(index) ? starts[index] : Double(index)
                return (start < max(0, position) - 5 ? 1_000_000 : 0) + start
            }
            let a = score(lhs), b = score(rhs)
            return a == b ? lhs < rhs : a < b
        }
        return Array(ordered.prefix(limit))
    }
}
