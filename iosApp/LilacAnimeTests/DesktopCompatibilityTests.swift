import XCTest
import LilacLocalAI
@testable import LilacAnime

final class DesktopCompatibilityTests: XCTestCase {
    func testOldPreferencesDecodeWithoutNewFields() throws {
        let original = AppPreferences()
        let encoded = try JSONEncoder().encode(original)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        for key in ["titleLanguage", "desktopWorkspace", "preferredServer", "modelSampling", "pretranslateNext", "downloadSubtitles", "translationModels", "cloudFallback"] { object.removeValue(forKey: key) }
        object["selectedGGUF"] = "existing.gguf"; object["source"] = "miruro"; object["subtitleOffset"] = 1.7
        let restored = try JSONDecoder().decode(AppPreferences.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(restored.selectedGGUF, "existing.gguf")
        XCTAssertEqual(restored.source, "miruro"); XCTAssertEqual(restored.subtitleOffset, 1.7)
        XCTAssertNil(restored.desktopWorkspace); XCTAssertNil(restored.titleLanguage)
    }
    func testActualGemma4TemplateKeepsSystemAndUserAndDisablesThinking() throws {
        let file = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "gemma4-template", withExtension: "txt"))
        let template = try String(contentsOf: file, encoding: .utf8)
        let output = try format(template, "표기 기준\u{1e}こんにちは")
        XCTAssertTrue(output.contains("표기 기준")); XCTAssertTrue(output.contains("こんにちは"))
        XCTAssertTrue(output.contains("<|turn>model"))
        XCTAssertTrue(output.hasSuffix("<|turn>model\n<|channel>final\n"))
    }
    func testChatMLTemplateAndUnicodeToJSON() throws {
        let template = "{% for message in messages %}<|im_start|>{{ message.role }}\n{{ message.content }}<|im_end|>\n{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}"
        XCTAssertEqual(try format(template, "규칙\u{1e}こんにちは"), "<|im_start|>system\n규칙<|im_end|>\n<|im_start|>user\nこんにちは<|im_end|>\n<|im_start|>assistant\n")
        XCTAssertTrue(try format("{{messages|tojson}}", "한글").contains("\\ud55c"))
    }
    func testPortableDownloadRejectsTraversingPaths() {
        XCTAssertFalse(DownloadTransfer.safeName("../video.mp4")); XCTAssertFalse(DownloadTransfer.safeName("C:\\secret"))
        XCTAssertFalse(DownloadTransfer.safeName("https://example.test")); XCTAssertTrue(DownloadTransfer.safeName("subtitle-한국어.ass"))
    }
    private func format(_ template: String, _ prompt: String) throws -> String {
        var error: UnsafeMutablePointer<CChar>?
        let result = lilac_format_prompt(template, prompt, "<bos>", "<eos>", &error)
        defer { if let error { lilac_string_free(error) }; if let result { lilac_string_free(result) } }
        guard let result else { throw SubtitleFiles.failure(error.map { String(cString: $0) } ?? "템플릿 실패") }
        return String(cString: result)
    }
}
