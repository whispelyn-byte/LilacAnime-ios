import SwiftUI
import UniformTypeIdentifiers
import LilacLocalAI

enum LocalModelFiles {
    static let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Models")
    static func importModel(_ url: URL) throws -> URL {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        try validate(url)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(url.lastPathComponent)
        if destination != url {
            if FileManager.default.fileExists(atPath: destination.path) { throw SubtitleFiles.failure("같은 이름의 모델이 있습니다.") }
            try FileManager.default.copyItem(at: url, to: destination)
        }
        return destination
    }
    static func validate(_ url: URL) throws {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let header = try handle.read(upToCount: 24) ?? Data()
        guard header.count == 24, header.prefix(4) == Data("GGUF".utf8), (1...3).contains(header[4]) else { throw SubtitleFiles.failure("GGUF 헤더가 올바르지 않습니다.") }
        guard url.pathExtension.lowercased() == "gguf" else { throw SubtitleFiles.failure("GGUF 모델을 선택하세요.") }
    }

}
private final class NativeModelBox: @unchecked Sendable {
    private let lock = NSLock()
    private var model: OpaquePointer?
    private var active: UUID?
    func begin(_ id: UUID) { lock.lock(); defer { lock.unlock() }; active = id }
    func end(_ id: UUID) { lock.lock(); defer { lock.unlock() }; if active == id { active = nil } }
    func assign(_ model: OpaquePointer?) { lock.lock(); defer { lock.unlock() }; self.model = model }
    func cancel(_ id: UUID) { lock.lock(); defer { lock.unlock() }; if active == id, let model { lilac_cancel(model) } }
    func close() { lock.lock(); defer { lock.unlock() }; if let model { lilac_model_close(model) }; model = nil }
}
@MainActor
final class LocalInferenceStatus: ObservableObject {
    static let shared = LocalInferenceStatus()
    @Published var text = UserDefaults.standard.string(forKey: "localai.last-run") ?? "아직 로컬 번역을 실행하지 않았습니다."
    func update(_ text: String) { self.text = text; UserDefaults.standard.set(text, forKey: "localai.last-run") }
}
actor LocalInference {
    static let shared = LocalInference()
    private var model: OpaquePointer?
    private nonisolated let state = NativeModelBox()
    nonisolated func cancel(requestID: UUID) { state.cancel(requestID) }
    private var loaded = ""
    private var loadedContext = 0
    private var loadedThreads = 0
    private var loadedGPU = true
    private var idle: Task<Void, Never>?
    func generate(_ prompt: String, preferences: AppPreferences, requestID: UUID) throws -> String {
        try Task.checkCancellation(); idle?.cancel()
        let path = LocalModelFiles.directory.appendingPathComponent(preferences.selectedGGUF).path
        let threads = preferences.threads == 0 ? max(1, ProcessInfo.processInfo.activeProcessorCount - 2) : preferences.threads
        if loaded != path || loadedContext != preferences.contextSize || loadedThreads != threads || loadedGPU != (preferences.localGPU != false) {
            if model != nil { state.close(); self.model = nil }
            let gpu = preferences.localGPU != false
            model = lilac_model_open_with_backend(path, Int32(preferences.contextSize), Int32(threads), gpu ? 1 : 0)
            if model == nil && gpu { model = lilac_model_open_with_backend(path, Int32(preferences.contextSize), Int32(threads), 0) }
            guard model != nil else { throw SubtitleFiles.failure("모델을 읽지 못했습니다. 기기 메모리 또는 llama.cpp b11490의 모델 지원을 확인하세요.") }
            state.assign(model)
            loaded = path; loadedContext = preferences.contextSize; loadedThreads = threads; loadedGPU = gpu
        }
        guard let model else { throw SubtitleFiles.failure("모델을 선택하세요.") }
        try Task.checkCancellation(); state.begin(requestID)
        defer { state.end(requestID) }
        guard let result = lilac_generate(model, prompt, Int32(preferences.maxTokens), Float(preferences.temperature),
                                         Float(preferences.topP), Int32(preferences.topK), Float(preferences.repetitionPenalty)) else {
            throw SubtitleFiles.failure(String(cString: lilac_error(model)))
        }
        defer { lilac_string_free(result) }
        let backend = String(cString: lilac_backend(model))
        let elapsed = lilac_generation_seconds(model)
        let tokens = lilac_output_tokens(model)
        let report = preferences.selectedGGUF + " · " + backend + " · " + String(tokens) + " 토큰 · " + String(format: "%.1f초", elapsed)
        Task { @MainActor in LocalInferenceStatus.shared.update(report) }
        idle = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 30_000_000_000) } catch { return }
            await self?.unload()
        }
        return String(cString: result)
    }
    func unload() { idle?.cancel(); idle = nil; if model != nil { state.close(); self.model = nil }; loaded = "" }
}
struct LocalModelsView: View {
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var lastRun = LocalInferenceStatus.shared
    @ObservedObject private var modelInstaller = DesktopModelInstaller.shared
    @State private var files: [URL] = []
    @State private var importing = false
    @State private var downloading = false
    @State private var modelSearch = false
    @State private var address = ""
    @State private var error: String?
    var body: some View {
        List {
            DesktopModelsSection()
            Section("실행 위치") {
                Toggle("Metal GPU 사용 (실패 시 CPU)", isOn: Binding(get: { library.preferences.localGPU ?? true }, set: { library.preferences.localGPU = $0 }))
                Text(lastRun.text).font(.caption)
                Text("모델 파일 크기 외에 추론 메모리가 필요합니다. 기기 메모리에 맞는 모델을 선택하세요.").font(.caption)
            }
            Section("GGUF 모델") {
                ForEach(files, id: \.path) { file in
                    Button {
                        library.preferences.selectedGGUF = file.lastPathComponent
                    } label: {
                        HStack { Text(file.lastPathComponent); Spacer(); if library.preferences.selectedGGUF == file.lastPathComponent { Image(systemName: "checkmark") } }
                    }
                }.onDelete { offsets in
                    for index in offsets {
                        let file = files[index]
                        do {
                            try FileManager.default.removeItem(at: file)
                            if library.preferences.selectedGGUF == file.lastPathComponent { library.preferences.selectedGGUF = "" }
                        } catch { self.error = error.localizedDescription }
                    }
                    refresh()
                }
                Button("파일 가져오기") { importing = true }
            }
            Section("모델 다운로드") {
                Button("Hugging Face에서 찾기") { modelSearch = true }
                TextField("HTTPS GGUF URL", text: $address).textInputAutocapitalization(.never).keyboardType(.URL)
                Button("다운로드") {
                    guard let url = URL(string: address), url.scheme == "https" else { error = "HTTPS 주소를 입력하세요."; return }
                    downloading = true
                    Task {
                        do {
                            let (temporary, response) = try await URLSession.shared.download(from: url)
                            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw SubtitleFiles.failure("모델 다운로드 실패") }
                            try FileManager.default.createDirectory(at: LocalModelFiles.directory, withIntermediateDirectories: true)
                            let name = response.suggestedFilename ?? url.lastPathComponent
                            guard name.lowercased().hasSuffix(".gguf") else { throw SubtitleFiles.failure("응답 파일이 GGUF가 아닙니다.") }
                            let destination = LocalModelFiles.directory.appendingPathComponent(URL(fileURLWithPath: name).lastPathComponent)
                            try FileManager.default.moveItem(at: temporary, to: destination)
                            library.preferences.selectedGGUF = destination.lastPathComponent; refresh()
                        } catch { self.error = error.localizedDescription }
                        downloading = false
                    }
                }.disabled(downloading)
                if downloading { ProgressView() }
                Text("iPhone 메모리에 맞는 양자화 모델을 사용하세요. 모델 파일은 앱 내부에 저장됩니다.").font(.footnote)
            }
            if let error { Text(error).foregroundStyle(.red) }
        }.navigationTitle("로컬 AI 모델").onAppear(perform: refresh)
            .onChange(of: modelInstaller.revision) { _ in refresh() }
            .sheet(isPresented: $modelSearch) { HuggingFaceSearchView { address = $0 } }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
                do { library.preferences.selectedGGUF = try LocalModelFiles.importModel(result.get()).lastPathComponent; refresh() }
                catch { self.error = error.localizedDescription }
            }
    }
    private func refresh() { files = ((try? FileManager.default.contentsOfDirectory(at: LocalModelFiles.directory, includingPropertiesForKeys: nil)) ?? []).filter { $0.pathExtension.lowercased() == "gguf" } }
}
