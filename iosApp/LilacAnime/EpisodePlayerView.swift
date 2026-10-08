import SwiftUI
import Combine
import WebKit
import AVKit
import MediaPlayer
import UniformTypeIdentifiers
import LilacShared

@MainActor
final class EpisodePlayerModel: ObservableObject {
    @Published var item: PlaybackItem
    @Published var previous: [PlaybackItem] = []
    @Published var active: ResolvedStream?
    @Published var error: String?
    @Published var assets: [SubtitleAsset] = []
    @Published var subtitleFiles: [URL] = []
    @Published var subtitle: URL?
    private var sourceSubtitle: URL?
    @Published var makers: [SubtitleMaker] = []
    @Published var prefetchStatus: String?
    @Published var searching = false
    @Published var searchTitle = ""
    @Published var subtitleOffset = 0.0
    private let titleLookup = TitleLookup()
    @Published var chapters: [OfflineChapter] = []
    @Published var systemPlayback = false
    @Published var systemStream: ResolvedStream?
    let engine = MPVEngine()
    let resolver = PlaybackResolver()
    let translation = TranslationCoordinator()
    let pretranslation = TranslationCoordinator()
    private let preparer = DesktopSubtitlePreparer()
    private let nextResolver = PlaybackResolver()
    private var prefetchTask: Task<Void, Never>?
    private var automaticTask: Task<Void, Never>?
    private let service = IosServices()
    private var proxy: HLSProxy?
    private let cast = CastService()
    private var loadedSkip = false
    private var lastSave = 0.0
    private var lastSkipped = ""
    private var generation = UUID()
    private var subtitleRequest = UUID()
    private var observers: Set<AnyCancellable> = []
    init(item: PlaybackItem) {
        self.item = item; previous = item.preceding; searchTitle = item.anime.title
        resolver.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &observers)
        translation.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &observers)
    }
    func begin(library: LibraryStore) {
        active = nil; loadedSkip = false; systemPlayback = false
        let token = generation
        subtitleOffset = library.subtitleChoice(animeID: item.anime.id, episodeID: item.episodeID)?.offset ?? library.preferences.subtitleOffset
        if let choice = library.subtitleChoice(animeID: item.anime.id, episodeID: item.episodeID), let relative = choice.relativeFile {
            let file = SubtitleFiles.root.appendingPathComponent(relative).standardizedFileURL
            if file.path.hasPrefix(SubtitleFiles.root.path + "/"), FileManager.default.fileExists(atPath: file.path) {
                sourceSubtitle = file; subtitle = file; subtitleFiles = [file]
                if library.preferences.autoTranslation && !SubtitleFiles.isKorean(file) { translate(library: library) }
            }
        }
        if subtitle == nil, !item.localSubtitles.isEmpty {
            subtitleFiles = item.localSubtitles.filter { FileManager.default.fileExists(atPath: $0.path) }
            if let first = preferredSubtitle(subtitleFiles) ?? subtitleFiles.first { sourceSubtitle = first; subtitle = first }
        }
        if subtitle == nil, let saved = EpisodeSubtitleStore.shared.list(item).first(where: { !$0.translated }), let file = saved.file {
            sourceSubtitle = file; subtitle = file; subtitleFiles = [file]
            if library.preferences.autoTranslation && !SubtitleFiles.isKorean(file) { translate(library: library) }
        }
        configure(library)
        engine.onEnd = { [weak self, weak library] in
            guard let self, let library, library.preferences.autoPlay, !self.item.next.isEmpty else { return }
            let remaining = self.item.next
            var next = remaining[0]; next.next = Array(remaining.dropFirst())
            self.change(next, library: library)
        }
        engine.onProgress = { [weak self, weak library] position, duration in
            guard let self, let library, token == self.generation else { return }
            if position - self.lastSave >= 5 || position < self.lastSave {
                self.lastSave = position; self.save(library: library)
            }
            if !self.loadedSkip && duration > 0 {
                self.loadedSkip = true
                self.service.skipSegments(anilistId: self.item.anime.anime.anilistId?.int32Value ?? 0,
                    malId: self.item.anime.anime.malId?.int32Value ?? 0, episode: Int32(self.item.number), duration: Int32(duration)) { [weak self] segments, _ in
                        guard let self, token == self.generation else { return }
                        if let segments, !segments.isEmpty {
                            self.chapters = segments.map { OfflineChapter(type: $0.type, start: $0.start, end: $0.end, score: 1) }
                        }
                    }
            }
            if library.preferences.autoSkip, !self.systemPlayback,
               let chapter = self.chapters.first(where: { position >= $0.start && position < $0.end }),
               chapter.id != self.lastSkipped {
                self.lastSkipped = chapter.id; self.engine.seek(chapter.end)
            }
        }
        chapters = OfflineAnalyzer.chapters(animeID: item.anime.id, episodeID: item.episodeID)
        resolver.resolve(item)
    }
    func change(_ item: PlaybackItem, library: LibraryStore, remember: Bool = true) {
        if remember { previous.append(self.item) }
        save(library: library); engine.pause(); proxy?.stop(); proxy = nil; stopPrefetch(); automaticTask?.cancel(); translation.cancel(); cast.stop()
        generation = UUID(); titleLookup.cancel(); searchTitle = item.anime.title; self.item = item; active = nil; subtitle = nil; sourceSubtitle = nil; assets = []; subtitleFiles = []; chapters = []
        loadedSkip = false; lastSave = 0; lastSkipped = ""; systemPlayback = false
        begin(library: library)
    }
    func play(_ stream: ResolvedStream, library: LibraryStore) {
        subtitleRequest = UUID()
        let token = generation
        Task {
            do {
                proxy?.stop(); proxy = nil
                var playable = stream
                if stream.manifestKey != nil || (library.preferences.quality != "Auto" && stream.url.pathExtension.lowercased() == "m3u8" && !stream.url.isFileURL) {
                    let proxy = HLSProxy(); self.proxy = proxy; playable = try await proxy.start(stream, quality: library.preferences.quality)
                }
                guard token == generation else { return }
                active = stream; systemStream = playable; error = nil; systemPlayback = false
                configure(library)
                engine.load(playable, resume: library.resume(item.anime, episodeID: item.episodeID))
                if let subtitle { engine.subtitle(subtitle) }
                else { automaticSubtitle(stream, library: library, token: token) }
                prefetchNext(library: library, token: token)
            } catch { if token == generation { self.error = error.localizedDescription } }
        }
    }
    func applyPreferences(_ library: LibraryStore) { configure(library) }
    private func configure(_ library: LibraryStore) {
        var settings = library.preferences
        settings.subtitleOffset = subtitleOffset
        engine.configure(settings)
    }
    func shiftSubtitle(_ delta: Double, library: LibraryStore) {
        subtitleOffset += delta
        engine.set("sub-delay", String(subtitleOffset))
        library.saveSubtitle(animeID: item.anime.id, episodeID: item.episodeID, file: sourceSubtitle, offset: subtitleOffset)
    }
    func castVideo() {
        guard let active else { return }
        cast.onError = { [weak self] error in self?.error = error }
        Task {
            do { try await cast.send(active, title: item.anime.title + " · " + item.title, position: engine.position, subtitle: subtitle, subtitleOffset: subtitleOffset); engine.pause() }
            catch { self.error = error.localizedDescription }
        }
    }
    func stopCast() { cast.stop() }
    func search(_ provider: String) {
        let token = generation
        searching = true; error = nil
        if provider != "jimaku", searchTitle == item.anime.title, !TitleCandidates.shared.isKorean(title: searchTitle) {
            Task {
                let anime = item.anime.anime
                let korean = [anime.title, anime.native, anime.romaji, anime.english].first { TitleCandidates.shared.isKorean(title: $0) }
                let resolved: String
                if let korean { resolved = korean }
                else { let found = await titleLookup.resolve(searchTitle, aliases: [anime.native, anime.romaji, anime.english]); resolved = found ?? searchTitle }
                guard token == generation else { return }
                searchTitle = resolved; performSubtitleSearch(provider, token: token)
            }
        } else { performSubtitleSearch(provider, token: token) }
    }
    private func performSubtitleSearch(_ provider: String, token: UUID) {
        service.findSubtitles(provider: provider, title: searchTitle, episode: Int32(item.number), episodeKey: item.displayNumber,
            anilistId: item.anime.anime.anilistId?.int32Value ?? Int32(DesktopCatalog.shared.record(item.anime)?.anilist ?? 0)) { [weak self] assets, error in
                guard token == self?.generation else { return }
                self?.assets = assets ?? []; self?.error = error; self?.searching = false
            }
    }
    func importSubtitle(_ url: URL, library: LibraryStore, headers: [String: String] = [:], translate: Bool = true) {
        subtitleRequest = UUID()
        let request = subtitleRequest
        let token = generation
        Task {
            do {
                let files = try await SubtitleFiles.prepare(url, headers: headers)
                guard token == generation, request == subtitleRequest else { return }
                subtitleFiles = files
                if let first = preferredSubtitle(subtitleFiles) { selectSubtitle(first, library: library, translate: translate) }
            } catch { if token == generation, request == subtitleRequest { self.error = error.localizedDescription } }
        }
    }
    func selectSubtitle(_ url: URL, library: LibraryStore, translate: Bool = true) {
        subtitleRequest = UUID(); translation.cancel()
        stopPrefetch(); sourceSubtitle = url; subtitle = url; engine.subtitle(url)
        EpisodeSubtitleStore.shared.save(url, item: item, provider: "선택", translated: EpisodeSubtitleStore.shared.list(item).contains { $0.file == url && $0.translated })
        library.saveSubtitle(animeID: item.anime.id, episodeID: item.episodeID, file: url, offset: subtitleOffset)
        if translate && library.preferences.autoTranslation && !SubtitleFiles.isKorean(url) { self.translate(library: library) }
        prefetchNext(library: library, token: generation)
    }
    private func preferredSubtitle(_ files: [URL]) -> URL? {
        files.first { SubtitleEpisodeMatcher.shared.matches(name: $0.lastPathComponent, episodeNumber: Int32(item.number), expectedSeason: nil) } ?? (files.count == 1 ? files.first : nil)
    }
    private func automaticSubtitle(_ stream: ResolvedStream, library: LibraryStore, token: UUID) {
        subtitleRequest = UUID()
        let request = subtitleRequest
        guard library.preferences.subtitleProvider != "manual", subtitle == nil else { return }
        if let track = stream.subtitles.first(where: { $0.language.lowercased().hasPrefix("ko") || $0.label.contains("한국") || $0.label.lowercased().contains("korean") }) {
            importSubtitle(track.url, library: library, headers: track.headers ?? [:], translate: false)
            return
        }
        guard !["linkkf", "ohli24", "linkani"].contains(item.anime.source) else { return }
        automaticTask?.cancel()
        automaticTask = Task {
            do {
                if let prepared = try await preparer.prepare(item, tracks: stream.subtitles, preferences: library.preferences) {
                    guard token == generation, request == subtitleRequest, subtitle == nil, !Task.isCancelled else { return }
                    subtitleFiles = [prepared.0]
                    selectSubtitle(prepared.0, library: library, translate: prepared.1 == "jimaku" || !SubtitleFiles.isKorean(prepared.0))
                }
            } catch is CancellationError { }
            catch { if token == generation { self.error = error.localizedDescription } }
        }
    }
    private func findAutomaticSubtitle(_ providers: [String], library: LibraryStore, token: UUID, request: UUID) {
        guard token == generation, request == subtitleRequest, subtitle == nil, let provider = providers.first else { return }
        service.findSubtitles(provider: provider, title: searchTitle, episode: Int32(item.number), episodeKey: item.displayNumber,
            anilistId: item.anime.anime.anilistId?.int32Value ?? Int32(DesktopCatalog.shared.record(item.anime)?.anilist ?? 0)) { [weak self] results, _ in
            Task { @MainActor in
                guard let self, token == self.generation, request == self.subtitleRequest, self.subtitle == nil else { return }
                let files = (results ?? []).filter { $0.source != "post" }
                for asset in files {
                    guard let url = URL(string: asset.url) else { continue }
                    do {
                        let prepared = try await SubtitleFiles.prepare(url)
                        guard token == self.generation, request == self.subtitleRequest, self.subtitle == nil else { return }
                        if let selected = self.preferredSubtitle(prepared) {
                            self.subtitleFiles = prepared; self.selectSubtitle(selected, library: library, translate: false); return
                        }
                    } catch { /* Continue with the next file/provider. */ }
                }
                self.findAutomaticSubtitle(Array(providers.dropFirst()), library: library, token: token, request: request)
            }
        }
    }
    func translate(library: LibraryStore, fresh: Bool = false, provider: String? = nil) {
        guard let sourceSubtitle else { error = "먼저 자막을 선택하세요."; return }
        stopPrefetch()
        var preferences = library.preferences
        if let provider { preferences.translationProvider = provider; preferences.translationModel = preferences.translationModels?[provider] ?? "" }
        let usedProvider = preferences.translationProvider
        translation.translate(sourceSubtitle, preferences: preferences, position: { [weak self] in self?.engine.position ?? 0 }, anime: item.anime, fresh: fresh) { [weak self] output in
            guard let self else { return }
            EpisodeSubtitleStore.shared.save(output, item: self.item, provider: usedProvider, translated: true)
            if self.subtitle == output { self.engine.reloadSubtitle() }
            else { self.subtitle = output; self.engine.subtitle(output) }
        }
        prefetchNext(library: library, token: generation)
    }
    func loadMakers() {
        searching = true
        let token = generation
        service.subtitleMakers(title: searchTitle) { [weak self] values, failure in
            guard token == self?.generation else { return }
            self?.makers = values ?? []; self?.error = failure; self?.searching = false
        }
    }
    func searchMaker(_ maker: SubtitleMaker) {
        searching = true
        let token = generation
        service.makerSubtitles(title: searchTitle, episode: Int32(item.number), episodeKey: item.displayNumber, website: maker.website, anilistId: item.anime.anime.anilistId?.int32Value ?? Int32(DesktopCatalog.shared.record(item.anime)?.anilist ?? 0)) { [weak self] values, failure in
            guard token == self?.generation else { return }
            self?.assets = values ?? []; self?.error = failure; self?.searching = false
        }
    }
    private func stopPrefetch() {
        prefetchTask?.cancel(); prefetchTask = nil; pretranslation.cancel(); nextResolver.cancel(); prefetchStatus = nil
    }
    private func prefetchNext(library: LibraryStore, token: UUID) {
        guard library.preferences.autoTranslation, library.preferences.pretranslateNext != false, !item.next.isEmpty, prefetchTask == nil else { return }
        let next = item.next[0]
        prefetchTask = Task {
            defer { if token == generation { prefetchTask = nil } }
            while !Task.isCancelled && token == generation && (translation.running || subtitle == nil) {
                do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
            }
            guard !Task.isCancelled, token == generation else { return }
            prefetchStatus = "다음 화 자막 준비 중"
            nextResolver.resolve(next)
            while nextResolver.loading && nextResolver.streams.isEmpty && !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 250_000_000) } catch { return }
            }
            do {
                try Task.checkCancellation()
                guard token == generation, let prepared = try await preparer.prepare(next, tracks: nextResolver.streams.first?.subtitles ?? [], preferences: library.preferences) else { prefetchStatus = nil; return }
                EpisodeSubtitleStore.shared.save(prepared.0, item: next, provider: prepared.1, translated: false)
                if SubtitleFiles.isKorean(prepared.0) { prefetchStatus = "다음 화 한국어 자막 저장 완료"; return }
                prefetchStatus = "다음 화 미리 번역 중"
                pretranslation.translate(prepared.0, preferences: library.preferences, anime: next.anime, background: true) { output in
                    EpisodeSubtitleStore.shared.save(output, item: next, provider: library.preferences.translationProvider, translated: true)
                }
                while pretranslation.running && !Task.isCancelled {
                    try await Task.sleep(nanoseconds: 500_000_000)
                }
                try Task.checkCancellation()
                if token == generation { prefetchStatus = pretranslation.error.map { "다음 화 번역: " + $0 } ?? "다음 화 자막 번역 저장 완료" }
            } catch is CancellationError { }
            catch { if token == generation { prefetchStatus = error.localizedDescription } }
        }
    }
    func save(library: LibraryStore) {
        guard active != nil, !systemPlayback else { return }
        library.progress(anime: item.anime, episodeID: item.episodeID, title: item.title, number: item.number,
            watchURL: item.watchURL.absoluteString, directURL: active?.url.absoluteString, position: engine.position, duration: engine.duration)
    }
    func shutdown(library: LibraryStore) {
        save(library: library); stopPrefetch(); automaticTask?.cancel(); generation = UUID(); engine.shutdown(); resolver.cancel(); translation.shutdown(); service.cancel(); titleLookup.cancel(); proxy?.stop(); proxy = nil; cast.stop()
    }
}
struct EpisodePlayerView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: DownloadStore
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: EpisodePlayerModel
    @ObservedObject private var savedSubtitles = EpisodeSubtitleStore.shared
    @State private var showWeb = false
    @State private var importer = false
    @State private var subtitleSheet = false
    @State private var settings = false
    @State private var settingsTab = 0
    @State private var originalOrientation: UIInterfaceOrientation = .portrait
    init(item: PlaybackItem) { _model = StateObject(wrappedValue: EpisodePlayerModel(item: item)) }
    var body: some View {
        ZStack {
            Color.black
            if UIShowcase.enabled {
                LinearGradient(colors: [Color(red: 0.18, green: 0.12, blue: 0.3), .black], startPoint: .topLeading, endPoint: .bottomTrailing)
                Text("UI PREVIEW · 예시 데이터").font(.caption).foregroundStyle(.white.opacity(0.4))
            } else {
                GeometryReader { geometry in
                    let size = model.engine.aspect.stageSize(in: geometry.size)
                    MPVPlayerView(engine: model.engine).frame(width: size.width, height: size.height)
                        .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
                }
                ProviderPlayerView(resolver: model.resolver).opacity(showWeb ? 1 : 0.001).allowsHitTesting(showWeb)
            }
            if model.systemPlayback, let stream = model.systemStream {
                SystemPlayerView(stream: stream, resume: library.resume(model.item.anime, episodeID: model.item.episodeID), onProgress: { position, duration in
                    library.progress(anime: model.item.anime, episodeID: model.item.episodeID, title: model.item.title,
                        number: model.item.number, watchURL: model.item.watchURL.absoluteString, directURL: stream.url.absoluteString, position: position, duration: duration)
                })
            }
            if !showWeb && !model.systemPlayback {
                PlayerControls(engine: model.engine, seek: library.preferences.seekSeconds,
                    title: DesktopCatalog.shared.title(model.item.anime.anime, source: model.item.anime.source, language: library.preferences.titleLanguage), episode: model.item.title,
                    canPrevious: !model.previous.isEmpty, canNext: !model.item.next.isEmpty,
                    back: { dismiss() },
                    previous: { if var item = model.previous.popLast() { item.next = [model.item] + model.item.next; model.change(item, library: library, remember: false) } },
                    next: nextEpisode,
                    settings: { settings = true },
                    chapter: model.chapters.first(where: { model.engine.position >= $0.start && model.engine.position < $0.end }),
                    skipChapter: { if let chapter = model.chapters.first(where: { model.engine.position >= $0.start && model.engine.position < $0.end }) { model.engine.seek(chapter.end) } },
                    adjustSubtitle: { model.shiftSubtitle($0, library: library) },
                    keyboardEnabled: !settings && !subtitleSheet && !importer)
            } else {
                VStack { HStack { Button { dismiss() } label: { Image(systemName: "arrow.left") }; Spacer(); Button { settings = true } label: { Image(systemName: "gearshape") } }.font(.title3).padding(20).background(.black.opacity(0.65)); Spacer() }.foregroundStyle(.white)
            }
            if !showWeb && model.active == nil && !UIShowcase.enabled {
                VStack(spacing: 12) {
                    if model.resolver.loading { ProgressView("영상을 준비하고 있어요").tint(.white) }
                    if let error = model.error ?? model.resolver.error { Text(error).font(.caption).multilineTextAlignment(.center); Button("다시 시도") { model.begin(library: library) }; Button("웹 플레이어 열기") { showWeb = true } }
                }.padding(24).foregroundStyle(.white).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 16))
            }
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(.black).ignoresSafeArea()
            .background(PlayerOrientationView().allowsHitTesting(false))
            .accessibilityIdentifier("fullscreen-player")
            .statusBarHidden(true).persistentSystemOverlays(.hidden)
            .toolbar(.hidden, for: .navigationBar).toolbar(.hidden, for: .tabBar)
            .onAppear { originalOrientation = OrientationController.current; OrientationController.landscape(); if UIShowcase.enabled { model.engine.position = 183; model.engine.duration = 1440 } else { model.begin(library: library) } }
            .onDisappear { model.shutdown(library: library); OrientationController.restore(originalOrientation) }
            .onChange(of: model.resolver.streams) { streams in
                if let stream = streams.first(where: { $0.label == library.preferences.preferredStream }) ?? streams.first,
                    model.active == nil || (model.active?.url == stream.url && model.active?.manifestKey != stream.manifestKey) {
                    model.play(stream, library: library); showWeb = false
                }
            }
            .fileImporter(isPresented: $importer, allowedContentTypes: [.data, .text]) { result in
                do { model.importSubtitle(try result.get(), library: library) } catch { model.error = error.localizedDescription }
            }
            .sheet(isPresented: $settings) { playerSettings }
            .sheet(isPresented: $subtitleSheet) { NavigationStack { subtitleList.navigationTitle("자막 선택").toolbar { Button("닫기") { subtitleSheet = false } } } }
    }
    private func nextEpisode() {
        let remaining = model.item.next
        guard !remaining.isEmpty else { return }
        var item = remaining[0]; item.next = Array(remaining.dropFirst()); model.change(item, library: library)
    }
    private func openSubtitleList() { settings = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { subtitleSheet = true } }
    private var playerSettings: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("플레이어 설정", selection: $settingsTab) { Text("재생").tag(0); Text("자막").tag(1); Text("자막 모양").tag(2) }.pickerStyle(.segmented).accessibilityIdentifier("player-settings-tabs").padding(16)
                Form {
                    if settingsTab == 0 { playbackSettings }
                    else if settingsTab == 1 { subtitleSettings }
                    else { styleSettings }
                }
            }.navigationTitle("플레이어 설정").navigationBarTitleDisplayMode(.inline).toolbar { Button("닫기") { settings = false } }
        }.presentationDetents([.large])
    }
    @ViewBuilder private var playbackSettings: some View {
        Section("영상 서버 · 화질") {
            ForEach(model.resolver.streams) { stream in
                Button { library.preferences.preferredStream = stream.label; model.play(stream, library: library); showWeb = false } label: {
                    HStack { Text(stream.label); Spacer(); if model.active?.id == stream.id { Image(systemName: "checkmark") } }
                }
            }
            Picker("화질", selection: $library.preferences.quality) { ForEach(["Auto", "480p", "720p", "1080p"], id: \.self) { Text($0) } }
                .onChange(of: library.preferences.quality) { _ in if let stream = model.active { model.play(stream, library: library) } }
            Button(showWeb ? "네이티브 플레이어" : "웹 플레이어") { showWeb.toggle(); settings = false }
        }
        Section("재생") {
            Picker("재생 속도", selection: $library.preferences.speed) { ForEach([0.1, 0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2], id: \.self) { Text(String(format: "%.2fx", $0)).tag($0) } }.onChange(of: library.preferences.speed) { model.engine.finishSpaceHold(); model.engine.setSpeed($0) }
            Picker("화면 비율", selection: Binding(get: { library.preferences.playerAspect ?? "original" }, set: {
                library.preferences.playerAspect = $0
                if $0 == "original" { library.preferences.playerFit = "contain"; model.engine.setFit("contain") }
                model.engine.setAspect(PlayerAspect(rawValue: $0) ?? .original)
            })) { ForEach(PlayerAspect.allCases, id: \.rawValue) { Text($0.title).tag($0.rawValue) } }
                .accessibilityIdentifier("player-aspect")
            Toggle("다음 화 자동 재생", isOn: $library.preferences.autoPlay)
            Toggle("OP/ED 자동 스킵", isOn: $library.preferences.autoSkip)
            Picker("화면 맞춤", selection: Binding(get: { library.preferences.playerFit ?? "contain" }, set: { library.preferences.playerFit = $0; model.engine.setFit($0) })) { Text("맞춤").tag("contain"); Text("채움").tag("cover"); Text("늘림").tag("stretch") }
            Toggle("음소거", isOn: Binding(get: { model.engine.muted }, set: { _ in model.engine.toggleMute() }))
            Slider(value: Binding(get: { model.engine.volume }, set: { model.engine.setVolume($0) }), in: 0...100) { Text("볼륨") }
            ForEach(model.engine.tracks.filter { $0.type == "audio" }) { track in Button(track.title) { model.engine.selectTrack(track) } }
        }
        Section("저장 · 연결") {
            if let active = model.active {
                Button("회차 다운로드") { downloads.download(model.item, stream: active, quality: library.preferences.quality) }
                Button("Cast 재생") { model.castVideo() }
                Button("시스템 재생 / PiP") { model.engine.pause(); model.systemPlayback = true; showWeb = false; settings = false }
                if model.systemPlayback { Button("mpv로 재생") { model.systemPlayback = false; model.play(active, library: library); settings = false } }
            }
            HStack { Text("AirPlay / Chromecast"); Spacer(); AirPlayButton().frame(width: 32, height: 32); CastButton().frame(width: 32, height: 32) }
            Button("Cast 중계 종료") { model.stopCast() }
        }
        if let error = model.error { Section { Text(error).foregroundStyle(.red) } }
    }
    @ViewBuilder private var subtitleSettings: some View {
        Section("자막 가져올 곳") {
            Picker("자막 소스", selection: Binding(get: { library.preferences.subtitleProvider ?? "auto" }, set: { library.preferences.subtitleProvider = $0 })) {
                Text("자동").tag("auto"); Text("Kairan").tag("kairan"); Text("Csora").tag("csora"); Text("Anissia").tag("anissia"); Text("Jimaku / AI 번역").tag("jimaku"); Text("직접 선택").tag("manual")
            }
            Button("한국어 자막 다시 찾기") { model.search(library.preferences.subtitleProvider == "auto" ? "kairan" : library.preferences.subtitleProvider ?? "kairan") }
            Button("자막 검색 · Jimaku 파일 · 저장 자막", action: openSubtitleList)
            Button("자막 파일 열기") { settings = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { importer = true } }
            if let subtitle = model.subtitle { Text(subtitle.lastPathComponent).font(.caption) }
            Button(model.engine.subtitlesVisible ? "자막 표시 끄기" : "자막 표시 켜기") { model.engine.toggleSubtitleVisibility() }
            ForEach(model.active?.subtitles ?? model.resolver.subtitles) { track in
                Button(track.label) { model.importSubtitle(track.url, library: library, headers: track.headers ?? [:]) }
            }
        }
        Section("번역") {
            Button("번역 API") { model.translate(library: library, provider: library.preferences.translationProvider == "local" ? "gemini" : library.preferences.translationProvider) }
            Button("로컬 AI") { model.translate(library: library, provider: "local") }
            Button("캐시 없이 다시 번역") { model.translate(library: library, fresh: true) }
            TranslationStatus(coordinator: model.translation)
            if let status = model.prefetchStatus { Text(status).font(.caption) }
        }
        Section("이 회차에 저장한 자막") {
            ForEach(savedSubtitles.list(model.item)) { record in
                if let file = record.file {
                    HStack {
                        Button(record.name) { model.selectSubtitle(file, library: library, translate: !record.translated) }
                        Spacer(); ShareLink(item: file) { Image(systemName: "square.and.arrow.up") }
                        Button(role: .destructive) { savedSubtitles.remove(record.id) } label: { Image(systemName: "trash") }
                    }
                }
            }
        }
        if model.searching { ProgressView("자막을 찾는 중") }
        ForEach(Array(model.assets.enumerated()), id: \.offset) { _, asset in
            if asset.source == "post", let url = URL(string: asset.url) { Link(asset.name, destination: url) }
            else { Button(asset.name) { if let url = URL(string: asset.url) { model.importSubtitle(url, library: library) } } }
        }
    }
    private var styleSettings: some View {
        Section("자막 모양") {
            Slider(value: $library.preferences.subtitleSize, in: 50...300, step: 10)
            Text("크기 \(Int(library.preferences.subtitleSize))%")
            Toggle("굵게", isOn: $library.preferences.subtitleBold)
            Toggle("ASS 효과", isOn: $library.preferences.assEffects)
            TextField("폰트", text: $library.preferences.subtitleFont)
            TextField("글자 색", text: $library.preferences.subtitleColor)
            TextField("테두리 색", text: $library.preferences.outlineColor)
            Slider(value: $library.preferences.outlineWidth, in: 0...6, step: 0.5)
            Slider(value: $library.preferences.subtitlePadding, in: 0...40)
            HStack { Button("빠르게 −0.5초") { model.shiftSubtitle(-0.5, library: library) }; Text("\(model.subtitleOffset, specifier: "%.1f")초"); Button("늦게 +0.5초") { model.shiftSubtitle(0.5, library: library) } }
            SubtitleSyncInput(value: model.subtitleOffset) { model.shiftSubtitle($0 - model.subtitleOffset, library: library) }
            Button("싱크 초기화") { model.shiftSubtitle(-model.subtitleOffset, library: library) }
        }.onChange(of: library.preferences) { _ in model.applyPreferences(library) }
    }
    private var subtitleList: some View {
        List {
                        HStack {
                            Button("-0.1초") { model.shiftSubtitle(-0.1, library: library) }
                            Text("자막 싱크 \(model.subtitleOffset, specifier: "%.1f")초")
                            Button("+0.1초") { model.shiftSubtitle(0.1, library: library) }
                        }
                        TextField("한국어 검색 제목", text: $model.searchTitle)
                        HStack { ForEach(["kairan","csora","anissia","jimaku"], id: \.self) { provider in Button(provider) { model.search(provider) } } }
                        Button("Anissia 자막 제작자 목록") { model.loadMakers() }
                        ForEach(Array(model.makers.enumerated()), id: \.offset) { _, maker in
                            Button(maker.name + " · " + maker.status) { model.searchMaker(maker) }
                        }
                        Section("다시 번역") {
                            ForEach(["local", "gemini", "openai", "deepl", "qwen"], id: \.self) { provider in
                                if provider == "local" ? !library.preferences.selectedGGUF.isEmpty : !SecureKeys.load(provider).isEmpty {
                                    Button(provider + "로 캐시 없이 다시 번역") { model.translate(library: library, fresh: true, provider: provider) }
                                }
                            }
                        }
                        Section("이 회차에 저장한 자막") {
                            ForEach(savedSubtitles.list(model.item)) { record in
                                if let file = record.file {
                                    HStack {
                                        Button(record.name) { model.selectSubtitle(file, library: library, translate: !record.translated); subtitleSheet = false }
                                        ShareLink(item: file) { Image(systemName: "square.and.arrow.up") }
                                    }
                                }
                            }
                        }
                        if model.searching { ProgressView() }
                        ForEach(Array(model.assets.enumerated()), id: \.offset) { _, asset in
                            if asset.source == "post", let url = URL(string: asset.url) { Link(asset.name, destination: url) }
                            else { Button(asset.name) { if let url = URL(string: asset.url) { model.importSubtitle(url, library: library); subtitleSheet = false } } }
                        }
                        if let error = model.error { Text(error).foregroundStyle(.red) }
                    }
    }
}
struct PlayerControls: View {
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject var engine: MPVEngine
    let seek: Double
    let title: String
    let episode: String
    let canPrevious: Bool
    let canNext: Bool
    let back: () -> Void
    let previous: () -> Void
    let next: () -> Void
    let settings: () -> Void
    let chapter: OfflineChapter?
    let skipChapter: () -> Void
    let adjustSubtitle: (Double) -> Void
    let keyboardEnabled: Bool
    @GestureState private var holdingSpeed = false
    @State private var dragging = false
    @State private var value = 0.0
    @State private var visible = true
    @State private var locked = false
    @State private var interaction = UUID()
    @State private var feedback = ""
    private var windowInsets: UIEdgeInsets { UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first(where: \.isKeyWindow)?.safeAreaInsets ?? .zero }
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                HStack(spacing: 0) {
                    tapZone(-seek)
                    tapZone(0)
                    tapZone(seek)
                }
                if visible && !locked {
                    LinearGradient(colors: [.black.opacity(0.55), .clear, .black.opacity(0.5)], startPoint: .top, endPoint: .bottom).allowsHitTesting(false)
                    VStack(spacing: 0) {
                        HStack(spacing: 10) {
                            control("arrow.left", label: "뒤로", action: back)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(title).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                                Text(episode).font(.system(size: 11)).foregroundStyle(.white.opacity(0.65)).lineLimit(1)
                            }.padding(.horizontal, 12).padding(.vertical, 8).background(.black.opacity(0.4), in: RoundedRectangle(cornerRadius: 14))
                            Spacer(minLength: 0)
                            control("gearshape", label: "플레이어 설정", action: settings)
                        }
                        Spacer(minLength: 4)
                        HStack(spacing: 12) {
                            control("backward.end.fill", label: "이전 회차", enabled: canPrevious, action: previous)
                            control("backward.fill", label: "\(Int(seek))초 뒤로") { engine.skip(-seek) }
                            Button { engine.toggle(); touch() } label: {
                                Image(systemName: engine.paused ? "play.fill" : "pause.fill").font(.system(size: 30)).foregroundStyle(.black)
                                    .frame(width: 64, height: 64).background(.white.opacity(0.92), in: Circle())
                            }.accessibilityLabel(engine.paused ? "재생" : "일시정지")
                            control("forward.fill", label: "\(Int(seek))초 앞으로") { engine.skip(seek) }
                            control("forward.end.fill", label: "다음 회차", enabled: canNext, action: next)
                        }
                        Spacer(minLength: 4)
                        HStack(spacing: 8) {
                            HStack(spacing: 8) {
                                Text(clock(dragging ? value : engine.position)).monospacedDigit()
                                Slider(value: Binding(get: { dragging ? value : min(max(engine.position, 0), max(engine.duration, 1)) }, set: { value = $0 }),
                                    in: 0...max(engine.duration, 1), onEditingChanged: { editing in
                                        if editing { value = engine.position }; dragging = editing
                                        if !editing { engine.seek(value) }; touch()
                                    }).tint(LilacStyle.accent)
                                Text(clock(engine.duration)).monospacedDigit().foregroundStyle(.white.opacity(0.65))
                            }.font(.system(size: 11)).padding(.horizontal, 12).padding(.vertical, 2)
                                .background(.black.opacity(0.52), in: RoundedRectangle(cornerRadius: 20))
                            control("lock.fill", label: "화면 잠금") { locked = true; visible = false }
                        }
                    }.padding(.horizontal, max(24, max(geometry.safeAreaInsets.leading, max(windowInsets.left, windowInsets.right)))).padding(.top, max(14, max(geometry.safeAreaInsets.top, windowInsets.top))).padding(.bottom, max(14, max(geometry.safeAreaInsets.bottom, windowInsets.bottom)))
                }
                if locked {
                    VStack { Spacer(); HStack { Spacer(); control("lock.open.fill", label: "잠금 해제") { locked = false; visible = true } }.padding(18) }
                }
                if !feedback.isEmpty { Text(feedback).font(.headline).padding(14).background(.black.opacity(0.7), in: Capsule()).allowsHitTesting(false) }
                if engine.buffering { ProgressView().tint(.white).allowsHitTesting(false) }
                if engine.speedBoosted { VStack { Text("2배속").font(.headline).padding(.horizontal, 18).padding(.vertical, 8).background(.black.opacity(0.75), in: Capsule()).accessibilityIdentifier("speed-boost"); Spacer() }.padding(.top, 72).allowsHitTesting(false) }
                if let chapter, !locked {
                    VStack { Spacer(); HStack { Spacer(); Button(action: skipChapter) { Label(chapter.type.uppercased() + " 건너뛰기", systemImage: "forward.fill").font(.caption.bold()).padding(12).background(.black.opacity(0.65), in: Capsule()) } }.padding(.bottom, visible ? 70 : 18).padding(.trailing, 18) }
                }
            }.frame(width: geometry.size.width, height: geometry.size.height)
        }.foregroundStyle(.white).buttonStyle(.plain)
            .background {
                Group {
                    Button("") { engine.skip(-seek) }.keyboardShortcut(.leftArrow, modifiers: [])
                    Button("") { engine.skip(seek) }.keyboardShortcut(.rightArrow, modifiers: [])
                    Button("") { engine.setVolume(engine.volume + 5) }.keyboardShortcut(.upArrow, modifiers: [])
                    Button("") { engine.setVolume(engine.volume - 5) }.keyboardShortcut(.downArrow, modifiers: [])
                    Button("") { engine.toggleMute() }.keyboardShortcut("m", modifiers: [])
                    Button("", action: back).keyboardShortcut(.escape, modifiers: [])
                    Group {
                        Button("") { engine.toggleSubtitleVisibility() }.keyboardShortcut("c", modifiers: [])
                        Button("") { shiftSubtitle(-0.5) }.keyboardShortcut("z", modifiers: [])
                        Button("") { shiftSubtitle(0.5) }.keyboardShortcut("x", modifiers: [])
                        Button("") { if chapter != nil { skipChapter() } }.keyboardShortcut("s", modifiers: [])
                        Button("") { changeSpeed(-0.25) }.keyboardShortcut("[", modifiers: [])
                        Button("") { changeSpeed(0.25) }.keyboardShortcut("]", modifiers: [])
                        Button("") { if canNext { next() } }.keyboardShortcut(.pageDown, modifiers: [])
                        Button("") { if canPrevious { previous() } }.keyboardShortcut(.pageUp, modifiers: [])
                        ForEach(0..<10) { digit in Button("") { engine.seek(engine.duration * Double(digit) / 10) }.keyboardShortcut(KeyEquivalent(Character(String(digit))), modifiers: []) }
                    }
                }.disabled(locked).frame(width: 0, height: 0).opacity(0).accessibilityHidden(true)
            }
            .background(SpaceHoldKeyboard(enabled: keyboardEnabled && !locked,
                began: engine.beginSpaceHold, ended: { engine.finishSpaceHold(toggle: true); touch() }, cancelled: { engine.finishSpaceHold() }).frame(width: 0, height: 0))
            .onChange(of: holdingSpeed) { holding in if !holding { engine.finishSpaceHold() } }
            .onDisappear { engine.finishSpaceHold() }
            .task(id: interaction) {
                guard !UIShowcase.enabled, !engine.paused, !locked, !dragging else { return }
                do { try await Task.sleep(nanoseconds: 4_000_000_000) } catch { return }
                if !dragging { withAnimation { visible = false } }
            }
            .onChange(of: engine.paused) { paused in if paused { visible = true }; touch() }
    }
    private func shiftSubtitle(_ delta: Double) {
        adjustSubtitle(delta)
    }
    private func changeSpeed(_ delta: Double) {
        library.preferences.speed = max(0.25, min(2, library.preferences.speed + delta))
        engine.finishSpaceHold(); engine.setSpeed(library.preferences.speed)
    }
    private func tapZone(_ delta: Double) -> some View {
        Color.clear.contentShape(Rectangle()).onTapGesture(count: 2) {
            guard !locked else { return }
            if delta == 0 { engine.toggle() } else { engine.skip(delta); feedback = "\(delta > 0 ? "+" : "−")\(Int(abs(delta)))초" }
            touch()
            Task { try? await Task.sleep(nanoseconds: 700_000_000); feedback = "" }
        }.onTapGesture { guard !locked else { return }; withAnimation { visible.toggle() }; touch() }
            .simultaneousGesture(LongPressGesture(minimumDuration: 0.4, maximumDistance: 24)
                .sequenced(before: DragGesture(minimumDistance: 0))
                .updating($holdingSpeed) { value, state, _ in if case .second(true, _) = value { state = true } }
                .onChanged { value in if case .second(true, _) = value, !locked, keyboardEnabled { engine.boostTouchHold() } }
                .onEnded { _ in engine.finishSpaceHold(); touch() })
    }
    private func icon(_ name: String, size: CGFloat = 44) -> some View {
        Image(systemName: name).font(.system(size: 19)).frame(width: size, height: size)
            .background(.black.opacity(0.48), in: Circle()).overlay(Circle().stroke(.white.opacity(0.1), lineWidth: 1))
    }
    private func control(_ name: String, label: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button { action(); touch() } label: { icon(name).opacity(enabled ? 1 : 0.3) }.disabled(!enabled).accessibilityLabel(label)
    }
    private func touch() { interaction = UUID() }
    private func clock(_ seconds: Double) -> String { let value = seconds.isFinite ? max(0, Int(seconds)) : 0; return String(format: "%d:%02d", value / 60, value % 60) }
}
struct EngineError: View {
    @ObservedObject var engine: MPVEngine
    var body: some View { if let error = engine.error { Text(error).foregroundStyle(.red) } }
}
struct TranslationStatus: View {
    @ObservedObject var coordinator: TranslationCoordinator
    var body: some View {
        if let status = coordinator.status { Text(status).font(.caption).foregroundStyle(.secondary) }
        if coordinator.running { ProgressView(value: coordinator.progress); Button("번역 중단") { coordinator.cancel() } }
        if let error = coordinator.error { Text(error).foregroundStyle(.red) }
    }
}
struct ProviderPlayerView: UIViewRepresentable {
    let resolver: PlaybackResolver
    func makeUIView(context: Context) -> WKWebView { resolver.webView }
    func updateUIView(_ view: WKWebView, context: Context) {}
}
struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView { let view = AVRoutePickerView(); view.prioritizesVideoDevices = true; return view }
    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}
struct VolumeControl: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView { MPVolumeView() }
    func updateUIView(_ view: MPVolumeView, context: Context) {}
}
struct PlayerOrientationView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {}
    final class Controller: UIViewController {
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            // The SwiftUI cover has completed presentation before requesting rotation.
            OrientationController.landscape()
        }
    }
}
@MainActor
enum OrientationController {
    static var supported: UIInterfaceOrientationMask = .allButUpsideDown
    static var current: UIInterfaceOrientation { UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.interfaceOrientation ?? .portrait }
    static func restore(_ orientation: UIInterfaceOrientation) {
        supported = .allButUpsideDown
        switch orientation { case .landscapeLeft: update(.landscapeLeft); case .landscapeRight: update(.landscapeRight); default: update(.portrait) }
    }
    static func landscape() { supported = .landscape; update(.landscape) }
    static func portrait() { supported = .allButUpsideDown; update(.portrait) }
    private static func update(_ mask: UIInterfaceOrientationMask) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }) else { return }
        for window in scene.windows where window.isKeyWindow {
            var controller = window.rootViewController
            while let value = controller {
                value.setNeedsUpdateOfSupportedInterfaceOrientations()
                controller = value.presentedViewController
            }
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { error in
            NSLog("Player orientation request failed: %@", error.localizedDescription)
        }
    }
}
struct SystemPlayerView: UIViewControllerRepresentable {
    let stream: ResolvedStream
    let resume: Double
    let onProgress: (Double, Double) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(onProgress: onProgress) }
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let view = AVPlayerViewController()
        let asset = AVURLAsset(url: stream.url, options: ["AVURLAssetHTTPHeaderFieldsKey": stream.headers.merging(["Referer": stream.referer]) { left, _ in left }])
        let player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
        view.player = player; view.allowsPictureInPicturePlayback = true; view.canStartPictureInPictureAutomaticallyFromInline = true
        context.coordinator.player = player
        context.coordinator.observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 600), queue: .main) { time in
            let duration = player.currentItem?.duration.seconds ?? 0
            if time.seconds.isFinite, duration.isFinite { context.coordinator.onProgress(time.seconds, duration) }
        }
        if resume > 0 { player.seek(to: CMTime(seconds: resume, preferredTimescale: 600)) }
        player.play(); return view
    }
    func updateUIViewController(_ view: AVPlayerViewController, context: Context) {}
    static func dismantleUIViewController(_ view: AVPlayerViewController, coordinator: Coordinator) {
        if let observer = coordinator.observer { coordinator.player?.removeTimeObserver(observer) }
        if let player = coordinator.player {
            let position = player.currentTime().seconds
            let duration = player.currentItem?.duration.seconds ?? 0
            if position.isFinite, duration.isFinite { coordinator.onProgress(position, duration) }
            player.pause()
        }
        coordinator.observer = nil
    }
    final class Coordinator {
        var player: AVPlayer?
        var observer: Any?
        let onProgress: (Double, Double) -> Void
        init(onProgress: @escaping (Double, Double) -> Void) { self.onProgress = onProgress }
    }
}
