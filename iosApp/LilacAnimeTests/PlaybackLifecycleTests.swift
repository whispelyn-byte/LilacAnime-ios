import XCTest
import AVFoundation
import QuartzCore
@testable import LilacAnime

final class PlaybackLifecycleTests: XCTestCase {
    @MainActor func testTwoTimesPlaybackSurvivesRepeatedOutputSuspensionWithoutSeeking() async throws {
        let movie = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
        defer { try? FileManager.default.removeItem(at: movie) }
        try await makeMovie(movie)
        // Exercise the real mpv core without requiring Metal on the simulator.
        let engine = MPVEngine(activeVideoOutput: "null")
        defer { engine.shutdown() }
        var settings = AppPreferences(); settings.speed = 2
        engine.configure(settings)
        engine.attach(CAMetalLayer())
        engine.load(ResolvedStream(label: "fixture", url: movie, referer: "", headers: [:]))
        let ready = Date().addingTimeInterval(15)
        while (engine.position < 0.25 || !engine.canCaptureVideo) && Date() < ready { try await Task.sleep(nanoseconds: 50_000_000) }
        XCTAssertGreaterThan(engine.position, 0.2)
        for _ in 0..<3 {
            let before = engine.position
            engine.setVideoOutputActive(false)
            let advanceDeadline = Date().addingTimeInterval(5)
            while engine.position <= before && Date() < advanceDeadline { try await Task.sleep(nanoseconds: 50_000_000) }
            XCTAssertGreaterThan(engine.position, before, "The media track keeps playing while output is hidden")
            engine.setVideoOutputActive(true)
            XCTAssertFalse(engine.canSaveProgress, "Do not save transitional output timestamps")
            let restoreDeadline = Date().addingTimeInterval(5)
            while !engine.canSaveProgress && Date() < restoreDeadline { try await Task.sleep(nanoseconds: 50_000_000) }
            XCTAssertTrue(engine.canSaveProgress)
            XCTAssertFalse(engine.paused); XCTAssertEqual(engine.speed, 2, accuracy: 0.001)
            XCTAssertEqual(engine.seekRevision, 0, "Foreground restoration must not seek or multiply the position by speed")
        }
        engine.pause(); try await Task.sleep(nanoseconds: 350_000_000)
        let pausedAt = engine.position
        engine.setVideoOutputActive(false); engine.setVideoOutputActive(true)
        try await Task.sleep(nanoseconds: 350_000_000)
        XCTAssertTrue(engine.paused); XCTAssertEqual(engine.position, pausedAt, accuracy: 0.1)
    }
    private func makeMovie(_ url: URL) async throws {
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 320, AVVideoHeightKey: 180])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA, kCVPixelBufferWidthKey as String: 320, kCVPixelBufferHeightKey as String: 180])
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? SubtitleFiles.failure("Fixture failed") }
        writer.startSession(atSourceTime: .zero)
        var pixel: CVPixelBuffer?
        guard CVPixelBufferCreate(kCFAllocatorDefault, 320, 180, kCVPixelFormatType_32BGRA, nil, &pixel) == noErr, let pixel else { throw SubtitleFiles.failure("Fixture failed") }
        CVPixelBufferLockBaseAddress(pixel, [])
        if let bytes = CVPixelBufferGetBaseAddress(pixel) { memset(bytes, 0, CVPixelBufferGetDataSize(pixel)) }
        CVPixelBufferUnlockBaseAddress(pixel, [])
        for frame in 0..<240 {
            let deadline = Date().addingTimeInterval(5)
            while !input.isReadyForMoreMediaData && Date() < deadline { try await Task.sleep(nanoseconds: 10_000_000) }
            guard input.isReadyForMoreMediaData, adaptor.append(pixel, withPresentationTime: CMTime(value: Int64(frame), timescale: 4)) else { throw writer.error ?? SubtitleFiles.failure("Fixture failed") }
        }
        input.markAsFinished(); await writer.finishWriting()
        guard writer.status == .completed else { throw writer.error ?? SubtitleFiles.failure("Fixture failed") }
    }
}
