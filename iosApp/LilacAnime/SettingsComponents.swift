import SwiftUI
import UIKit

/// The desktop's settings pages (ott.css .android-settings): a page title with what it covers, then bordered blocks,
/// each a name, its control and a grey note; switches as rows of their own; choices as a row of buttons.
struct SettingsPage<Content: View>: View {
    let title: String
    var intro: String? = nil
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 22, weight: .heavy)).foregroundStyle(LilacStyle.text).padding(.bottom, 10)
            if let intro { SettingNote(intro, size: 13).padding(.bottom, 6) }
            content()
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// .setting-block: a bordered card with the setting's name, its control and what it does.
struct SettingBlock<Content: View>: View {
    let title: String
    var note: String? = nil
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !title.isEmpty { Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(LilacStyle.text) }
            content()
            if let note, !note.isEmpty { SettingNote(note) }
        }.frame(maxWidth: .infinity, alignment: .leading).settingCard()
    }
}

extension SettingBlock where Content == EmptyView {
    init(title: String, note: String? = nil) { self.init(title: title, note: note) { EmptyView() } }
}

/// The grey line under a setting (.setting-block > p).
struct SettingNote: View {
    let text: String
    var size: CGFloat = 12.5
    var color = LilacStyle.muted
    init(_ text: String, size: CGFloat = 12.5, color: Color = LilacStyle.muted) { self.text = text; self.size = size; self.color = color }
    var body: some View {
        Text(text).font(.system(size: size)).foregroundStyle(color).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// .setting-switch: the name and what it does on the left, the switch on the right, in a card of its own.
struct SettingSwitch: View {
    let title: String
    var detail: String? = nil
    @Binding var isOn: Bool
    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 5) {
                Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(LilacStyle.text)
                if let detail { Text(detail).font(.system(size: 12.5)).foregroundStyle(LilacStyle.muted).lineSpacing(3).fixedSize(horizontal: false, vertical: true) }
            }
        }.toggleStyle(.switch).tint(LilacStyle.accent).settingCard()
    }
}

/// .choice-row: the options as buttons, the chosen one filled lilac.
struct SettingChoices<Value: Hashable>: View {
    let options: [(Value, String)]
    @Binding var selection: Value
    var identifier: String? = nil
    var body: some View {
        PSFlow(spacing: 6) {
            ForEach(options.indices, id: \.self) { index in
                let option = options[index]
                let chosen = selection == option.0
                Button { selection = option.0 } label: {
                    Text(option.1).font(.system(size: 13, weight: chosen ? .bold : .semibold)).lineLimit(1)
                        .padding(.horizontal, 14).frame(height: 34)
                        .foregroundStyle(chosen ? LilacStyle.onAccent : LilacStyle.text)
                        .background(chosen ? LilacStyle.accent : LilacStyle.card2, in: RoundedRectangle(cornerRadius: LilacStyle.radius))
                        .overlay(RoundedRectangle(cornerRadius: LilacStyle.radius).stroke(chosen ? LilacStyle.accent : LilacStyle.line))
                        .contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityAddTraits(chosen ? .isSelected : [])
                    .accessibilityIdentifier(identifier.map { $0 + "-" + String(describing: option.0) } ?? option.1)
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// .button-row button: the small bordered buttons; danger ones in red.
struct SettingButtonStyle: ButtonStyle {
    var danger = false
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13, weight: .semibold)).lineLimit(1)
            .padding(.horizontal, 14).frame(minHeight: 34)
            .foregroundStyle(danger ? Color(uiColor: UIColor(rgb: 0xFF7A85)) : LilacStyle.text)
            .background(configuration.isPressed ? LilacStyle.line : LilacStyle.card2, in: RoundedRectangle(cornerRadius: LilacStyle.radius))
            .overlay(RoundedRectangle(cornerRadius: LilacStyle.radius).stroke(danger && configuration.isPressed ? Color(uiColor: UIColor(rgb: 0xFF7A85)) : LilacStyle.line))
            .opacity(enabled ? 1 : 0.45)
    }
}

/// .button-row: buttons side by side, wrapping when they run out of room.
struct SettingButtons<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        PSFlow(spacing: 6) { content() }.buttonStyle(SettingButtonStyle()).frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A name and its value on one line (설치 버전 · 0.4.6).
struct SettingValueRow: View {
    let title: String
    let value: String
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(.system(size: 13)).foregroundStyle(LilacStyle.muted)
            Spacer(minLength: 12)
            Text(value).font(.system(size: 13, weight: .semibold)).foregroundStyle(LilacStyle.text).multilineTextAlignment(.trailing)
        }
    }
}

/// A number set with − and + (the desktop's number inputs), its value beside the name.
struct SettingStepperRow<Value: Strideable & Comparable>: View where Value.Stride: SignedNumeric {
    let title: String
    let value: String
    @Binding var number: Value
    let range: ClosedRange<Value>
    let step: Value.Stride
    var body: some View {
        HStack {
            Text(title).font(.system(size: 13)).foregroundStyle(LilacStyle.text)
            Spacer(minLength: 8)
            Text(value).font(.system(size: 13, weight: .semibold)).monospacedDigit().foregroundStyle(LilacStyle.text)
            Stepper(title, value: $number, in: range, step: step).labelsHidden()
        }
    }
}

/// A row that opens another page, in a card (설정 > 로컬 GGUF 모델 >).
struct SettingLinkLabel: View {
    let title: String
    var detail: String? = nil
    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(LilacStyle.text)
                if let detail, !detail.isEmpty { Text(detail).font(.system(size: 12.5)).foregroundStyle(LilacStyle.muted).fixedSize(horizontal: false, vertical: true) }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(LilacStyle.muted)
        }.frame(maxWidth: .infinity, alignment: .leading).settingCard().contentShape(Rectangle())
    }
}

/// A page opened from the settings: the same blocks on the app's background, its name in the bar.
struct SettingsScreen<Content: View>: View {
    let title: String
    var identifier: String? = nil
    @ViewBuilder let content: () -> Content
    @Environment(\.horizontalSizeClass) private var sizeClass
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) { content() }
                .frame(maxWidth: 860, alignment: .leading).frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, LilacStyle.gutter(sizeClass)).padding(.vertical, 20)
        }.background(LilacStyle.background).accessibilityIdentifier(identifier ?? "settings-screen")
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline).toolbar(.visible, for: .navigationBar)
    }
}

/// The 자막 싱크 field: milliseconds, applied on return, with −250 · 초기화 · +250 under it.
struct SettingSyncInput: View {
    let value: Double
    let update: (Double) -> Void
    @State private var input = ""
    @State private var invalid = false
    @FocusState private var focused: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                TextField("0", text: $input).keyboardType(.numbersAndPunctuation).submitLabel(.done)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().focused($focused)
                    .onSubmit(apply).settingField().frame(width: 140).accessibilityIdentifier("subtitle-sync-input")
                Text("ms").font(.system(size: 13)).foregroundStyle(LilacStyle.muted)
                if focused { Button("적용", action: apply).buttonStyle(SettingButtonStyle()).accessibilityIdentifier("subtitle-sync-apply") }
            }
            if invalid { SettingNote("유효한 숫자를 입력하세요.", color: .red) }
            SettingButtons {
                Button("-250ms") { update(((value - 0.25) * 1000).rounded() / 1000) }
                Button("초기화") { update(0) }
                Button("+250ms") { update(((value + 0.25) * 1000).rounded() / 1000) }
            }
        }.onAppear { show(value) }.onChange(of: value) { show($0) }
    }
    private func apply() {
        guard let milliseconds = Double(input.trimmingCharacters(in: .whitespacesAndNewlines)), milliseconds.isFinite else { invalid = true; return }
        update(milliseconds.rounded() / 1000); invalid = false; focused = false
    }
    /// As PSSyncInput: the new value from onChange, also while the field is being edited.
    private func show(_ seconds: Double) { input = String(format: "%.0f", (seconds * 1000).rounded()); invalid = false }
}

extension View {
    /// The block's frame: panel colour, a hairline border, radius 8.
    func settingCard() -> some View {
        padding(.horizontal, 18).padding(.vertical, 16)
            .background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(LilacStyle.line))
    }
    /// .tmdb-row input: a field on the page colour with a hairline border.
    func settingField() -> some View {
        font(.system(size: 14)).padding(.horizontal, 10).frame(minHeight: 36)
            .background(LilacStyle.background, in: RoundedRectangle(cornerRadius: LilacStyle.radius))
            .overlay(RoundedRectangle(cornerRadius: LilacStyle.radius).stroke(LilacStyle.line))
    }
}

/// #RRGGBB for the subtitle colour settings, as a colour the picker can show and back.
enum HexColor {
    static func color(_ hex: String) -> Color {
        let digits = hex.trimmingCharacters(in: CharacterSet(charactersIn: "# ")).prefix(6)
        guard digits.count == 6, let value = UInt32(digits, radix: 16) else { return .white }
        return Color(uiColor: UIColor(rgb: value))
    }
    static func hex(_ color: Color) -> String {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        UIColor(color).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        let clamp = { (value: CGFloat) in Int((min(max(value, 0), 1) * 255).rounded()) }
        return String(format: "#%02X%02X%02X", clamp(red), clamp(green), clamp(blue))
    }
}
