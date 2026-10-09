import SwiftUI
import UIKit

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
                        Image(systemName: "gearshape").font(.system(size: 19)).frame(width: 36, height: 36)
                            .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                        VStack(alignment: .leading, spacing: 3) {
                            Text("플레이어 설정").font(.system(size: 15, weight: .bold))
                            Text("재생 · 화질 · 자막").font(.system(size: 11)).foregroundStyle(.white.opacity(0.5))
                        }
                        Spacer()
                        Button(action: close) { Image(systemName: "xmark").frame(width: 44, height: 44) }
                            .buttonStyle(.plain).accessibilityLabel("닫기").keyboardShortcut(.escape, modifiers: [])
                    }.padding(.leading, 16).padding(.trailing, 8).padding(.vertical, 8)
                    Divider().overlay(.white.opacity(0.08))
                    HStack(spacing: 4) {
                        ForEach(tabs.indices, id: \.self) { index in
                            Button { tab = index } label: {
                                Text(tabs[index]).font(.system(size: 13, weight: .semibold))
                                    .frame(maxWidth: .infinity).padding(.vertical, 10)
                                    .background(tab == index ? LilacStyle.accent.opacity(0.2) : .clear, in: RoundedRectangle(cornerRadius: 10))
                            }.buttonStyle(.plain).foregroundStyle(tab == index ? LilacStyle.accent : .white.opacity(0.65))
                                .accessibilityIdentifier("player-settings-tab-\(index)")
                                .accessibilityAddTraits(tab == index ? .isSelected : [])
                        }
                    }.padding(8).accessibilityIdentifier("player-settings-tabs")
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) { content() }.padding(.bottom, 12)
                    }.id(tab).accessibilityIdentifier("player-settings-scroll")
                }.frame(width: min(380, max(0, geometry.size.width - right - max(16, insets.left + 12))))
                    .frame(maxHeight: max(0, geometry.size.height - top - bottom))
                    .background(Color(red: 0.067, green: 0.067, blue: 0.086).opacity(0.98), in: RoundedRectangle(cornerRadius: 22))
                    .overlay(RoundedRectangle(cornerRadius: 22).stroke(.white.opacity(0.12), lineWidth: 1))
                    .shadow(color: .black.opacity(0.65), radius: 24, y: 12)
                    .padding(.top, top).padding(.trailing, right).padding(.bottom, bottom)
                    .accessibilityElement(children: .contain).accessibilityAddTraits(.isModal)
                    .accessibilityIdentifier("player-settings-panel")
            }.frame(width: geometry.size.width, height: geometry.size.height)
        }.foregroundStyle(.white).font(.system(size: 13)).tint(LilacStyle.accent)
            .buttonStyle(.bordered).pickerStyle(.menu).textFieldStyle(.roundedBorder).environment(\.colorScheme, .dark)
    }
}

struct PlayerSettingsGroup<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content
    init(_ title: String, @ViewBuilder content: @escaping () -> Content) { self.title = title; self.content = content }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.system(size: 12, weight: .bold)).foregroundStyle(.white.opacity(0.65))
            content()
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 18).padding(.vertical, 14)
        Divider().overlay(.white.opacity(0.08))
    }
}

struct PlayerChoiceGrid<Value: Hashable>: View {
    let options: [(Value, String)]
    @Binding var selection: Value
    let identifier: String
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 74), spacing: 6)], spacing: 6) {
            ForEach(options.indices, id: \.self) { index in
                let item = options[index]
                Button { selection = item.0 } label: {
                    Text(item.1).font(.system(size: 12, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity, minHeight: 36)
                        .background(selection == item.0 ? LilacStyle.accent.opacity(0.25) : .white.opacity(0.07), in: RoundedRectangle(cornerRadius: 9))
                        .overlay(RoundedRectangle(cornerRadius: 9).stroke(selection == item.0 ? LilacStyle.accent.opacity(0.6) : .white.opacity(0.1), lineWidth: 1))
                }.buttonStyle(.plain).foregroundStyle(selection == item.0 ? LilacStyle.accent : .white.opacity(0.8))
                    .accessibilityIdentifier(identifier + "-" + String(describing: item.0))
                    .accessibilityAddTraits(selection == item.0 ? .isSelected : [])
            }
        }.accessibilityIdentifier(identifier)
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
