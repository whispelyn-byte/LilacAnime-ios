import Foundation
import LilacShared

/// 자막 싱크 맞추기 (desktop subtitle-sync.cjs, 0.5.29–0.5.36): the subtitle on screen matched to the episode — by the
/// site's own dialogue track when it has one (seconds, nothing downloaded), else by the speech in its audio — and a
/// retimed copy written beside it ("… · 자동 싱크"). The desktop's ffsubsync runs in Python; here the same alignment is
/// SubtitleSync (shared) and the audio is read by NativeAudio's LilacSpeechLevels.
enum SubtitleAutoSync {
    /// How a sync moved the lines (also for a translation finished after its source was synced).
    struct Fix: Equatable {
        var offset: Double
        var scale: Double
        var segments: [SyncSegment]
    }
    enum Outcome {
        case unchanged
        case uncertain(String)
        case synced(URL, Fix, method: String)
    }
    /// The video to listen to: a downloaded file, or the stream (the local proxy's address when it plays through it).
    struct Video {
        var url: URL
        var headers: [String: String]
        var duration: Double
    }
    static let label = " · 자동 싱크"
    /// A copy made by a sync is not synced again (its name says so).
    static func isSynced(_ file: URL) -> Bool { file.lastPathComponent.range(of: #"\.sync-[a-z0-9]+\.[^.]+$|\.synced\.[^.]+$"#, options: .regularExpression) != nil }
    /// "1.05초 앞당김", or by segments "구간별 0:00~ 1.01초 앞당김 · 2:08~ 89.25초 늦춤".
    static func moved(_ fix: Fix) -> String {
        func say(_ shift: Double) -> String { String(format: "%.2f초 ", abs(shift)) + (shift < 0 ? "앞당김" : "늦춤") }
        func clock(_ time: Double) -> String { "\(Int(time) / 60):" + String(format: "%02d", Int(time) % 60) }
        return fix.segments.count > 1 ? "구간별 " + fix.segments.map { clock($0.from) + "~ " + say($0.shift) }.joined(separator: " · ") : say(fix.offset)
    }
    /// desktop syncReferences: the site's tracks to choose the reference from, up to four, the ones named as dialogue first
    /// and signs, songs, forced or dubbed ones (timed to the dub) left out.
    static func referenceTracks(_ tracks: [RemoteSubtitle]) -> [RemoteSubtitle] {
        let usable = tracks.filter { ($0.label + " " + $0.language).range(of: #"signs|songs|s&s|forced|dubtitle|\(ai\)|\bai\b|commentary"#, options: [.regularExpression, .caseInsensitive]) == nil }
        func named(_ track: RemoteSubtitle) -> Bool { (track.label + " " + track.language).range(of: "full|dialog", options: [.regularExpression, .caseInsensitive]) != nil }
        return Array((usable.filter(named) + usable.filter { !named($0) }).prefix(4))
    }

    /// subtitle: the file on screen; timing: the file a translation was made from (its lines tell dialogue from captions);
    /// references: the site's tracks as files; chapters: the opening and ending, where the audio may split.
    static func sync(subtitle: URL, timing: URL?, references: [URL], video: Video?, chapters: [OfflineChapter],
                     status: @escaping @MainActor (String, Double?) -> Void, cancelled: @escaping @Sendable () -> Bool) async throws -> Outcome {
        let prepared = try await Task.detached(priority: .utility) { () throws -> ([SyncCue], [SyncCue], (count: Int, cues: [SyncCue])?) in
            let content = try SubtitleFiles.text(subtitle), ext = subtitle.pathExtension
            let timingCues: [SyncCue]
            if let timing, let source = try? SubtitleFiles.text(timing) { timingCues = try SubtitleSync.shared.spokenCues(content: source, extension: timing.pathExtension) }
            else { timingCues = try SubtitleSync.shared.spokenCues(content: content, extension: ext) }
            // The track with the most spoken lines (by content: a "S&S" track has none).
            let best = references.compactMap { file -> (count: Int, cues: [SyncCue])? in
                guard let text = try? SubtitleFiles.text(file) else { return nil }
                guard let cues = try? SubtitleSync.shared.spokenCues(content: text, extension: file.pathExtension) else { return nil }
                return (cues.count, cues)
            }.max { $0.count < $1.count }
            return (timingCues, try SubtitleSync.shared.allCues(content: content, extension: ext), best)
        }.value
        let (timing, all, best) = prepared
        guard !timing.isEmpty else { throw SubtitleFiles.failure("싱크를 맞출 자막 줄이 없습니다.") }
        let byTrack = (best?.count ?? 0) >= 20
        var reference = best?.cues ?? []
        var duration = video?.duration ?? 0
        if byTrack { await status("사이트 자막 트랙에 맞추는 중", nil) }
        else {
            guard let video, video.url.isFileURL || ["http", "https"].contains(video.url.scheme?.lowercased() ?? "") else {
                throw SubtitleFiles.failure("이 영상은 자동 싱크를 쓸 수 없습니다.")
            }
            await status("영상 음성 읽는 중", 0)
            reference = try await speech(video, status: status, cancelled: cancelled)
            duration = max(duration, reference.last?.end ?? 0)
            await status("음성에 맞추는 중", nil)
        }
        if cancelled() { throw CancellationError() }
        let marks = chapters.filter { ["op", "ed"].contains($0.type.lowercased()) || $0.type.lowercased().hasSuffix("op") || $0.type.lowercased().hasSuffix("ed") }
            .map { SyncCue(start: $0.start, end: $0.end) }
        let result = try await Task.detached(priority: .utility) {
            try SubtitleSync.shared.sync(timing: timing, all: all, reference: reference, byTrack: byTrack, chapters: marks, duration: duration)
        }.value
        if cancelled() { throw CancellationError() }
        if result.uncertain { return .uncertain("이 자막이 영상과 맞는 위치를 찾지 못해 그대로 뒀어요 (다른 판본이나 다른 회차의 자막일 수 있어요).") }
        if result.unchanged { return .unchanged }
        let fix = Fix(offset: result.offset, scale: result.scale, segments: result.segments ?? [])
        return .synced(try await retimed(subtitle, fix), fix, method: result.method)
    }

    /// A retimed copy of a subtitle: beside it when it is the app's own (an ASS keeps the folder with its fonts), else
    /// under Subtitles/Synced; ".sync-<time>" makes a new name each run, so the player shows no cached copy. stable: one
    /// name for a file rewritten as it goes (a translation still running), in the temporary folder.
    static func retimed(_ file: URL, _ fix: Fix, stable: Bool = false) async throws -> URL {
        try await Task.detached(priority: .userInitiated) {
            let content = try SubtitleFiles.text(file)
            let ext = SubtitleSync.shared.retimedExtension(extension: file.pathExtension)
            let base = file.deletingPathExtension().lastPathComponent.replacingOccurrences(of: #"\.(sync-[a-z0-9]+|synced)$"#, with: "", options: .regularExpression)
            let folder: URL
            if stable { folder = FileManager.default.temporaryDirectory.appendingPathComponent("SubtitleSync") }
            else { folder = file.standardizedFileURL.path.hasPrefix(SubtitleFiles.root.path + "/") ? file.deletingLastPathComponent() : SubtitleFiles.root.appendingPathComponent("Synced") }
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let name = stable ? base + ".synced." + ext : base + ".sync-" + String(Int(Date().timeIntervalSince1970 * 1000), radix: 36) + "." + ext
            let target = folder.appendingPathComponent(name)
            let text = try SubtitleSync.shared.retime(content: content, extension: file.pathExtension, offset: fix.offset, scale: fix.scale, segments: fix.segments)
            try text.write(to: target, atomically: true, encoding: .utf8)
            try? SubtitleFiles.associateFonts(SubtitleFiles.fonts(for: file), with: target)
            return target
        }.value
    }

    /// The speech in the episode's audio: its dialogue level every 10 ms (the whole episode is read: a stream is
    /// downloaded once, 1–2 minutes), then where it rises above the quiet around it.
    private final class Progress { var report: (Double) -> Void = { _ in }; var cancelled: () -> Bool = { false } }
    private static var levels: [String: [SyncCue]] = [:]
    static func speech(_ video: Video, status: @escaping @MainActor (String, Double?) -> Void, cancelled: @escaping @Sendable () -> Bool) async throws -> [SyncCue] {
        let key = video.url.absoluteString
        if let known = await MainActor.run(body: { levels[key] }) { return known }
        let found = try await Task.detached(priority: .utility) { () throws -> [SyncCue] in
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("SubtitleSync")
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let file = folder.appendingPathComponent(UUID().uuidString + ".f32")
            defer { try? FileManager.default.removeItem(at: file) }
            let progress = Progress()
            progress.report = { value in Task { @MainActor in status("영상 음성 읽는 중", value) } }
            progress.cancelled = { cancelled() || Task.isCancelled }
            let box = Unmanaged.passRetained(progress)
            defer { box.release() }
            let source = video.url.isFileURL ? video.url.path : video.url.absoluteString
            let headers = video.headers.filter { !$0.value.isEmpty }.map { $0.key + ": " + $0.value + "\r\n" }.joined()
            var message: UnsafeMutablePointer<CChar>?
            let result = LilacSpeechLevels(source, headers, file.path, video.duration,
                { opaque in Unmanaged<Progress>.fromOpaque(opaque!).takeUnretainedValue().cancelled() },
                { opaque, value in Unmanaged<Progress>.fromOpaque(opaque!).takeUnretainedValue().report(value) },
                box.toOpaque(), &message)
            defer { if let message { LilacAudioFreeError(message) } }
            if progress.cancelled() { throw CancellationError() }
            guard result == 0 else {
                throw SubtitleFiles.failure("영상 음성을 읽지 못했어요" + (message.map { " (" + String(cString: $0) + ")" } ?? ""))
            }
            let data = try Data(contentsOf: file)
            let count = data.count / 4
            let values = KotlinFloatArray(size: Int32(count))
            data.withUnsafeBytes { buffer in for index in 0..<count { values.set(index: Int32(index), value: buffer.loadUnaligned(fromByteOffset: index * 4, as: Float.self)) } }
            return try SubtitleSync.shared.speechFromLevels(levels: values, frameSeconds: 0.01)
        }.value
        await MainActor.run { if levels.count > 4 { levels.removeAll() }; levels[key] = found }
        return found
    }
}

/// A run's stop, read from the decoding thread.
final class SyncCancel: @unchecked Sendable {
    private let lock = NSLock()
    private var stopped = false
    func cancel() { lock.lock(); stopped = true; lock.unlock() }
    var cancelled: Bool { lock.lock(); defer { lock.unlock() }; return stopped }
}
