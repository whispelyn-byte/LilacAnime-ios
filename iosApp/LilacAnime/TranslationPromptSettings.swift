import SwiftUI
import LilacShared

/// 설정 > 자막 자동 번역 > 번역 프롬프트 (desktop index.html .prompt-setting): 추가 지시사항 comes after the app's rules,
/// 고급: 번역 규칙 전체 교체 takes their place. The boxes keep what is typed until 저장 (desktop: dataset.dirty).
struct TranslationPromptSettings: View {
    @EnvironmentObject private var store: LibraryStore
    @State private var extra = ""
    @State private var custom = ""
    @State private var advanced = false
    @State private var loaded = false
    @State private var message: String?
    @State private var confirmReset = false
    @State private var confirmDefault = false
    static let limit = 4000
    var body: some View {
        SettingBlock(title: "번역 프롬프트", note: message ?? "로컬 AI(Gemma·Aya 등)와 Gemini·OpenAI·Qwen·Claude 번역에 적용돼요. DeepL과 번역 전용 모델(TranslateGemma, Hy-MT, ja-ko-vn)은 지시를 받지 않아 적용되지 않아요. 바꾸면 이후 번역은 새 프롬프트로 다시 번역해요.") {
            Text("추가 지시사항: 앱의 기본 규칙 뒤에 붙고, 겹치면 이쪽을 먼저 따라요.").font(.system(size: 12.5)).foregroundStyle(LilacStyle.muted)
            editor($extra, placeholder: "예: 직역보다 자연스러운 의역을 우선해 주세요. 한 단어에 번역을 여러 개 쓰지 마세요.", height: 76)
                .accessibilityIdentifier("settings-prompt-extra")
            DisclosureGroup("고급: 번역 규칙 전체 교체", isExpanded: $advanced) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("쓰면 앱의 기본 번역 규칙 대신 이 규칙으로 번역해요. 작품 정보·등장인물 이름·용어집·앞 대사와 답변 형식은 앱이 계속 넣어 줘요.")
                        .font(.system(size: 12.5)).foregroundStyle(LilacStyle.muted).fixedSize(horizontal: false, vertical: true)
                    editor($custom, placeholder: "비워 두면 앱의 기본 번역 규칙을 씁니다.", height: 170)
                        .accessibilityIdentifier("settings-prompt-rules")
                    SettingButtons {
                        Button("기본 규칙 불러오기") {
                            if custom.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { custom = DesktopCloudPrompt.shared.rules } else { confirmDefault = true }
                        }
                    }
                }.padding(.top, 6)
            }.font(.system(size: 12.5)).foregroundStyle(LilacStyle.muted).tint(LilacStyle.muted)
            SettingButtons {
                Button("저장") { save(extra: extra, custom: custom, done: "번역 프롬프트를 저장했어요. 이후 번역부터 적용돼요.") }
                    .accessibilityIdentifier("settings-prompt-save")
                Button("초기화") { confirmReset = true }
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true; extra = store.preferences.promptExtra ?? ""; custom = store.preferences.promptRules ?? ""
            advanced = !custom.isEmpty
        }
        .alert("번역 프롬프트를 지우고 앱의 기본 규칙으로 되돌릴까요?", isPresented: $confirmReset) {
            Button("되돌리기", role: .destructive) { save(extra: "", custom: "", done: "번역 프롬프트를 기본으로 되돌렸어요.") }
            Button("취소", role: .cancel) {}
        }
        .alert("쓰던 규칙을 기본 규칙으로 바꿀까요?", isPresented: $confirmDefault) {
            Button("바꾸기") { custom = DesktopCloudPrompt.shared.rules }
            Button("취소", role: .cancel) {}
        }
    }
    private func editor(_ text: Binding<String>, placeholder: String, height: CGFloat) -> some View {
        ZStack(alignment: .topLeading) {
            if text.wrappedValue.isEmpty {
                Text(placeholder).font(.system(size: 13)).foregroundStyle(LilacStyle.muted.opacity(0.7)).padding(.horizontal, 5).padding(.vertical, 8).allowsHitTesting(false)
            }
            TextEditor(text: text).font(.system(size: 13)).scrollContentBackground(.hidden).frame(minHeight: height)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
        }.settingField()
    }
    private func save(extra: String, custom: String, done: String) {
        let values = [extra, custom].map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard values.allSatisfy({ $0.count <= Self.limit }) else { message = "저장하지 못했습니다: 번역 프롬프트는 4000자까지 쓸 수 있습니다."; return }
        store.preferences.promptExtra = values[0]; store.preferences.promptRules = values[1]
        self.extra = values[0]; self.custom = values[1]; message = done
    }
}
