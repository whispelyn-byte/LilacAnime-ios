import SwiftUI
import UIKit

enum PlayerAspect: String, CaseIterable {
    case original, widescreen = "16:9", ultrawide = "21:9", standard = "4:3", fill
    var title: String { switch self { case .original: return "원본"; case .fill: return "화면 채움"; default: return rawValue } }
    var ratio: CGFloat? { switch self { case .widescreen: return 16 / 9; case .ultrawide: return 21 / 9; case .standard: return 4 / 3; default: return nil } }
    func stageSize(in size: CGSize) -> CGSize {
        guard let ratio else { return size }
        let width = min(size.width, size.height * ratio)
        return CGSize(width: width, height: width / ratio)
    }
}

struct SpaceHoldSession {
    let originalSpeed: Double
    var boosted = false
    func restoredSpeed(current: Double) -> Double { boosted && current == 2 ? originalSpeed : current }
}

struct SubtitleSyncInput: View {
    let value: Double
    let update: (Double) -> Void
    @State private var input = ""
    @State private var invalid = false
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TextField("자막 싱크 (ms)", text: $input).keyboardType(.numbersAndPunctuation)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("subtitle-sync-input")
                Text("ms").foregroundStyle(.secondary)
                Button("적용") {
                    guard let milliseconds = Double(input.trimmingCharacters(in: .whitespacesAndNewlines)), milliseconds.isFinite else { invalid = true; return }
                    update(milliseconds.rounded() / 1000); invalid = false
                }.accessibilityIdentifier("subtitle-sync-apply")
            }
            if invalid { Text("유효한 숫자를 입력하세요.").font(.caption).foregroundStyle(.red) }
            Text("양수는 늦게, 음수는 빠르게 표시합니다.").font(.caption).foregroundStyle(.secondary)
        }.onAppear { refresh() }.onChange(of: value) { _ in refresh() }
    }
    private func refresh() { input = String(format: "%.0f", value * 1000); invalid = false }
}

struct SpaceHoldKeyboard: UIViewRepresentable {
    let enabled: Bool
    let began: () -> Void
    let ended: () -> Void
    let cancelled: () -> Void
    func makeUIView(context: Context) -> SpaceHoldKeyView { SpaceHoldKeyView() }
    func updateUIView(_ view: SpaceHoldKeyView, context: Context) {
        view.began = began; view.ended = ended; view.cancelled = cancelled
        view.setEnabled(enabled)
    }
    static func dismantleUIView(_ view: SpaceHoldKeyView, coordinator: ()) { view.cancelled?(); view.resignFirstResponder() }
}

final class SpaceHoldKeyView: UIView {
    var began: (() -> Void)?
    var ended: (() -> Void)?
    var cancelled: (() -> Void)?
    private var enabled = false
    override var canBecomeFirstResponder: Bool { enabled }
    func setEnabled(_ value: Bool) {
        guard enabled != value else { return }; enabled = value
        if value { focus() } else { cancelled?(); resignFirstResponder() }
    }
    override func didMoveToWindow() { super.didMoveToWindow(); if window != nil { focus() } else { cancelled?() } }
    private func focus() { DispatchQueue.main.async { [weak self] in guard let self, self.enabled, self.window != nil else { return }; self.becomeFirstResponder() } }
    private func space(_ press: UIPress) -> Bool { press.key?.keyCode == .keyboardSpacebar && press.key?.modifierFlags.intersection([.command, .control, .alternate]).isEmpty == true }
    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let handled = presses.filter(space)
        if !handled.isEmpty { began?() }
        let remaining = presses.subtracting(handled); if !remaining.isEmpty { super.pressesBegan(remaining, with: event) }
    }
    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let handled = presses.filter(space)
        if !handled.isEmpty { ended?() }
        let remaining = presses.subtracting(handled); if !remaining.isEmpty { super.pressesEnded(remaining, with: event) }
    }
    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) { cancelled?(); super.pressesCancelled(presses, with: event) }
    override func resignFirstResponder() -> Bool { cancelled?(); return super.resignFirstResponder() }
}
