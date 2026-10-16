import XCTest
@testable import LilacAnime

final class Anime4KTests: XCTestCase {
    func testLegacyAndUnknownChoicesKeepAnime4KOff() throws {
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(AppPreferences())) as? [String: Any])
        json.removeValue(forKey: "anime4k")
        let old = try JSONDecoder().decode(AppPreferences.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(Anime4KMode(saved: old.anime4k), .off)
        XCTAssertEqual(Anime4KMode(saved: "unrecognized"), .off)
        var settings = old; settings.anime4k = "b"
        let saved = try JSONDecoder().decode(AppPreferences.self, from: JSONEncoder().encode(settings))
        XCTAssertEqual(Anime4KMode(saved: saved.anime4k), .b)
    }
    func testEveryEnabledProfileResolvesRealBundledShaderFiles() throws {
        XCTAssertTrue(try Anime4KMode.off.paths().isEmpty)
        for mode in Anime4KMode.allCases where mode != .off {
            let files = try mode.paths()
            XCTAssertEqual(files.count, mode == .c ? 2 : 3)
            XCTAssertTrue(files.first?.hasSuffix("Anime4K_Clamp_Highlights.glsl") == true)
            for file in files {
                let source = try String(contentsOfFile: file, encoding: .utf8)
                XCTAssertTrue(source.contains("//!HOOK MAIN"), file)
                XCTAssertTrue(source.contains("vec4 hook()"), file)
                XCTAssertTrue(source.contains("MIT License"), file)
            }
        }
        XCTAssertTrue(try Anime4KMode.light.paths().last?.hasSuffix("Anime4K_Upscale_CNN_x2_S.glsl") == true)
        XCTAssertTrue(try Anime4KMode.b.paths()[1].hasSuffix("Anime4K_Restore_CNN_Soft_M.glsl"))
        XCTAssertTrue(try Anime4KMode.c.paths().last?.hasSuffix("Anime4K_Upscale_Denoise_CNN_x2_M.glsl") == true)
    }
    func testOnlyGPUShaderErrorsTriggerFallback() {
        XCTAssertTrue(Anime4KMode.shaderFailed(prefix: "vo/gpu-next/libplacebo", text: "Shader compilation failed"))
        XCTAssertTrue(Anime4KMode.shaderFailed(prefix: "vo/gpu-next", text: "Error while parsing user shader"))
        XCTAssertFalse(Anime4KMode.shaderFailed(prefix: "demux", text: "Error loading shader-subtitle.ass"))
        XCTAssertFalse(Anime4KMode.shaderFailed(prefix: "vo/gpu-next", text: "Shader compiled successfully"))
        XCTAssertFalse(Anime4KMode.shaderFailed(prefix: "ao/coreaudio", text: "Audio device failed"))
    }
}
