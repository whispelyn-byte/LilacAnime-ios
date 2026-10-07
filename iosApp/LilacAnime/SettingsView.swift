import SwiftUI
import UniformTypeIdentifiers
import LilacShared

struct SettingsView: View {
    @EnvironmentObject private var store: LibraryStore
    @State private var apiKey = ""
    @State private var error: String?
    @State private var importFont = false
    @State private var tmdbKey = ""
    @State private var tmdbStatus: String?
    @State private var tmdbTesting = false
    @State private var tmdbService = IosServices()
    var body: some View {
        NavigationStack {
            Form {
                Section("화면과 콘텐츠") {
                    Picker("테마", selection: $store.preferences.theme) { Text("시스템").tag("system"); Text("밝게").tag("light"); Text("어둡게").tag("dark") }
                    Picker("영상 소스", selection: $store.preferences.source) { Text("Linkkf").tag("linkkf"); Text("ReAnime").tag("reanime"); Text("Animenosub").tag("animenosub") }
                    Picker("기본 화질", selection: $store.preferences.quality) { ForEach(["Auto", "720p", "1080p"], id: \.self) { Text($0) } }
                }
                Section("재생") {
                    Slider(value: $store.preferences.speed, in: 0.25...2, step: 0.25) { Text("배속") }
                    Text("기본 배속 \(store.preferences.speed, specifier: "%.2f")x")
                    Stepper("탐색 \(Int(store.preferences.seekSeconds))초", value: $store.preferences.seekSeconds, in: 1...120)
                    Toggle("다음 회차 자동 재생", isOn: $store.preferences.autoPlay)
                    Toggle("OP/ED 자동 스킵", isOn: $store.preferences.autoSkip)
                    Toggle("오프라인 OP/ED 분석", isOn: $store.preferences.offlineAnalysis)
                    Toggle("백그라운드 오디오", isOn: $store.preferences.backgroundAudio)
                }
                Section("자막") {
                    Slider(value: $store.preferences.subtitleSize, in: 50...300, step: 10)
                    Text("크기 \(Int(store.preferences.subtitleSize))%")
                    TextField("글꼴 이름", text: $store.preferences.subtitleFont)
                    Button("사용자 글꼴 가져오기") { importFont = true }
                    TextField("글자 색 (#RRGGBB)", text: $store.preferences.subtitleColor)
                    TextField("테두리 색 (#RRGGBB)", text: $store.preferences.outlineColor)
                    Slider(value: $store.preferences.outlineWidth, in: 0...6, step: 0.5)
                    Toggle("굵은 글씨", isOn: $store.preferences.subtitleBold)
                    Toggle("ASS 효과", isOn: $store.preferences.assEffects)
                    Slider(value: $store.preferences.subtitlePadding, in: 0...40)
                    Stepper("싱크 \(store.preferences.subtitleOffset, specifier: "%.1f")초", value: $store.preferences.subtitleOffset, in: -120...120, step: 0.1)
                }
                Section("한국어 제목 검색 · TMDB") {
                    SecureField("API Key 또는 Read Access Token", text: $tmdbKey)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("TMDB 키 저장") {
                        do { try SecureKeys.save(tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines), name: "tmdb"); tmdbStatus = "저장했습니다." }
                        catch { self.error = error.localizedDescription }
                    }
                    Button("TMDB 연결 테스트") {
                        tmdbTesting = true
                        tmdbService.testTmdb(credential: tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines)) { result, failure in
                            Task { @MainActor in tmdbStatus = result ?? failure; tmdbTesting = false }
                        }
                    }.disabled(tmdbTesting || tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if tmdbTesting { ProgressView() }
                    if let tmdbStatus { Text(tmdbStatus) }
                    Text("키는 Keychain에 저장합니다. 자막 검색 제목을 찾을 때 작품 제목과 키를 api.themoviedb.org에 전송합니다. 빈 키를 저장하면 삭제됩니다.")
                        .font(.caption).foregroundStyle(.secondary)
                    Link("TMDB API 설정", destination: URL(string: "https://www.themoviedb.org/settings/api")!)
                    Text("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("AI 번역") {
                    Toggle("자막 자동 번역", isOn: $store.preferences.autoTranslation)
                    Picker("공급자", selection: $store.preferences.translationProvider) {
                        ForEach(["local", "gemini", "openai", "deepl", "qwen"], id: \.self) { Text($0) }
                    }
                    TextField("클라우드 모델 ID (빈칸: 기본값)", text: $store.preferences.translationModel).textInputAutocapitalization(.never)
                    if store.preferences.translationProvider != "local" {
                        SecureField("API Key", text: $apiKey).textInputAutocapitalization(.never).autocorrectionDisabled()
                        Button("키 저장") {
                            do { try SecureKeys.save(apiKey.trimmingCharacters(in: .whitespacesAndNewlines), name: store.preferences.translationProvider); error = nil }
                            catch { self.error = error.localizedDescription }
                        }
                    }
                    if store.preferences.translationProvider == "qwen" {
                        Picker("지역", selection: $store.preferences.qwenRegion) { Text("International").tag("international"); Text("China").tag("china") }
                    }
                    NavigationLink("로컬 GGUF 모델") { LocalModelsView() }
                    Stepper("컨텍스트 \(store.preferences.contextSize)", value: $store.preferences.contextSize, in: 512...32768, step: 512)
                    Stepper("스레드 \(store.preferences.threads) (0=자동)", value: $store.preferences.threads, in: 0...16)
                    Stepper("최대 토큰 \(store.preferences.maxTokens)", value: $store.preferences.maxTokens, in: 32...2048, step: 32)
                    Slider(value: $store.preferences.temperature, in: 0...2, step: 0.05)
                    Text("Temperature \(store.preferences.temperature, specifier: "%.2f")")
                    Slider(value: $store.preferences.topP, in: 0.05...1, step: 0.05)
                    Stepper("Top K \(store.preferences.topK)", value: $store.preferences.topK, in: 1...200)
                    Slider(value: $store.preferences.repetitionPenalty, in: 1...2, step: 0.05)
                    Stepper("앞 문맥 \(store.preferences.contextCues)개", value: $store.preferences.contextCues, in: 0...20)
                    Stepper("미리 번역 \(store.preferences.prefetchAhead)개", value: $store.preferences.prefetchAhead, in: 0...40)
                    Picker("Thinking", selection: $store.preferences.thinking) { ForEach(["auto", "on", "off"], id: \.self) { Text($0) } }
                    TextEditor(text: $store.preferences.prompt).frame(minHeight: 120)
                }
                Section("저장 공간") {
                    Button("시청 기록 삭제", role: .destructive) { store.clearHistory() }
                    Button("번역 캐시 삭제") { SubtitleFiles.clearTranslationCache() }
                    Button("OP/ED 분석 캐시 삭제") { OfflineAnalyzer.clearCache() }
                }
                if let error { Text(error).foregroundStyle(.red) }
                if let error = store.persistenceError { Text(error).foregroundStyle(.red) }
            }.navigationTitle("설정")
            .onAppear { apiKey = SecureKeys.load(store.preferences.translationProvider); tmdbKey = SecureKeys.load("tmdb") }
            .onChange(of: store.preferences.translationProvider) { provider in apiKey = SecureKeys.load(provider) }
            .fileImporter(isPresented: $importFont, allowedContentTypes: [.data]) { result in
                do {
                    let url = try result.get()
                    let imported = try SubtitleFiles.importFont(url)
                    store.preferences.subtitleFont = imported
                } catch { self.error = error.localizedDescription }
            }
        }
    }
}
