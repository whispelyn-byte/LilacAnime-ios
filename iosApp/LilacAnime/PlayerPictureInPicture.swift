import SwiftUI
import AVKit
import Accelerate
import Libmpv

/// Like the desktop canvas PiP: video and the currently selected subtitles form one frame.
/// Capture, conversion and scaling run on MPVEngine's serial worker, never the UI thread.
enum PiPFrame {
    static func capture(_ handle: OpaquePointer, position: Double) throws -> CMSampleBuffer {
        let arguments = ["screenshot-raw", "subtitles", "bgra"].map { strdup($0) }
        defer { arguments.forEach { free($0) } }
        var values: [UnsafePointer<CChar>?] = arguments.map { $0.map { UnsafePointer<CChar>($0) } } + [nil]
        var result = mpv_node()
        let status = mpv_command_ret(handle, &values, &result)
        defer { mpv_free_node_contents(&result) }
        guard status >= 0, result.format == MPV_FORMAT_NODE_MAP,
              let map = result.u.list, let keys = map.pointee.keys, let fields = map.pointee.values else {
            throw SubtitleFiles.failure("PiP 영상 프레임을 읽지 못했습니다.")
        }
        var width = 0, height = 0, stride = 0
        var bytes: mpv_byte_array?
        for index in 0..<Int(map.pointee.num) {
            guard let key = keys[index] else { continue }
            let name = String(cString: key), field = fields[index]
            if field.format == MPV_FORMAT_INT64 {
                if name == "w" { width = Int(field.u.int64) }
                if name == "h" { height = Int(field.u.int64) }
                if name == "stride" { stride = Int(field.u.int64) }
            }
            if name == "data", field.format == MPV_FORMAT_BYTE_ARRAY { bytes = field.u.ba?.pointee }
        }
        guard let bytes, let data = bytes.data else { throw SubtitleFiles.failure("PiP 영상 데이터가 없습니다.") }
        // The UI observes time at 4 Hz; frame timestamps need the current core time for smooth PiP.
        var current = position
        if mpv_get_property(handle, "time-pos", MPV_FORMAT_DOUBLE, &current) < 0 || !current.isFinite { current = position }
        return try make(width: width, height: height, stride: stride, bytes: data, count: bytes.size, position: current)
    }
    static func make(width: Int, height: Int, stride: Int, bytes: UnsafeMutableRawPointer, count: Int, position: Double) throws -> CMSampleBuffer {
        guard width > 0, width <= 8192, height > 0, height <= 8192,
              stride >= width * 4, stride <= 65536, count >= stride * height else {
            throw SubtitleFiles.failure("PiP 영상 크기가 올바르지 않습니다.")
        }
        let outputWidth = min(width, 960), outputHeight = max(1, Int((Double(height) * Double(outputWidth) / Double(width)).rounded()))
        var pixel: CVPixelBuffer?
        let attributes: [CFString: Any] = [kCVPixelBufferIOSurfacePropertiesKey: [:]]
        guard CVPixelBufferCreate(kCFAllocatorDefault, outputWidth, outputHeight, kCVPixelFormatType_32BGRA, attributes as CFDictionary, &pixel) == kCVReturnSuccess,
              let pixel else { throw SubtitleFiles.failure("PiP 영상 버퍼를 만들지 못했습니다.") }
        CVPixelBufferLockBaseAddress(pixel, [])
        defer { CVPixelBufferUnlockBaseAddress(pixel, []) }
        guard let destination = CVPixelBufferGetBaseAddress(pixel) else { throw SubtitleFiles.failure("PiP 영상 버퍼를 열지 못했습니다.") }
        var input = vImage_Buffer(data: bytes, height: vImagePixelCount(height), width: vImagePixelCount(width), rowBytes: stride)
        var output = vImage_Buffer(data: destination, height: vImagePixelCount(outputHeight), width: vImagePixelCount(outputWidth), rowBytes: CVPixelBufferGetBytesPerRow(pixel))
        guard vImageScale_ARGB8888(&input, &output, nil, vImage_Flags(kvImageNoFlags)) == kvImageNoError else {
            throw SubtitleFiles.failure("PiP 영상 크기를 조절하지 못했습니다.")
        }
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pixel, formatDescriptionOut: &format) == noErr, let format else {
            throw SubtitleFiles.failure("PiP 영상 형식을 만들지 못했습니다.")
        }
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 24), presentationTimeStamp: CMTime(seconds: position.isFinite ? max(0, position) : 0, preferredTimescale: 600), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pixel, formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sample) == noErr,
              let sample else { throw SubtitleFiles.failure("PiP 영상 샘플을 만들지 못했습니다.") }
        return sample
    }
}

@MainActor
final class PlayerPictureInPicture: NSObject, ObservableObject, AVPictureInPictureControllerDelegate, AVPictureInPictureSampleBufferPlaybackDelegate {
    @Published private(set) var active = false
    @Published private(set) var starting = false
    @Published private(set) var rendering = false
    let layer = AVSampleBufferDisplayLayer()
    private weak var engine: MPVEngine?
    private var controller: AVPictureInPictureController?
    private var frames: Task<Void, Never>?
    private var failure: String?
    private var revision = UUID()
    private var timebase: CMTimebase?
    private var pausedState: Bool?
    var supported: Bool { AVPictureInPictureController.isPictureInPictureSupported() }
    init(engine: MPVEngine) {
        self.engine = engine
        super.init()
        layer.videoGravity = .resizeAspect
        CMTimebaseCreateWithSourceClock(allocator: kCFAllocatorDefault, sourceClock: CMClockGetHostTimeClock(), timebaseOut: &timebase)
        layer.controlTimebase = timebase
    }
    func attach() {
        guard supported, controller == nil else { return }
        let source = AVPictureInPictureController.ContentSource(sampleBufferDisplayLayer: layer, playbackDelegate: self)
        let controller = AVPictureInPictureController(contentSource: source)
        controller.delegate = self
        controller.canStartPictureInPictureAutomaticallyFromInline = false
        self.controller = controller
    }
    func toggle() async -> String? {
        if active { controller?.stopPictureInPicture(); return nil }
        guard !starting else { return nil }
        guard supported else { return "이 기기에서는 PiP를 지원하지 않습니다." }
        guard let engine, engine.canCaptureVideo else { return "먼저 영상을 재생하세요." }
        attach()
        guard let controller else { return "PiP를 준비하지 못했습니다." }
        starting = true; failure = nil; revision = UUID()
        let token = revision
        do {
            try await engine.beginPictureInPictureOutput()
            guard token == revision, !Task.isCancelled else { throw CancellationError() }
            rendering = true
            pump(token)
            // A sample must reach the attached display layer before iOS allows PiP to start.
            for _ in 0..<50 {
                if controller.isPictureInPicturePossible || failure != nil { break }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            guard token == revision, failure == nil, controller.isPictureInPicturePossible else {
                throw SubtitleFiles.failure(failure ?? "PiP를 시작하지 못했습니다. 영상을 재생한 뒤 다시 시도하세요.")
            }
            controller.startPictureInPicture()
            for _ in 0..<50 {
                if active || failure != nil { break }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            guard token == revision, active else { throw SubtitleFiles.failure(failure ?? "PiP를 시작하지 못했습니다.") }
            starting = false
            return nil
        } catch {
            if token == revision { cleanup() }
            return error is CancellationError ? nil : error.localizedDescription
        }
    }
    private func pump(_ token: UUID) {
        frames?.cancel()
        frames = Task { [weak self] in
            var failures = 0
            while let self, !Task.isCancelled, token == self.revision, let engine = self.engine {
                do {
                    if self.layer.status == .failed { self.layer.flush() }
                    if engine.canCaptureVideo && self.layer.isReadyForMoreMediaData {
                        let sample = try await engine.pictureInPictureFrame()
                        guard !Task.isCancelled, token == self.revision else { return }
                        self.syncTimebase(CMSampleBufferGetPresentationTimeStamp(sample).seconds)
                        self.layer.enqueue(sample)
                        failures = 0
                    }
                    if self.pausedState != engine.paused {
                        self.pausedState = engine.paused; self.controller?.invalidatePlaybackState()
                    }
                } catch {
                    failures += 1
                    if failures >= 24 {
                        self.failure = error.localizedDescription
                        self.controller?.stopPictureInPicture()
                        self.cleanup(); return
                    }
                }
                do { try await Task.sleep(nanoseconds: 41_666_667) } catch { return }
            }
        }
    }
    private func syncTimebase(_ position: Double) {
        guard let timebase, position.isFinite else { return }
        CMTimebaseSetTime(timebase, time: CMTime(seconds: max(0, position), preferredTimescale: 600))
        CMTimebaseSetRate(timebase, rate: engine?.paused == true ? 0 : (engine?.speed ?? 1))
    }
    func stop() { controller?.stopPictureInPicture(); cleanup() }
    private func cleanup() {
        revision = UUID(); frames?.cancel(); frames = nil
        active = false; starting = false; rendering = false; pausedState = nil
        layer.flushAndRemoveImage()
        engine?.endPictureInPictureOutput()
    }
    func pictureInPictureControllerDidStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) { active = true }
    func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) { cleanup() }
    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) { failure = error.localizedDescription }
    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) { completionHandler(true) }
    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, setPlaying playing: Bool) { playing ? engine?.play() : engine?.pause(); syncTimebase(engine?.position ?? 0) }
    func pictureInPictureControllerTimeRangeForPlayback(_ pictureInPictureController: AVPictureInPictureController) -> CMTimeRange {
        guard let duration = engine?.duration, duration.isFinite, duration > 0 else { return .invalid }
        return CMTimeRange(start: .zero, duration: CMTime(seconds: duration, preferredTimescale: 600))
    }
    func pictureInPictureControllerIsPlaybackPaused(_ pictureInPictureController: AVPictureInPictureController) -> Bool { engine?.paused ?? true }
    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}
    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, skipByInterval skipInterval: CMTime, completion completionHandler: @escaping @Sendable () -> Void) {
        guard let engine else { completionHandler(); return }
        let target = max(0, min(engine.duration, engine.position + skipInterval.seconds))
        engine.seek(target); layer.flush()
        Task {
            // Wait for mpv's observed position, then update the PiP timebase before completing the seek.
            for _ in 0..<20 {
                if abs(engine.position - target) < 0.5 { break }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            syncTimebase(engine.position); completionHandler()
        }
    }
}

struct PlayerPiPSurface: UIViewRepresentable {
    let pip: PlayerPictureInPicture
    func makeUIView(context: Context) -> PiPSurface {
        let view = PiPSurface(); view.videoLayer = pip.layer; view.layer.addSublayer(pip.layer); pip.attach(); return view
    }
    func updateUIView(_ view: PiPSurface, context: Context) {}
}
final class PiPSurface: UIView {
    var videoLayer: AVSampleBufferDisplayLayer?
    override func layoutSubviews() { super.layoutSubviews(); videoLayer?.frame = bounds }
}
