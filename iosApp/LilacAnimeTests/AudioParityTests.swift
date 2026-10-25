import XCTest
@testable import LilacAnime

final class AudioParityTests: XCTestCase {
    func testWhisperAudioSeeksAndBoundsTheDecodedChunkAndCancelsCleanly() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        var wav = Data()
        func ascii(_ value: String) { wav.append(contentsOf: value.utf8) }
        func u16(_ value: UInt16) { var little = value.littleEndian; withUnsafeBytes(of: &little) { wav.append(contentsOf: $0) } }
        func u32(_ value: UInt32) { var little = value.littleEndian; withUnsafeBytes(of: &little) { wav.append(contentsOf: $0) } }
        let rate = 16000, count = rate * 4
        ascii("RIFF"); u32(UInt32(36 + count * 2)); ascii("WAVEfmt "); u32(16)
        u16(1); u16(1); u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        ascii("data"); u32(UInt32(count * 2))
        for index in 0..<count { u16(index < rate * 2 ? 4096 : 16384) }
        let source = folder.appendingPathComponent("seek.wav"), output = folder.appendingPathComponent("chunk.pcm")
        try wav.write(to: source)
        var message: UnsafeMutablePointer<CChar>?
        let result = LilacWhisperAudio(source.path, "", output.path, 2.2, 0.5, nil, nil, &message)
        defer { if let message { LilacAudioFreeError(message) } }
        XCTAssertEqual(result, 0, message.map { String(cString: $0) } ?? "decode failed")
        let bytes = try Data(contentsOf: output)
        XCTAssertEqual(bytes.count, rate / 2 * 4)
        XCTAssertEqual(bytes.withUnsafeBytes { $0.loadUnaligned(as: Float.self) }, 0.5, accuracy: 0.001)
        var cancelledMessage: UnsafeMutablePointer<CChar>?
        XCTAssertEqual(LilacWhisperAudio(source.path, "", output.path, 0, 1, { _ in true }, nil, &cancelledMessage), -1)
        if let cancelledMessage { LilacAudioFreeError(cancelledMessage) }
        XCTAssertFalse(FileManager.default.fileExists(atPath: output.path))
    }
    func testNativeDecoderQuantizesLikeDesktopS16BeforeFingerprinting() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        var wav = Data()
        func ascii(_ value: String) { wav.append(contentsOf: value.utf8) }
        func u16(_ value: UInt16) { var little = value.littleEndian; withUnsafeBytes(of: &little) { wav.append(contentsOf: $0) } }
        func u32(_ value: UInt32) { var little = value.littleEndian; withUnsafeBytes(of: &little) { wav.append(contentsOf: $0) } }
        let count = 2048
        ascii("RIFF"); u32(UInt32(36 + count * 4)); ascii("WAVEfmt "); u32(16)
        u16(3); u16(1); u32(8000); u32(32000); u16(4); u16(32)
        ascii("data"); u32(UInt32(count * 4))
        for _ in 0..<count { u32(Float(0.123456).bitPattern) }
        let source = folder.appendingPathComponent("float.wav"), output = folder.appendingPathComponent("audio.f32")
        try wav.write(to: source)
        var message: UnsafeMutablePointer<CChar>?
        let result = LilacDecodeAudio(source.path, output.path, nil, nil, &message)
        defer { if let message { LilacAudioFreeError(message) } }
        XCTAssertEqual(result, 0, message.map { String(cString: $0) } ?? "decode failed")
        let bytes = try Data(contentsOf: output)
        XCTAssertEqual(bytes.count, count * 4)
        let sample = bytes.withUnsafeBytes { $0.loadUnaligned(as: Float.self) }
        XCTAssertEqual(sample, Float(4045) / 32768, accuracy: 0.0000001)
    }
}
