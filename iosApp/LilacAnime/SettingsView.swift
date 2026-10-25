import SwiftUI
import UniformTypeIdentifiers
import LilacShared

/// The desktop's settings (index.html #settingsView): the categories down the side on a wide screen and across the top
/// on a phone, and the chosen category's page of blocks beside or under them.
struct SettingsView: View {
    private enum Destination: Hashable { case localModels }
    @EnvironmentObject private var store: LibraryStore
    @ObservedObject private var updater = DesktopUpdater.shared
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var apiKey = ""
    @State private var apiModels: [String] = []
    @State private var apiStatus: String?
    @State private var testingAPI = false
    @State private var draftQwenRegion = "international"
    @State private var apiRequest = UUID()
    @State private var previousProvider = ""
    @State private var error: String?
    @State private var importFont = false
    @State private var tmdbKey = ""
    @State private var tmdbStatus: String?
    @State private var tmdbTesting = false
    @State private var tmdbService = IosServices()
    @State private var category = "general"
    @State private var clearHistory = false
    @State private var path = NavigationPath()
    private let categories = ["general", "playback", "subtitle", "titles", "translate", "oped", "sync", "about"]
    private let categoryNames = ["일반", "재생", "자막", "한국어 제목 검색", "자막 자동 번역", "OP/ED 분석 데이터", "기기 동기화", "업데이트 · 정보"]
    private var gutter: CGFloat { LilacStyle.gutter(sizeClass) }
    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if sizeClass == .regular {
                    HStack(alignment: .top, spacing: 40) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("설정").font(.system(size: 28, weight: .heavy)).foregroundStyle(LilacStyle.text).padding(.horizontal, 12).padding(.bottom, 16)
                            ForEach(categories.indices, id: \.self) { navButton($0, wide: true) }
                        }.frame(width: 210, alignment: .leading).padding(.top, 30).accessibilityElement(children: .contain).accessibilityIdentifier("settings-categories")
                        page
                    }.padding(.leading, gutter)
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("설정").font(.system(size: 28, weight: .heavy)).foregroundStyle(LilacStyle.text).padding(.horizontal, gutter).padding(.top, 16)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 4) { ForEach(categories.indices, id: \.self) { navButton($0, wide: false) } }
                                .padding(.horizontal, gutter - 4).padding(.vertical, 10)
                        }.accessibilityIdentifier("settings-categories")
                        LilacStyle.line.frame(height: 1)
                        page
                    }
                }
            }.background(LilacStyle.background)
            .navigationTitle("설정").toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Destination.self) { _ in LocalModelsView() }
            .onAppear { previousProvider = store.preferences.translationProvider; apiKey = SecureKeys.load(store.preferences.translationProvider); draftQwenRegion = store.preferences.qwenRegion; tmdbKey = SecureKeys.load("tmdb") }
            .onChange(of: store.preferences.translationProvider) { provider in
                var models = store.preferences.translationModels ?? [:]
                if !previousProvider.isEmpty { models[previousProvider] = store.preferences.translationModel }
                store.preferences.translationModels = models; store.preferences.translationModel = models[provider] ?? ""
                previousProvider = provider; apiKey = SecureKeys.load(provider); draftQwenRegion = store.preferences.qwenRegion; apiModels = []; apiStatus = nil; apiRequest = UUID(); testingAPI = false
            }
            .onChange(of: store.preferences.translationModel) { model in
                var models = store.preferences.translationModels ?? [:]; models[store.preferences.translationProvider] = model; store.preferences.translationModels = models
            }
            .fileImporter(isPresented: $importFont, allowedContentTypes: [.data]) { result in
                do { store.preferences.subtitleFont = try SubtitleFiles.importFont(try result.get()) }
                catch { self.error = error.localizedDescription }
            }
            .confirmationDialog("시청 기록을 모두 삭제할까요?", isPresented: $clearHistory, titleVisibility: .visible) {
                Button("전체 삭제", role: .destructive) { store.clearHistory() }
            }
        }
    }
    /// .settings-nav button: quiet until chosen; on a wide screen the chosen one gets the lilac bar on its left.
    private func navButton(_ index: Int, wide: Bool) -> some View {
        let key = categories[index], chosen = category == key
        return Button { category = key } label: {
            Text(categoryNames[index]).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                .foregroundStyle(chosen ? LilacStyle.text : LilacStyle.muted)
                .padding(.horizontal, 14).frame(maxWidth: wide ? .infinity : nil, minHeight: 40, alignment: .leading)
                .background(chosen ? LilacStyle.text.opacity(0.09) : .clear, in: RoundedRectangle(cornerRadius: LilacStyle.radius))
                .overlay(alignment: .leading) {
                    if wide && chosen { RoundedRectangle(cornerRadius: 2).fill(LilacStyle.accent).frame(width: 3).padding(.vertical, 11) }
                }
                .contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityIdentifier("settings-category-" + key).accessibilityAddTraits(chosen ? .isSelected : [])
    }
    private var page: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                switch category {
                case "playback": playback
                case "subtitle": subtitle
                case "titles": titles
                case "translate": translate
                case "oped": oped
                case "sync": SettingsPage(title: "기기 동기화") { DeviceSyncView() }
                case "about": about
                default: general
                }
                if let error { SettingNote(error, color: .red) }
                if let error = store.persistenceError { SettingNote(error, color: .red) }
            }.frame(maxWidth: 860, alignment: .leading).frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, sizeClass == .regular ? 0 : gutter).padding(.trailing, sizeClass == .regular ? gutter : 0)
                .padding(.top, sizeClass == .regular ? 30 : 20).padding(.bottom, 40)
        }.id(category).modifier(LilacAppearMotion()).scrollDismissesKeyboard(.interactively).accessibilityIdentifier("settings-form")
    }
    private var general: some View {
        SettingsPage(title: "일반") {
            SettingBlock(title: "작품 제목 표시", note: "한국어 제목은 Anissia, TMDB, AniList, Wikidata에서 찾습니다. 찾지 못한 작품은 원래 제목으로 표시합니다.") {
                SettingChoices(options: [("original", "원래 제목"), ("ko", "한국어"), ("en", "영어")],
                               selection: Binding(get: { store.preferences.titleLanguage ?? "original" }, set: { store.preferences.titleLanguage = $0 }))
            }
            SettingBlock(title: "테마") {
                SettingChoices(options: [("system", "시스템 설정"), ("light", "라이트 모드"), ("dark", "다크 모드")], selection: $store.preferences.theme)
            }
        }
    }
    private var playback: some View {
        SettingsPage(title: "재생") {
            SettingBlock(title: "콘텐츠 / 영상 소스", note: "선택한 사이트의 애니 목록·제목·회차·영상 소스를 함께 사용합니다.") {
                SettingChoices(options: ContentSources.keys.map { ($0, ContentSources.name($0)) }, selection: $store.preferences.source)
            }
            SettingBlock(title: "기본 화질") {
                SettingChoices(options: ["Auto", "480p", "720p", "1080p"].map { ($0, $0) }, selection: $store.preferences.quality)
            }
            SettingBlock(title: "화면 비율", note: "선택한 비율로 영상을 늘립니다. 화면 채우기는 여백 없이 늘립니다.") {
                SettingChoices(options: PlayerAspect.allCases.map { ($0.rawValue, $0.title) },
                               selection: Binding(get: { store.preferences.playerAspect ?? "original" }, set: { store.preferences.selectPlayerAspect(PlayerAspect(rawValue: $0) ?? .original) }))
            }
            SettingBlock(title: "화면 맞춤", note: "맞춤은 원본 비율을 지키고, 채움은 가장자리를 잘라 화면을 채우고, 늘림은 화면 전체로 늘립니다.") {
                SettingChoices(options: PlayerFit.allCases.map { ($0.rawValue, $0.title) },
                               selection: Binding(get: { PlayerPresentation(store.preferences).fit.rawValue }, set: { store.preferences.selectPlayerFit(PlayerFit(rawValue: $0) ?? .contain) }))
            }
            SettingBlock(title: String(format: "기본 재생 배속 (%.2fx)", store.preferences.speed)) {
                Slider(value: $store.preferences.speed, in: 0.1...4, step: 0.05).tint(LilacStyle.accent).accessibilityLabel("기본 재생 배속")
                HStack { Text("0.1x"); Spacer(); Text("1.0x"); Spacer(); Text("4.0x") }.font(.system(size: 11)).foregroundStyle(LilacStyle.muted)
            }
            // 다음 화 미리 준비 (desktop 0.5.37): while an episode plays, the next one's stream, Korean subtitle (synced by the
            // site's track) and machine translation are made ready, each as set here.
            SettingBlock(title: "다음 화 미리 준비", note: "보는 동안 다음 화를 미리 준비해 두면, 넘기자마자 바로 재생되고 자막도 바로 나와요.")
            SettingSwitch(title: "영상", detail: "다음 화의 영상 주소와 시작 부분(이어볼 위치부터)을 미리 받아 둡니다. 넘길 때 그 주소가 아직 되는지 확인하고, 안 되면 새로 받아요.",
                          isOn: Binding(get: { store.preferences.prepareNextVideo ?? true }, set: { store.preferences.prepareNextVideo = $0 }))
            SettingSwitch(title: "자막과 싱크", detail: "다음 화의 한국어 자막을 미리 찾고, 사이트 자막 트랙이 있으면 싱크까지 맞춰 둡니다. 영상이 켜져 있어야 싱크를 맞출 수 있어요.",
                          isOn: Binding(get: { store.preferences.prepareNextSubtitle ?? true }, set: { store.preferences.prepareNextSubtitle = $0 }))
            SettingSwitch(title: "AI 번역", detail: "한국어 자막이 없으면 다음 화를 미리 번역해 둡니다 (설정 > 자막 자동 번역이 켜져 있을 때. 번역 API는 거기서 따로 켜야 해요).",
                          isOn: Binding(get: { store.preferences.pretranslateNext ?? true }, set: { store.preferences.pretranslateNext = $0 }))
            SettingBlock(title: "뒤로/앞으로 버튼 이동 시간", note: "재생 화면 중앙 버튼과 두 번 탭, 방향키로 이동할 시간입니다.") {
                SettingStepperRow(title: "이동 시간", value: "\(Int(store.preferences.seekSeconds))초", number: $store.preferences.seekSeconds, range: 1...120, step: 1)
            }
            SettingSwitch(title: "다음 화 자동재생", detail: "영상이 끝나면 다음 화를 재생합니다.", isOn: $store.preferences.autoPlay)
            SettingSwitch(title: "오프닝·엔딩 자동으로 건너뛰기", detail: "버튼을 누르지 않아도 알아서 넘어갑니다.", isOn: $store.preferences.autoSkip)
            SettingSwitch(title: "백그라운드 오디오", detail: "다른 앱으로 가거나 화면을 꺼도 소리를 계속 재생합니다.", isOn: $store.preferences.backgroundAudio)
            SettingSwitch(title: "다운로드에 자막 포함", detail: "회차를 받을 때 찾은 자막과 글꼴을 함께 저장합니다.",
                          isOn: Binding(get: { store.preferences.downloadSubtitles ?? true }, set: { store.preferences.downloadSubtitles = $0 }))
        }
    }
    private var subtitle: some View {
        SettingsPage(title: "자막") {
            SettingBlock(title: "자막 소스", note: "자동은 영상에 딸린 한국어 자막(트랙)을 먼저 쓰고, 없으면 Kairan → Csora → Anissia 순서로 찾습니다. 다른 소스를 고르면 그 소스를 먼저 찾습니다. 다른 언어 트랙은 플레이어 설정에서 고릅니다.") {
                SettingChoices(options: [("auto", "자동"), ("kairan", "Kairan"), ("csora", "Csora"), ("anissia", "Anissia"), ("jimaku", "Jimaku"), ("manual", "직접 선택")],
                               selection: Binding(get: { store.preferences.subtitleProvider ?? "auto" }, set: { store.preferences.subtitleProvider = $0 }))
            }
            SubtitleLineSpacingControl(value: $store.preferences.subtitleLineHeight)
            SettingBlock(title: "기본 자막 크기 (\(Int(store.preferences.subtitleSize))%)") {
                Slider(value: $store.preferences.subtitleSize, in: 50...300, step: 10).tint(LilacStyle.accent).accessibilityLabel("기본 자막 크기")
            }
            SettingBlock(title: "기본 자막 폰트", note: store.preferences.subtitleFont.isEmpty ? "폰트 파일을 지정하면 기본 글꼴 대신 사용합니다." : "사용 중: " + store.preferences.subtitleFont) {
                SettingButtons {
                    Button("폰트 파일 선택") { importFont = true }
                    if !store.preferences.subtitleFont.isEmpty { Button("파일 해제") { store.preferences.subtitleFont = "" } }
                }
            }
            SettingBlock(title: "자막 색", note: "SRT · VTT 자막의 글자와 테두리 색입니다.") {
                HStack(spacing: 20) {
                    ColorPicker("글자", selection: Binding(get: { HexColor.color(store.preferences.subtitleColor) }, set: { store.preferences.subtitleColor = HexColor.hex($0) }), supportsOpacity: false)
                        .fixedSize()
                    ColorPicker("테두리", selection: Binding(get: { HexColor.color(store.preferences.outlineColor) }, set: { store.preferences.outlineColor = HexColor.hex($0) }), supportsOpacity: false)
                        .fixedSize()
                }.font(.system(size: 13)).foregroundStyle(LilacStyle.text)
            }
            SettingSwitch(title: "자막 굵게", isOn: $store.preferences.subtitleBold)
            SettingSwitch(title: "특수 효과 자막 원본 그대로", detail: "ASS 자막의 노래 가사·간판 번역 등의 위치·색·움직임을 그대로 보여 줍니다.", isOn: $store.preferences.assEffects)
            SettingBlock(title: String(format: "자막 테두리 두께 (%.1fpx)", store.preferences.outlineWidth)) {
                Slider(value: $store.preferences.outlineWidth, in: 0...6, step: 0.5).tint(LilacStyle.accent).accessibilityLabel("테두리 두께")
            }
            SettingBlock(title: "기본 자막 위치 (\(Int(store.preferences.subtitlePadding))%)") {
                Slider(value: $store.preferences.subtitlePadding, in: 3...30, step: 1).tint(LilacStyle.accent).accessibilityLabel("자막 높이")
            }
            SettingBlock(title: "자막 싱크 (\(Int((store.preferences.subtitleOffset * 1000).rounded())) ms)",
                         note: "음수는 빠르게, 양수는 늦게 표시합니다. 조절 범위 제한 없이 직접 입력하거나 250ms씩 조절할 수 있어요.") {
                SettingSyncInput(value: store.preferences.subtitleOffset) { store.preferences.subtitleOffset = $0 }
            }
            NavigationLink { SubtitleStorageView() } label: { SettingLinkLabel(title: "저장 자막·캐시", detail: "회차마다 저장한 자막을 내보내거나 캐시를 정리합니다.") }.buttonStyle(.plain)
        }
    }
    private var titles: some View {
        SettingsPage(title: "한국어 제목 검색", intro: "Re:Anime·Animenosub·Miruro의 일본어 원제를 Anissia 제목 사전과 맞춰 한국어로 보여 주고 자막을 검색합니다. TMDB·AniList·Wikidata도 함께 사용하며, TMDB만 별도 API 키가 필요합니다.") {
            SettingBlock(title: "TMDB API 키", note: tmdbStatus ?? "themoviedb.org 설정 > API에서 발급한 API 키(v3) 또는 읽기 액세스 토큰을 넣을 수 있습니다. 키는 Keychain에 저장하고, 빈 키를 저장하면 삭제됩니다.") {
                HStack(spacing: 6) {
                    SecureField("TMDB API 키 또는 읽기 액세스 토큰", text: $tmdbKey).textInputAutocapitalization(.never).autocorrectionDisabled().settingField()
                    Button("저장") {
                        do { try SecureKeys.save(tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines), name: "tmdb"); tmdbStatus = "저장했습니다." }
                        catch { self.error = error.localizedDescription }
                    }.buttonStyle(SettingButtonStyle())
                }
                SettingButtons {
                    Button(tmdbTesting ? "확인 중…" : "연결 테스트") {
                        tmdbTesting = true
                        tmdbService.testTmdb(credential: tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines)) { result, failure in
                            Task { @MainActor in tmdbStatus = result ?? failure; tmdbTesting = false }
                        }
                    }.disabled(tmdbTesting || tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Link("TMDB API 설정", destination: URL(string: "https://www.themoviedb.org/settings/api")!)
                }
            }
            NavigationLink { CatalogIndexView() } label: { SettingLinkLabel(title: "한국어 전체 카탈로그 · 인덱스", detail: "소스의 전체 목록과 한국어 제목을 모아 검색에 씁니다.") }.buttonStyle(.plain)
            SettingNote("This product uses the TMDB API but is not endorsed or certified by TMDB.", size: 11).padding(.top, 4)
        }
    }
    private var translate: some View {
        SettingsPage(title: "자막 자동 번역", intro: "Re:Anime·Miruro 자막 트랙과 Jimaku 일본어 자막을 한국어로 번역합니다. 플레이어 설정의 번역 버튼으로 번역하고, 번역한 자막은 저장되어 다시 볼 때 바로 적용됩니다. 한쪽을 쓸 수 없으면 준비된 다른 쪽이 이어서 번역합니다.") {
            SettingBlock(title: "번역 엔진", note: "Gemini · OpenAI · Qwen · Claude는 작품 정보와 등장인물 이름을 함께 보내 대사 흐름에 맞게 번역하고, DeepL은 줄마다 그대로 번역합니다. 로컬 AI는 인터넷 없이 이 기기에서 번역합니다.") {
                SettingChoices(options: ["gemini", "openai", "deepl", "qwen", "claude", "local"].map { ($0, SubtitleLabels.engine($0)) }, selection: $store.preferences.translationProvider)
            }
            WhisperSettingsView()
            if store.preferences.translationProvider != "local" { cloudBlocks }
            NavigationLink(value: Destination.localModels) {
                SettingLinkLabel(title: "로컬 AI 모델 (llama.cpp)", detail: store.preferences.selectedGGUF.isEmpty ? "모델을 받거나 GGUF 파일을 가져옵니다." : "사용 중: " + store.preferences.selectedGGUF)
            }.buttonStyle(.plain).accessibilityIdentifier("settings-local-models")
            Group {
                SettingSwitch(title: "자막 자동 번역", detail: "한국어 자막이 없으면 그 회차의 Jimaku 일본어 자막을, 없으면 영상 사이트의 일본어 트랙을, 그것도 없으면 영어 트랙을 알아서 번역해 띄웁니다. 보고 있는 장면부터 번역합니다.", isOn: $store.preferences.autoTranslation)
                SettingSwitch(title: "번역 API로도 다음 화 미리 번역", detail: "번역 API로 번역하면 다음 화도 미리 번역해 둬서, 다음 화를 열면 처음부터 한국어로 나옵니다. 다음 화를 안 봐도 한 화 분량의 사용량을 씁니다.",
                              isOn: Binding(get: { store.preferences.prepareNextCloud ?? false }, set: { store.preferences.prepareNextCloud = $0 }))
                SettingSwitch(title: "효과음·화자 이름 빼고 번역", detail: "（ため息）·[laughs] 같은 효과음 설명, 웃음소리·신음만 있는 줄은 지우고, 줄 앞의 （소타）·SOTA: 같은 화자 이름을 떼고 번역합니다. 프롬프트를 따르지 않는 번역 전용 모델(TranslateGemma 등)에도 적용돼요.",
                              isOn: Binding(get: { store.preferences.dropCaptions ?? false }, set: { store.preferences.dropCaptions = $0 }))
                TranslationPromptSettings()
                SettingSwitch(title: "다운로드할 때 자막 번역", detail: "회차를 다운로드하면 일본어 트랙(없으면 영어 트랙)을 한국어로 번역해 함께 저장합니다. 번역 API로 번역할 때는 회차마다 API 요청을 씁니다.",
                              isOn: Binding(get: { store.preferences.translateDownloads ?? true }, set: { store.preferences.translateDownloads = $0 }))
                SettingSwitch(title: "실패하면 다른 번역 API로", detail: "키를 등록한 다른 번역 API로 이어서 번역합니다.",
                              isOn: Binding(get: { store.preferences.cloudFallback ?? true }, set: { store.preferences.cloudFallback = $0 }))
                SettingSwitch(title: "번역 API가 안 되면 로컬 AI로", detail: "사용량을 다 썼거나 연결이 끊기면 로컬 AI가 이어서 번역합니다.",
                              isOn: Binding(get: { store.preferences.translationFallback ?? true }, set: { store.preferences.translationFallback = $0 }))
            }
            SettingBlock(title: "이름·용어 표기", note: "한 줄에 하나씩 원문=한국어로 적으면 번역에서 그대로 씁니다.") {
                TextEditor(text: Binding(get: { store.preferences.translationGlossary ?? "" }, set: { store.preferences.translationGlossary = $0 }))
                    .scrollContentBackground(.hidden).frame(minHeight: 80).settingField()
            }
            localTuning
            SettingBlock(title: "자막 캐시", note: "안 쓰는 자막 파일과 번역 캐시를 정리하거나, 회차마다 저장한 자막을 관리합니다.") {
                NavigationLink { SubtitleStorageView() } label: { Text("저장 자막·캐시 관리") }.buttonStyle(SettingButtonStyle())
            }
        }
    }
    @ViewBuilder private var cloudBlocks: some View {
        let provider = store.preferences.translationProvider
        if provider == "qwen" {
            SettingBlock(title: "Qwen 지역", note: "API 키를 만든 지역을 고르세요. 키는 그 지역에서만 쓸 수 있습니다.") {
                SettingChoices(options: [("international", "국제 (싱가포르)"), ("china", "중국 (베이징)")], selection: $draftQwenRegion)
            }
        }
        SettingBlock(title: (provider == "claude" ? "Anthropic" : SubtitleLabels.engine(provider)) + " API 키",
                     note: apiStatus ?? (provider == "claude" ? "console.anthropic.com에서 발급한 Anthropic API 키를 넣어 주세요. Claude 구독과 별개로 API 크레딧 충전이 필요합니다." : nil)) {
            HStack(spacing: 6) {
                SecureField(SubtitleLabels.engine(provider) + " API 키", text: $apiKey).textInputAutocapitalization(.never).autocorrectionDisabled().settingField()
                Button("저장") {
                    fetchCloudModels(save: true)
                }.buttonStyle(SettingButtonStyle()).disabled(testingAPI)
            }
        }
        if provider != "deepl" {
            SettingBlock(title: SubtitleLabels.engine(provider) + " 모델", note: provider == "claude" ? "키를 저장하면 그 키로 쓸 수 있는 모든 모델을 불러와 Claude Opus 5.5(claude-opus-5-5)를 고릅니다. Haiku는 훨씬 싸고 빠르며, Opus는 번역이 가장 자연스럽습니다. Claude 구독(Pro·Max)과 별개로 API 크레딧을 충전해야 씁니다." : "키를 저장하면 쓸 수 있는 모델을 불러와 알맞은 모델을 고릅니다. 비워 두면 기본 모델을 씁니다.") {
                if !apiModels.isEmpty {
                    Menu {
                        Button("자동") { store.preferences.translationModel = "" }
                        ForEach(apiModels, id: \.self) { name in Button(name) { store.preferences.translationModel = name } }
                    } label: {
                        HStack { Text(store.preferences.translationModel.isEmpty ? "자동" : store.preferences.translationModel).lineLimit(1); Spacer(); Image(systemName: "chevron.up.chevron.down") }
                            .foregroundStyle(LilacStyle.text).settingField()
                    }
                } else {
                    TextField("모델 ID (빈칸: 기본값)", text: $store.preferences.translationModel).textInputAutocapitalization(.never).autocorrectionDisabled().settingField()
                }
                SettingButtons {
                    Button(testingAPI ? "불러오는 중…" : "연결 테스트 · 모델 목록") { fetchCloudModels() }.disabled(testingAPI || apiKey.isEmpty)
                }
            }
        }
    }
    /// The local model's knobs (desktop: the llama.cpp settings), together in one block.
    private var localTuning: some View {
        SettingBlock(title: "로컬 AI 세부 설정", note: "값을 바꾸면 다음 번역부터 적용됩니다. 모델별 권장값을 켜 두면 모델에 맞는 프롬프트와 샘플링을 씁니다.") {
            Toggle("모델별 권장 프롬프트·샘플링", isOn: Binding(get: { store.preferences.modelSampling ?? true }, set: { store.preferences.modelSampling = $0 }))
                .font(.system(size: 13)).tint(LilacStyle.accent)
            SettingStepperRow(title: "컨텍스트", value: "\(store.preferences.contextSize)", number: $store.preferences.contextSize, range: 512...32768, step: 512)
            SettingStepperRow(title: "스레드 (0 = 자동)", value: "\(store.preferences.threads)", number: $store.preferences.threads, range: 0...16, step: 1)
            SettingStepperRow(title: "최대 토큰", value: "\(store.preferences.maxTokens)", number: $store.preferences.maxTokens, range: 32...2048, step: 32)
            SettingStepperRow(title: "Top K", value: "\(store.preferences.topK)", number: $store.preferences.topK, range: 1...200, step: 1)
            SettingStepperRow(title: "앞 문맥", value: "\(store.preferences.contextCues)개", number: $store.preferences.contextCues, range: 0...20, step: 1)
            SettingStepperRow(title: "미리 번역", value: "\(store.preferences.prefetchAhead)개", number: $store.preferences.prefetchAhead, range: 0...40, step: 1)
            Group {
                SettingValueRow(title: "Temperature", value: String(format: "%.2f", store.preferences.temperature))
                Slider(value: $store.preferences.temperature, in: 0...2, step: 0.05).tint(LilacStyle.accent).accessibilityLabel("Temperature")
                SettingValueRow(title: "Top P", value: String(format: "%.2f", store.preferences.topP))
                Slider(value: $store.preferences.topP, in: 0.05...1, step: 0.05).tint(LilacStyle.accent).accessibilityLabel("Top P")
                SettingValueRow(title: "반복 억제", value: String(format: "%.2f", store.preferences.repetitionPenalty))
                Slider(value: $store.preferences.repetitionPenalty, in: 1...2, step: 0.05).tint(LilacStyle.accent).accessibilityLabel("반복 억제")
                Text("Thinking").font(.system(size: 13)).foregroundStyle(LilacStyle.text)
                SettingChoices(options: [("auto", "자동"), ("on", "켜기"), ("off", "끄기")], selection: $store.preferences.thinking)
                Text("프롬프트").font(.system(size: 13)).foregroundStyle(LilacStyle.text)
                TextEditor(text: $store.preferences.prompt).font(.system(size: 13, design: .monospaced)).scrollContentBackground(.hidden).frame(minHeight: 120).settingField()
            }
        }
    }
    private var oped: some View {
        SettingsPage(title: "OP/ED 분석 데이터", intro: "분석한 회차의 결과와 학습된 오디오 Fingerprint를 저장합니다.") {
            SettingSwitch(title: "오프라인 OP/ED 자동 분석", detail: "다운로드한 회차에 AniSkip 타임스탬프가 없을 때, 다운로드한 다른 회차의 오디오와 비교해 구간을 찾습니다.", isOn: $store.preferences.offlineAnalysis)
            NavigationLink { DownloadsView().navigationBarTitleDisplayMode(.inline).toolbar(.visible, for: .navigationBar) } label: { SettingLinkLabel(title: "다운로드한 회차 분석", detail: "다운로드 화면에서 받은 회차를 한꺼번에 분석합니다.") }.buttonStyle(.plain)
            SettingBlock(title: "저장된 분석 데이터 지우기") {
                SettingButtons { Button("분석 데이터 모두 삭제") { OfflineAnalyzer.clearCache() }.buttonStyle(SettingButtonStyle(danger: true)) }
            }
        }
    }
    private var about: some View {
        SettingsPage(title: "업데이트 · 정보") {
            DesktopUpdateView()
            SettingBlock(title: "시청 기록", note: "이 기기의 시청 기록을 모두 지웁니다. 기기 동기화를 쓰고 있으면 다음 동기화 때 서버에서도 지워집니다.") {
                SettingButtons { Button("시청 기록 삭제") { clearHistory = true }.buttonStyle(SettingButtonStyle(danger: true)) }
            }
        }
    }
    private func fetchCloudModels(save: Bool = false) {
        if save && apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            do { try SecureKeys.save("", name: store.preferences.translationProvider); apiModels = []; apiStatus = "API 키를 지웠습니다."; error = nil }
            catch { apiStatus = error.localizedDescription }
            return
        }
        testingAPI = true
        let provider = store.preferences.translationProvider
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines), region = draftQwenRegion
        let request = UUID(); apiRequest = request
        let config = TranslationConfig(provider: provider, key: key, model: "", region: region, terminology: "")
        tmdbService.cloudModels(config: config) { values, failure in
            guard request == apiRequest, provider == store.preferences.translationProvider else { return }
            testingAPI = false
            guard key == apiKey.trimmingCharacters(in: .whitespacesAndNewlines), region == draftQwenRegion else { apiStatus = "입력이 바뀌었습니다. 다시 확인하세요."; return }
            guard failure == nil, let values, !values.isEmpty else { apiStatus = failure ?? "사용 가능한 모델이 없습니다."; return }
            do {
                if save {
                    try SecureKeys.save(key, name: provider)
                    if provider == "qwen" { store.preferences.qwenRegion = region }
                    if !values.contains(store.preferences.translationModel) { store.preferences.translationModel = CloudModelRules.shared.default(provider: provider, names: values) }
                }
                apiModels = values; apiStatus = "연결했어요 · 모델 \(values.count)개"; error = nil
            } catch { apiStatus = error.localizedDescription }
        }
    }
}
