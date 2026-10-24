import UIKit
import SwiftUI
import AVFoundation
import MediaPlayer
import Libmpv
import CoreMedia

struct MediaTrack: Identifiable {
    let id: Int
    let type: String
    let title: String
    var selected = false
}
@MainActor
final class MPVEngine: ObservableObject {
    private let activeVideoOutput: String
    init(activeVideoOutput: String = "gpu-next") { self.activeVideoOutput = activeVideoOutput }
    @Published var position = 0.0
    @Published var duration = 0.0
    @Published var volume = 100.0
    @Published var muted = false
    @Published var subtitlesVisible = true
    @Published var fit = "contain"
    @Published var aspect: PlayerAspect = .original
    @Published var speed = 1.0
    @Published var speedBoosted = false
    private var spaceHold: SpaceHoldSession?
    private var spaceTimer: Task<Void, Never>?
    @Published var paused = true { didSet { updateScreenAwake() } }
    @Published var buffering = false
    @Published var tracks: [MediaTrack] = []
    @Published var error: String?
    @Published private(set) var anime4kError: String?
    private var configuredAnime4K: Anime4KMode?
    private var anime4kReply: UInt64 = 1000
    var onEnd: (() -> Void)?
    var onProgress: ((Double, Double) -> Void)?
    private var handle: OpaquePointer?
    private var timer: Timer?
    private var pendingStream: ResolvedStream?
    private var resumePosition = 0.0
    private var fileLoaded = false { didSet { updateScreenAwake() } }
    private var completion = PlaybackCompletion()
    private let screenAwake = PlaybackScreenAwake()
    private var pendingSubtitle: URL?
    private(set) var translatedSubtitle = false
    private(set) var pictureInPictureOutput = false
    private var softwareOutput = false
    private var videoOutputHidden = false
    private var videoOutputReply: UInt64 = 0x10000000
    private(set) var restoringVideoOutput = false
    private var pendingEnd = false
    private var loadTimeout: Task<Void, Never>?
    private var mediaLayer: CAMetalLayer?
    private var preferences = AppPreferences()
    private var notifications: [NSObjectProtocol] = []
    private var remoteTargets: [(MPRemoteCommand, Any)] = []
    /// Values mpv reports through property observation; the main thread never waits on the mpv core for them.
    private var observed: [ObservedProperty: Double] = [:]
    /// Settings already sent, so a repeated configure (a subtitle attached, a preference saved) does not make mpv
    /// reconfigure the video output while it is starting.
    private var configured: [String: String] = [:]
    private var configuredSpeed: Double?
    private var lastNowPlaying: (paused: Bool, duration: Int, speed: Double, at: Date)?
    private enum ObservedProperty: UInt64, CaseIterable {
        case position = 1, duration, pause, cache, volume, mute, speed, tracks, eof
        var name: String { ["time-pos", "duration", "pause", "paused-for-cache", "volume", "mute", "speed", "track-list", "eof-reached"][Int(rawValue) - 1] }
        var format: mpv_format {
            switch self {
            case .pause, .cache, .mute, .eof: return MPV_FORMAT_FLAG
            case .tracks: return MPV_FORMAT_NODE
            default: return MPV_FORMAT_DOUBLE
            }
        }
    }

    func attach(_ layer: CAMetalLayer) {
        guard handle == nil else { return }
        mediaLayer = layer
        guard let context = mpv_create() else { error = "mpv 초기화 실패"; return }
        handle = context
        var window = Int64(Int(bitPattern: Unmanaged.passUnretained(layer).toOpaque()))
        mpv_set_option(context, "wid", MPV_FORMAT_INT64, &window)
        for (key, value) in ["vo": activeVideoOutput, "gpu-api": "vulkan", "gpu-context": "moltenvk",
                             "hwdec": "videotoolbox", "keep-open": "yes", "idle": "yes", "cache": "yes", "input-default-bindings": "no"] {
            mpv_set_option_string(context, key, value)
        }
        let result = mpv_initialize(context)
        guard result >= 0 else { error = String(cString: mpv_error_string(result)); mpv_terminate_destroy(context); handle = nil; return }
        configured = [:]; configuredSpeed = nil; observed = [:]
        configuredAnime4K = nil
        videoOutputHidden = false; restoringVideoOutput = false
        mpv_request_log_messages(context, "error")
        for property in ObservedProperty.allCases { mpv_observe_property(context, property.rawValue, property.name, property.format) }
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.poll() }
        // The player's volume and mute stay as they were left (app.js playerVolume / playerMuted).
        if let saved = UserDefaults.standard.object(forKey: "player.volume") as? Double { volume = max(0, min(100, saved)); set("volume", String(volume)) }
        muted = UserDefaults.standard.bool(forKey: "player.muted"); set("mute", muted ? "yes" : "no")
        setupAudio()
        if let pendingStream {
            let subtitle = pendingSubtitle
            let translated = translatedSubtitle
            load(pendingStream, resume: resumePosition)
            if let subtitle { self.subtitle(subtitle, translated: translated) }
        }
    }
    func configure(_ settings: AppPreferences) {
        preferences = settings
        let presentation = PlayerPresentation(settings)
        aspect = presentation.aspect; fit = presentation.fit.rawValue
        applyPresentation()
        configureAnime4K()
        if configuredSpeed != settings.speed { configuredSpeed = settings.speed; setSpeed(settings.speed) }
        configure("sub-scale", (settings.subtitleSize / 100).description)
        configure("sub-line-spacing", SubtitleLineSpacing.points(settings.subtitleLineHeight).description)
        configure("sub-delay", settings.subtitleOffset.description)
        configure("sub-font", SubtitleAppearance.font(settings.subtitleFont))
        configure("sub-color", settings.subtitleColor); configure("sub-border-color", settings.outlineColor)
        configure("sub-border-size", settings.outlineWidth.description)
        configure("sub-bold", settings.subtitleBold ? "yes" : "no")
        configure("sub-pos", (100 - settings.subtitlePadding).description)
        configure("sub-margin-x", "36")
        configure("sub-font-size", "36")
        configure("sub-ass", settings.assEffects ? "yes" : "no")
        configure("sub-ass-override", assOverride)
        configure("embeddedfonts", translatedSubtitle ? "no" : "yes")
        configure("sub-shadow-offset", "0")
        configure("sub-scale-with-window", "no")
        configure("sub-fonts-dir", SubtitleFiles.fontDirectory.path)
    }
    private var assOverride: String { SubtitleAppearance.assOverride(extension: pendingSubtitle?.pathExtension, translated: translatedSubtitle, effects: preferences.assEffects, keepStyle: preferences.vttStyle != false) }
    private var configuredCleanup: VideoCleanupSettings?
    private func configureAnime4K() {
        guard handle != nil else { return }
        let mode = Anime4KMode(saved: preferences.anime4k)
        guard configuredAnime4K != mode || configuredCleanup != preferences.videoCleanup else { return }
        configuredAnime4K = mode; configuredCleanup = preferences.videoCleanup; anime4kError = nil
        anime4kReply &+= 1
        do {
            let paths = try VideoCleanup.paths(preferences.videoCleanup) + mode.paths()
            let result = set("glsl-shaders", paths.joined(separator: ":"), replyID: anime4kReply)
            if result < 0 { failAnime4K() }
        } catch { failAnime4K() }
    }
    private func failAnime4K() {
        guard configuredAnime4K != nil, anime4kError == nil else { return }
        anime4kError = "영상 보정을 적용하지 못해 원본 영상으로 돌아왔습니다. 다른 모드를 선택해 다시 시도할 수 있어요."
        // Leave the saved choice intact, but do not retry on every subtitle/settings update.
        set("glsl-shaders", "")
    }
    private func configure(_ name: String, _ value: String) {
        guard configured[name] != value else { return }
        configured[name] = value; set(name, value)
    }
    /// The video opened again after the app was suspended stays paused, as it was left.
    private var startPaused = false
    func load(_ stream: ResolvedStream, resume: Double = 0, paused: Bool = false) {
        finishSpaceHold()
        loadTimeout?.cancel()
        pendingStream = stream; resumePosition = resume; pendingSubtitle = nil; translatedSubtitle = false; startPaused = paused
        completion.reset(); pendingEnd = false; observed[.eof] = 0
        guard handle != nil else { return }
        error = nil; fileLoaded = false
        set("http-header-fields", stream.headers.map { $0.key + ": " + $0.value.replacingOccurrences(of: ",", with: "\\,") }.joined(separator: ","))
        set("referrer", stream.referer)
        // A download is this app's own local HLS. FFmpeg refuses local files not named as media (an AES key's .key, a
        // nameless part's .bin, a CDN's .png/.webp disguise) and, since FFmpeg 8, segments whose extension does not match
        // their format: every segment was dropped and mpv ended with "no audio or video data played". NativeAudio reads
        // the same files with these options. Online streams keep FFmpeg's checks.
        set("demuxer-lavf-o", stream.url.isFileURL ? "allowed_extensions=ALL,allowed_segment_extensions=ALL,extension_picky=0" : "")
        command(["loadfile", stream.url.isFileURL ? stream.url.path : stream.url.absoluteString, "replace"])
        loadTimeout = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 30_000_000_000) } catch { return }
            guard let self, !Task.isCancelled else { return }
            self.error = "영상 재생을 시작하지 못했습니다. 다른 영상 서버를 선택하거나 다시 시도하세요."
        }
    }
    func setVolume(_ value: Double) { volume = max(0, min(100, value)); set("volume", String(volume)); UserDefaults.standard.set(volume, forKey: "player.volume") }
    func toggleMute() { muted.toggle(); set("mute", muted ? "yes" : "no"); UserDefaults.standard.set(muted, forKey: "player.muted") }
    func setFit(_ value: String) {
        preferences.selectPlayerFit(PlayerFit(rawValue: value) ?? .contain)
        let presentation = PlayerPresentation(preferences); aspect = presentation.aspect; fit = presentation.fit.rawValue
        applyPresentation()
    }
    func setAspect(_ value: PlayerAspect) {
        preferences.selectPlayerAspect(value); aspect = value; fit = PlayerPresentation(preferences).fit.rawValue
        applyPresentation()
    }
    var presentation: PlayerPresentation { PlayerPresentation(aspect: aspect, fit: PlayerFit(rawValue: fit) ?? .contain) }
    private func applyPresentation() { for (name, value) in presentation.mpvOptions.sorted(by: { $0.key < $1.key }) { configure(name, value) } }
    func setSpeed(_ value: Double) { speed = value; set("speed", value.description) }
    func beginSpaceHold() {
        guard spaceHold == nil else { return }
        spaceHold = SpaceHoldSession(originalSpeed: speed)
        spaceTimer = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 400_000_000) } catch { return }
            guard let self, self.spaceHold != nil, !self.paused else { return }
            self.boostTouchHold()
        }
    }
    func boostTouchHold() {
        guard !paused, spaceHold?.boosted != true else { return }
        if spaceHold == nil { spaceHold = SpaceHoldSession(originalSpeed: speed) }
        spaceHold?.boosted = true; speedBoosted = true; setSpeed(2)
    }
    func finishSpaceHold(toggle: Bool = false) {
        spaceTimer?.cancel(); spaceTimer = nil
        guard let hold = spaceHold else { return }; spaceHold = nil; speedBoosted = false
        if hold.boosted { setSpeed(hold.restoredSpeed(current: speed)) }
        else if toggle { self.toggle() }
    }
    func play() { paused = false; set("pause", "no") }
    func pause() { finishSpaceHold(); paused = true; set("pause", "yes") }
    func toggle() { paused ? play() : pause() }
    private(set) var seekRevision: UInt64 = 0
    func seek(_ seconds: Double) { seekRevision &+= 1; command(["seek", max(0, seconds).description, "absolute+exact"]) }
    /// Where the viewer moved to themselves, and when (desktop seekPlayer): the OP/ED there is watched, not skipped.
    private(set) var viewerSeek: (time: Double, at: Date)?
    func seekByViewer(_ seconds: Double) {
        guard seconds.isFinite else { return }
        viewerSeek = (max(0, seconds), Date()); seek(seconds)
    }
    func clearViewerSeek() { viewerSeek = nil }
    func skip(_ delta: Double) { seekByViewer(position + delta) }
    func subtitle(_ file: URL, translated: Bool = false) {
        let reload = pendingSubtitle == file
        pendingSubtitle = file; translatedSubtitle = translated
        configure("sub-ass-override", assOverride)
        configure("embeddedfonts", translated ? "no" : "yes")
        if fileLoaded { command(["sub-add", file.path, "select"]); if reload { command(["sub-reload"]) } }
    }
    func reloadSubtitle() { command(["sub-reload"]) }
    func selectTrack(_ track: MediaTrack) { set(track.type == "audio" ? "aid" : "sid", track.id.description) }
    func toggleSubtitleVisibility() { subtitlesVisible.toggle(); set("sub-visibility", subtitlesVisible ? "yes" : "no") }
    func disableSubtitles() { set("sid", "no") }
    /// Asynchronous: mpv copies the value, and the main thread does not wait for the core (which may itself be waiting on
    /// the main thread while the video output starts).
    @discardableResult func set(_ name: String, _ value: String, replyID: UInt64 = 0) -> Int32 {
        guard let handle, let copy = strdup(value) else { return -1 }
        defer { free(copy) }
        var pointer: UnsafeMutablePointer<CChar>? = copy
        return withUnsafeMutablePointer(to: &pointer) { mpv_set_property_async(handle, replyID, name, MPV_FORMAT_STRING, $0) }
    }
    /// mpv calls that wait for the core: off the main thread, one at a time, so a screenshot never overlaps the player
    /// being stopped.
    private static let blocking = DispatchQueue(label: "Lilac.MPV", qos: .userInitiated)
    var canCaptureVideo: Bool { handle != nil && fileLoaded }
    var canSaveProgress: Bool { canCaptureVideo && !restoringVideoOutput }
    /// Keep the video track selected: toggling vid reselects the demux stream on
    /// return and can discard buffered audio/video while synchronizing again.
    /// A null output submits no Metal work and leaves the media timeline intact.
    func setVideoOutputActive(_ active: Bool) {
        guard handle != nil, !pictureInPictureOutput, videoOutputHidden == active else { return }
        videoOutputHidden = !active; restoringVideoOutput = active
        videoOutputReply &+= 1
        let status = set("vo", active ? activeVideoOutput : "null", replyID: videoOutputReply)
        if status < 0 { restoringVideoOutput = false; error = String(cString: mpv_error_string(status)) }
    }
    /// PiP receives software frames with mpv's subtitles already drawn into them. The normal
    /// Metal output is stopped before PiP starts, so background playback never submits GPU work.
    func beginPictureInPictureOutput() async throws {
        guard let handle, fileLoaded else { throw SubtitleFiles.failure("먼저 영상을 재생하세요.") }
        pictureInPictureOutput = true; softwareOutput = true
        let result: Int32 = await withCheckedContinuation { continuation in
            Self.blocking.async {
                mpv_set_property_string(handle, "screenshot-sw", "yes")
                mpv_set_property_string(handle, "hwdec", "videotoolbox-copy")
                continuation.resume(returning: mpv_set_property_string(handle, "vo", "null"))
            }
        }
        guard self.handle == handle, pictureInPictureOutput else { throw CancellationError() }
        if result < 0 { endPictureInPictureOutput(); throw SubtitleFiles.failure(String(cString: mpv_error_string(result))) }
    }
    func pictureInPictureFrame() async throws -> CMSampleBuffer {
        guard let handle, fileLoaded, pictureInPictureOutput else { throw SubtitleFiles.failure("PiP 영상이 준비되지 않았습니다.") }
        let position = position
        let result: Result<CMSampleBuffer, Error> = await withCheckedContinuation { continuation in
            Self.blocking.async { continuation.resume(returning: Result { try PiPFrame.capture(handle, position: position) }) }
        }
        return try result.get()
    }
    func endPictureInPictureOutput() {
        guard pictureInPictureOutput || softwareOutput else { return }
        pictureInPictureOutput = false
        guard handle != nil else { return }
        let foreground = UIApplication.shared.applicationState != .background
        if foreground { softwareOutput = false }
        if !foreground && !preferences.backgroundAudio { pause() }
        if foreground {
            set("hwdec", "videotoolbox")
            videoOutputHidden = true; setVideoOutputActive(true)
        } else {
            videoOutputHidden = true; restoringVideoOutput = false
            set("vo", "null")
        }
    }
    /// 스크린샷 (desktop takeScreenshot): the frame on screen at the video's own size (1920×1080 for a 1080p episode),
    /// without subtitles or controls, as a PNG.
    func screenshot(to file: URL) async throws {
        guard let handle, fileLoaded else { throw SubtitleFiles.failure("영상이 나온 뒤에 찍을 수 있어요") }
        let result: Result<Void, Error> = await withCheckedContinuation { continuation in
            Self.blocking.async {
                continuation.resume(returning: Result {
                    mpv_set_property_string(handle, "screenshot-sw", "yes")
                    let frame = try PiPFrame.capture(handle, position: 0, mode: "video", maximumWidth: 8192)
                    guard let pixel = CMSampleBufferGetImageBuffer(frame) else { throw SubtitleFiles.failure("영상 프레임이 없습니다.") }
                    try PlayerScreenshot.write(pixel, to: file)
                })
            }
        }
        try result.get()
    }
    private func command(_ arguments: [String]) {
        guard let handle else { return }
        let allocated = arguments.map { strdup($0) }
        defer { allocated.forEach { free($0) } }
        var values: [UnsafePointer<CChar>?] = allocated.map { $0.map { UnsafePointer<CChar>($0) } } + [nil]
        let result = mpv_command_async(handle, 0, &values)
        if result < 0 { error = String(cString: mpv_error_string(result)) }
    }
    private func poll() {
        guard let handle else { return }
        var ended = false
        while let event = mpv_wait_event(handle, 0), event.pointee.event_id != MPV_EVENT_NONE {
            switch event.pointee.event_id {
            case MPV_EVENT_SET_PROPERTY_REPLY:
                if event.pointee.reply_userdata == anime4kReply, event.pointee.error < 0 { failAnime4K() }
                if event.pointee.reply_userdata == videoOutputReply {
                    restoringVideoOutput = false
                    if event.pointee.error < 0 { error = String(cString: mpv_error_string(event.pointee.error)) }
                }
            case MPV_EVENT_LOG_MESSAGE:
                if let data = event.pointee.data {
                    let message = data.assumingMemoryBound(to: mpv_event_log_message.self).pointee
                    if let prefix = message.prefix, let text = message.text,
                       Anime4KMode.shaderFailed(prefix: String(cString: prefix), text: String(cString: text)) { failAnime4K() }
                }
            case MPV_EVENT_PROPERTY_CHANGE:
                guard let property = ObservedProperty(rawValue: event.pointee.reply_userdata), let data = event.pointee.data else { break }
                let change = data.assumingMemoryBound(to: mpv_event_property.self).pointee
                guard let value = change.data else { observed[property] = 0; break }
                switch change.format {
                case MPV_FORMAT_DOUBLE: let number = value.assumingMemoryBound(to: Double.self).pointee; observed[property] = number.isFinite ? number : 0
                case MPV_FORMAT_FLAG: observed[property] = value.assumingMemoryBound(to: Int32.self).pointee != 0 ? 1 : 0
                case MPV_FORMAT_NODE: if property == .tracks { updateTracks(value.assumingMemoryBound(to: mpv_node.self).pointee) }
                default: break
                }
            case MPV_EVENT_FILE_LOADED:
                fileLoaded = true
                configure(preferences)
                if resumePosition > 0 { seek(resumePosition); resumePosition = 0 }
                if let pendingSubtitle { command(["sub-add", pendingSubtitle.path, "select"]) }
                if startPaused { startPaused = false; loadTimeout?.cancel(); pause() } else { play() }
            case MPV_EVENT_PLAYBACK_RESTART:
                if fileLoaded { loadTimeout?.cancel(); loadTimeout = nil }
            case MPV_EVENT_END_FILE:
                finishSpaceHold()
                fileLoaded = false
                if let data = event.pointee.data {
                    let end = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                    // Replacing a file reports STOP for the old file; it must
                    // not cancel the new file's startup deadline.
                    if end.reason == MPV_END_FILE_REASON_EOF || end.reason == MPV_END_FILE_REASON_ERROR { loadTimeout?.cancel(); loadTimeout = nil }
                    if end.reason == MPV_END_FILE_REASON_EOF { ended = true; pendingEnd = true }
                    if end.reason == MPV_END_FILE_REASON_ERROR { error = String(cString: mpv_error_string(end.error)) }
                }
            default: break
            }
        }
        // Published only when they change: every assignment redraws the player controls.
        let position = observed[.position] ?? 0, duration = observed[.duration] ?? 0
        if self.position != position { self.position = position }
        if self.duration != duration { self.duration = duration }
        if let pause = observed[.pause], paused != (pause != 0) { paused = pause != 0 }
        if buffering != ((observed[.cache] ?? 0) != 0) { buffering = (observed[.cache] ?? 0) != 0 }
        if let volume = observed[.volume], self.volume != volume { self.volume = volume }
        if let mute = observed[.mute], muted != (mute != 0) { muted = mute != 0 }
        if let speed = observed[.speed], speed > 0, self.speed != speed { self.speed = speed }
        if paused { finishSpaceHold() }
        if fileLoaded && !restoringVideoOutput { onProgress?(position, duration) }
        // keep-open retains the last frame without END_FILE; eof-reached is its completion signal.
        // Notify after progress so history is saved before the next episode replaces this one.
        if !restoringVideoOutput && (ended || pendingEnd || (fileLoaded && observed[.eof] == 1)) {
            pendingEnd = false
            if completion.reachedEnd() { onEnd?() }
        } else if fileLoaded, observed[.eof] == 0 { completion.resume() }
        // The system extrapolates the elapsed time from the rate, so it is told again only when something changes.
        let now = Date()
        if let last = lastNowPlaying, last.paused == paused, last.duration == Int(duration), last.speed == speed, now.timeIntervalSince(last.at) < 5 { return }
        lastNowPlaying = (paused, Int(duration), speed, now)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPMediaItemPropertyPlaybackDuration: duration, MPNowPlayingInfoPropertyPlaybackRate: paused ? 0 : speed]
    }
    private func updateTracks(_ node: mpv_node) {
        guard node.format == MPV_FORMAT_NODE_ARRAY, let list = node.u.list, let values = list.pointee.values else { tracks = []; return }
        var result: [MediaTrack] = []
        for index in 0..<Int(list.pointee.num) {
            let entry = values[index]
            guard entry.format == MPV_FORMAT_NODE_MAP, let map = entry.u.list, let keys = map.pointee.keys, let fields = map.pointee.values else { continue }
            var id = 0; var type = ""; var title = ""; var selected = false
            for field in 0..<Int(map.pointee.num) {
                guard let keyPointer = keys[field] else { continue }
                let key = String(cString: keyPointer)
                let value = fields[field]
                if key == "id" && value.format == MPV_FORMAT_INT64 { id = Int(value.u.int64) }
                if key == "selected" && value.format == MPV_FORMAT_FLAG { selected = value.u.flag != 0 }
                if value.format == MPV_FORMAT_STRING, let string = value.u.string {
                    if key == "type" { type = String(cString: string) }
                    if key == "title" || (title.isEmpty && key == "lang") { title = String(cString: string) }
                }
            }
            if type == "audio" || type == "sub" { result.append(MediaTrack(id: id, type: type, title: title.isEmpty ? type + " \(id)" : title, selected: selected)) }
        }
        tracks = result
    }
    private func updateScreenAwake() { screenAwake.update(playing: fileLoaded && !paused) }
    private func setupAudio() {
        // .playback already plays over AirPlay; .allowAirPlay is only for playAndRecord, and iOS refused the whole category
        // with it (OSStatus -50), which showed over the player as a playback error. A refused audio session is noted, not
        // shown: the video still plays.
        do { try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback) } catch { audioSessionFailed(error) }
        do { try AVAudioSession.sharedInstance().setActive(true) } catch { audioSessionFailed(error) }
        let center = MPRemoteCommandCenter.shared()
        remoteTargets = [
            (center.playCommand, center.playCommand.addTarget { [weak self] _ in DispatchQueue.main.async { self?.play() }; return .success }),
            (center.pauseCommand, center.pauseCommand.addTarget { [weak self] _ in DispatchQueue.main.async { self?.pause() }; return .success }),
            (center.changePlaybackPositionCommand, center.changePlaybackPositionCommand.addTarget { [weak self] event in
                guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
                DispatchQueue.main.async { self?.seekByViewer(event.positionTime) }; return .success
            })
        ]
        notifications.append(NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            if !self.pictureInPictureOutput {
                self.setVideoOutputActive(false); if !self.preferences.backgroundAudio { self.pause() }
            }
        })
        notifications.append(NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.finishSpaceHold(); self?.screenAwake.release(); self?.setVideoOutputActive(false)
        })
        notifications.append(NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.updateScreenAwake()
            do { try AVAudioSession.sharedInstance().setActive(true) } catch { self.audioSessionFailed(error) }
            if !self.pictureInPictureOutput && self.softwareOutput { self.endPictureInPictureOutput() }
            else { self.setVideoOutputActive(true) }
        })
        notifications.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            if (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue { self?.pause() }
        })
    }
    private func audioSessionFailed(_ error: Error) {
        NSLog("LilacAnime audio session: %@", error.localizedDescription)
    }
    func shutdown() {
        loadTimeout?.cancel(); loadTimeout = nil
        pictureInPictureOutput = false; softwareOutput = false
        finishSpaceHold()
        timer?.invalidate(); timer = nil
        notifications.forEach(NotificationCenter.default.removeObserver); notifications = []
        remoteTargets.forEach { $0.0.removeTarget($0.1) }; remoteTargets = []
        if let handle {
            // Waits for mpv to stop, which a stalled stream can make slow: not on the main thread. mpv draws into the
            // layer until then, so the layer is kept until it has stopped.
            let layer = mediaLayer
            Self.blocking.async {
                mpv_terminate_destroy(handle)
                DispatchQueue.main.async { withExtendedLifetime(layer) {} }
            }
            self.handle = nil
        }
        mediaLayer = nil; pendingStream = nil; pendingSubtitle = nil; fileLoaded = false; position = 0; duration = 0
        observed = [:]; configured = [:]; configuredSpeed = nil; lastNowPlaying = nil
        configuredAnime4K = nil; anime4kError = nil
        videoOutputHidden = false; restoringVideoOutput = false; pendingEnd = false
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}
final class LilacMetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            guard newValue.width.isFinite, newValue.height.isFinite,
                  newValue.width >= 2, newValue.height >= 2, newValue != super.drawableSize else { return }
            super.drawableSize = newValue
        }
    }
}
final class MPVSurface: UIView {
    // UIKit owns the backing layer; mpv owns the drawable of this separate child layer.
    // Do not start mpv with the zero-sized frame UIViewRepresentable initially supplies.
    let metal = LilacMetalLayer()
    weak var engine: MPVEngine?
    override init(frame: CGRect) {
        super.init(frame: frame)
        metal.backgroundColor = UIColor.black.cgColor
        metal.framebufferOnly = true
        layer.addSublayer(metal)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func didMoveToWindow() { super.didMoveToWindow(); setNeedsLayout() }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let window, bounds.width > 1, bounds.height > 1 else { return }
        let scale = window.screen.scale
        let pixels = CGSize(width: (bounds.width * scale).rounded(), height: (bounds.height * scale).rounded())
        // Controls/progress can cause layout even while paused. An implicit layer resize
        // would animate the last frame separately from mpv's video/subtitle viewport.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if metal.frame != bounds { metal.frame = bounds }
        if metal.contentsScale != scale { metal.contentsScale = scale }
        if metal.drawableSize != pixels { metal.drawableSize = pixels }
        CATransaction.commit()
        engine?.attach(metal)
    }
}
struct MPVPlayerView: UIViewRepresentable {
    let engine: MPVEngine
    func makeUIView(context: Context) -> MPVSurface {
        let view = MPVSurface(frame: .zero); view.backgroundColor = .black; view.engine = engine
        return view
    }
    func updateUIView(_ view: MPVSurface, context: Context) { view.setNeedsLayout() }
}
