import SwiftUI
import UniformTypeIdentifiers
import LilacShared

struct SettingsView: View {
    @EnvironmentObject private var store: LibraryStore
    @State private var apiKey = ""
    @State private var apiModels: [String] = []
    @State private var apiStatus: String?
    @State private var testingAPI = false
    @State private var previousProvider = ""
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
                    Toggle("데스크탑 작업 공간", isOn: Binding(get: { store.preferences.desktopWorkspace ?? false }, set: { store.preferences.desktopWorkspace = $0 }))
                    Picker("작품 제목 표시", selection: Binding(get: { store.preferences.titleLanguage ?? "original" }, set: { store.preferences.titleLanguage = $0 })) {
                        Text("원래 제목").tag("original"); Text("한국어").tag("ko"); Text("영어").tag("en")
                    }
                    NavigationLink("한국어 전체 카탈로그", destination: CatalogIndexView())

                    Picker("테마", selection: $store.preferences.theme) { Text("시스템").tag("system"); Text("밝게").tag("light"); Text("어둡게").tag("dark") }
                    Picker("영상 소스", selection: $store.preferences.source) { ForEach(ContentSources.keys, id: \.self) { Text(ContentSources.name($0)).tag($0) } }
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
                    Picker("기본 자막 소스", selection: Binding(get: { store.preferences.subtitleProvider ?? "auto" }, set: { store.preferences.subtitleProvider = $0 })) {
                        Text("자동 (한국어 트랙 우선)").tag("auto")
                        Text("Kairan 우선").tag("kairan"); Text("Csora 우선").tag("csora"); Text("Anissia 우선").tag("anissia")
                        Text("직접 선택").tag("manual")
                    }
                    Text("한국어 트랙이 없으면 Kairan → Csora → Anissia 순서로 찾습니다.").font(.caption).foregroundStyle(.secondary)
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
                    Toggle("다음 화 자막 미리 번역", isOn: Binding(get: { store.preferences.pretranslateNext ?? true }, set: { store.preferences.pretranslateNext = $0 }))
                    Toggle("실패 시 등록한 다른 번역 API 사용", isOn: Binding(get: { store.preferences.cloudFallback ?? true }, set: { store.preferences.cloudFallback = $0 }))
                    Toggle("클라우드 실패 시 로컬 AI로 이어서 번역", isOn: Binding(get: { store.preferences.translationFallback ?? true }, set: { store.preferences.translationFallback = $0 }))
                    Text("현재 재생 위치에 가까운 자막부터 번역하며, 중단한 번역은 다음 시도에 이어서 처리합니다.").font(.caption).foregroundStyle(.secondary)
                    Text("이름·용어 표기 (원문=한국어)").font(.subheadline)
                    TextEditor(text: Binding(get: { store.preferences.translationGlossary ?? "" }, set: { store.preferences.translationGlossary = $0 })).frame(minHeight: 70)
                    Picker("공급자", selection: $store.preferences.translationProvider) {
                        ForEach(["local", "gemini", "openai", "deepl", "qwen"], id: \.self) { Text($0) }
                    }
                    TextField("클라우드 모델 ID (빈칸: 기본값)", text: $store.preferences.translationModel).textInputAutocapitalization(.never)
                    if store.preferences.translationProvider != "local" {
                        Button("API 연결 테스트·모델 목록") {
                            testingAPI = true
                            let config = TranslationConfig(provider: store.preferences.translationProvider, key: apiKey.trimmingCharacters(in: .whitespacesAndNewlines),
                                model: "", region: store.preferences.qwenRegion, terminology: "")
                            tmdbService.cloudModels(config: config) { values, failure in
                                apiModels = values ?? []; apiStatus = failure ?? "API 연결 성공 · 모델 " + String(values?.count ?? 0) + "개"; testingAPI = false
                            }
                        }.disabled(testingAPI || apiKey.isEmpty)
                        if testingAPI { ProgressView() }
                        if let apiStatus { Text(apiStatus).font(.caption) }
                        if !apiModels.isEmpty {
                            Picker("사용 가능한 모델", selection: $store.preferences.translationModel) {
                                Text("자동").tag("")
                                ForEach(apiModels, id: \.self) { Text($0).tag($0) }
                            }
                        }
                        SecureField("API Key", text: $apiKey).textInputAutocapitalization(.never).autocorrectionDisabled()
                        Button("키 저장") {
                            do { try SecureKeys.save(apiKey.trimmingCharacters(in: .whitespacesAndNewlines), name: store.preferences.translationProvider); error = nil }
                            catch { self.error = error.localizedDescription }
                        }
                    }
                    if store.preferences.translationProvider == "qwen" {
                        Picker("지역", selection: $store.preferences.qwenRegion) { Text("International").tag("international"); Text("China").tag("china") }
                    }
                    Toggle("모델별 권장 프롬프트·샘플링", isOn: Binding(get: { store.preferences.modelSampling ?? true }, set: { store.preferences.modelSampling = $0 }))
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
                    NavigationLink("저장 자막·캐시 관리") { SubtitleStorageView() }
                    NavigationLink("업데이트·릴리즈 노트") { DesktopUpdateView() }
                    Toggle("다운로드에 자막 포함", isOn: Binding(get: { store.preferences.downloadSubtitles ?? true }, set: { store.preferences.downloadSubtitles = $0 }))
                    Button("시청 기록 삭제", role: .destructive) { store.clearHistory() }
                    Button("번역 캐시 삭제") { SubtitleFiles.clearTranslationCache() }
                    Button("OP/ED 분석 캐시 삭제") { OfflineAnalyzer.clearCache() }
                }
                if let error { Text(error).foregroundStyle(.red) }
                if let error = store.persistenceError { Text(error).foregroundStyle(.red) }
            }.navigationTitle("설정")
            .onAppear { previousProvider = store.preferences.translationProvider; apiKey = SecureKeys.load(store.preferences.translationProvider); tmdbKey = SecureKeys.load("tmdb") }
             .onChange(of: store.preferences.translationProvider) { provider in
                var models = store.preferences.translationModels ?? [:]
                if !previousProvider.isEmpty { models[previousProvider] = store.preferences.translationModel }
                store.preferences.translationModels = models; store.preferences.translationModel = models[provider] ?? ""
                previousProvider = provider; apiKey = SecureKeys.load(provider); apiModels = []; apiStatus = nil
            }
            .onChange(of: store.preferences.translationModel) { model in
                var models = store.preferences.translationModels ?? [:]; models[store.preferences.translationProvider] = model; store.preferences.translationModels = models
            }
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
