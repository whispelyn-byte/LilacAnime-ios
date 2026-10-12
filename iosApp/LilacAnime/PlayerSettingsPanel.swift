import SwiftUI
import UIKit

/// The desktop's player settings (player-android.css .player-settings): a dark card at the top right with the badge and
/// title, the three tabs in a pill where the open one is white, and the open tab's settings under them.
struct PlayerSettingsPanel<Content: View>: View {
    @Binding var tab: Int
    let close: () -> Void
    @ViewBuilder let content: () -> Content
    private let tabs = ["재생", "자막", "자막 모양"]
    init(tab: Binding<Int>, close: @escaping () -> Void, @ViewBuilder content: @escaping () -> Content) {
        _tab = tab; self.close = close; self.content = content
    }
    var body: some View {
        GeometryReader { geometry in
            let insets = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.windows.first(where: \.isKeyWindow)?.safeAreaInsets ?? .zero
            let top = max(geometry.size.height < 500 ? 12.0 : 72.0, insets.top + 8)
            let bottom = max(12, insets.bottom + 8)
            let right = max(16, insets.right + 12)
            ZStack(alignment: .topTrailing) {
                Button(action: close) { Color.black.opacity(0.16).contentShape(Rectangle()) }
                    .buttonStyle(.plain).accessibilityLabel("설정 닫기")
                VStack(spacing: 0) {
                    HStack(spacing: 10) {
                        Image(systemName: "gearshape").font(.system(size: 17)).frame(width: 36, height: 36)
                            .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                        VStack(alignment: .leading, spacing: 1) {
                            Text("플레이어 설정").font(.system(size: 15, weight: .bold))
                            Text("재생 · 화질 · 자막").font(.system(size: 11)).foregroundStyle(.white.opacity(0.5))
                        }
                        Spacer()
                        Button(action: close) { Image(systemName: "xmark").font(.system(size: 17)).frame(width: 42, height: 42).contentShape(Circle()) }
                            .buttonStyle(.plain).accessibilityLabel("닫기").keyboardShortcut(.escape, modifiers: [])
                    }.padding(.leading, 16).padding(.trailing, 10).padding(.vertical, 8)
                    PS.line.frame(height: 1)
                    HStack(spacing: 4) {
                        ForEach(tabs.indices, id: \.self) { index in
                            Button { tab = index } label: {
                                Text(tabs[index]).font(.system(size: 13, weight: .semibold))
                                    .frame(maxWidth: .infinity, minHeight: 32)
                                    .background(tab == index ? Color.white : .clear, in: RoundedRectangle(cornerRadius: 9))
                                    .foregroundStyle(tab == index ? Color.black : Color.white.opacity(0.7))
                                    .contentShape(Rectangle())
                            }.buttonStyle(.plain)
                                .accessibilityIdentifier("player-settings-tab-\(index)")
                                .accessibilityAddTraits(tab == index ? .isSelected : [])
                        }
                    }.padding(4).background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 2)
                        .accessibilityElement(children: .contain).accessibilityIdentifier("player-settings-tabs")
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) { content() }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 18).padding(.top, 4).padding(.bottom, 14)
                    }.id(tab).accessibilityIdentifier("player-settings-scroll")
                }.frame(width: min(380, max(0, geometry.size.width - right - max(16, insets.left + 12))))
                    .frame(maxHeight: max(0, geometry.size.height - top - bottom))
                    .background(Color(red: 0.067, green: 0.067, blue: 0.086).opacity(0.97), in: RoundedRectangle(cornerRadius: 22))
                    .overlay(RoundedRectangle(cornerRadius: 22).stroke(.white.opacity(0.1), lineWidth: 1))
                    .shadow(color: .black.opacity(0.7), radius: 30, y: 12)
                    .padding(.top, top).padding(.trailing, right).padding(.bottom, bottom)
                    .accessibilityElement(children: .contain).accessibilityAddTraits(.isModal)
                    .accessibilityIdentifier("player-settings-panel")
            }.frame(width: geometry.size.width, height: geometry.size.height)
        }.foregroundStyle(.white).font(.system(size: 13)).tint(LilacStyle.accent)
            .buttonStyle(PSButtonStyle()).environment(\.colorScheme, .dark)
    }
}

/// The desktop's player-settings pieces (player-android.css .ps-*): the group titles, the notes, the switch rows, the
/// white-on-select chips, the source cards with a line under the name, and the bordered boxes of tracks and files.
enum PS {
    static let note = Color.white.opacity(0.5)
    static let detail = Color.white.opacity(0.55)
    static let line = Color.white.opacity(0.07)
}

/// .ps-group: a group's name, a little above its settings.
struct PSGroup: View {
    let title: String
    init(_ title: String) { self.title = title }
    var body: some View {
        Text(title).font(.system(size: 12, weight: .semibold)).foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 16).padding(.bottom, 4)
    }
}

/// .ps-note: the small grey line that says what a setting does.
struct PSNote: View {
    let text: String
    var color = PS.note
    init(_ text: String, color: Color = PS.note) { self.text = text; self.color = color }
    var body: some View {
        Text(text).font(.system(size: 11)).foregroundStyle(color).lineSpacing(2).fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A section of the 재생 tab (.ps-pane .ps-section): its rows, then a hairline under them.
struct PSSection<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 6) { content() }.padding(.bottom, 12)
            .overlay(alignment: .bottom) { PS.line.frame(height: 1) }
    }
}

/// .ps-switch: the name and what it does on the left, the switch on the right.
struct PSSwitch: View {
    let title: String
    let detail: String
    @Binding var isOn: Bool
    var small = false
    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: small ? 12 : 13, weight: .semibold)).foregroundStyle(.white)
                Text(detail).font(.system(size: small ? 10 : 11)).foregroundStyle(PS.detail).fixedSize(horizontal: false, vertical: true)
            }
        }.toggleStyle(.switch).tint(LilacStyle.accent).padding(.vertical, 5)
    }
}

/// .ps-row: a name with its current value on the left and one action on the right.
struct PSRow<Trailing: View>: View {
    let title: String
    let value: String
    @ViewBuilder let trailing: () -> Trailing
    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 13, weight: .medium))
                Text(value).font(.system(size: 11)).foregroundStyle(PS.detail).lineLimit(1)
            }
            Spacer(minLength: 0)
            trailing()
        }.padding(.vertical, 6)
    }
}

/// .ps-label: a slider's name with its value beside it.
struct PSLabel: View {
    let title: String
    let value: String
    var body: some View {
        HStack(spacing: 6) { Text(title); Text(value).foregroundStyle(.white) }
            .font(.system(size: 11)).foregroundStyle(.white.opacity(0.72)).padding(.top, 10)
    }
}

/// .sheet-button: the wide bordered buttons, two to a row in .sheet-button-row.
struct PSButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13)).multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.85)
            .frame(maxWidth: .infinity, minHeight: 38).padding(.horizontal, 10)
            .background(.white.opacity(configuration.isPressed ? 0.1 : 0.05), in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(.white.opacity(0.13), lineWidth: 1))
            .foregroundStyle(.white).scaleEffect(configuration.isPressed ? 0.97 : 1)
    }
}

/// .ps-row > button: a borderless text button.
struct PSTextButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 12)).padding(.horizontal, 10).frame(minHeight: 32)
            .background(.white.opacity(configuration.isPressed ? 0.1 : 0), in: RoundedRectangle(cornerRadius: 8))
            .foregroundStyle(.white)
    }
}

struct PSButtonRow<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        HStack(alignment: .top, spacing: 6) { content() }.buttonStyle(PSButtonStyle()).padding(.top, 8)
    }
}

/// .sheet-tracks: a bordered box with its name and count on top, a note, then its rows.
struct PSBox<Content: View>: View {
    let title: String
    let state: String
    let note: String
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
                Spacer(minLength: 8)
                Text(state).font(.system(size: 11)).foregroundStyle(PS.detail)
            }
            PSNote(note).padding(.top, 4).padding(.bottom, 8)
            VStack(alignment: .leading, spacing: 6) { content() }
        }.padding(12)
            .background(.white.opacity(0.03), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(.white.opacity(0.1), lineWidth: 1))
            .padding(.top, 14)
    }
}

/// .track-option: a track or file, its name over its format, outlined in lilac when it is the one on screen.
struct PSTrackOption: View {
    let title: String
    let detail: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 13, weight: .semibold)).lineLimit(2)
                    if !detail.isEmpty { Text(detail).font(.system(size: 11)).foregroundStyle(PS.note).lineLimit(1) }
                }
                Spacer(minLength: 0)
                if selected { Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)).foregroundStyle(LilacStyle.accent) }
            }.foregroundStyle(.white).padding(.horizontal, 11).padding(.vertical, 9)
                .background(selected ? LilacStyle.accent.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? LilacStyle.accent : .white.opacity(0.12), lineWidth: 1))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// .ps-chips: small chips that wrap, white with black text when chosen.
struct PlayerChoiceGrid<Value: Hashable>: View {
    let options: [(Value, String)]
    @Binding var selection: Value
    let identifier: String
    var body: some View {
        PSFlow(spacing: 5) {
            ForEach(options.indices, id: \.self) { index in
                let item = options[index]
                let chosen = selection == item.0
                Button { selection = item.0 } label: {
                    Text(item.1).font(.system(size: 12, weight: chosen ? .semibold : .regular)).lineLimit(1)
                        .padding(.horizontal, 10).frame(minHeight: 32)
                        .background(chosen ? Color.white : Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
                        .foregroundStyle(chosen ? Color.black : Color.white)
                        .contentShape(Rectangle())
                }.buttonStyle(.plain)
                    .accessibilityIdentifier(identifier + "-" + String(describing: item.0))
                    .accessibilityAddTraits(chosen ? .isSelected : [])
            }
        }.frame(maxWidth: .infinity, alignment: .leading).accessibilityElement(children: .contain).accessibilityIdentifier(identifier)
    }
}

struct PSSource {
    let id: String
    let title: String
    let detail: String
}

/// .ps-sources: the subtitle sources as cards, the name over where its subtitle comes from.
struct PSSourceGrid: View {
    let options: [PSSource]
    let selection: String
    let choose: (String) -> Void
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 6)], spacing: 6) {
            ForEach(options, id: \.id) { option in
                let chosen = selection == option.id
                Button { choose(option.id) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(option.title).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                        Text(option.detail).font(.system(size: 10)).foregroundStyle(chosen ? Color.black.opacity(0.55) : PS.detail)
                            .lineLimit(1).minimumScaleFactor(0.85)
                    }.frame(maxWidth: .infinity, minHeight: 40, alignment: .leading).padding(.horizontal, 10).padding(.vertical, 5)
                        .background(chosen ? Color.white : Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
                        .foregroundStyle(chosen ? Color.black : Color.white)
                        .contentShape(Rectangle())
                }.buttonStyle(.plain)
                    .accessibilityIdentifier("player-subtitle-source-" + option.id)
                    .accessibilityAddTraits(chosen ? .isSelected : [])
            }
        }.padding(.top, 6).accessibilityElement(children: .contain).accessibilityIdentifier("player-subtitle-source")
    }
}

/// The 자막 타이밍 field (.ps-sync-input): milliseconds, applied on return.
struct PSSyncInput: View {
    let value: Double
    let update: (Double) -> Void
    @State private var input = ""
    @State private var invalid = false
    @FocusState private var focused: Bool
    var body: some View {
        HStack(spacing: 8) {
            TextField("0", text: $input).keyboardType(.numbersAndPunctuation).submitLabel(.done)
                .textInputAutocapitalization(.never).autocorrectionDisabled().focused($focused)
                .font(.system(size: 13)).padding(8).frame(width: 140)
                .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(focused ? LilacStyle.accent : .white.opacity(0.19), lineWidth: focused ? 2 : 1))
                .onSubmit(apply).accessibilityIdentifier("subtitle-sync-input")
            Text("ms").font(.system(size: 12)).foregroundStyle(.white.opacity(0.72))
            if focused {
                Button("적용", action: apply).buttonStyle(PSTextButtonStyle()).foregroundStyle(LilacStyle.accent)
                    .accessibilityIdentifier("subtitle-sync-apply")
            }
        }.padding(.vertical, 8).onAppear { refresh() }.onChange(of: value) { _ in refresh() }
        if invalid { Text("유효한 숫자를 입력하세요.").font(.system(size: 11)).foregroundStyle(.red) }
    }
    private func apply() {
        guard let milliseconds = Double(input.trimmingCharacters(in: .whitespacesAndNewlines)), milliseconds.isFinite else { invalid = true; return }
        update(milliseconds.rounded() / 1000); invalid = false; focused = false
    }
    private func refresh() { if !focused { input = String(format: "%.0f", value * 1000) }; invalid = false }
}

/// Lays its children out in rows, moving to the next row when one is full (CSS flex-wrap).
struct PSFlow: Layout {
    var spacing: CGFloat = 5
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: height)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: y + (row.height - size.height) / 2), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }
    private struct Row { var indices: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }
    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = [], row = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            if !row.indices.isEmpty && row.width + spacing + size.width > width { rows.append(row); row = Row() }
            row.width += (row.indices.isEmpty ? 0 : spacing) + size.width
            row.height = max(row.height, size.height)
            row.indices.append(index)
        }
        if !row.indices.isEmpty { rows.append(row) }
        return rows
    }
}

struct PlayerVideoSurface: View {
    @ObservedObject var engine: MPVEngine
    var body: some View {
        if UIShowcase.enabled {
            GeometryReader { geometry in
                let size = engine.presentation.stageSize(in: geometry.size)
                ZStack {
                    LinearGradient(colors: [Color(red: 0.24, green: 0.15, blue: 0.4), Color(red: 0.08, green: 0.06, blue: 0.15)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    Canvas { context, canvas in
                        context.scaleBy(x: canvas.width / 1280, y: canvas.height / 720)
                        var grid = Path()
                        for x in stride(from: 0, through: 1280, by: 160) { grid.move(to: CGPoint(x: CGFloat(x), y: 0)); grid.addLine(to: CGPoint(x: CGFloat(x), y: 720)) }
                        for y in stride(from: 0, through: 720, by: 120) { grid.move(to: CGPoint(x: 0, y: CGFloat(y))); grid.addLine(to: CGPoint(x: 1280, y: CGFloat(y))) }
                        context.stroke(grid, with: .color(.white.opacity(0.08)), lineWidth: 2)
                        context.stroke(Path(ellipseIn: CGRect(x: 460, y: 180, width: 360, height: 360)), with: .color(.white.opacity(0.12)), lineWidth: 3)
                    }
                    Text("UI PREVIEW · 예시 데이터").font(.caption).foregroundStyle(.white.opacity(0.4))
                }.frame(width: size.width, height: size.height)
                    .accessibilityElement(children: .ignore).accessibilityLabel("예시 영상")
                    .accessibilityIdentifier("preview-video-frame")
                    .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
            }.clipped()
        } else {
            // Keep the Metal drawable at the viewport size; mpv scales/crops video and ASS together.
            MPVPlayerView(engine: engine).frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}
