import UIKit
import SwiftUI
import AVFoundation
import MediaPlayer
import Libmpv

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
    @Published var paused = true
    @Published var buffering = false
    @Published var tracks: [MediaTrack] = []
    @Published var error: String?
    var onEnd: (() -> Void)?
    var onProgress: ((Double, Double) -> Void)?
    private var handle: OpaquePointer?
    private var timer: Timer?
    private var pendingStream: ResolvedStream?
    private var resumePosition = 0.0
    private var fileLoaded = false
    private var pendingSubtitle: URL?
    private var mediaLayer: CAMetalLayer?
    private var preferences = AppPreferences()
    private var notifications: [NSObjectProtocol] = []
    private var remoteTargets: [(MPRemoteCommand, Any)] = []

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
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.poll() }
        setupAudio()
        if let pendingStream {
            let subtitle = pendingSubtitle
            load(pendingStream, resume: resumePosition)
            if let subtitle { self.subtitle(subtitle) }
        }
    }
    func configure(_ settings: AppPreferences) {
        preferences = settings
        aspect = PlayerAspect(rawValue: settings.playerAspect ?? "original") ?? .original
        setFit(settings.playerFit ?? "contain")
        setSpeed(settings.speed)
        set("sub-scale", (settings.subtitleSize / 100).description)
        set("sub-delay", settings.subtitleOffset.description)
        set("sub-font", settings.subtitleFont.isEmpty ? "sans-serif" : settings.subtitleFont)
        set("sub-color", settings.subtitleColor); set("sub-border-color", settings.outlineColor)
        set("sub-border-size", settings.outlineWidth.description)
        set("sub-bold", settings.subtitleBold ? "yes" : "no")
        set("sub-pos", (100 - settings.subtitlePadding).description)
        set("sub-margin-x", "36")
        set("sub-font-size", "36")
        set("sub-ass", settings.assEffects ? "yes" : "no")
        set("sub-fonts-dir", SubtitleFiles.fontDirectory.path)
    }
    func load(_ stream: ResolvedStream, resume: Double = 0) {
        finishSpaceHold()
        pendingStream = stream; resumePosition = resume; pendingSubtitle = nil
        guard handle != nil else { return }
        error = nil; fileLoaded = false
        set("http-header-fields", stream.headers.map { $0.key + ": " + $0.value.replacingOccurrences(of: ",", with: "\\,") }.joined(separator: ","))
        set("referrer", stream.referer)
        command(["loadfile", stream.url.isFileURL ? stream.url.path : stream.url.absoluteString, "replace"])
    }
    func setVolume(_ value: Double) { volume = max(0, min(100, value)); set("volume", String(volume)) }
    func toggleMute() { muted.toggle(); set("mute", muted ? "yes" : "no") }
    func setFit(_ value: String) {
        fit = value; set("keepaspect", aspect != .original || value == "stretch" ? "no" : "yes"); set("panscan", aspect == .original && value == "cover" ? "1" : "0")
    }
    func setAspect(_ value: PlayerAspect) { aspect = value; setFit(fit) }
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
    func seek(_ seconds: Double) { command(["seek", max(0, seconds).description, "absolute+exact"]) }
    func skip(_ delta: Double) { seek(position + delta) }
    func subtitle(_ file: URL) { pendingSubtitle = file; if fileLoaded { command(["sub-add", file.path, "select"]) } }
    func reloadSubtitle() { command(["sub-reload"]) }
    func selectTrack(_ track: MediaTrack) { set(track.type == "audio" ? "aid" : "sid", track.id.description) }
    func toggleSubtitleVisibility() { subtitlesVisible.toggle(); set("sub-visibility", subtitlesVisible ? "yes" : "no") }
    func disableSubtitles() { set("sid", "no") }
    func set(_ name: String, _ value: String) { if let handle { mpv_set_property_string(handle, name, value) } }
    private func command(_ arguments: [String]) {
        guard let handle else { return }
        let allocated = arguments.map { strdup($0) }
        defer { allocated.forEach { free($0) } }
        var values: [UnsafePointer<CChar>?] = allocated.map { $0.map { UnsafePointer<CChar>($0) } } + [nil]
        let result = mpv_command_async(handle, 0, &values)
        if result < 0 { error = String(cString: mpv_error_string(result)) }
    }
    private func double(_ property: String) -> Double {
        guard let handle else { return 0 }
        var value = 0.0
        return mpv_get_property(handle, property, MPV_FORMAT_DOUBLE, &value) >= 0 && value.isFinite ? value : 0
    }
    private func flag(_ property: String) -> Bool {
        guard let handle else { return false }
        var value: Int32 = 0
        mpv_get_property(handle, property, MPV_FORMAT_FLAG, &value)
        return value != 0
    }
    private func poll() {
        guard let handle else { return }
        while let event = mpv_wait_event(handle, 0), event.pointee.event_id != MPV_EVENT_NONE {
            switch event.pointee.event_id {
            case MPV_EVENT_FILE_LOADED:
                fileLoaded = true
                configure(preferences)
                if resumePosition > 0 { seek(resumePosition); resumePosition = 0 }
                if let pendingSubtitle { command(["sub-add", pendingSubtitle.path, "select"]) }
                updateTracks(); play()
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
        position = double("time-pos"); duration = double("duration")
        paused = flag("pause"); buffering = flag("paused-for-cache"); volume = double("volume"); muted = flag("mute")
        speed = double("speed"); if paused { finishSpaceHold() }
        if fileLoaded { onProgress?(position, duration) }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPMediaItemPropertyPlaybackDuration: duration, MPNowPlayingInfoPropertyPlaybackRate: paused ? 0 : double("speed")]
    }
    private func updateTracks() {
        guard let handle else { return }
        var node = mpv_node()
        guard mpv_get_property(handle, "track-list", MPV_FORMAT_NODE, &node) >= 0 else { return }
        defer { mpv_free_node_contents(&node) }
        guard node.format == MPV_FORMAT_NODE_ARRAY, let list = node.u.list, let values = list.pointee.values else { return }
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
            self.set("vid", "no"); if !self.preferences.backgroundAudio { self.pause() }
        })
        notifications.append(NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.finishSpaceHold() })
        notifications.append(NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in self?.set("vid", "auto") })
        notifications.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            if (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue { self?.pause() }
        })
    }
    func shutdown() {
        finishSpaceHold()
        timer?.invalidate(); timer = nil
        notifications.forEach(NotificationCenter.default.removeObserver); notifications = []
        remoteTargets.forEach { $0.0.removeTarget($0.1) }; remoteTargets = []
        if let handle { mpv_terminate_destroy(handle); self.handle = nil }
        mediaLayer = nil; pendingStream = nil; pendingSubtitle = nil; fileLoaded = false; position = 0; duration = 0
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
