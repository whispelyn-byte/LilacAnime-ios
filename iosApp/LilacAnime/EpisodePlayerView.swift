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
    @Published private(set) var playbackError: String?
    @Published var assets: [SubtitleAsset] = []
    @Published var subtitleFiles: [URL] = []
    @Published var subtitle: URL?
    @Published private(set) var selectedJimakuURL: String?
    @Published var koreanOffer: String?
    private var offeredSubtitle: (URL, String)?
    private var offerTask: Task<Void, Never>?
    private var offerTimer: Task<Void, Never>?
    private var selectedProvider = ""
    /// desktop toast(): a short note over the player.
    struct Toast: Equatable { let id = UUID(); let text: String }
    @Published private(set) var toast: Toast?
    func notify(_ text: String) { toast = Toast(text: text) }
    /// desktop koreanFound: a Korean subtitle (a fansub, Linkkf's) was put on for this episode.

    private var koreanFound = false
    private var searchProvider = "kairan"
    private var skipEntered: (id: String, countdown: PlaybackSkipCountdown)?
    private var playRequest = UUID()
    private var sourceSubtitle: URL?
    @Published var makers: [SubtitleMaker] = []
    @Published var prefetchStatus: String?
    @Published var searching = false
    @Published private(set) var subtitleResultsProvider = ""
    @Published private(set) var subtitleSearchError: String?
    @Published var searchTitle = ""
    @Published var subtitleOffset = 0.0
    /// Player subtitle tab (app.js renderSubtitleSheet): the chip pressed last, the list opened under the chips and the state line.
    @Published private(set) var pressedSource: String?
    private var pressedFor: URL?
    @Published private(set) var pickedSheet: String?
    private var sheetPicked = false
    @Published private(set) var subtitleState: String?
    @Published private(set) var selectedTrackURL: URL?
    @Published private(set) var selectedMaker: String?
    private var chipTask: Task<Void, Never>?
    private var chipRequest = UUID()
    @Published private var koreanFiles: [URL: Bool] = [:]
    private(set) var subtitleClassification: Task<Void, Never>?
    private var refreshedSeries: Set<String> = []
    private var pendingFraction: Double?
    private let titleLookup = TitleLookup()
    @Published var chapters: [OfflineChapter] = []
    @Published var systemPlayback = false
    @Published var systemStream: ResolvedStream?
    let engine = MPVEngine()
    lazy var pictureInPicture = PlayerPictureInPicture(engine: engine)
    let resolver = PlaybackResolver()
    let translation = TranslationCoordinator()
    let pretranslation = TranslationCoordinator()
    private let preparer: DesktopSubtitlePreparer
    let jimaku: DesktopJimakuCatalog
    private let nextResolver = PlaybackResolver()
    private var prefetchTask: Task<Void, Never>?
    private var automaticTask: Task<Void, Never>?
    private var resolutionTask: Task<Void, Never>?
    private var selectionTask: Task<Void, Never>?
    private var miruroRecoveryTask: Task<Void, Never>?
    private var miruroMonitor = MiruroStallMonitor()
    private var miruroFailedServers: Set<String> = []
    // 자막 싱크 맞추기 (desktop runSubtitleSync / checkSubtitleSync): one run at a time, each subtitle once.
    @Published private(set) var syncRunning = false
    @Published private(set) var syncStatus: String?
    @Published private(set) var syncNotice: String?
    private var syncUndo: (() -> Bool)?
    private var syncNoticeTimer: Task<Void, Never>?
    private var syncTask: Task<Void, Never>?
    private var syncCancel = SyncCancel()
    private var syncChecked: Set<URL> = []
    /// The fix found for a subtitle still being translated, by that source: the translation's lines get it as they come.
    private var syncFixes: [URL: SubtitleAutoSync.Fix] = [:]
    private var waitingSync: URL?
    private var lastTranslation: URL?
    /// 다음 화 미리 준비 > 영상: what the next episode's resolver found, kept until that episode opens.
    private var preparedStreams: (key: String, streams: [ResolvedStream], subtitles: [RemoteSubtitle], servers: [DesktopPlaybackServer], label: String, raw: Bool)?

    private var preferRaw = false
    private let service = IosServices()
    private var proxy: HLSProxy?
    private let cast = CastService()
    private var loadedSkip = false
    private var lastSave = 0.0
    private var lastSkipped = ""
    private var generation = UUID()
    private var subtitleRequest = UUID()
    private var subtitleSearchRequest = UUID()
    private var subtitleSearchCache: [String: [SubtitleAsset]] = [:]
    private let subtitleLookup: ((String, @escaping ([SubtitleAsset]?, String?) -> Void) -> Void)?
    private var observers: Set<AnyCancellable> = []
    init(item: PlaybackItem, subtitleLookup: ((String, @escaping ([SubtitleAsset]?, String?) -> Void) -> Void)? = nil) {
        self.subtitleLookup = subtitleLookup
        if let subtitleLookup {
            jimaku = DesktopJimakuCatalog(fetch: { _ in
                try await withCheckedThrowingContinuation { continuation in
                    subtitleLookup("jimaku") { values, error in
                        if let error { continuation.resume(throwing: SubtitleFiles.failure(error)) }
                        else { continuation.resume(returning: values ?? []) }
                    }
                }
            })
        } else { jimaku = DesktopJimakuCatalog() }
        preparer = DesktopSubtitlePreparer(jimaku: jimaku)
        self.item = item; previous = item.preceding; searchTitle = item.anime.title
        pictureInPicture.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &observers)
        NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification).sink { [weak self] _ in Task { @MainActor in self?.resigningActive() } }.store(in: &observers)
        NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification).sink { [weak self] _ in Task { @MainActor in self?.enteredBackground() } }.store(in: &observers)
        NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification).sink { [weak self] _ in Task { @MainActor in self?.enteringForeground() } }.store(in: &observers)
        // Paused from the lock screen while playing in the background: the app is suspended from then on too.
        engine.$paused.dropFirst().sink { [weak self] paused in Task { @MainActor in if paused { self?.pausedInBackground() } } }.store(in: &observers)
        engine.$error.dropFirst().sink { [weak self] message in
            Task { @MainActor in
                guard let self, self.playbackError != message else { return }
                self.playbackError = message
            }
        }.store(in: &observers)
        resolver.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &observers)
        translation.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &observers)
        jimaku.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &observers)
    }
    var displayedSubtitleAssets: [SubtitleAsset] { subtitleResultsProvider == "jimaku" ? jimaku.files(item) ?? [] : assets }
    var subtitleLookupLoading: Bool { subtitleResultsProvider == "jimaku" ? jimaku.files(item) == nil && jimaku.error(item) == nil : searching }
    var displayedSubtitleSearchError: String? { subtitleResultsProvider == "jimaku" ? jimaku.error(item) : subtitleSearchError }
    // MARK: Screen off while paused
    // Paused, the app is suspended a few seconds after the screen goes off, and the stream's connections (the local HLS
    // proxy's included) die with it. Back in front, mpv took that for the end of the video: the timeline sat at the end,
    // seeking snapped back there, and the next progress save recorded the end, so the episode started over next time
    // (and 기기 동기화 sent that). Now the position is saved as the screen goes off, nothing is saved while suspended,
    // and the stream is opened again at that position, still paused.
    private weak var lifecycleLibrary: LibraryStore?
    private var suspended: (at: Date, paused: Bool)?
    private(set) var holdProgress = false
    private func resigningActive() {
        guard let library = lifecycleLibrary, !holdProgress else { return }
        save(library: library)
    }
    private func enteredBackground() {
        guard let library = lifecycleLibrary, active != nil, !systemPlayback, !pictureInPicture.active, engine.canCaptureVideo else { return }
        suspended = (Date(), false)
        // Playing on in the background (background audio) keeps the app running and its connections alive.
        if engine.paused || !library.preferences.backgroundAudio { holdSuspended(library) }
    }
    private func pausedInBackground() {
        guard let library = lifecycleLibrary, suspended?.paused == false, UIApplication.shared.applicationState == .background else { return }
        holdSuspended(library)
    }
    private func holdSuspended(_ library: LibraryStore) {
        save(library: library)
        suspended = (suspended?.at ?? Date(), true); holdProgress = true
    }
    private func enteringForeground() {
        guard let state = suspended else { return }
        suspended = nil
        guard state.paused else { return }
        guard let library = lifecycleLibrary, let stream = active, !stream.url.isFileURL, !systemPlayback,
              Date().timeIntervalSince(state.at) >= 3 else { holdProgress = false; return }
        play(stream, library: library, paused: true)
    }
    func begin(library: LibraryStore) {
        lifecycleLibrary = library; suspended = nil; holdProgress = false
        active = nil; loadedSkip = false; systemPlayback = false; engine.error = nil
        let token = generation
        subtitleOffset = library.subtitleChoice(animeID: item.anime.id, episodeID: item.episodeID)?.offset ?? library.preferences.subtitleOffset
        pendingFraction = library.resume(item.anime, episodeID: item.episodeID) > 0 ? nil : library.resumeFraction(item.anime, episodeID: item.episodeID)
        if library.preferences.subtitleProvider == "manual", let choice = library.subtitleChoice(animeID: item.anime.id, episodeID: item.episodeID), let relative = choice.relativeFile {
            let file = SubtitleFiles.root.appendingPathComponent(relative).standardizedFileURL
            if file.path.hasPrefix(SubtitleFiles.root.path + "/"), FileManager.default.fileExists(atPath: file.path) {
                sourceSubtitle = file; subtitle = file; subtitleFiles = [file]
                // Its language decides whether the 번역 row shows; it is read off the main thread.
                Task { _ = await isKoreanSubtitle(file) }
            }
        }
        miruroRecoveryTask?.cancel(); miruroMonitor = MiruroStallMonitor(); miruroFailedServers = []
        miruroRecoveryTask = Task { [weak self, weak library] in
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
                guard let self, let library, token == self.generation else { return }
                await self.recoverMiruroIfStalled(library: library, token: token)
            }
        }
        configure(library)
        let opening = item
        Task { await jimaku.load(opening) }
        engine.onEnd = { [weak self, weak library] in
            guard let self, let library, token == self.generation, !self.holdProgress, !self.systemPlayback else { return }
            // desktop video 'ended': said when nothing follows.
            guard !self.item.next.isEmpty else { self.notify("마지막 회차입니다."); return }
            guard library.preferences.autoPlay else { self.notify("재생이 끝났습니다."); return }
            let remaining = self.item.next
            var next = remaining[0]; next.next = Array(remaining.dropFirst())
            self.change(next, library: library)
        }
        engine.onProgress = { [weak self, weak library] position, duration in
            guard let self, let library, token == self.generation, !self.holdProgress else { return }
            // Watched elsewhere (기기 동기화 gives only the percentage): resumed once the length is known.
            if let fraction = self.pendingFraction, duration > 0 {
                self.pendingFraction = nil
                if fraction > 0 && fraction < 0.95 && position < 5 { self.engine.seek(duration * fraction); return }
            }
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
            self.updateAutoSkip(position: position, enabled: library.preferences.autoSkip)
        }
        chapters = OfflineAnalyzer.chapters(animeID: item.anime.id, episodeID: item.episodeID)
        refreshSeries(token: token)
        resolutionTask?.cancel()
        resolutionTask = Task {
            // 다음 화 미리 준비 > 영상: the stream found while the episode before played, when it still answers.
            if let ready = preparedStreams, ready.key == item.anime.id + "#" + item.episodeID {
                preparedStreams = nil
                if item.directURL == nil, let first = ready.streams.first, await StreamProbe.alive(first) {
                    guard token == generation, !Task.isCancelled else { return }
                    preferRaw = ready.raw
                    resolver.adopt(item, streams: ready.streams, subtitles: ready.subtitles, servers: ready.servers, label: ready.label)
                    return
                }
            }
            preferRaw = await preparer.preferRaw(item, preferences: library.preferences)
            guard token == generation, !Task.isCancelled else { return }
            resolver.resolve(item, preferRaw: preferRaw, preferred: library.preferences.preferredServers?[item.anime.source] ?? library.preferences.preferredStream)
        }
    }
    /// An episode opened from the watch history carries the list saved back then; like app.js playHistoryItem the series
    /// is asked again, so episodes that came out since are next.
    private func refreshSeries(token: UUID) {
        guard item.next.isEmpty, item.directURL == nil, item.anime.source != "local",
              refreshedSeries.insert(item.anime.id + "#" + item.episodeID).inserted else { return }
        let opening = item
        service.detail(summary: opening.anime.anime, sourceKey: opening.anime.source) { [weak self] detail, _ in
            Task { @MainActor in
                guard let self, token == self.generation, let detail else { return }
                let server = detail.servers.first { $0.episodes.contains { $0.id == opening.episodeID } } ?? detail.servers.first { $0.episodes.contains { Int($0.number) == opening.number } }
                guard let episodes = server?.episodes,
                      let index = episodes.firstIndex(where: { $0.id == opening.episodeID }) ?? episodes.firstIndex(where: { Int($0.number) == opening.number }) else { return }
                let saved = SavedAnime(AnimeSnapshot.shared.withEpisodes(anime: detail.anime, episodes: episodes), source: opening.anime.source)
                guard saved.id == opening.anime.id, self.item.next.isEmpty else { return }
                let next = episodes.dropFirst(index + 1).map { PlaybackItem(anime: saved, episode: $0) }
                if !next.isEmpty { self.item.next = next }
                if self.previous.isEmpty { self.previous = episodes.prefix(index).map { PlaybackItem(anime: saved, episode: $0) } }
            }
        }
    }
    func selectResolved(_ streams: [ResolvedStream], library: LibraryStore) {
        guard !streams.isEmpty else { return }
        let token = generation
        let preferred = library.preferences.preferredServers?[item.anime.source] ?? library.preferences.preferredStream
        let ordered = item.anime.source == "miruro" ? DesktopStreamPolicy.ordered(streams, preferRaw: preferRaw, preferred: preferred, workingProvider: UserDefaults.standard.string(forKey: "miruro.working-provider")) : streams.sorted { $0.label == preferred && $1.label != preferred }
        guard active == nil || ordered.contains(where: { $0.url == active?.url && $0.manifestKey != active?.manifestKey }) else { return }
        selectionTask?.cancel()
        selectionTask = Task {
            if item.anime.source == "miruro", ordered.allSatisfy({ !$0.url.isFileURL }) {
                do {
                    let selected = try await MiruroPlayback.choose(ordered, preferRaw: preferRaw, preferred: preferred)
                    guard token == generation, !Task.isCancelled else { return }
                    play(selected, library: library)
                } catch { if token == generation && !Task.isCancelled { self.error = error.localizedDescription } }
                return
            }
            if let stream = ordered.first, token == generation, !Task.isCancelled { play(stream, library: library); return }
            if !Task.isCancelled { error = "모든 영상 서버 연결에 실패했습니다." }
        }
    }
    func change(_ item: PlaybackItem, library: LibraryStore, remember: Bool = true) {
        // desktop playSiblingEpisode → resolveIntoPlayer: the next episode, when saved, plays from its file too.
        let item = DownloadStore.shared.offline(item) ?? item
        resolutionTask?.cancel(); selectionTask?.cancel()
        if remember { previous.append(self.item) }
        save(library: library); engine.pause(); proxy?.stop(); proxy = nil; stopPrefetch(); automaticTask?.cancel(); translation.cancel(); cast.stop()
        cancelSubtitleBackground(); preparer.cancel(); cancelSync()
        generation = UUID(); titleLookup.cancel(); searchTitle = item.anime.title; self.item = item; active = nil; subtitle = nil; sourceSubtitle = nil; assets = []; subtitleFiles = []; chapters = []; selectedProvider = ""; koreanFound = false; skipEntered = nil
        selectedJimakuURL = nil
        subtitleSearchRequest = UUID(); subtitleSearchCache = [:]; subtitleResultsProvider = ""; subtitleSearchError = nil; searching = false; makers = []
        chipTask?.cancel(); chipTask = nil; chipRequest = UUID(); pressedSource = nil; pressedFor = nil; pickedSheet = nil; sheetPicked = false
        subtitleState = nil; selectedTrackURL = nil; selectedMaker = nil
        loadedSkip = false; lastSave = 0; lastSkipped = ""; systemPlayback = false; engine.clearViewerSeek()
        begin(library: library)
    }
    func play(_ stream: ResolvedStream, library: LibraryStore, paused: Bool = false) {
        miruroFailedServers = []; miruroMonitor = MiruroStallMonitor()
        playRequest = UUID(); let playing = playRequest
        let token = generation
        Task {
            do {
                proxy?.stop(); proxy = nil
                var playable = stream
                if stream.manifestKey != nil || stream.hlsManifest != nil || (library.preferences.quality != "Auto" && stream.url.pathExtension.lowercased() == "m3u8" && !stream.url.isFileURL) {
                    let proxy = HLSProxy(); self.proxy = proxy; playable = try await proxy.start(stream, quality: library.preferences.quality)
                }
                guard token == generation, playing == playRequest else { return }
                active = stream; systemStream = playable; error = nil; systemPlayback = false
                if item.anime.source == "miruro", let provider = stream.label.components(separatedBy: " - ").dropFirst().first?.components(separatedBy: " ").first { UserDefaults.standard.set(provider, forKey: "miruro.working-provider") }
                configure(library)
                holdProgress = false
                reloadVideo(playable, resume: library.resume(item.anime, episodeID: item.episodeID), paused: paused)
                if subtitle == nil { automaticSubtitle(stream, library: library, token: token) }
                prefetchNext(library: library, token: token)
            } catch { if token == generation, playing == playRequest { self.error = error.localizedDescription } }
        }
    }
    private func recoverMiruroIfStalled(library: LibraryStore, token: UUID) async {
        guard let current = active else { return }
        let eligible = item.anime.source == "miruro" && !current.url.isFileURL && !engine.paused && !holdProgress && !systemPlayback && UIApplication.shared.applicationState == .active
        guard miruroMonitor.update(context: current.id, position: engine.position, seek: engine.seekRevision, eligible: eligible,
            buffering: engine.buffering, now: Date().timeIntervalSinceReferenceDate),
            MiruroPlayback.canRecover(resolver.streams, current: current, failed: miruroFailedServers) else { return }
        miruroFailedServers.insert(current.label)
        let position = engine.position, seek = engine.seekRevision
        notify("영상 서버가 느려 다른 서버로 연결하는 중…")
        do {
            let selected = try await MiruroPlayback.choose(resolver.streams, preferRaw: preferRaw, preferred: current.label, excluded: miruroFailedServers)
            var playable = selected
            var replacement: HLSProxy?
            if selected.manifestKey != nil || selected.hlsManifest != nil || (library.preferences.quality != "Auto" && selected.url.pathExtension.lowercased() == "m3u8") {
                let nextProxy = HLSProxy(); replacement = nextProxy
                do { playable = try await nextProxy.start(selected, quality: library.preferences.quality) }
                catch { nextProxy.stop(); throw error }
            }
            guard token == generation, !Task.isCancelled, active?.id == current.id, !engine.paused, !holdProgress,
                  engine.seekRevision == seek, abs(engine.position - position) <= 0.1, engine.buffering else { replacement?.stop(); return }
            proxy?.stop(); proxy = replacement
            active = selected; systemStream = playable; playbackError = nil; error = nil
            reloadVideo(playable, resume: position)
        } catch { if token == generation, !Task.isCancelled { self.error = error.localizedDescription } }
    }
    func applyPreferences(_ library: LibraryStore) { configure(library) }
    func updateAutoSkip(position: Double, enabled: Bool, now: Date = Date()) {
        guard enabled, !systemPlayback else { skipEntered = nil; return }
        let chapter = chapters.first(where: { position >= $0.start && position < $0.end })
        let watched = watchedByChoice(chapter, now: now)
        guard let chapter else { skipEntered = nil; lastSkipped = ""; return }
        if skipEntered?.id != chapter.id {
            skipEntered = (chapter.id, PlaybackSkipCountdown(position: position, speed: engine.speed, revision: engine.seekRevision))
        } else {
            skipEntered?.countdown.update(position: position, speed: engine.speed, revision: engine.seekRevision)
        }
        // Remember the last played sample even when it arrives after pause. An
        // unchanged position adds no time, and a seek is allowed only while playing.
        if !engine.paused, !engine.buffering, !watched, chapter.id != lastSkipped,
           (skipEntered?.countdown.elapsed ?? 0) >= 2.5 {
            lastSkipped = chapter.id; engine.seek(chapter.end)
        }
    }
    /// A quality/server change reloads the video, but keeps the selected subtitle's styling policy,
    /// including a working translation that has not yet been added to the saved subtitle list.
    func reloadVideo(_ stream: ResolvedStream, resume: Double, paused: Bool = false) {
        let translated = engine.translatedSubtitle || currentSaved?.translated == true
        engine.load(stream, resume: resume, paused: paused)
        if let subtitle { engine.subtitle(subtitle, translated: translated) }
    }
    /// 애니24's own subtitle file can be found after the stream: the playing stream takes it and it is put on.
    func siteSubtitlesArrived(_ tracks: [RemoteSubtitle], library: LibraryStore) {
        guard item.anime.source == "ohli24", var stream = active, stream.subtitles.isEmpty, !tracks.isEmpty else { return }
        stream.subtitles = tracks; stream.burnedKorean = false; active = stream
        if subtitle == nil { automaticSubtitle(stream, library: library, token: generation) }
    }
    private func configure(_ library: LibraryStore) {
        var settings = library.preferences
        settings.subtitleOffset = subtitleOffset
        engine.configure(settings)
    }
    /// desktop updateSkipState: an OP/ED the viewer moved into themselves is watched until they leave it; other
    /// segments, and this one once played past, are skipped as before. mpv reports the new position a moment after a
    /// seek, so the mark is kept that long instead of being judged on the position before it.
    func watchedByChoice(_ chapter: OfflineChapter?, now: Date = Date()) -> Bool {
        guard let seek = engine.viewerSeek else { return false }
        if let chapter, seek.time >= chapter.start && seek.time < chapter.end { return true }
        if now.timeIntervalSince(seek.at) >= 1.5 { engine.clearViewerSeek() }
        return false
    }
    func shiftSubtitle(_ delta: Double, library: LibraryStore) {
        setSubtitleOffset(subtitleOffset + delta, library: library)
    }
    /// Sets the offset itself (desktop setSubtitleSync), kept to whole milliseconds as the field shows it.
    func setSubtitleOffset(_ seconds: Double, library: LibraryStore) {
        guard seconds.isFinite else { return }
        subtitleOffset = (seconds * 1000).rounded() / 1000
        engine.set("sub-delay", String(subtitleOffset))
        library.saveSubtitle(animeID: item.anime.id, episodeID: item.episodeID, file: sourceSubtitle, offset: subtitleOffset)
    }
    /// 스크린샷 (P, the camera button): what it did, for the player to say.
    func screenshot(title: String) async -> String {
        let name = PlayerScreenshot.name(title: title, episode: item.title, position: engine.position)
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let file = folder.appendingPathComponent("screenshot.png")
            try await engine.screenshot(to: file)
            try await PlayerScreenshot.save(file, name: name)
            return "스크린샷을 사진 앱에 저장했어요"
        } catch { return "스크린샷을 저장하지 못했어요: " + error.localizedDescription }
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
        searchProvider = provider
        if provider == "jimaku" {
            subtitleSearchRequest = UUID(); subtitleResultsProvider = "jimaku"; searching = false; makers = []
            let opening = item
            Task { await jimaku.load(opening, retryFailure: true) }
            return
        }
        let key = provider + ":" + searchTitle
        let request = beginSubtitleSearch(key: key, provider: provider)
        let token = generation
        makers = []
        if provider != "jimaku", searchTitle == item.anime.title, !TitleCandidates.shared.isKorean(title: searchTitle) {
            Task {
                let anime = item.anime.anime
                let korean = [anime.title, anime.native, anime.romaji, anime.english].first { TitleCandidates.shared.isKorean(title: $0) }
                let resolved: String
                if let korean { resolved = korean }
                else { let found = await titleLookup.resolve(searchTitle, aliases: [anime.native, anime.romaji, anime.english]); resolved = found ?? searchTitle }
                guard token == generation, request == subtitleSearchRequest else { return }
                searchTitle = resolved; performSubtitleSearch(provider, key: key, token: token, request: request)
            }
        } else { performSubtitleSearch(provider, key: key, token: token, request: request) }
    }
    private func beginSubtitleSearch(key: String, provider: String) -> UUID {
        subtitleSearchRequest = UUID(); searching = true; subtitleSearchError = nil
        subtitleResultsProvider = provider; assets = subtitleSearchCache[key] ?? []
        return subtitleSearchRequest
    }
    private func finishSubtitleSearch(_ values: [SubtitleAsset]?, failure: String?, key: String, token: UUID, request: UUID) {
        guard token == generation, request == subtitleSearchRequest else { return }
        searching = false; subtitleSearchError = failure
        // A failed refresh must not erase a successful list. A successful empty
        // response still clears it, so removed files do not persist forever.
        guard failure == nil else { return }
        var seen = Set<String>()
        assets = (values ?? []).filter { seen.insert($0.source + ":" + $0.url).inserted }
        subtitleSearchCache[key] = assets
    }
    private func performSubtitleSearch(_ provider: String, key: String, token: UUID, request: UUID) {
        let completion: ([SubtitleAsset]?, String?) -> Void = { [weak self] assets, error in
            self?.finishSubtitleSearch(assets, failure: error, key: key, token: token, request: request)
        }
        if let subtitleLookup { subtitleLookup(provider, completion); return }
        service.findSubtitles(provider: provider, title: searchTitle, episode: Int32(item.number), episodeKey: item.subtitleEpisodeKey,
            anilistId: item.anime.anime.anilistId?.int32Value ?? Int32(DesktopCatalog.shared.record(item.anime)?.anilist ?? 0), completion: completion)
    }
    func importSubtitle(_ url: URL, library: LibraryStore, headers: [String: String] = [:], translate: Bool = true, provider: String? = nil, asset: SubtitleAsset? = nil) {
        subtitleRequest = UUID()
        let request = subtitleRequest
        let token = generation
        Task {
            do {
                let japanese = provider == "jimaku" || asset?.source == "jimaku"
                if japanese && !JimakuRules.shared.validDownload(url: url.absoluteString) { throw SubtitleFiles.failure("Jimaku 파일 주소가 아닙니다.") }
                let files = try await SubtitleFiles.prepare(url, headers: headers, japanese: japanese, filename: japanese ? asset?.name : nil)
                guard token == generation, request == subtitleRequest else { return }
                subtitleFiles = files
                if let first = preferredSubtitle(subtitleFiles, asset: asset) {
                    if asset?.source == "jimaku", let asset { jimaku.remember(asset.name, item: item); selectedJimakuURL = asset.url }
                    selectSubtitle(first, library: library, translate: translate, provider: provider ?? (url.isFileURL ? "user" : searchProvider))
                }
                else if files.isEmpty { error = "자막이 없습니다. ASS · SSA · SRT · VTT · SMI 파일을 선택하세요." }
                else { error = "여러 자막을 가져왔습니다. 자막 목록에서 사용할 파일을 선택하세요." }
            } catch { if token == generation, request == subtitleRequest { self.error = error.localizedDescription } }
        }
    }
    func selectSubtitle(_ url: URL, library: LibraryStore, translate: Bool = true, provider: String? = nil, automatic: Bool = false) {
        subtitleClassification?.cancel()
        subtitleRequest = UUID(); translation.cancel()
        stopPrefetch(); cancelSubtitleBackground()
        let saved = EpisodeSubtitleStore.shared.list(item).first { $0.file == url }
        selectedProvider = provider ?? saved?.provider ?? "user"
        if ["kairan", "csora", "anissia", "linkkf"].contains(selectedProvider) { koreanFound = true }
        sourceSubtitle = saved?.original ?? url; subtitle = url; engine.subtitle(url, translated: saved?.translated == true)
        EpisodeSubtitleStore.shared.save(url, item: item, provider: selectedProvider, translated: saved?.translated ?? false, original: saved?.original)
        if selectedProvider != "jimaku" { selectedJimakuURL = nil }
        if selectedProvider != "reanime" { selectedTrackURL = nil }
        if selectedProvider != "anissia" { selectedMaker = nil }
        checkSubtitleSync(url, library: library)
        if !automatic {
            library.saveSubtitle(animeID: item.anime.id, episodeID: item.episodeID, file: url, offset: subtitleOffset)
            if saved?.translated == true { library.preferAI(true, animeID: item.anime.id) }
            // Jimaku is picked per episode and is never kept as the default source; the site's tracks are 자동 (app.js switchSubtitleSource).
            if ["kairan", "csora", "anissia"].contains(selectedProvider) { library.preferences.subtitleProvider = selectedProvider }
            else if selectedProvider == "reanime" && library.preferences.subtitleProvider != "manual" { library.preferences.subtitleProvider = "auto" }
        }
        let token = generation, request = subtitleRequest
        subtitleClassification = Task {
        let korean = await isKoreanSubtitle(url)
        guard !Task.isCancelled, token == generation, request == subtitleRequest, subtitle == url else { return }
        if !automatic && saved?.translated != true && korean { library.preferAI(false, animeID: item.anime.id) }
        if translate && selectedProvider != "user" && saved?.translated != true && library.preferences.translationPreferences(automatic: true) != nil && !korean { self.translate(library: library, manual: false) }
        if automatic && saved?.translated != true && !korean && !["kairan", "csora", "anissia", "user"].contains(selectedProvider) { offerKorean(library: library) }
        prefetchNext(library: library, token: generation)
        }
    }
    private func preferredSubtitle(_ files: [URL], asset: SubtitleAsset? = nil) -> URL? {
        if let asset, ["kairan", "csora", "anissia"].contains(asset.source) {
            let index = DesktopCommunityFiles.shared.select(names: files.map(\.lastPathComponent), sizes: files.map { KotlinLong(value: Int64((try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)) }, episode: asset.matchedEpisode?.doubleValue ?? Double(asset.episode?.intValue ?? item.number), strict: asset.strict, bundle: asset.bundle, season: DesktopTitleRules.shared.season(title: item.anime.title))
            return index >= 0 && Int(index) < files.count ? files[Int(index)] : nil
        }
        return files.first { SubtitleEpisodeMatcher.shared.matches(name: $0.lastPathComponent, episodeNumber: Int32(item.number), expectedSeason: nil) } ?? (files.count == 1 ? files.first : nil)
    }
    private func automaticSubtitle(_ stream: ResolvedStream, library: LibraryStore, token: UUID) {
        subtitleRequest = UUID()
        let request = subtitleRequest
        guard library.preferences.subtitleProvider != "manual", subtitle == nil else { return }
        guard !DesktopSubtitlePolicy.burnedKorean(source: item.anime.source, stream: stream) else { return }
        automaticTask?.cancel()
        automaticTask = Task {
            do {
                if let prepared = try await preparer.prepare(item, tracks: stream.subtitles, preferences: library.preferences, prefersAI: library.aiSubtitleSeries[item.anime.id] == true, stream: stream) {
                    guard token == generation, request == subtitleRequest, subtitle == nil, !Task.isCancelled else { return }
                    subtitleFiles = [prepared.0]
                    selectSubtitle(prepared.0, library: library, provider: prepared.1, automatic: true)
                }
            } catch is CancellationError { }
            catch { if token == generation { self.error = error.localizedDescription } }
        }
    }
    func translate(library: LibraryStore, fresh: Bool = false, provider: String? = nil, manual: Bool = true) {
        if manual { subtitleRequest = UUID(); automaticTask?.cancel(); cancelSubtitleBackground() }
        stopPrefetch()
        var preferences = library.preferences
        if let provider = provider ?? (manual ? nil : seriesSide(library)) { preferences.translationProvider = provider; preferences.translationModel = preferences.translationModels?[provider] ?? "" }
        guard let configured = preferences.translationPreferences(localOnly: manual && preferences.translationProvider == "local") else { error = "번역 API 키 또는 로컬 GGUF 모델을 먼저 설정하세요."; return }
        if let sourceSubtitle {
            runTranslation(sourceSubtitle, library: library, preferences: configured, fresh: fresh, manual: manual)
        } else {
            let token = generation; let request = subtitleRequest
            automaticTask?.cancel()
            automaticTask = Task {
                do {
                    let prepared = try await preparer.translationSource(item, tracks: active?.subtitles ?? [], preferences: configured, requireAutomatic: false)
                    guard token == generation, request == subtitleRequest, !Task.isCancelled else { return }
                    guard let prepared else { error = "번역할 원문 자막을 찾지 못했습니다. 자막 파일을 직접 열거나 사이트 자막 트랙을 선택하세요."; return }
                    sourceSubtitle = prepared.0
                    EpisodeSubtitleStore.shared.save(prepared.0, item: item, provider: prepared.1, translated: false)
                    runTranslation(prepared.0, library: library, preferences: configured, fresh: fresh, manual: manual)
                } catch { if token == generation { self.error = error.localizedDescription } }
            }
        }
    }
    private func runTranslation(_ original: URL, library: LibraryStore, preferences: AppPreferences, fresh: Bool, manual: Bool) {
        let token = generation; let request = subtitleRequest; let episode = item
        if manual {
            // desktop runTranslation: pressed with a Korean subtitle there, the series goes on in machine translation.
            if koreanFound || DesktopSubtitlePolicy.siteKorean(source: item.anime.source, tracks: active?.subtitles ?? [], stream: active) {
                library.preferAI(true, animeID: item.anime.id)
            }
            var sides = UserDefaults.standard.dictionary(forKey: Self.seriesSideKey) as? [String: String] ?? [:]
            sides[item.anime.id] = preferences.translationProvider == "local" ? "local" : "cloud"
            UserDefaults.standard.set(sides, forKey: Self.seriesSideKey)
        }
        translation.translate(original, preferences: preferences, position: { [weak self] in self?.engine.position ?? 0 }, seekRevision: { [weak self] in self?.engine.seekRevision ?? 0 }, anime: item.anime, fresh: fresh, localOnly: manual && preferences.translationProvider == "local") { [weak self] output in
            guard let self, token == self.generation, request == self.subtitleRequest else { return }
            self.lastTranslation = output
            // Its source was synced while it ran: the translation is retimed the same way (desktop retimeSubtitle).
            if let fix = self.syncFixes[original] {
                let working = output.lastPathComponent.hasPrefix("working-")
                Task {
                    guard let shown = try? await SubtitleAutoSync.retimed(output, fix, stable: working), token == self.generation, request == self.subtitleRequest, self.lastTranslation == output else { return }
                    if !working {
                        self.syncChecked.insert(shown); self.syncFixes[original] = nil
                        EpisodeSubtitleStore.shared.save(shown, item: episode, provider: preferences.translationProvider, translated: true, original: original)
                        self.notify("번역을 마치고 맞춰 둔 자막 싱크를 적용했습니다.")
                    }
                    if self.subtitle == shown { self.engine.reloadSubtitle() } else { self.subtitle = shown; self.engine.subtitle(shown, translated: true) }
                }
                return
            }
            EpisodeSubtitleStore.shared.save(output, item: episode, provider: preferences.translationProvider, translated: true, original: original)
            if self.subtitle == output { self.engine.reloadSubtitle() }
            else { self.subtitle = output; self.engine.subtitle(output, translated: true) }
        }
        prefetchNext(library: library, token: generation)
    }
    private func cancelSubtitleBackground() {
        offerTask?.cancel(); offerTimer?.cancel()
        offerTask = nil; offerTimer = nil; offeredSubtitle = nil; koreanOffer = nil
    }
    private func offerKorean(library: LibraryStore) {
        let token = generation; let request = subtitleRequest
        offerTask = Task {
            do {
                guard let found = try await preparer.community(item, preferences: library.preferences), token == generation, request == subtitleRequest, !Task.isCancelled else { return }
                offeredSubtitle = found; koreanOffer = "한국어 자막(\(found.1))을 찾았어요. 바꿀까요?"
                offerTimer = Task {
                    try? await Task.sleep(nanoseconds: 20_000_000_000)
                    if !Task.isCancelled, token == generation, request == subtitleRequest { dismissKoreanOffer() }
                }
            } catch { }
        }
    }
    func dismissKoreanOffer() { koreanOffer = nil; offeredSubtitle = nil; offerTimer?.cancel() }
    func acceptKoreanOffer(library: LibraryStore) {
        guard let found = offeredSubtitle else { return }
        library.preferAI(false, animeID: item.anime.id)
        selectSubtitle(found.0, library: library, translate: false, provider: found.1)
    }
    func loadMakers() {
        subtitleSearchRequest = UUID()
        let request = subtitleSearchRequest
        searching = true; subtitleSearchError = nil; assets = []; makers = []; subtitleResultsProvider = "anissia"
        let token = generation, opening = item, typed = searchTitle
        Task {
            // Anissia lists Korean titles: an English or Japanese one is looked up first, as the subtitle search does.
            let title = typed == opening.anime.title && !TitleCandidates.shared.isKorean(title: typed) ? await preparer.searchTitle(opening) : typed
            guard token == generation, request == subtitleSearchRequest else { return }
            service.subtitleMakers(title: title) { [weak self] values, failure in
                guard token == self?.generation, request == self?.subtitleSearchRequest else { return }
                self?.makers = values ?? []; self?.subtitleSearchError = failure; self?.searching = false
            }
        }
    }
    func searchMaker(_ maker: SubtitleMaker) {
        let key = "maker:" + maker.website + ":" + searchTitle
        let request = beginSubtitleSearch(key: key, provider: "anissia")
        let token = generation
        service.makerSubtitles(title: searchTitle, episode: Int32(item.number), episodeKey: item.subtitleEpisodeKey, website: maker.website, anilistId: item.anime.anime.anilistId?.int32Value ?? Int32(DesktopCatalog.shared.record(item.anime)?.anilist ?? 0)) { [weak self] values, failure in
            self?.finishSubtitleSearch(values, failure: failure, key: key, token: token, request: request)
        }
    }
    // MARK: Subtitle tab (app.js switchSubtitleSource / renderSubtitleSheet)

    func isKoreanSubtitle(_ url: URL) async -> Bool {
        if let known = koreanFiles[url] { return known }
        let value = await SubtitleFiles.isKorean(url); koreanFiles[url] = value; return value
    }
    var siteTracks: [RemoteSubtitle] { active?.subtitles ?? resolver.subtitles }
    /// Re:Anime / Miruro list their subtitle languages; Linkkf has its own Korean subtitle.
    var trackSource: String? { ["reanime", "miruro", "linkkf"].contains(item.anime.source) && !siteTracks.isEmpty ? (item.anime.source == "linkkf" ? "linkkf" : "reanime") : nil }
    var currentSaved: SavedSubtitle? { subtitle.flatMap { file in EpisodeSubtitleStore.shared.list(item).first { $0.file == file } } }
    /// The chip lit: the one pressed last until the subtitle on screen changes, then the source of the subtitle on screen.
    var activeSubtitleSource: String {
        if let pressedSource, pressedFor == subtitle { return pressedSource }
        if pressedSource == "ai" && translation.running { return "ai" }
        guard subtitle != nil else { return "" }
        if currentSaved?.translated == true { return "ai" }
        return ["linkkf", "provider", "reanime"].contains(selectedProvider) ? (trackSource ?? selectedProvider) : selectedProvider
    }
    /// The list open under the chips: the one picked last, else the one of the subtitle on screen.
    var openSheet: String? {
        if sheetPicked { return pickedSheet }
        return ["reanime", "jimaku", "anissia"].contains(activeSubtitleSource) ? activeSubtitleSource : nil
    }
    var subtitleLabel: String? {
        guard let subtitle else { return nil }
        if let saved = currentSaved { return SubtitleLabels.label(saved, maker: selectedMaker) }
        return SubtitleLabels.label(provider: selectedProvider, translated: false, name: subtitle.lastPathComponent, maker: selectedMaker)
    }
    /// One 번역 row: shown while a subtitle that is not Korean is on, a machine translation can be made again, or one runs.
    var translatable: Bool { translation.running || currentSaved?.translated == true || (subtitle.map { koreanFiles[$0] == false } ?? false) }
    var retranslatable: Bool { currentSaved?.translated == true && sourceSubtitle != nil && sourceSubtitle != subtitle }

    private func press(_ source: String, sheet: String?) {
        chipTask?.cancel(); chipRequest = UUID()
        pressedSource = source; pressedFor = subtitle
        pickedSheet = sheet; sheetPicked = true; subtitleState = nil
    }
    func chooseSource(_ source: String, library: LibraryStore) {
        if source == "jimaku" {
            press("jimaku", sheet: "jimaku"); subtitleResultsProvider = "jimaku"
            let opening = item
            Task { await jimaku.load(opening, retryFailure: true) }
            return
        }
        if source == "ai" {
            press("ai", sheet: nil)
            if let translated = EpisodeSubtitleStore.shared.list(item).first(where: \.translated), let file = translated.file {
                library.preferAI(true, animeID: item.anime.id)
                selectSubtitle(file, library: library, translate: false, provider: translated.provider); return
            }
            translate(library: library); return
        }
        press(source, sheet: ["reanime", "anissia"].contains(source) ? source : nil)
        library.preferAI(false, animeID: item.anime.id)
        if library.preferences.subtitleProvider != "manual" || ["kairan", "csora", "anissia"].contains(source) {
            library.preferences.subtitleProvider = ["kairan", "csora", "anissia"].contains(source) ? source : "auto"
        }
        if source == "anissia" { loadMakers() }
        // Re:Anime / Miruro: the site's own subtitle, never a machine translation (that is the AI 번역 chip): its Korean
        // track, else its best other track (Japanese, then the English dialogue), the rest listed under the chip. Pressing it
        // ends a series watched in machine translation (preferAI above), so its next episodes open with the site's track too.
        if source == "reanime" || source == "linkkf" {
            let tracks = siteTracks
            if let korean = tracks.first(where: DesktopSubtitlePolicy.isKorean) { chooseTrack(korean, library: library); return }
            if let track = DesktopSubtitlePolicy.translationTrack(tracks) ?? tracks.first { chooseTrack(track, library: library) }
            else { subtitleState = "사이트 자막 트랙이 없습니다." }
            return
        }
        if let saved = EpisodeSubtitleStore.shared.list(item).first(where: { $0.provider == source && !$0.translated }), let file = saved.file {
            selectSubtitle(file, library: library, provider: source); return
        }
        let label = SubtitleLabels.provider(source)
        subtitleState = label + " 자막을 찾는 중..."
        let token = generation, request = chipRequest, opening = item
        chipTask = Task {
            let found = try? await preparer.community(opening, provider: source)
            guard token == generation, request == chipRequest, !Task.isCancelled else { return }
            if let found { subtitleFiles = [found.0]; subtitleState = nil; selectSubtitle(found.0, library: library, provider: found.1) }
            else { subtitleState = label + " 자막을 찾지 못했습니다." }
        }
    }
    func chooseTrack(_ track: RemoteSubtitle, library: LibraryStore, translate: Bool = false) {
        if pressedSource == nil || pressedFor != subtitle { pressedSource = trackSource ?? "reanime"; pressedFor = subtitle }
        selectedTrackURL = track.url
        importSubtitle(track.url, library: library, headers: track.headers ?? [:], translate: translate, provider: "reanime")
    }
    func chooseMaker(_ maker: SubtitleMaker, library: LibraryStore) {
        press("anissia", sheet: "anissia")
        subtitleState = "Anissia · " + maker.name + " 자막을 찾는 중..."
        let token = generation, request = chipRequest, opening = item
        chipTask = Task {
            let found = try? await preparer.maker(opening, website: maker.website)
            guard token == generation, request == chipRequest, !Task.isCancelled else { return }
            if let found {
                subtitleFiles = [found.0]; subtitleState = nil
                selectSubtitle(found.0, library: library, provider: "anissia"); selectedMaker = maker.name
            } else { subtitleState = maker.name + "의 " + opening.subtitleEpisodeKey + "화 자막을 찾지 못했습니다." }
        }
    }
    /// 한국어 자막 다시 찾기: the automatic order again, past the saved subtitles and earlier results (app.js #findSubtitle).
    func findKoreanAgain(library: LibraryStore) {
        chipTask?.cancel(); chipRequest = UUID(); pressedSource = nil; sheetPicked = false
        library.preferAI(false, animeID: item.anime.id)
        preparer.resetSearches(); selectedTrackURL = nil
        subtitleState = "온라인 자막을 찾는 중..."
        var preferences = library.preferences
        if preferences.subtitleProvider == "manual" { preferences.subtitleProvider = "auto" }
        let token = generation, request = chipRequest, opening = item, stream = active
        chipTask = Task {
            let found = try? await preparer.prepare(opening, tracks: [], preferences: preferences, skipSaved: true, stream: stream)
            guard token == generation, request == chipRequest, !Task.isCancelled else { return }
            if let found { subtitleFiles = [found.0]; subtitleState = "자막 적용 완료"; selectSubtitle(found.0, library: library, provider: found.1) }
            else { subtitleState = "자막을 찾지 못했습니다. 자막 파일을 직접 열 수 있어요." }
        }
    }
    /// desktop seriesPick().provider (autoTranslateProvider): the side that translated this series by hand last, 로컬 AI or
    /// the API, translates its next episodes too while it is set up; nil keeps 설정's choice.
    private static let seriesSideKey = "subtitle.translation.series-side"
    func seriesSide(_ library: LibraryStore) -> String? {
        switch (UserDefaults.standard.dictionary(forKey: Self.seriesSideKey) as? [String: String])?[item.anime.id] {
        case "local": return library.preferences.translationProvider == "local" ? nil : "local"
        case "cloud": return library.preferences.translationProvider == "local" ? cloudProvider(library) : nil
        default: return nil
        }
    }
    /// 설정 > 자막 자동 번역 as this series uses it (seriesSide first).
    func automaticPreferences(_ library: LibraryStore) -> AppPreferences? {
        var base = library.preferences
        if let side = seriesSide(library) { base.translationProvider = side; base.translationModel = base.translationModels?[side] ?? "" }
        return base.translationPreferences(automatic: true) ?? library.preferences.translationPreferences(automatic: true)
    }
    /// The configured translation API (the chosen one first), for the 번역 button beside 로컬 AI.
    func cloudProvider(_ library: LibraryStore) -> String? {
        let clouds = ["gemini", "openai", "deepl", "qwen"], wanted = library.preferences.translationProvider
        return (clouds.contains(wanted) ? [wanted] + clouds.filter { $0 != wanted } : clouds).first { !SecureKeys.load($0).isEmpty }
    }

    // MARK: 자막 싱크 맞추기
    // Every subtitle put on is matched in the background (자동으로 맞추기) and changed only when a candidate fits clearly
    // better than the subtitle as it is; otherwise it stays without a word. A change is told in the box over the player,
    // with 되돌리기. The 자막 타이밍 setting goes back to 0 with it, since the copy is matched as it is.

    /// The video to listen to: the file, or the address mpv plays (the local proxy's, which needs no headers).
    private var syncVideo: SubtitleAutoSync.Video? {
        guard let stream = active else { return nil }
        let playing = systemStream ?? stream
        var headers = playing.url == stream.url ? stream.headers : [:]
        if playing.url == stream.url, !stream.referer.isEmpty, headers["Referer"] == nil { headers["Referer"] = stream.referer }
        return SubtitleAutoSync.Video(url: playing.url, headers: headers, duration: engine.duration)
    }
    var syncAvailable: Bool { subtitle != nil && syncVideo != nil && !systemPlayback }
    var syncButtonTitle: String { syncRunning ? (syncStatus ?? "싱크 맞추는 중") + " · 눌러서 취소" : "음성에 맞춰 싱크 맞추기" }
    private func cancelSync() {
        syncCancel.cancel(); syncTask?.cancel(); syncTask = nil; syncRunning = false; syncStatus = nil
        syncFixes = [:]; waitingSync = nil; lastTranslation = nil; dismissSyncNotice()
    }
    func dismissSyncNotice() { syncNotice = nil; syncUndo = nil; syncNoticeTimer?.cancel() }
    func undoSync() {
        let undo = syncUndo; dismissSyncNotice()
        if undo?() == false { notify("이미 다른 자막으로 바뀌어 되돌릴 수 없습니다.") }
    }
    private func showSyncDone(_ text: String, undo: @escaping () -> Bool) {
        dismissKoreanOffer()
        syncNotice = text; syncUndo = undo; syncNoticeTimer?.cancel()
        let token = generation
        syncNoticeTimer = Task { try? await Task.sleep(nanoseconds: 20_000_000_000); if !Task.isCancelled, token == generation { syncNotice = nil; syncUndo = nil } }
    }
    /// Each subtitle put on is synced once, as soon as the episode's length is known, or right after a sync still running.
    /// A translation is timed as the file it was made from, so it is not run again when that file was.
    func checkSubtitleSync(_ url: URL, library: LibraryStore) {
        guard library.preferences.subtitleSyncCheck != false, !SubtitleAutoSync.isSynced(url), !syncChecked.contains(url) else { return }
        if let original = EpisodeSubtitleStore.shared.list(item).first(where: { $0.file == url })?.original, syncChecked.contains(original) { return }
        let token = generation
        Task {
            var waited = 0
            while (engine.duration <= 0 || active == nil) && waited < 240 {
                try? await Task.sleep(nanoseconds: 500_000_000)
                if token != generation { return }
                waited += 1
            }
            guard token == generation, subtitle == url || sourceSubtitle == url, !syncChecked.contains(url), syncAvailable else { return }
            if syncRunning { waitingSync = url; return }
            runSubtitleSync(library: library, auto: true)
        }
    }
    /// auto: started by itself for the subtitle put on, not by the button: it says nothing unless it changes the subtitle.
    func runSubtitleSync(library: LibraryStore, auto: Bool = false) {
        if syncRunning { if !auto { syncCancel.cancel(); syncTask?.cancel() }; return }
        // A subtitle being translated is synced by its source (its lines come in on the source's times).
        let translating = translation.running && sourceSubtitle != nil
        guard let target = translating ? sourceSubtitle : subtitle, let video = syncVideo else { if !auto { notify("싱크를 맞출 자막이 없습니다.") }; return }
        let saved = EpisodeSubtitleStore.shared.list(item).first { $0.file == target }
        let timing = saved?.translated == true ? saved?.original : nil
        syncChecked.insert(target); if let timing { syncChecked.insert(timing) }
        let token = generation, cancel = SyncCancel(), tracks = selectedProvider == "reanime" || trackSource != "reanime" ? [] : SubtitleAutoSync.referenceTracks(siteTracks)
        let chapters = self.chapters, provider = selectedProvider
        syncCancel = cancel; syncRunning = true; syncStatus = "자막 싱크 확인 중..."
        if !auto { dismissSyncNotice() }
        syncTask = Task {
            defer {
                if token == generation {
                    syncRunning = false; syncStatus = nil; syncTask = nil
                    if let next = waitingSync { waitingSync = nil; checkSubtitleSync(next, library: library) }
                }
            }
            do {
                var references: [URL] = []
                for track in tracks {
                    if let file = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]).first { references.append(file) }
                    if cancel.cancelled || token != generation { return }
                }
                let outcome = try await SubtitleAutoSync.sync(subtitle: target, timing: timing, references: references, video: video, chapters: chapters,
                    status: { [weak self] message, progress in
                        guard let self, token == self.generation else { return }
                        self.syncStatus = message + (progress.map { " \(Int(($0 * 100).rounded()))%" } ?? "")
                    }, cancelled: { cancel.cancelled })
                guard token == generation, !cancel.cancelled else { return }
                switch outcome {
                case .unchanged: if !auto { notify("자막 싱크가 이미 잘 맞아요") }
                case let .uncertain(message): if !auto { notify("자막 싱크: " + message) }
                case let .synced(copy, fix, method):
                    applySync(target: target, copy: copy, fix: fix, method: method, saved: saved, provider: provider, auto: auto, library: library)
                }
            } catch is CancellationError {
                if token == generation && !auto { notify("자막 싱크 맞추기를 취소했습니다.") }
            } catch {
                if token == generation && !auto && !cancel.cancelled { notify("자막 싱크 맞추기 실패: " + error.localizedDescription) }
            }
        }
    }
    private func applySync(target: URL, copy: URL, fix: SubtitleAutoSync.Fix, method: String, saved: SavedSubtitle?, provider: String, auto: Bool, library: LibraryStore) {
        syncChecked.insert(copy)
        let manual = subtitleOffset
        if abs(manual) >= 0.0005 { setSubtitleOffset(0, library: library) }
        let done = "자막 싱크를 " + (auto ? "자동으로 " : "") + "맞췄어요 (" + SubtitleAutoSync.moved(fix) + (method == "track" ? " · 사이트 자막 기준" : "") + (abs(manual) >= 0.0005 ? " · 수동 싱크 0으로" : "") + ")"
        // Still being translated: its lines are shown moved as they come in, and the translation gets the fix when done.
        if translation.running && sourceSubtitle == target {
            syncFixes[target] = fix
            if let output = lastTranslation { let latest = output; lastTranslation = nil; Task { await reshowTranslation(latest) } }
            showSyncDone(done + " · 번역이 끝나면 번역 자막에도 적용돼요") { [weak self] in
                guard let self, self.syncFixes[target] == fix else { return false }
                self.syncFixes[target] = nil
                if abs(manual) >= 0.0005 { self.setSubtitleOffset(manual, library: library) }
                if let output = self.lastTranslation, self.subtitle != output { self.subtitle = output; self.engine.subtitle(output, translated: true) }
                self.notify("자막 싱크를 원래대로 되돌렸습니다."); return true
            }
            return
        }
        // The subtitle on screen now: the one synced, or the translation of it finished meanwhile (timed as it is).
        if subtitle != target {
            guard let current = subtitle, let saved = currentSaved, saved.original == target else { return }
            Task {
                guard let retimed = try? await SubtitleAutoSync.retimed(current, fix), subtitle == current else { return }
                syncChecked.insert(retimed)
                finishSync(from: current, copy: retimed, record: saved, provider: provider, done: done, manual: manual, library: library)
            }
            return
        }
        finishSync(from: target, copy: copy, record: saved, provider: provider, done: done, manual: manual, library: library)
    }
    private func reshowTranslation(_ output: URL) async {
        guard let source = sourceSubtitle, let fix = syncFixes[source], let shown = try? await SubtitleAutoSync.retimed(output, fix, stable: true) else { return }
        guard syncFixes[source] == fix, lastTranslation == nil || lastTranslation == output else { return }
        lastTranslation = output
        if subtitle == shown { engine.reloadSubtitle() } else { subtitle = shown; engine.subtitle(shown, translated: true) }
    }
    /// The synced copy on screen, kept for the episode (so it comes first next time); 되돌리기 puts the subtitle and the
    /// 자막 타이밍 setting back.
    private func finishSync(from original: URL, copy: URL, record: SavedSubtitle?, provider: String, done: String, manual: Double, library: LibraryStore) {
        let translated = record?.translated == true
        EpisodeSubtitleStore.shared.save(copy, item: item, provider: record?.provider ?? provider, translated: translated, original: record?.original)
        if !translated { sourceSubtitle = copy }
        subtitle = copy; subtitleFiles = subtitleFiles.map { $0 == original ? copy : $0 }
        engine.subtitle(copy, translated: translated)
        library.saveSubtitle(animeID: item.anime.id, episodeID: item.episodeID, file: translated ? copy : sourceSubtitle, offset: subtitleOffset)
        showSyncDone(done) { [weak self] in
            guard let self, self.subtitle == copy else { return false }
            EpisodeSubtitleStore.shared.save(original, item: self.item, provider: record?.provider ?? provider, translated: translated, original: record?.original)
            if !translated { self.sourceSubtitle = original }
            self.subtitle = original; self.engine.subtitle(original, translated: translated)
            if abs(manual) >= 0.0005 { self.setSubtitleOffset(manual, library: library) }
            else { library.saveSubtitle(animeID: self.item.anime.id, episodeID: self.item.episodeID, file: translated ? original : self.sourceSubtitle, offset: self.subtitleOffset) }
            self.notify("자막 싱크를 원래대로 되돌렸습니다."); return true
        }
    }

    private func stopPrefetch() {
        prefetchTask?.cancel(); prefetchTask = nil; pretranslation.cancel(); nextResolver.cancel(); prefetchStatus = nil
    }
    /// 다음 화 미리 준비 (desktop prepareNext, 설정 > 재생): while an episode plays, the next one is made ready in the
    /// background, each part as 설정 says: its stream (영상: found now, used when the episode opens if it still answers),
    /// its Korean subtitle synced to it (자막과 싱크: found and kept for the episode, synced by its site track), and its
    /// machine translation when it has no Korean one (AI 번역). Once per next episode.
    private func prefetchNext(library: LibraryStore, token: UUID) {
        guard !item.next.isEmpty, prefetchTask == nil else { return }
        let next = item.next[0], key = next.anime.id + "#" + next.episodeID
        let offline = DownloadStore.shared.offline(next) != nil || next.directURL != nil
        let wantsVideo = library.preferences.prepareNextVideo != false && !offline && preparedStreams?.key != key
        let wantsSubtitle = library.preferences.prepareNextSubtitle != false && !offline && !["ohli24", "linkani"].contains(next.anime.source)
        let siteKorean = DesktopSubtitlePolicy.siteKorean(source: item.anime.source, tracks: active?.subtitles ?? [], stream: active)
        let translating = automaticPreferences(library).flatMap { preferences -> AppPreferences? in
            library.preferences.pretranslateNext != false && DesktopSubtitlePolicy.shouldPrefetch(provider: preferences.translationProvider, cloud: preferences.prepareNextCloud == true,
                siteKorean: siteKorean, prefersAI: library.aiSubtitleSeries[item.anime.id] == true) ? preferences : nil
        }
        guard wantsVideo || wantsSubtitle || translating != nil else { return }
        prefetchTask = Task {
            defer { if token == generation { prefetchTask = nil } }
            // As this episode starts playing.
            while !Task.isCancelled && token == generation && (active == nil || engine.duration <= 0) {
                do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
            }
            guard !Task.isCancelled, token == generation else { return }
            var raw = false
            let known = preparedStreams?.key == key ? preparedStreams?.streams ?? [] : []
            if !offline && known.isEmpty {
                raw = await preparer.preferRaw(next, preferences: library.preferences)
                guard !Task.isCancelled, token == generation else { return }
                nextResolver.resolve(next, preferRaw: raw, preferred: library.preferences.preferredServers?[next.anime.source] ?? library.preferences.preferredStream)
                let deadline = Date().addingTimeInterval(60)
                while nextResolver.loading && nextResolver.streams.isEmpty && Date() < deadline && !Task.isCancelled {
                    do { try await Task.sleep(nanoseconds: 250_000_000) } catch { return }
                }
            }
            guard !Task.isCancelled, token == generation else { return }
            let streams = known.isEmpty ? nextResolver.streams : known, tracks = streams.flatMap { $0.subtitles }
            if wantsVideo, known.isEmpty, !streams.isEmpty {
                preparedStreams = (key, streams, nextResolver.subtitles, nextResolver.servers, nextResolver.serverLabel, raw)
            }
            var korean = false
            if wantsSubtitle {
                if let found = try? await preparer.community(next, preferences: library.preferences), token == generation, !Task.isCancelled {
                    korean = true
                    EpisodeSubtitleStore.shared.save(found.0, item: next, provider: found.1, translated: false)
                    prefetchStatus = "다음 화 한국어 자막 저장 완료"
                    await syncPrepared(found.0, item: next, provider: found.1, translated: false, original: nil, tracks: tracks)
                }
            }
            guard !korean, let preferences = translating, !Task.isCancelled, token == generation else { return }
            // The episode playing translates first.
            while !Task.isCancelled && token == generation && (translation.running || subtitle == nil) {
                do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
            }
            guard !Task.isCancelled, token == generation else { return }
            prefetchStatus = "다음 화 자막 준비 중"
            do {
                try Task.checkCancellation()
                guard token == generation, let prepared = try await preparer.prepare(next, tracks: tracks, preferences: preferences, prefersAI: false, stream: streams.first) else { prefetchStatus = nil; return }
                if EpisodeSubtitleStore.shared.list(next).contains(where: { $0.file == prepared.0 && $0.translated }) { prefetchStatus = "다음 화 번역 자막 저장 완료"; return }
                EpisodeSubtitleStore.shared.save(prepared.0, item: next, provider: prepared.1, translated: false)
                if await SubtitleFiles.isKorean(prepared.0) { prefetchStatus = "다음 화 한국어 자막 저장 완료"; return }
                prefetchStatus = "다음 화 미리 번역 중"
                var translated: URL?
                pretranslation.translate(prepared.0, preferences: preferences, anime: next.anime, background: true) { output in
                    guard token == self.generation else { return }
                    EpisodeSubtitleStore.shared.save(output, item: next, provider: preferences.translationProvider, translated: true, original: prepared.0)
                    if !output.lastPathComponent.hasPrefix("working-") { translated = output }
                }
                while pretranslation.running && !Task.isCancelled {
                    try await Task.sleep(nanoseconds: 500_000_000)
                }
                try Task.checkCancellation()
                guard token == generation else { return }
                prefetchStatus = pretranslation.error.map { "다음 화 번역: " + $0 } ?? "다음 화 자막 번역 저장 완료"
                // 자막과 싱크: the translation is synced with the next episode's site track too.
                if wantsSubtitle, let translated {
                    await syncPrepared(translated, item: next, provider: preferences.translationProvider, translated: true, original: prepared.0, tracks: tracks)
                }
            } catch is CancellationError { }
            catch { if token == generation { prefetchStatus = error.localizedDescription } }
        }
    }
    /// desktop syncPrepared: a subtitle made ready for the next episode, synced with that episode's site tracks and kept for
    /// it ("… · 자동 싱크"). Only by a site track (seconds, nothing downloaded) and after the player's own syncs, so the
    /// episode playing never waits behind it; without a track the episode is synced when it opens. Left as it is (and
    /// not kept) when the sync finds nothing better.
    private func syncPrepared(_ file: URL, item next: PlaybackItem, provider: String, translated: Bool, original: URL?, tracks: [RemoteSubtitle]) async {
        var references: [URL] = []
        for track in SubtitleAutoSync.referenceTracks(tracks) {
            if let file = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]).first { references.append(file) }
            if Task.isCancelled { return }
        }
        guard !references.isEmpty else { return }
        while syncRunning { do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return } }
        guard case let .synced(copy, _, method)? = try? await SubtitleAutoSync.sync(subtitle: file, timing: original, references: references, video: nil, chapters: [],
            status: { _, _ in }, cancelled: { Task.isCancelled }), method == "track", !Task.isCancelled else { return }
        EpisodeSubtitleStore.shared.save(copy, item: next, provider: provider, translated: translated, original: original)
        prefetchStatus = "다음 화 자막 싱크까지 맞춰 둠"
    }
    func save(library: LibraryStore) {
        // Only a position mpv reports for an open video, and none while suspended (it may read as the end).
        guard active != nil, !systemPlayback, !holdProgress, engine.canSaveProgress else { return }
        library.progress(anime: item.anime, episodeID: item.episodeID, title: item.title, number: item.number,
            watchURL: item.watchURL.absoluteString, directURL: active?.url.absoluteString, position: engine.position, duration: engine.duration, displayNumber: item.displayNumber)
    }
    func shutdown(library: LibraryStore) {
        pictureInPicture.stop()
        subtitleClassification?.cancel()
        resolutionTask?.cancel(); selectionTask?.cancel()
        save(library: library); stopPrefetch(); automaticTask?.cancel(); cancelSubtitleBackground(); preparer.cancel(); cancelSync(); generation = UUID(); miruroRecoveryTask?.cancel(); miruroRecoveryTask = nil; engine.shutdown(); resolver.cancel(); translation.shutdown(); service.cancel(); titleLookup.cancel(); proxy?.stop(); proxy = nil; cast.stop()
    }
}
struct EpisodePlayerView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: DownloadStore
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: EpisodePlayerModel
    @State private var showWeb = false
    @State private var importer = false
    @State private var importingFont = false
    @State private var settings = false
    @AppStorage("playerSettingsTab") private var settingsTab = 0
    @State private var openServerKind = ""
    @State private var originalOrientation: UIInterfaceOrientation = .portrait
    init(item: PlaybackItem) { _model = StateObject(wrappedValue: EpisodePlayerModel(item: item)) }
    var body: some View {
        ZStack {
            Color.black
            PlayerVideoSurface(engine: model.engine).allowsHitTesting(false)
            PlayerPiPSurface(pip: model.pictureInPicture)
                .opacity(model.pictureInPicture.rendering ? 1 : 0.001).allowsHitTesting(false)
            if !UIShowcase.enabled {
                ProviderPlayerView(resolver: model.resolver).opacity(showWeb ? 1 : 0.001).allowsHitTesting(showWeb)
            }
            if model.systemPlayback, let stream = model.systemStream {
                SystemPlayerView(stream: stream, resume: library.resume(model.item.anime, episodeID: model.item.episodeID), onProgress: { position, duration in
                    library.progress(anime: model.item.anime, episodeID: model.item.episodeID, title: model.item.title,
                        number: model.item.number, watchURL: model.item.watchURL.absoluteString, directURL: stream.url.absoluteString, position: position, duration: duration, displayNumber: model.item.displayNumber)
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
                    pictureInPicture: { await model.pictureInPicture.toggle() },
                    pipSupported: model.pictureInPicture.supported, pipActive: model.pictureInPicture.active,
                    screenshot: { await model.screenshot(title: DesktopCatalog.shared.title(model.item.anime.anime, source: model.item.anime.source, language: library.preferences.titleLanguage)) },
                    subtitleOffset: model.subtitleOffset,
                    keyboardEnabled: !settings && !importer, toast: model.toast)
            } else {
                VStack { HStack { Button { dismiss() } label: { Image(systemName: "arrow.left") }; Spacer(); Button { settings = true } label: { Image(systemName: "gearshape") } }.font(.title3).padding(20).background(.black.opacity(0.65)); Spacer() }.foregroundStyle(.white)
            }
            if !showWeb && (model.active == nil || model.playbackError != nil) && !UIShowcase.enabled {
                VStack(spacing: 12) {
                    if model.resolver.loading { ProgressView("영상을 준비하고 있어요").tint(.white) }
                    if let error = model.playbackError ?? model.error ?? model.resolver.error { Text(error).font(.caption).multilineTextAlignment(.center); Button("다시 시도") { model.begin(library: library) }; Button("웹 플레이어 열기") { showWeb = true } }
                }.padding(24).foregroundStyle(.white).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 16))
            }
            if settings { playerSettings.zIndex(10) }
            if let offer = model.koreanOffer {
                VStack { Spacer(); HStack(spacing: 16) {
                    Text(offer).font(.subheadline)
                    Button("바꾸기") { model.acceptKoreanOffer(library: library) }.buttonStyle(.borderedProminent)
                    Button("닫기") { model.dismissKoreanOffer() }
                }.padding(16).background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 16)).padding(.bottom, 100) }.foregroundStyle(.white).padding(.horizontal, 24).zIndex(9)
            }
            if let notice = model.syncNotice {
                // desktop showSyncDone: what the sync did, and 되돌리기 while the subtitle it changed is still on screen.
                VStack { Spacer(); HStack(spacing: 16) {
                    Text(notice).font(.subheadline).fixedSize(horizontal: false, vertical: true)
                    Button("되돌리기") { model.undoSync() }.buttonStyle(.borderedProminent)
                    Button("확인") { model.dismissSyncNotice() }
                }.padding(16).background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 16)).padding(.bottom, 100) }.foregroundStyle(.white).padding(.horizontal, 24).zIndex(9)
                    .accessibilityIdentifier("player-sync-notice")
            }
            if model.pictureInPicture.active {
                // Keep the PiP source attached underneath; covering it does not alter its frames.
                ZStack {
                    Color.black
                    VStack(spacing: 16) {
                        Image(systemName: "pip")
                            .font(.system(size: 40))
                            .accessibilityHidden(true)
                        Text("PiP 모드로 재생 중입니다")
                            .font(.headline)
                        Text("작은 화면에서 영상을 계속 시청할 수 있어요.")
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.7))
                        Button("전체 화면으로 돌아가기") { model.pictureInPicture.stop() }
                            .buttonStyle(.bordered)
                    }
                    .multilineTextAlignment(.center)
                    .padding(24)
                    .foregroundStyle(.white)
                }
                .accessibilityIdentifier("pip-playback-placeholder")
                .zIndex(20)
            }
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(.black).ignoresSafeArea(.container)
            .background(PlayerOrientationView().allowsHitTesting(false))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("fullscreen-player")
            .statusBarHidden(true).persistentSystemOverlays(.hidden)
            .toolbar(.hidden, for: .navigationBar).toolbar(.hidden, for: .tabBar)
            .onAppear {
                originalOrientation = OrientationController.current; OrientationController.landscape()
                if UIShowcase.enabled {
                    if UIShowcase.screen.hasPrefix("player-fit-"), let fit = PlayerFit(rawValue: String(UIShowcase.screen.dropFirst("player-fit-".count))) { library.preferences.selectPlayerFit(fit) }
                    model.applyPreferences(library); model.engine.position = 183; model.engine.duration = 1440
                    if UIShowcase.screen.hasPrefix("player-settings") { settingsTab = Int(UIShowcase.screen.dropFirst("player-settings-".count)) ?? 0; settings = true }
                } else { model.begin(library: library) }
            }
            .onDisappear { model.shutdown(library: library); OrientationController.restore(originalOrientation) }
            .onChange(of: model.resolver.streams) { streams in
                model.selectResolved(streams, library: library); showWeb = false
            }
            .onChange(of: showWeb) { active in
                if active { model.engine.pause() }
                model.resolver.setWebPlaybackActive(active)
            }
            .onChange(of: model.resolver.subtitles) { model.siteSubtitlesArrived($0, library: library) }
            .fileImporter(isPresented: $importer, allowedContentTypes: [.data, .text]) { result in
                defer { importingFont = false }
                do {
                    let file = try result.get()
                    if importingFont {
                        guard ["ttf", "otf", "ttc"].contains(file.pathExtension.lowercased()) else { throw SubtitleFiles.failure("TTF · OTF · TTC 글꼴 파일을 선택하세요.") }
                        library.preferences.subtitleFont = try SubtitleFiles.importFont(file)
                        model.applyPreferences(library)
                    } else { model.importSubtitle(file, library: library, translate: false, provider: "user") }
                } catch { model.error = error.localizedDescription }
            }
            .onChange(of: library.preferences) { _ in model.applyPreferences(library) }
            .onChange(of: settings) { if $0 { model.engine.finishSpaceHold() } }
            .alert("다운로드", isPresented: Binding(get: { downloads.notice != nil }, set: { if !$0 { downloads.notice = nil } })) {
                Button("확인") { downloads.notice = nil }
            } message: { Text(downloads.notice ?? "") }
    }
    private func nextEpisode() {
        let remaining = model.item.next
        guard !remaining.isEmpty else { return }
        var item = remaining[0]; item.next = Array(remaining.dropFirst()); model.change(item, library: library)
    }
    private var playerSettings: some View {
        PlayerSettingsPanel(tab: $settingsTab, close: { settings = false }) {
            if settingsTab == 0 { playbackSettings }
            else if settingsTab == 1 { subtitleSettings }
            else { styleSettings }
        }
    }
    /// The desktop's 재생 tab (index.html data-ps-pane="play"), then what only this device has: the audio tracks, the
    /// download, AirPlay and Cast, and the system or web player.
    @ViewBuilder private var playbackSettings: some View {
        let servers = ServerChoice.all(streams: model.resolver.streams, servers: model.resolver.servers)
        if servers.count >= 2 {
            PSSection {
                PSGroup("영상 서버")
                PSNote("영상이 안 나오거나 자꾸 끊기면 다른 서버를 골라 보세요. 보통은 자동이 알맞게 고릅니다.")
                serverPicker(servers)
            }
        }
        PSSection {
            PSGroup("화질")
            PSNote("숫자가 클수록 선명하지만, 인터넷이 느리면 끊길 수 있어요.")
            PlayerChoiceGrid(options: ["Auto", "480p", "720p", "1080p"].map { ($0, $0 == "Auto" ? "자동" : $0) }, selection: Binding(get: { library.preferences.quality }, set: {
                library.preferences.quality = $0; if let stream = model.active { model.save(library: library); model.play(stream, library: library) }
            }), identifier: "player-quality").padding(.top, 4)
        }
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("재생 속도").font(.system(size: 13, weight: .medium))
                Text(String(format: "%.2fx", library.preferences.speed)).font(.system(size: 11)).foregroundStyle(PS.detail)
            }
            PlayerChoiceGrid(options: [0.75, 1, 1.25, 1.5, 2, 3, 4].map { ($0, String(format: "%.2fx", $0)) }, selection: Binding(get: { library.preferences.speed }, set: {
                model.engine.finishSpaceHold(); library.preferences.speed = $0; model.engine.setSpeed($0)
            }), identifier: "player-speed")
        }.padding(.top, 14)
        // 뒤로/앞으로 이동 (desktop 0.5.32 #psSeekSteps): the same setting as 설정 > 재생 (1–120 seconds).
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("뒤로/앞으로 이동").font(.system(size: 13, weight: .medium))
                Text("\(Int(library.preferences.seekSeconds))초").font(.system(size: 11)).foregroundStyle(PS.detail)
            }
            PlayerChoiceGrid(options: SeekStep.choices.map { (Double($0), "\($0)초") }, selection: Binding(get: { library.preferences.seekSeconds }, set: {
                library.preferences.seekSeconds = SeekStep.clamped($0)
            }), identifier: "player-seek-steps")
            PSSecondsInput(value: library.preferences.seekSeconds) { library.preferences.seekSeconds = SeekStep.clamped($0) }
            PSNote("화면 가운데 뒤로/앞으로 버튼과 ←/→ 방향키로 이동할 시간이에요. 1~120초로 직접 입력할 수도 있어요.")
        }.padding(.top, 12).padding(.bottom, 12)
        PSSection {
            PSGroup("화면 비율")
            PlayerChoiceGrid(options: PlayerAspect.allCases.map { ($0.rawValue, $0.title) }, selection: Binding(get: { library.preferences.playerAspect ?? "original" }, set: {
                let value = PlayerAspect(rawValue: $0) ?? .original
                library.preferences.selectPlayerAspect(value); model.engine.setAspect(value)
            }), identifier: "player-aspect")
            PSNote("선택한 비율로 영상을 늘립니다. 화면 채우기는 화면에 맞춰 여백 없이 늘립니다.")
            Text("화면 맞춤").font(.system(size: 11)).foregroundStyle(.white.opacity(0.72)).padding(.top, 6)
            PlayerChoiceGrid(options: PlayerFit.allCases.map { ($0.rawValue, $0.title) }, selection: Binding(get: { PlayerPresentation(library.preferences).fit.rawValue }, set: {
                library.preferences.selectPlayerFit(PlayerFit(rawValue: $0) ?? .contain); model.engine.setFit($0)
            }), identifier: "player-fit")
            PSNote("맞춤은 원본 비율을 지키고, 채움은 가장자리를 잘라 화면을 채우고, 늘림은 화면 전체로 늘립니다.")
        }
        Group {
            PSGroup("Anime4K 영상 보정")
            PlayerChoiceGrid(options: Anime4KMode.allCases.map { ($0.rawValue, $0.title) }, selection: Binding(get: {
                Anime4KMode(saved: library.preferences.anime4k).rawValue
            }, set: { library.preferences.anime4k = $0 }), identifier: "player-anime4k")
            PSNote("기본은 끄기입니다. 가볍게부터 사용해 보세요. 화면 확대 시 2배 업스케일을 사용하며, 기기에 따라 발열·배터리 사용량이 늘 수 있습니다.")
            PSNote("앱 기본 플레이어에서 적용됩니다. PiP·웹/외부 플레이어와 원본 스크린샷에는 적용되지 않습니다.")
            if let error = model.engine.anime4kError { PSNote(error, color: .orange) }
            PSGroup("자동 재생 · 건너뛰기")
            PSSwitch(title: "다음 화 자동재생", detail: "영상이 끝나면 다음 화를 재생합니다", isOn: $library.preferences.autoPlay)
            PSSwitch(title: "오프닝·엔딩 건너뛰기 버튼", detail: "오프닝(OP)·엔딩(ED) 노래가 나오면 건너뛰기 버튼을 띄웁니다",
                isOn: Binding(get: { library.preferences.showSkipButton ?? true }, set: { library.preferences.showSkipButton = $0 }))
            PSSwitch(title: "오프닝·엔딩 자동으로 건너뛰기", detail: "버튼을 누르지 않아도 알아서 넘어갑니다", isOn: $library.preferences.autoSkip)
        }
        deviceSettings
        (Text("조작  ").foregroundColor(Color.white.opacity(0.8)).fontWeight(.semibold)
            + Text("영상 꾹 누르기 2배속 · 왼쪽/오른쪽 두 번 탭 탐색 · 키보드: Space 짧게 재생/정지 · 길게 2배속 · ←/→ 탐색 · 0~9 위치 · [ ] 속도 · C 자막 켜기/끄기 · Z/X 자막 싱크 · S 오프닝·엔딩 건너뛰기 · P 스크린샷 · M 음소거 · PageUp/PageDown 이전/다음 화"))
            .font(.system(size: 11)).foregroundStyle(PS.detail).lineSpacing(4).fixedSize(horizontal: false, vertical: true).padding(.top, 14)
        if let error = model.error { PSNote(error, color: .red).padding(.top, 10) }
    }
    /// What the desktop has elsewhere or not at all: the audio tracks, the download, Cast and AirPlay, the other players.
    @ViewBuilder private var deviceSettings: some View {
        let audio = model.engine.tracks.filter { $0.type == "audio" }
        if audio.count > 1 {
            PSGroup("음성")
            PlayerChoiceGrid(options: audio.map { ($0.id, $0.title) }, selection: Binding(get: { audio.first(where: \.selected)?.id ?? -1 }, set: { id in
                if let track = audio.first(where: { $0.id == id }) { model.engine.selectTrack(track) }
            }), identifier: "player-audio")
        }
        PSGroup("이 기기에서")
        if let active = model.active {
            PSButtonRow {
                Button("이 회차 다운로드") { downloads.download(model.item, stream: active, quality: library.preferences.quality) }
                Button("Cast로 보내기") { model.castVideo() }
            }
            PSButtonRow {
                if model.systemPlayback { Button("mpv로 재생") { model.systemPlayback = false; model.play(active, library: library); settings = false } }
                else { Button("시스템 플레이어") { model.pictureInPicture.stop(); model.engine.pause(); model.systemPlayback = true; showWeb = false; settings = false } }
                Button(showWeb ? "앱 플레이어" : "웹 플레이어") { showWeb.toggle(); settings = false }
            }
        } else {
            PSButtonRow { Button(showWeb ? "앱 플레이어" : "웹 플레이어") { showWeb.toggle(); settings = false } }
        }
        PSRow(title: "AirPlay · Chromecast", value: "TV나 스피커로 보냅니다") {
            AirPlayButton().frame(width: 32, height: 32); CastButton().frame(width: 32, height: 32)
            Button("중계 종료") { model.stopCast() }.buttonStyle(PSTextButtonStyle())
        }
    }
    /// Video servers grouped by kind (player.js renderVideoServers): 자동 and one chip per kind, the servers of the open kind
    /// under them, their names without the kind prefix. A picked server is kept for the source's later episodes.
    @ViewBuilder private func serverPicker(_ choices: [ServerChoice]) -> some View {
        let playing = model.active?.label ?? model.resolver.serverLabel
        let picked = library.preferences.preferredServers?[model.item.anime.source] ?? ""
        let kinds = ServerChoice.kinds(choices)
        let open = kinds.contains(openServerKind) ? openServerKind : (choices.first { $0.label == playing } ?? choices[0]).kind
        PSNote("재생 중: " + (playing.isEmpty ? "-" : playing), color: .white.opacity(0.66))
        PlayerChoiceGrid(options: [("", "자동")] + kinds.map { ("kind:" + $0, ServerChoice.title($0)) },
            selection: Binding(get: { picked.isEmpty ? "" : "kind:" + open }, set: { value in
                if value.hasPrefix("kind:") { openServerKind = String(value.dropFirst(5)); return }
                var servers = library.preferences.preferredServers ?? [:]; servers[model.item.anime.source] = nil
                library.preferences.preferredServers = servers; library.preferences.preferredStream = nil
                model.save(library: library); showWeb = false; model.begin(library: library)
            }), identifier: "player-server-kinds").padding(.top, 4)
        VStack(alignment: .leading, spacing: 6) {
            PlayerChoiceGrid(options: choices.filter { $0.kind == open }.map { ($0.id, ServerChoice.name($0.label)) },
                selection: Binding(get: { model.active?.id ?? choices.first { $0.label == (picked.isEmpty ? playing : picked) }?.id ?? "" }, set: { id in
                    guard let choice = choices.first(where: { $0.id == id }) else { return }
                    library.preferences.preferredServers = (library.preferences.preferredServers ?? [:]).merging([model.item.anime.source: choice.label]) { _, new in new }
                    model.save(library: library); showWeb = false
                    if let stream = choice.stream {
                        library.preferences.preferredStream = stream.label
                        if model.item.anime.source == "miruro" { model.active = nil; model.selectResolved(model.resolver.streams, library: library) }
                        else { model.play(stream, library: library) }
                    }
                    else if let server = choice.server { model.active = nil; model.engine.pause(); model.resolver.selectServer(server) }
                }), identifier: "player-servers")
            if let note = ServerChoice.note(open) { PSNote(note) }
        }.padding(8).background(.white.opacity(0.04), in: RoundedRectangle(cornerRadius: 10))
    }
    /// The desktop's 자막 tab (data-ps-pane="subtitle"): a source's saved subtitle comes back when its card is pressed,
    /// so there is no separate list of saved subtitles.
    @ViewBuilder private var subtitleSettings: some View {
        Group {
            PSSwitch(title: "자막 표시", detail: "끄면 자막을 숨깁니다", isOn: Binding(get: { model.engine.subtitlesVisible }, set: { _ in model.engine.toggleSubtitleVisibility() }))
                .padding(.top, 6)
            Text(subtitleStateText).font(.system(size: 12)).foregroundStyle(.white.opacity(0.66)).fixedSize(horizontal: false, vertical: true)
                .padding(.top, 2).accessibilityIdentifier("player-subtitle-state")
            if model.translatable { translateRow }
        }
        Group {
            PSGroup("자막 가져올 곳")
            PSNote("누르면 그곳의 자막으로 바꾸고, 다음 화부터도 그곳을 먼저 찾습니다.")
            PSSourceGrid(options: subtitleSources, selection: model.activeSubtitleSource) { model.chooseSource($0, library: library) }
            if model.openSheet == "anissia" { makerBox }
            if model.openSheet == "reanime" && model.trackSource == "reanime" { trackBox }
            if model.openSheet == "jimaku" { jimakuBox }
        }
        PSGroup("자막이 없거나 안 맞을 때")
        PSButtonRow {
            Button("한국어 자막 다시 찾기") { model.findKoreanAgain(library: library) }
            Button("파일 앱에서 자막 열기") { importingFont = false; settings = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { importer = true } }
        }
        PSNote("자막 파일은 ASS · SSA · SRT · VTT · SMI를 열 수 있어요.").padding(.top, 6)
        PSButtonRow {
            Button(model.syncButtonTitle) { model.runSubtitleSync(library: library) }
                .disabled(!model.syncRunning && !model.syncAvailable).accessibilityIdentifier("player-subtitle-sync-run")
        }
        PSNote("자막이 대사보다 일찍·늦게 나오면 영상 음성에 맞춰 시간을 고쳐요. Re:Anime·Miruro처럼 사이트 자막 트랙이 있으면 그 트랙에 맞춰 몇 초 만에 맞춥니다.").padding(.top, 6)
        PSSwitch(title: "싱크가 어긋나면 자동으로 맞추기", detail: "자막을 켜면 뒤에서 영상 음성(사이트 자막 트랙이 있으면 그 트랙)과 비교해, 확실히 어긋나 있을 때만 맞춰요. 확실하지 않으면 그대로 둬요. 잘못 맞으면 알림에서 되돌릴 수 있어요",
                 isOn: Binding(get: { library.preferences.subtitleSyncCheck ?? true }, set: { library.preferences.subtitleSyncCheck = $0 }), small: true)
        if let status = model.prefetchStatus { PSNote(status).padding(.top, 4) }
        if let error = model.error { PSNote(error, color: .red).padding(.top, 8) }
    }
    private var subtitleStateText: String {
        if let state = model.subtitleState { return state }
        if let status = model.syncStatus { return status }
        if model.translation.running, let status = model.translation.status { return status }
        if let label = model.subtitleLabel { return label + " 적용됨" }
        if model.active == nil { return "영상 연결 후 자막을 확인합니다." }
        return "자막을 찾는 중이거나 찾은 자막이 없습니다."
    }
    /// The source cards; the site's own subtitle shows only on the source that has one.
    private var subtitleSources: [PSSource] {
        var sources: [PSSource] = []
        if model.trackSource == "linkkf" { sources.append(PSSource(id: "linkkf", title: "Linkkf", detail: "사이트 한국어 자막")) }
        if model.trackSource == "reanime" { sources.append(PSSource(id: "reanime", title: model.item.anime.source == "miruro" ? "Miruro" : "Re:Anime", detail: "사이트 자막 트랙")) }
        let korean = ["kairan": "Kairan", "csora": "Csora", "anissia": "Anissia"]
        for id in ["kairan", "csora", "anissia"] { sources.append(PSSource(id: id, title: korean[id] ?? id, detail: "한국어 자막")) }
        sources.append(PSSource(id: "jimaku", title: "Jimaku", detail: "일본어 → 번역"))
        sources.append(PSSource(id: "ai", title: "AI 번역", detail: "한국어로 번역"))
        return sources
    }
    /// The one 번역 row (app.js labelTranslateButtons): the API configured, and the local model; again for a translation on screen.
    @ViewBuilder private var translateRow: some View {
        let again = model.retranslatable
        let cloud = model.cloudProvider(library)
        PSButtonRow {
            if let cloud {
                Button(SubtitleLabels.engine(cloud) + (again ? "로 다시 번역" : "로 한국어 번역")) { model.translate(library: library, fresh: again, provider: cloud) }
            }
            Button(again ? "로컬 AI로 다시 번역" : "로컬 AI로 번역") { model.translate(library: library, fresh: again, provider: "local") }
        }.disabled(model.translation.running)
        TranslationStatus(coordinator: model.translation)
    }
    private var makerBox: some View {
        PSBox(title: "Anissia 자막 제작자", state: model.searching ? "불러오는 중…" : model.makers.isEmpty ? "" : "\(model.makers.count)명",
              note: "이 작품의 자막을 만든 분들이에요. 다른 분의 자막으로 바꾸려면 누르세요.") {
            if !model.searching && model.makers.isEmpty { PSNote(model.subtitleSearchError ?? "제작자 정보를 찾지 못했습니다.") }
            PlayerChoiceGrid(options: model.makers.map { ($0.website, $0.name) }, selection: Binding(get: { model.makers.first { $0.name == model.selectedMaker }?.website ?? "" }, set: { website in
                if let maker = model.makers.first(where: { $0.website == website }) { model.chooseMaker(maker, library: library) }
            }), identifier: "player-anissia-makers")
        }
    }
    private var trackBox: some View {
        PSBox(title: (model.item.anime.source == "miruro" ? "Miruro" : "Re:Anime") + " 자막 트랙", state: "\(model.siteTracks.count)개 트랙",
              note: "영상 사이트가 주는 여러 언어 자막이에요. 한국어가 없으면 영어 트랙 등을 고르세요. 고르면 위의 번역 버튼으로 한국어로 번역할 수 있어요.") {
            ForEach(SiteTrackOrder.sorted(model.siteTracks)) { track in
                PSTrackOption(title: SiteTrackOrder.name(track), detail: SiteTrackOrder.detail(track), selected: model.selectedTrackURL == track.url) {
                    model.chooseTrack(track, library: library)
                }
            }
        }
    }
    private var jimakuBox: some View {
        let files = model.jimaku.files(model.item)
        let failure = model.jimaku.error(model.item)
        return PSBox(title: "Jimaku 일본어 자막", state: failure != nil ? "" : files == nil ? "Jimaku에서 찾는 중…" : files!.isEmpty ? "" : "\(files!.count)개 파일",
                     note: "일본어 자막 파일이에요. 고르면 일본어로 먼저 나오고, 설정에 따라 한국어로 번역해 바꿔 줍니다.") {
            if let failure { PSNote(failure, color: .red) }
            else if files?.isEmpty == true { PSNote("이 회차의 파일이 없습니다.") }
            ForEach(files ?? [], id: \.subtitleResultID) { asset in
                PSTrackOption(title: SubtitleNames.label(asset.name, url: asset.url),
                              detail: ([URL(fileURLWithPath: asset.name).pathExtension.uppercased()] + (asset.size > 0 ? ["\(max(1, Int((Double(asset.size) / 1024).rounded())))KB"] : [])).filter { !$0.isEmpty }.joined(separator: " · "),
                              selected: model.selectedJimakuURL == asset.url) {
                    if let url = URL(string: asset.url) { model.importSubtitle(url, library: library, provider: "jimaku", asset: asset) }
                }.accessibilityIdentifier("player-jimaku-file")
            }
        }
    }
    /// The desktop's 자막 모양 tab (data-ps-pane="style"), with its ranges.
    @ViewBuilder private var styleSettings: some View {
        Group {
            PSLabel(title: "자막 크기", value: "\(Int(library.preferences.subtitleSize))%")
            Slider(value: $library.preferences.subtitleSize, in: 50...180, step: 5).accessibilityLabel("자막 크기")
            PSLabel(title: "자막 높이", value: "\(Int(library.preferences.subtitlePadding))%")
            Slider(value: $library.preferences.subtitlePadding, in: 3...30, step: 1).accessibilityLabel("자막 높이")
            PSLabel(title: "자막 타이밍", value: syncLabel)
            PSSyncInput(value: model.subtitleOffset) { model.setSubtitleOffset($0, library: library) }
            PSNote("음수는 빠르게, 양수는 늦게 표시합니다. 크기 제한 없이 직접 입력할 수 있어요.").padding(.bottom, 6)
            PlayerChoiceGrid(options: [("fast", "0.25초 빠르게"), ("reset", "원래대로"), ("slow", "0.25초 늦게")], selection: Binding(get: { "" }, set: {
                if $0 == "reset" { model.setSubtitleOffset(0, library: library) }
                else { model.shiftSubtitle($0 == "fast" ? -0.25 : 0.25, library: library) }
            }), identifier: "player-subtitle-sync")
            PSNote("자막이 대사보다 늦게 나오면 빠르게, 먼저 나오면 늦게를 누르세요.").padding(.top, 6)
        }
        Group {
            PSGroup("특수 효과 자막 (ASS)")
            PSSwitch(title: "원본 효과 그대로", detail: "노래 가사·간판 번역 등의 위치·색·움직임을 그대로 보여 줍니다", isOn: $library.preferences.assEffects, small: true)
            PSGroup("일반 자막 (SRT · VTT)")
            PSSwitch(title: "자막 파일의 색 쓰기", detail: "자막 파일에 들어 있는 색·기울임을 그대로 씁니다",
                     isOn: Binding(get: { library.preferences.vttStyle != false }, set: { library.preferences.vttStyle = $0 }), small: true)
            PSSwitch(title: "글자 굵게", detail: "자막 글자를 굵게 표시합니다", isOn: $library.preferences.subtitleBold, small: true)
            PSLabel(title: "글자 테두리 두께", value: String(format: "%.1fpx", library.preferences.outlineWidth))
            Slider(value: $library.preferences.outlineWidth, in: 0...6, step: 0.5).accessibilityLabel("글자 테두리 두께")
        }
        Group {
            PSGroup("글꼴")
            PSRow(title: "자막 글꼴", value: library.preferences.subtitleFont.isEmpty ? "기본 글꼴 사용 중" : library.preferences.subtitleFont) {
                if !library.preferences.subtitleFont.isEmpty {
                    Button("기본으로") { library.preferences.subtitleFont = ""; model.applyPreferences(library) }.buttonStyle(PSTextButtonStyle())
                }
                Button("글꼴 파일 선택") { importingFont = true; settings = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { importer = true } }
                    .buttonStyle(PSTextButtonStyle())
            }
            PSNote("파일 앱의 글꼴 파일(TTF · OTF · TTC)을 고르면 자막을 그 글꼴로 보여 줍니다.")
            discoveredFonts
        }
    }
    private var syncLabel: String {
        let offset = model.subtitleOffset
        if abs(offset) < 0.0005 { return "0초" }
        return (offset > 0 ? "+" : "") + String(format: "%.2f", offset).replacingOccurrences(of: #"\.?0+$"#, with: "", options: .regularExpression) + "초"
    }
    /// player.js renderDiscoveredFonts: the fonts that came with the subtitle on screen, to use as the subtitle font.
    @ViewBuilder private var discoveredFonts: some View {
        let families = (model.subtitle.map { SubtitleFiles.fonts(for: $0) } ?? []).reduce(into: [(String, URL)]()) { list, file in
            let family = SubtitleFiles.familyName(file)
            if !list.contains(where: { $0.0 == family }) { list.append((family, file)) }
        }
        if !families.isEmpty {
            PSLabel(title: "이 자막에 들어 있는 글꼴", value: "")
            PlayerChoiceGrid(options: [("", "기본")] + families.map { ($0.0, $0.0) }, selection: Binding(get: { library.preferences.subtitleFont }, set: { family in
                if family.isEmpty { library.preferences.subtitleFont = "" }
                else if let file = families.first(where: { $0.0 == family })?.1 { library.preferences.subtitleFont = (try? SubtitleFiles.importFont(file)) ?? family }
                model.applyPreferences(library)
            }), identifier: "player-subtitle-fonts").padding(.top, 4)
        }
    }
}
private extension SubtitleAsset {
    var subtitleResultID: String { source + ":" + url }
}
struct PlayerControls: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
    let pictureInPicture: () async -> String?
    let pipSupported: Bool
    let pipActive: Bool
    let screenshot: () async -> String
    var subtitleOffset = 0.0
    let keyboardEnabled: Bool
    var toast: EpisodePlayerModel.Toast? = nil
    @GestureState private var holdingSpeed = false
    @State private var dragging = false
    @State private var value = 0.0
    @State private var visible = true
    @State private var locked = false
    @State private var interaction = UUID()
    @State private var feedback = ""
    @State private var takingScreenshot = false
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
                            control(pipActive ? "pip.exit" : "pip.enter", label: pipActive ? "PiP 닫기" : "자막과 함께 PiP", enabled: pipSupported) {
                                Task { if let message = await pictureInPicture() { flash(message) } }
                            }
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
                                        if !editing { engine.seekByViewer(value) }; touch()
                                    }).tint(LilacStyle.accent)
                                Text(clock(engine.duration)).monospacedDigit().foregroundStyle(.white.opacity(0.65))
                            }.font(.system(size: 11)).padding(.horizontal, 12).padding(.vertical, 2)
                                .background(.black.opacity(0.52), in: RoundedRectangle(cornerRadius: 20))
                            control("camera", label: "스크린샷", enabled: !takingScreenshot) { takeScreenshot() }
                            control("lock.fill", label: "화면 잠금") { locked = true; visible = false }
                        }
                    }.padding(.horizontal, max(24, max(geometry.safeAreaInsets.leading, max(windowInsets.left, windowInsets.right)))).padding(.top, max(14, max(geometry.safeAreaInsets.top, windowInsets.top))).padding(.bottom, max(14, max(geometry.safeAreaInsets.bottom, windowInsets.bottom)))
                }
                if locked {
                    VStack { Spacer(); HStack { Spacer(); control("lock.open.fill", label: "잠금 해제") { locked = false; visible = true } }.padding(18) }
                }
                if !feedback.isEmpty { Text(feedback).font(.headline).multilineTextAlignment(.center).padding(14).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 22)).padding(.horizontal, 24).allowsHitTesting(false) }
                if engine.buffering { ProgressView().tint(.white).allowsHitTesting(false) }
                if engine.speedBoosted { VStack { Text("2배속").font(.headline).padding(.horizontal, 18).padding(.vertical, 8).background(.black.opacity(0.75), in: Capsule()).accessibilityIdentifier("speed-boost"); Spacer() }.padding(.top, 72).allowsHitTesting(false) }
                if let chapter, !locked, library.preferences.showSkipButton ?? true {
                    VStack { Spacer(); HStack { Spacer(); Button(action: skipChapter) { Label(OfflineChapter.skipLabel(chapter.type), systemImage: "forward.fill").font(.caption.bold()).padding(12).background(.black.opacity(0.65), in: Capsule()) } }.padding(.bottom, visible ? 70 : 18).padding(.trailing, 18) }
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
                        Button("") { engine.toggleSubtitleVisibility(); flash(engine.subtitlesVisible ? "자막을 켰어요" : "자막을 껐어요") }.keyboardShortcut("c", modifiers: [])
                        Button("") { shiftSubtitle(-0.5) }.keyboardShortcut("z", modifiers: [])
                        Button("") { shiftSubtitle(0.5) }.keyboardShortcut("x", modifiers: [])
                        Button("") { if chapter != nil { skipChapter() } else { flash("지금은 건너뛸 OP/ED 구간이 아니에요") } }.keyboardShortcut("s", modifiers: [])
                        Button("") { takeScreenshot() }.keyboardShortcut("p", modifiers: [])
                        Button("") { changeSpeed(-0.25) }.keyboardShortcut("[", modifiers: [])
                        Button("") { changeSpeed(0.25) }.keyboardShortcut("]", modifiers: [])
                        Button("") { if canNext { next() } }.keyboardShortcut(.pageDown, modifiers: [])
                        Button("") { if canPrevious { previous() } }.keyboardShortcut(.pageUp, modifiers: [])
                        ForEach(0..<10) { digit in Button("") { engine.seekByViewer(engine.duration * Double(digit) / 10) }.keyboardShortcut(KeyEquivalent(Character(String(digit))), modifiers: []) }
                    }
                }.disabled(locked || !keyboardEnabled).frame(width: 0, height: 0).opacity(0).accessibilityHidden(true)
            }
            .background(SpaceHoldKeyboard(enabled: keyboardEnabled && !locked,
                began: engine.beginSpaceHold, ended: { engine.finishSpaceHold(toggle: true); touch() }, cancelled: { engine.finishSpaceHold() }).frame(width: 0, height: 0))
            .onChange(of: holdingSpeed) { holding in if !holding { engine.finishSpaceHold() } }
            .onDisappear { engine.finishSpaceHold() }
            .task(id: interaction) {
                guard !UIShowcase.enabled, !engine.paused, !locked, !dragging else { return }
                do { try await Task.sleep(nanoseconds: 2_000_000_000) } catch { return }
                if !dragging { withAnimation(LilacMotion.animation(reduced: reduceMotion)) { visible = false } }
            }
            .onChange(of: engine.paused) { paused in if paused { visible = true }; touch() }
            .onChange(of: toast) { note in if let note { flash(note.text, seconds: 2.2) } }
    }
    private func shiftSubtitle(_ delta: Double) {
        adjustSubtitle(delta)
        let next = subtitleOffset + delta
        flash("자막 싱크 " + (next > 0 ? "+" : "") + String(format: "%.1f", next) + "초 (" + (next < -0.001 ? "자막이 빨리 나옴" : next > 0.001 ? "자막이 늦게 나옴" : "원래대로") + ")")
    }
    /// [ / ] step through the player's speeds (player.js SPEED_OPTIONS), not by a fixed amount.
    private func changeSpeed(_ delta: Double) {
        let speeds = [0.1, 0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 3, 4]
        let at = speeds.firstIndex { abs($0 - library.preferences.speed) < 0.001 } ?? speeds.firstIndex(of: 1)!
        library.preferences.speed = speeds[max(0, min(speeds.count - 1, at + (delta > 0 ? 1 : -1)))]
        engine.finishSpaceHold(); engine.setSpeed(library.preferences.speed)
        flash("재생 속도 " + String(format: "%.2f", library.preferences.speed) + "x")
    }
    private func takeScreenshot() {
        guard !takingScreenshot else { return }
        takingScreenshot = true
        Task { defer { takingScreenshot = false }; flash(await screenshot()) }
    }
    private func flash(_ text: String, seconds: Double = 1.4) {
        feedback = text
        Task { try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)); if feedback == text { feedback = "" } }
    }
    private func tapZone(_ delta: Double) -> some View {
        Color.clear.contentShape(Rectangle()).onTapGesture(count: 2) {
            guard !locked else { return }
            if delta == 0 { engine.toggle() } else { engine.skip(delta); feedback = "\(delta > 0 ? "+" : "−")\(Int(abs(delta)))초" }
            touch()
            Task { try? await Task.sleep(nanoseconds: 700_000_000); feedback = "" }
        }.onTapGesture { guard !locked else { return }; withAnimation(LilacMotion.animation(reduced: reduceMotion)) { visible.toggle() }; touch() }
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
    private func clock(_ seconds: Double) -> String {
        let value = seconds.isFinite ? max(0, Int(seconds)) : 0
        return value >= 3600 ? String(format: "%d:%02d:%02d", value / 3600, value % 3600 / 60, value % 60) : String(format: "%02d:%02d", value / 60, value % 60)
    }
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
    func makeUIView(context: Context) -> UIView { UIView() }
    func updateUIView(_ view: UIView, context: Context) {
        let web = resolver.playbackWebView
        guard web.superview !== view else { return }
        view.subviews.forEach { $0.removeFromSuperview() }
        web.frame = view.bounds; web.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(web)
    }
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
