import Foundation
import SwiftUI
import WhisperKit

struct WhisperLine: Codable, Equatable {
    var start: Double
    var end: Double
    var text: String
}

enum WhisperSubtitleFile {
    static func nextChunk(duration: Double, position: Double, completed: Set<Int>) -> Int? {
        guard duration.isFinite, duration > 0 else { return nil }
        let count = Int(ceil(min(duration, 86400) / 30))
        let current = min(count - 1, max(0, Int(max(0, position.isFinite ? position : 0) / 30)))
        return (Array(current..<count) + Array(0..<current)).first { !completed.contains($0) }
    }
    static func vtt(_ lines: [WhisperLine]) -> String {
        func stamp(_ value: Double) -> String {
            let ms = Int(max(0, value) * 1000)
            return String(format: "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        }
        return "WEBVTT\n\n" + lines.sorted { $0.start < $1.start }.map {
            let text = $0.text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
            return "\(stamp($0.start)) --> \(stamp(max($0.end, $0.start + 0.1)))\n\(text)\n"
        }.joined(separator: "\n")
    }
}

@MainActor final class WhisperModels: ObservableObject {
    static let shared = WhisperModels()
    static let variants = ["tiny", "base", "small", "large-v3-v20240930_626MB"]
    nonisolated static let root = SubtitleFiles.root.appendingPathComponent("WhisperModels", isDirectory: true)
    @Published private(set) var downloading: String?
    @Published private(set) var progress = 0.0
    @Published private(set) var installed: [String: String] = UserDefaults.standard.dictionary(forKey: "whisper-model-folders") as? [String: String] ?? [:]
    @Published var error: String?
    private var task: Task<Void, Never>?
    func folder(_ id: String) -> URL? {
        guard Self.variants.contains(id), let path = installed[id] else { return nil }
        let url = URL(fileURLWithPath: path).standardizedFileURL
        guard url.path.hasPrefix(Self.root.standardizedFileURL.path + "/"), FileManager.default.fileExists(atPath: url.appendingPathComponent("AudioEncoder.mlmodelc").path) else { return nil }
        return url
    }
    func download(_ id: String) {
        guard Self.variants.contains(id), downloading == nil else { return }
        downloading = id; progress = 0; error = nil
        task = Task {
            defer { downloading = nil; task = nil }
            do {
                let url = try await WhisperKit.download(variant: id, downloadBase: Self.root, progressCallback: { progress in
                    Task { @MainActor in self.progress = progress.fractionCompleted }
                })
                try Task.checkCancellation()
                // Load once while online to also cache the tokenizer. Later transcription does not download models.
                _ = try await WhisperKit(WhisperKitConfig(modelFolder: url.path, tokenizerFolder: Self.root, verbose: false, prewarm: false, load: true, download: false))
                try Task.checkCancellation()
                installed[id] = url.path; UserDefaults.standard.set(installed, forKey: "whisper-model-folders")
            } catch is CancellationError { }
            catch { self.error = error.localizedDescription }
        }
    }
    func cancelDownload() { task?.cancel() }
    func remove(_ id: String) {
        guard let folder = folder(id), downloading == nil else { return }
        task = Task {
            do {
                try await WhisperRuntime.shared.remove(folder)
                installed[id] = nil; UserDefaults.standard.set(installed, forKey: "whisper-model-folders")
            } catch { self.error = error.localizedDescription }
        }
    }
}

private actor WhisperRuntime {
    static let shared = WhisperRuntime()
    private var engine: WhisperKit?
    private var model: URL?
    private var busy = false
    func remove(_ folder: URL) throws {
        guard !busy else { throw SubtitleFiles.failure("음성 인식이 끝난 뒤 모델을 삭제해 주세요.") }
        if model == folder { engine = nil; model = nil }
        try FileManager.default.removeItem(at: folder)
    }
    func recognize(_ samples: [Float], folder: URL) async throws -> [WhisperLine] {
        while busy { try await Task.sleep(nanoseconds: 100_000_000) }
        try Task.checkCancellation(); busy = true
        defer { busy = false }
        if engine == nil || model != folder {
            engine = nil; model = nil
            engine = try await WhisperKit(WhisperKitConfig(modelFolder: folder.path, tokenizerFolder: WhisperModels.root, verbose: false, prewarm: false, load: true, download: false))
            model = folder
        }
        try Task.checkCancellation()
        let result: [TranscriptionResult] = try await engine!.transcribe(audioArray: samples,
            decodeOptions: DecodingOptions(language: "ja", temperatureFallbackCount: 2, skipSpecialTokens: true, concurrentWorkerCount: 1),
            callback: { _ in Task.isCancelled ? false : nil })
        try Task.checkCancellation()
        return result.flatMap(\.segments).compactMap { segment in
            let text = segment.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, segment.end > segment.start, segment.noSpeechProb < 0.6 else { return nil }
            return WhisperLine(start: Double(segment.start), end: Double(segment.end), text: text)
        }
    }
}

@MainActor final class WhisperSubtitles: ObservableObject {
    @Published private(set) var running = false
    @Published private(set) var status = ""
    private var task: Task<Void, Never>?
    private var cancellation = SyncCancel()
    private var generation = UUID()
    private struct Cache: Codable { var chunks: Set<Int>; var lines: [WhisperLine] }
    func cancel() { generation = UUID(); cancellation.cancel(); task?.cancel(); task = nil; running = false }
    func start(video: SubtitleAutoSync.Video, key: String, model: String, position: @escaping () -> Double,
               receive: @escaping (URL, [WhisperLine], Bool) -> Void, failed: @escaping (String) -> Void) {
        cancel()
        guard let folder = WhisperModels.shared.folder(model) else { failed("설정 > 자막 자동 번역 > Whisper에서 모델을 먼저 받아 주세요."); return }
        guard video.duration.isFinite, video.duration > 0 else { failed("영상 길이가 확인된 뒤 음성 인식을 시작해 주세요."); return }
        cancellation = SyncCancel(); let cancellation = cancellation, token = generation
        running = true; status = "Whisper 모델 준비 중"
        task = Task {
            defer { if token == generation { running = false; task = nil } }
            do {
                let directory = SubtitleFiles.root.appendingPathComponent("Whisper", isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let name = SubtitleFiles.key("whisper-ja-v1|\(model)|\(key)|\(Int(video.duration))")
                let file = directory.appendingPathComponent(name + ".vtt"), cache = directory.appendingPathComponent(name + ".json")
                var saved = (try? JSONDecoder().decode(Cache.self, from: Data(contentsOf: cache))) ?? Cache(chunks: [], lines: [])
                if !saved.lines.isEmpty { receive(file, saved.lines, false) }
                while let chunk = WhisperSubtitleFile.nextChunk(duration: video.duration, position: position(), completed: saved.chunks) {
                    try Task.checkCancellation()
                    let start = Double(chunk * 30), length = min(30, video.duration - start)
                    status = "Whisper \(Int(start) / 60):\(String(format: "%02d", Int(start) % 60)) 음성 인식 중"
                    let samples = try await Self.audio(video, start: start, length: length, cancellation: cancellation)
                    var lines = try await WhisperRuntime.shared.recognize(samples, folder: folder)
                    try Task.checkCancellation(); guard token == generation else { return }
                    lines = lines.filter { $0.start < length }.map { WhisperLine(start: start + max(0, $0.start), end: start + min(length, $0.end), text: $0.text) }
                    saved.lines += lines; saved.chunks.insert(chunk)
                    let snapshot = saved
                    try await Task.detached(priority: .utility) {
                        try Data(WhisperSubtitleFile.vtt(snapshot.lines).utf8).write(to: file, options: .atomic)
                        try JSONEncoder().encode(snapshot).write(to: cache, options: .atomic)
                    }.value
                    try Task.checkCancellation(); guard token == generation else { return }
                    receive(file, saved.lines, false)
                }
                guard token == generation else { return }
                status = saved.lines.isEmpty ? "인식된 대사가 없습니다." : "Whisper 자막 저장 완료"
                receive(file, saved.lines, true)
            } catch is CancellationError { }
            catch { if token == generation { status = error.localizedDescription; failed(status) } }
        }
    }
    private static func audio(_ video: SubtitleAutoSync.Video, start: Double, length: Double, cancellation: SyncCancel) async throws -> [Float] {
        let work = Task.detached(priority: .utility) {
            let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".pcm")
            defer { try? FileManager.default.removeItem(at: file) }
            let source = video.url.isFileURL ? video.url.path : video.url.absoluteString
            let headers = video.headers.filter { !$0.key.contains("\n") && !$0.value.contains("\n") && !$0.value.contains("\r") }.map { $0.key + ": " + $0.value + "\r\n" }.joined()
            let box = Unmanaged.passRetained(cancellation); defer { box.release() }
            var message: UnsafeMutablePointer<CChar>?
            let result = LilacWhisperAudio(source, headers, file.path, start, length,
                { opaque in Unmanaged<SyncCancel>.fromOpaque(opaque!).takeUnretainedValue().cancelled }, box.toOpaque(), &message)
            defer { if let message { LilacAudioFreeError(message) } }
            if cancellation.cancelled { throw CancellationError() }
            guard result == 0 else { throw SubtitleFiles.failure(message.map { String(cString: $0) } ?? "Whisper 음성을 읽지 못했습니다.") }
            let data = try Data(contentsOf: file)
            return data.withUnsafeBytes { buffer in (0..<(data.count / 4)).map { buffer.loadUnaligned(fromByteOffset: $0 * 4, as: Float.self) } }
        }
        return try await withTaskCancellationHandler(operation: { try await work.value }, onCancel: { cancellation.cancel(); work.cancel() })
    }
}

struct WhisperSettingsView: View {
    @EnvironmentObject var store: LibraryStore
    @ObservedObject private var models = WhisperModels.shared
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Whisper 음성 자막").font(.headline)
            Toggle("자막이 없으면 음성으로 만들기", isOn: Binding(get: { store.preferences.whisperAuto != false }, set: { store.preferences.whisperAuto = $0 }))
            Text("일본어 음성을 기기 안에서 인식하고, 설정한 번역 엔진으로 한국어로 바꿉니다. 먼저 모델을 받아 주세요. 큰 모델은 메모리와 배터리를 더 사용합니다.").font(.caption).foregroundStyle(.secondary)
            ForEach(WhisperModels.variants, id: \.self) { id in
                HStack {
                    Button(id == "large-v3-v20240930_626MB" ? "Large v3 Turbo" : id.capitalized) { store.preferences.whisperModel = id }
                    if (store.preferences.whisperModel ?? "base") == id { Image(systemName: "checkmark") }
                    Spacer()
                    if models.downloading == id { ProgressView(value: models.progress); Button("취소") { models.cancelDownload() } }
                    else if models.folder(id) != nil { Button("삭제", role: .destructive) { models.remove(id) }.disabled(models.downloading != nil) }
                    else { Button("받기") { models.download(id) }.disabled(models.downloading != nil) }
                }
            }
            if let error = models.error { Text(error).font(.caption).foregroundStyle(.red) }
        }
    }
}
