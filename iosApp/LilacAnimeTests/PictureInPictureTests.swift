import XCTest
import CoreMedia
import CoreVideo
import AVFoundation
import Libmpv
@testable import LilacAnime

final class PictureInPictureTests: XCTestCase {
    func testSoftwareMpvCaptureContainsSubtitleWithoutMetalOutput() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let movie = folder.appendingPathComponent("black.mp4"), subtitle = folder.appendingPathComponent("subtitle.srt")
        try "1\n00:00:00,000 --> 00:00:10,000\nPiP 자막\n".write(to: subtitle, atomically: true, encoding: .utf8)
        try await blackMovie(movie)
        let visible = try await Task.detached { () throws -> Bool in
            guard let handle = mpv_create() else { throw SubtitleFiles.failure("mpv initialization failed") }
            defer { mpv_terminate_destroy(handle) }
            for (key, value) in ["vo": "null", "ao": "null", "hwdec": "no", "screenshot-sw": "yes", "pause": "yes", "keep-open": "yes", "sub-font": SubtitleAppearance.defaultFont] {
                mpv_set_option_string(handle, key, value)
            }
            guard mpv_initialize(handle) >= 0 else { throw SubtitleFiles.failure("mpv initialization failed") }
            func command(_ values: [String]) {
                let strings = values.map { strdup($0) }
                defer { strings.forEach { free($0) } }
                var pointers: [UnsafePointer<CChar>?] = strings.map { $0.map { UnsafePointer<CChar>($0) } } + [nil]
                mpv_command(handle, &pointers)
            }
            command(["loadfile", movie.path])
            let deadline = Date().addingTimeInterval(10)
            var loaded = false
            while Date() < deadline {
                if mpv_wait_event(handle, 0.05)?.pointee.event_id == MPV_EVENT_FILE_LOADED { loaded = true; break }
            }
            guard loaded else { throw SubtitleFiles.failure("Fixture video did not load") }
            command(["sub-add", subtitle.path, "select"])
            // Pause at a cue with a decoded frame. No GPU output exists in this test.
            mpv_set_property_string(handle, "pause", "no")
            let frameDeadline = Date().addingTimeInterval(5)
            var position = 0.0
            while Date() < frameDeadline {
                _ = mpv_get_property(handle, "time-pos", MPV_FORMAT_DOUBLE, &position)
                if position >= 0.25 { break }
                _ = mpv_wait_event(handle, 0.05)
            }
            guard position >= 0.25 else { throw SubtitleFiles.failure("Fixture video did not advance to a decoded subtitle frame") }
            mpv_set_property_string(handle, "pause", "yes")
            let sample = try PiPFrame.capture(handle, position: 0)
            guard let pixel = CMSampleBufferGetImageBuffer(sample) else { return false }
            CVPixelBufferLockBaseAddress(pixel, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(pixel, .readOnly) }
            guard let data = CVPixelBufferGetBaseAddress(pixel)?.assumingMemoryBound(to: UInt8.self) else { return false }
            let width = CVPixelBufferGetWidth(pixel), height = CVPixelBufferGetHeight(pixel), stride = CVPixelBufferGetBytesPerRow(pixel)
            return (0..<height).contains { y in (0..<width).contains { x in data[y * stride + x * 4] > 100 } }
        }.value
        XCTAssertTrue(visible, "The black video should contain bright subtitle pixels in the PiP frame")
    }
    private func blackMovie(_ url: URL) async throws {
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 320, AVVideoHeightKey: 180])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA, kCVPixelBufferWidthKey as String: 320, kCVPixelBufferHeightKey as String: 180])
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? SubtitleFiles.failure("Fixture writer failed") }
        writer.startSession(atSourceTime: .zero)
        var pixel: CVPixelBuffer?
        guard CVPixelBufferCreate(kCFAllocatorDefault, 320, 180, kCVPixelFormatType_32BGRA, nil, &pixel) == kCVReturnSuccess, let pixel else { throw SubtitleFiles.failure("Fixture buffer failed") }
        CVPixelBufferLockBaseAddress(pixel, [])
        if let data = CVPixelBufferGetBaseAddress(pixel) { memset(data, 0, CVPixelBufferGetDataSize(pixel)) }
        CVPixelBufferUnlockBaseAddress(pixel, [])
        for frame in 0..<48 {
            let deadline = Date().addingTimeInterval(5)
            while !input.isReadyForMoreMediaData && Date() < deadline { try await Task.sleep(nanoseconds: 10_000_000) }
            guard input.isReadyForMoreMediaData, adaptor.append(pixel, withPresentationTime: CMTime(value: Int64(frame), timescale: 24)) else { throw writer.error ?? SubtitleFiles.failure("Fixture frame failed") }
        }
        input.markAsFinished()
        await writer.finishWriting()
        if writer.status != .completed { throw writer.error ?? SubtitleFiles.failure("Fixture writer failed") }
    }
    func testFrameCopiesPixelsAndPreservesPlaybackTime() throws {
        var bytes: [UInt8] = [20, 40, 60, 255, 80, 100, 120, 255]
        let sample = try bytes.withUnsafeMutableBytes {
            try PiPFrame.make(width: 2, height: 1, stride: 8, bytes: $0.baseAddress!, count: $0.count, position: 12.5)
        }
        XCTAssertEqual(CMSampleBufferGetPresentationTimeStamp(sample).seconds, 12.5, accuracy: 0.001)
        let pixel = try XCTUnwrap(CMSampleBufferGetImageBuffer(sample))
        XCTAssertEqual(CVPixelBufferGetWidth(pixel), 2)
        CVPixelBufferLockBaseAddress(pixel, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixel, .readOnly) }
        let data = try XCTUnwrap(CVPixelBufferGetBaseAddress(pixel)).assumingMemoryBound(to: UInt8.self)
        XCTAssertEqual(Array(UnsafeBufferPointer(start: data, count: 8)), bytes)
    }
    func testFrameRejectsTruncatedPixelsAndScalesLargeFrames() throws {
        var bytes = [UInt8](repeating: 255, count: 1920 * 4)
        let sample = try bytes.withUnsafeMutableBytes { raw -> CMSampleBuffer in
            XCTAssertThrowsError(try PiPFrame.make(width: 1920, height: 2, stride: 1920 * 4, bytes: raw.baseAddress!, count: raw.count, position: 0))
            return try PiPFrame.make(width: 1920, height: 1, stride: 1920 * 4, bytes: raw.baseAddress!, count: raw.count, position: 0)
        }
        XCTAssertEqual(CVPixelBufferGetWidth(try XCTUnwrap(CMSampleBufferGetImageBuffer(sample))), 960)
    }
    @MainActor func testPiPWithoutLoadedVideoCannotStartAndCleanupIsSafe() async {
        let engine = MPVEngine()
        let pip = PlayerPictureInPicture(engine: engine)
        // Unsupported devices and an unloaded video both return a useful failure without changing the renderer.
        let message = await pip.toggle()
        XCTAssertNotNil(message)
        XCTAssertFalse(pip.active); XCTAssertFalse(pip.starting); XCTAssertFalse(pip.rendering)
        pip.stop(); pip.stop()
        XCTAssertFalse(engine.pictureInPictureOutput)
        engine.shutdown()
    }
    func testScreenshotFilenameKeepsEpisodeTimeAndRemovesPathSeparators() {
        XCTAssertEqual(PlayerScreenshot.name(title: "작품/제목", episode: "2화", position: 75), "작품_제목 2화 01-15")
        XCTAssertFalse(PlayerScreenshot.name(title: "../a", episode: "b", position: .nan).contains("/"))
    }
}
