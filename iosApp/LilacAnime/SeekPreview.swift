import SwiftUI
import UIKit

/// 재생 막대 미리보기 (desktop player.js seek bar preview, v0.5.56): while the seek bar is dragged, the picture at the place
/// under the finger, above it with its time. The episode is read by its own FFmpeg reader (NativeAudio's
/// LilacFrameReader) from the address the player reads; the proxy serves it behind the player and fetches nothing ahead
/// of it. Each picture is kept by its 5-second slot, so going back over the bar shows it at once. A new source starts over.
@MainActor
final class SeekPreview: ObservableObject {
    static let slotSeconds = 5.0
    /// About 20 MB of pictures at most (320×180).
    static let keptLimit = 90
    @Published private(set) var image: UIImage?
    private var frames: [Int: UIImage] = [:]
    private var kept: [Int] = []
    private var source: String?
    private var reader: SeekFrameReader?
    private var wanted: Int?
    private var pending: Task<Void, Never>?

    nonisolated static func slot(_ time: Double) -> Int { Int((max(0, time) / slotSeconds).rounded()) }
    /// Where a slot's picture is read from: its start, kept half a second inside the episode's end.
    nonisolated static func readTime(slot: Int, duration: Double) -> Double {
        min(Double(slot) * slotSeconds, max(0, duration - 0.5))
    }

    func show(_ time: Double, duration: Double, video: SubtitleAutoSync.Video?) {
        guard let video, duration > 0 else { pending?.cancel(); image = nil; return }
        let key = video.url.absoluteString
        // The episode is opened (its playlist and stream read) as soon as the bar is touched, while the finger has yet to
        // rest: the first picture then needs only its seek (desktop c2aad8d).
        if key != source { reset(); source = key; reader = SeekFrameReader(video); reader?.prepare() }
        let slot = Self.slot(time)
        guard slot != wanted, let reader else { return }
        wanted = slot
        pending?.cancel()
        if let frame = frames[slot] { image = frame; return }
        // Asked for only once the finger rests a moment, so a sweep across the bar loads nothing on the way.
        let at = Self.readTime(slot: slot, duration: duration)
        pending = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 120_000_000)
            guard !Task.isCancelled, let picture = await reader.frame(at: at) else { return }
            guard let self else { return }
            let frame = UIImage(cgImage: picture)
            self.keep(frame, slot: slot)
            if self.wanted == slot { self.image = frame }
        }
    }
    func hide() { pending?.cancel(); pending = nil; wanted = nil }
    func reset() {
        hide(); frames = [:]; kept = []; image = nil; source = nil
        reader?.close(); reader = nil
    }
    private func keep(_ frame: UIImage, slot: Int) {
        if frames[slot] == nil { kept.append(slot) }
        frames[slot] = frame
        while kept.count > Self.keptLimit { frames[kept.removeFirst()] = nil }
    }
}

/// One episode's FFmpeg reader, used on its own queue one picture at a time; a picture no longer wanted stops reading.
final class SeekFrameReader: @unchecked Sendable {
    private final class Flag { var cancelled = false }
    private let queue = DispatchQueue(label: "lilac.seek-preview", qos: .userInitiated)
    private let video: SubtitleAutoSync.Video
    private var reader: OpaquePointer?
    private var failed = false
    init(_ video: SubtitleAutoSync.Video) { self.video = video }
    deinit { if let reader { LilacFrameReaderClose(reader) } }

    func frame(at seconds: Double, width: Int32 = 320, height: Int32 = 180) async -> CGImage? {
        let flag = Flag()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                queue.async { continuation.resume(returning: self.read(seconds, width: width, height: height, flag: flag)) }
            }
        } onCancel: { flag.cancelled = true }
    }
    func close() { queue.async { if let reader = self.reader { LilacFrameReaderClose(reader) }; self.reader = nil; self.failed = true } }
    /// Opens the episode ahead of the first picture asked for.
    func prepare() { queue.async { _ = self.open() } }

    private func open() -> Bool {
        if reader != nil { return true }
        guard !failed else { return false }
        let source = video.url.isFileURL ? video.url.path : video.url.absoluteString
        let headers = video.headers.filter { !$0.value.isEmpty }.map { $0.key + ": " + $0.value + "\r\n" }.joined()
        var message: UnsafeMutablePointer<CChar>?
        reader = LilacFrameReaderOpen(source, headers, &message)
        if let message { LilacAudioFreeError(message) }
        // A stream it cannot open is not tried again for every slot.
        if reader == nil { failed = true }
        return reader != nil
    }
    private func read(_ seconds: Double, width: Int32, height: Int32, flag: Flag) -> CGImage? {
        guard !flag.cancelled, !failed, open() else { return nil }
        var rgba: UnsafeMutablePointer<UInt8>?, outWidth: Int32 = 0, outHeight: Int32 = 0
        let opaque = Unmanaged.passUnretained(flag).toOpaque()
        let status = withExtendedLifetime(flag) {
            LilacFrameReaderRead(reader, seconds, width, height, &rgba, &outWidth, &outHeight,
                                 { opaque in Unmanaged<Flag>.fromOpaque(opaque!).takeUnretainedValue().cancelled }, opaque)
        }
        guard status == 0, let rgba else { return nil }
        let data = Data(bytes: rgba, count: Int(outWidth) * Int(outHeight) * 4)
        LilacFrameFree(rgba)
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        return CGImage(width: Int(outWidth), height: Int(outHeight), bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: Int(outWidth) * 4,
                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}

/// The picture and its time over the seek bar (player.js .seek-preview).
struct SeekPreviewBubble: View {
    @ObservedObject var preview: SeekPreview
    let time: String
    var body: some View {
        VStack(spacing: 4) {
            ZStack {
                Color.black
                if let image = preview.image { Image(uiImage: image).resizable().aspectRatio(contentMode: .fit) }
            }.frame(width: 176, height: 99).clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(.white.opacity(0.25)))
            Text(time).font(.system(size: 12, weight: .semibold)).monospacedDigit().foregroundStyle(.white)
                .padding(.horizontal, 8).padding(.vertical, 3).background(.black.opacity(0.7), in: Capsule())
        }.allowsHitTesting(false).accessibilityHidden(true)
    }
}
