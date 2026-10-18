import XCTest
import LilacLocalAI
@testable import LilacAnime

final class LocalModelInputTests: XCTestCase {
    func testAutomaticDetectionUsesTemplateInputsAndHonorsOverrides() {
        let template = "{{ source_lang_code }} {{ target_lang_code }}"
        var options = LocalModelInputOptions()
        XCTAssertTrue(options.usesTranslationInput(template: template))
        XCTAssertFalse(options.usesTranslationInput(template: "{{ messages }}"))
        XCTAssertFalse(options.usesTranslationInput(template: "{{ my_source_lang_code }} {{ target_lang_code }}"))
        options.inputMode = "chat"
        XCTAssertFalse(options.usesTranslationInput(template: template))
        options.inputMode = "translation"
        XCTAssertTrue(options.usesTranslationInput(template: ""))
        // desktop 0.5.29: a language template takes the source alone, a saved prompt or not (it was translated into the line).
        options.inputMode = "auto"; options.customPrompt = "Translate {text}"
        XCTAssertTrue(options.usesTranslationInput(template: template))
        XCTAssertFalse(options.usesTranslationInput(template: "{{ messages }}"))
    }
    func testTranslationOnlyModelRefusesAPromptOnceItsTemplateIsKnown() throws {
        let model = "translategemma-test-" + UUID().uuidString + ".gguf"
        var options = LocalModelInputOptions(); options.customPrompt = "Rules
{text}"
        XCTAssertNoThrow(try options.validate(model: model))
        LocalModelInputOptions.remember(template: "{{ source_lang_code }} {{ target_lang_code }}", model: model)
        XCTAssertTrue(LocalModelInputOptions.translationOnly(model))
        XCTAssertThrowsError(try options.validate(model: model)) { XCTAssertTrue($0.localizedDescription.contains("번역 전용")) }
        options.inputMode = "chat"; XCTAssertNoThrow(try options.validate(model: model))
        options.inputMode = "auto"; options.customPrompt = ""; XCTAssertNoThrow(try options.validate(model: model))
        LocalModelInputOptions.remember(template: "{{ messages }}", model: model)
        XCTAssertFalse(LocalModelInputOptions.translationOnly(model))
    }
    func testSourceLanguageMatchesDesktopIncludingExplicitChinese() {
        var options = LocalModelInputOptions()
        XCTAssertEqual(options.language(for: "こんにちは"), "ja")
        XCTAssertEqual(options.language(for: "你好"), "ja")
        XCTAssertEqual(options.language(for: "\u{20000}"), "ja")
        XCTAssertEqual(options.language(for: "안녕"), "ko")
        XCTAssertEqual(options.language(for: "Hello"), "en")
        options.sourceLanguage = "zh"
        XCTAssertEqual(options.language(for: "你好"), "zh")
    }
    func testCustomPromptSubstitutesOnlyTemplatePlaceholders() {
        var options = LocalModelInputOptions()
        options.sourceLanguage = "ja"
        options.customPrompt = "{source_lang} -> {target_lang}\n{text} {text}"
        XCTAssertEqual(options.prompt(for: "こんにちは {target_lang}"), "ja -> ko\nこんにちは {target_lang} こんにちは {target_lang}")
    }
    func testInvalidOptionsAndPromptLengthAreRejected() {
        var options = LocalModelInputOptions()
        XCTAssertNoThrow(try options.validate())
        options.inputMode = "invalid"; XCTAssertThrowsError(try options.validate())
        options.inputMode = "chat"; options.sourceLanguage = "invalid"; XCTAssertThrowsError(try options.validate())
        options.sourceLanguage = "ja"; options.customPrompt = "No placeholder"; XCTAssertThrowsError(try options.validate())
        options.customPrompt = String(repeating: "😀", count: 4000) + "{text}"; XCTAssertThrowsError(try options.validate())
    }
    func testEnglishOutputCannotCountAsKoreanTranslation() {
        let options = LocalModelInputOptions()
        XCTAssertFalse(options.acceptsTranslation("Hello, how are you?"))
        XCTAssertFalse(options.acceptsTranslation("ありがとう"))
        XCTAssertTrue(options.acceptsTranslation("안녕하세요"))
        XCTAssertTrue(options.acceptsTranslation("…!? 123"))
    }
    func testOptionsPersistPerFileAndChangeCacheIdentity() throws {
        var preferences = AppPreferences()
        preferences.selectedGGUF = "renamed-model.gguf"
        let initial = preferences.selectedLocalInput.cacheIdentity
        var options = LocalModelInputOptions(); options.inputMode = "translation"; options.sourceLanguage = "zh"
        preferences.localModelInputs = [preferences.selectedGGUF: options]
        let restored = try JSONDecoder().decode(AppPreferences.self, from: JSONEncoder().encode(preferences))
        XCTAssertEqual(restored.selectedLocalInput, options)
        XCTAssertNotEqual(initial, restored.selectedLocalInput.cacheIdentity)
        options.customPrompt = "{text}"; XCTAssertNotEqual(options.cacheIdentity, restored.selectedLocalInput.cacheIdentity)
        var old = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(preferences)) as? [String: Any])
        old.removeValue(forKey: "localModelInputs")
        let migrated = try JSONDecoder().decode(AppPreferences.self, from: JSONSerialization.data(withJSONObject: old))
        XCTAssertEqual(migrated.selectedLocalInput, LocalModelInputOptions())
    }
    func testNativeTemplateGetsRawSourceAndLanguageGlobals() throws {
        let template = "{{ source_lang_code }}>{{ target_lang_code }}:{{ type }}:{{ messages[0]['role'] }}:{{ messages[0]['content'] }}"
        let source = "こんにちは\u{001e}{target_lang}"
        var error: UnsafeMutablePointer<CChar>?
        let result = lilac_format_translation_prompt(template, source, "ja", "<bos>", "<eos>", &error)
        defer { if let error { lilac_string_free(error) }; if let result { lilac_string_free(result) } }
        XCTAssertNil(error)
        XCTAssertEqual(String(cString: try XCTUnwrap(result)), "ja>ko:text:user:" + source)
    }
    func testOrdinaryChatDoesNotInheritTranslationGlobals() throws {
        var error: UnsafeMutablePointer<CChar>?
        let result = lilac_format_prompt("{{ source_lang_code | default('none') }}:{{ messages[0]['content'] }}", "Translate into Korean", "", "", &error)
        defer { if let error { lilac_string_free(error) }; if let result { lilac_string_free(result) } }
        XCTAssertNil(error)
        XCTAssertEqual(String(cString: try XCTUnwrap(result)), "none:Translate into Korean")
    }
}
