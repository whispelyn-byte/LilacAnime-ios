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
}
@MainActor
final class MPVEngine: ObservableObject {
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
    var onEnd: (() -> Void)?
    var onProgress: ((Double, Double) -> Void)?
    private var handle: OpaquePointer?
    private var timer: Timer?
    private var pendingStream: ResolvedStream?
    private var resumePosition = 0.0
    private var fileLoaded = false { didSet { updateScreenAwake() } }
    private let screenAwake = PlaybackScreenAwake()
    private var pendingSubtitle: URL?
    private(set) var translatedSubtitle = false
    private(set) var pictureInPictureOutput = false
    private var softwareOutput = false
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
        case position = 1, duration, pause, cache, volume, mute, speed, tracks
        var name: String { ["time-pos", "duration", "pause", "paused-for-cache", "volume", "mute", "speed", "track-list"][Int(rawValue) - 1] }
        var format: mpv_format {
            switch self {
            case .pause, .cache, .mute: return MPV_FORMAT_FLAG
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
        for (key, value) in ["vo": "gpu-next", "gpu-api": "vulkan", "gpu-context": "moltenvk",
                             "hwdec": "videotoolbox", "keep-open": "yes", "idle": "yes", "cache": "yes", "input-default-bindings": "no"] {
            mpv_set_option_string(context, key, value)
        }
        let result = mpv_initialize(context)
        guard result >= 0 else { error = String(cString: mpv_error_string(result)); mpv_terminate_destroy(context); handle = nil; return }
        configured = [:]; configuredSpeed = nil; observed = [:]
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
        if configuredSpeed != settings.speed { configuredSpeed = settings.speed; setSpeed(settings.speed) }
        configure("sub-scale", (settings.subtitleSize / 100).description)
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
    private func configure(_ name: String, _ value: String) {
        guard configured[name] != value else { return }
        configured[name] = value; set(name, value)
    }
    /// The video opened again after the app was suspended stays paused, as it was left.
    private var startPaused = false
    func load(_ stream: ResolvedStream, resume: Double = 0, paused: Bool = false) {
        finishSpaceHold()
        pendingStream = stream; resumePosition = resume; pendingSubtitle = nil; translatedSubtitle = false; startPaused = paused
        guard handle != nil else { return }
        error = nil; fileLoaded = false
        set("http-header-fields", stream.headers.map { $0.key + ": " + $0.value.replacingOccurrences(of: ",", with: "\\,") }.joined(separator: ","))
        set("referrer", stream.referer)
        command(["loadfile", stream.url.isFileURL ? stream.url.path : stream.url.absoluteString, "replace"])
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
    func skip(_ delta: Double) { seek(position + delta) }
    func subtitle(_ file: URL, translated: Bool = false) {
        pendingSubtitle = file; translatedSubtitle = translated
        configure("sub-ass-override", assOverride)
        configure("embeddedfonts", translated ? "no" : "yes")
        if fileLoaded { command(["sub-add", file.path, "select"]) }
    }
    func reloadSubtitle() { command(["sub-reload"]) }
    func selectTrack(_ track: MediaTrack) { set(track.type == "audio" ? "aid" : "sid", track.id.description) }
    func toggleSubtitleVisibility() { subtitlesVisible.toggle(); set("sub-visibility", subtitlesVisible ? "yes" : "no") }
    func disableSubtitles() { set("sid", "no") }
    /// Asynchronous: mpv copies the value, and the main thread does not wait for the core (which may itself be waiting on
    /// the main thread while the video output starts).
    func set(_ name: String, _ value: String) {
        guard let handle, let copy = strdup(value) else { return }
        defer { free(copy) }
        var pointer: UnsafeMutablePointer<CChar>? = copy
        _ = withUnsafeMutablePointer(to: &pointer) { mpv_set_property_async(handle, 0, name, MPV_FORMAT_STRING, $0) }
    }
    /// mpv calls that wait for the core: off the main thread, one at a time, so a screenshot never overlaps the player
    /// being stopped.
    private static let blocking = DispatchQueue(label: "Lilac.MPV", qos: .userInitiated)
    var canCaptureVideo: Bool { handle != nil && fileLoaded }
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
        guard let handle else { return }
        let foreground = UIApplication.shared.applicationState != .background
        if foreground { softwareOutput = false }
        if !foreground && !preferences.backgroundAudio { pause() }
        Self.blocking.async {
            if foreground {
                mpv_set_property_string(handle, "vo", "gpu-next")
                mpv_set_property_string(handle, "hwdec", "videotoolbox")
                mpv_set_property_string(handle, "vid", "auto")
            } else {
                // Keep the software output until the app is foreground again.
                mpv_set_property_string(handle, "vid", "no")
            }
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
        while let event = mpv_wait_event(handle, 0), event.pointee.event_id != MPV_EVENT_NONE {
            switch event.pointee.event_id {
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
                if startPaused { startPaused = false; pause() } else { play() }
            case MPV_EVENT_END_FILE:
                finishSpaceHold()
                fileLoaded = false
                if let data = event.pointee.data {
                    let end = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                    if end.reason == MPV_END_FILE_REASON_EOF { onEnd?() }
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
        if fileLoaded { onProgress?(position, duration) }
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
            var id = 0; var type = ""; var title = ""
            for field in 0..<Int(map.pointee.num) {
                guard let keyPointer = keys[field] else { continue }
                let key = String(cString: keyPointer)
                let value = fields[field]
                if key == "id" && value.format == MPV_FORMAT_INT64 { id = Int(value.u.int64) }
                if value.format == MPV_FORMAT_STRING, let string = value.u.string {
                    if key == "type" { type = String(cString: string) }
                    if key == "title" || (title.isEmpty && key == "lang") { title = String(cString: string) }
                }
            }
            if type == "audio" || type == "sub" { result.append(MediaTrack(id: id, type: type, title: title.isEmpty ? type + " \(id)" : title)) }
        }
        tracks = result
    }
    private func updateScreenAwake() { screenAwake.update(playing: fileLoaded && !paused) }
    private func setupAudio() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [.allowAirPlay])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch { self.error = error.localizedDescription }
        let center = MPRemoteCommandCenter.shared()
        remoteTargets = [
            (center.playCommand, center.playCommand.addTarget { [weak self] _ in DispatchQueue.main.async { self?.play() }; return .success }),
            (center.pauseCommand, center.pauseCommand.addTarget { [weak self] _ in DispatchQueue.main.async { self?.pause() }; return .success }),
            (center.changePlaybackPositionCommand, center.changePlaybackPositionCommand.addTarget { [weak self] event in
                guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
                DispatchQueue.main.async { self?.seek(event.positionTime) }; return .success
            })
        ]
        notifications.append(NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            if !self.pictureInPictureOutput {
                self.set("vid", "no"); if !self.preferences.backgroundAudio { self.pause() }
            }
        })
        notifications.append(NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.finishSpaceHold(); self?.screenAwake.release()
        })
        notifications.append(NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.updateScreenAwake() })
        notifications.append(NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, !self.pictureInPictureOutput else { return }
            if self.softwareOutput { self.endPictureInPictureOutput() }
            else { self.set("vid", "auto") }
        })
        notifications.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            if (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue { self?.pause() }
        })
    }
    func shutdown() {
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
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}
final class LilacMetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set { if newValue.width > 1 && newValue.height > 1 { super.drawableSize = newValue } }
    }
}
final class MPVSurface: UIView {
    override class var layerClass: AnyClass { LilacMetalLayer.self }
    weak var engine: MPVEngine?
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let metal = layer as? CAMetalLayer else { return }
        metal.contentsScale = window?.screen.scale ?? UIScreen.main.scale
        metal.drawableSize = CGSize(width: bounds.width * metal.contentsScale, height: bounds.height * metal.contentsScale)
    }
}
struct MPVPlayerView: UIViewRepresentable {
    let engine: MPVEngine
    func makeUIView(context: Context) -> MPVSurface {
        let view = MPVSurface(); view.backgroundColor = .black; view.engine = engine
        engine.attach(view.layer as! CAMetalLayer)
        return view
    }
    func updateUIView(_ view: MPVSurface, context: Context) { engine.attach(view.layer as! CAMetalLayer) }
}
