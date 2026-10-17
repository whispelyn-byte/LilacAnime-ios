import Foundation
import SwiftUI

struct LocalModelInputOptions: Codable, Equatable {
    var inputMode = "auto"
    var sourceLanguage = "auto"
    var customPrompt = ""
    func validate() throws {
        guard ["auto", "chat", "translation"].contains(inputMode), ["auto", "ja", "en", "zh", "ko"].contains(sourceLanguage) else {
            throw SubtitleFiles.failure("올바른 입력 형식과 원문 언어를 선택해 주세요.")
        }
        guard customPrompt.utf16.count <= 8000, customPrompt.isEmpty || customPrompt.contains("{text}") else {
            throw SubtitleFiles.failure("프롬프트는 8,000자 이하이며 원문이 들어갈 {text}를 포함해야 합니다.")
        }
    }
    func usesTranslationInput(template: String) -> Bool {
        inputMode == "translation" || (inputMode == "auto" && customPrompt.isEmpty &&
            template.range(of: #"\bsource_lang_code\b"#, options: .regularExpression) != nil &&
            template.range(of: #"\btarget_lang_code\b"#, options: .regularExpression) != nil)
    }
    func language(for source: String) -> String {
        if sourceLanguage != "auto" { return sourceLanguage }
        if source.range(of: #"[\p{Hiragana}\p{Katakana}\p{Han}]"#, options: .regularExpression) != nil { return "ja" }
        if source.range(of: #"\p{Hangul}"#, options: .regularExpression) != nil { return "ko" }
        return "en"
    }
    func prompt(for source: String) -> String {
        let regex = try! NSRegularExpression(pattern: #"\{(text|source_lang|target_lang)\}"#)
        var result = customPrompt
        // Replace matches from the original template once: placeholders in source text remain literal.
        for match in regex.matches(in: customPrompt, range: NSRange(customPrompt.startIndex..., in: customPrompt)).reversed() {
            let token = (customPrompt as NSString).substring(with: match.range(at: 1))
            let value = token == "text" ? source : token == "source_lang" ? language(for: source) : "ko"
            result = (result as NSString).replacingCharacters(in: match.range, with: value)
        }
        return result
    }
    func acceptsTranslation(_ text: String) -> Bool {
        text.rangeOfCharacter(from: .letters) == nil || text.range(of: #"\p{Hangul}"#, options: .regularExpression) != nil
    }
    var cacheIdentity: String {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        return "input-1:" + String(decoding: (try? encoder.encode(self)) ?? Data(), as: UTF8.self)
    }
}
extension AppPreferences {
    var selectedLocalInput: LocalModelInputOptions { localModelInputs?[selectedGGUF] ?? LocalModelInputOptions() }
}

struct LocalModelOptionsEditor: View {
    let fileName: String
    @EnvironmentObject private var library: LibraryStore
    @State private var draft = LocalModelInputOptions()
    @State private var message: String?
    var body: some View {
        DisclosureGroup("번역 입력 설정") {
            VStack(alignment: .leading, spacing: 10) {
                Picker("입력 형식", selection: $draft.inputMode) {
                    Text("자동 · 모델 템플릿").tag("auto")
                    Text("일반 지시문").tag("chat")
                    Text("원문 + 언어 지정").tag("translation")
                }
                Picker("원문 언어", selection: $draft.sourceLanguage) {
                    Text("자동").tag("auto"); Text("일본어").tag("ja"); Text("영어").tag("en")
                    Text("중국어").tag("zh"); Text("한국어").tag("ko")
                }
                if draft.inputMode != "translation" {
                    Text("사용자 프롬프트").font(.subheadline)
                    TextEditor(text: $draft.customPrompt).font(.system(size: 13)).frame(minHeight: 100)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Text("비워 두면 기본 프롬프트 사용 · {text}: 원문 · {source_lang}: 원문 언어 · {target_lang}: ko").font(.caption).foregroundStyle(LilacStyle.muted)
                }
                Button("저장") {
                    do {
                        try draft.validate()
                        var values = library.preferences.localModelInputs ?? [:]
                        values[fileName] = draft; library.preferences.localModelInputs = values
                        message = "번역 입력 설정을 저장했어요."
                    } catch { message = error.localizedDescription }
                }.buttonStyle(SettingButtonStyle())
                if let message { Text(message).font(.caption).foregroundStyle(LilacStyle.muted) }
            }.padding(.top, 8)
        }.font(.system(size: 13)).onAppear { draft = library.preferences.localModelInputs?[fileName] ?? LocalModelInputOptions() }
    }
}
