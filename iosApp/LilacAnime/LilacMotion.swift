import SwiftUI

/// Desktop motion.css: 140 / 200 / 280 ms, cubic-bezier(.2, .8, .2, 1).
enum LilacMotion {
    static func animation(reduced: Bool, duration: Double = 0.2) -> Animation? {
        reduced ? nil : .timingCurve(0.2, 0.8, 0.2, 1, duration: duration)
    }
}

struct LilacPressFeedback: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduced
    let pressed: Bool
    func body(content: Content) -> some View {
        content.scaleEffect(pressed && !reduced ? 0.97 : 1)
            .animation(LilacMotion.animation(reduced: reduced, duration: 0.14), value: pressed)
    }
}

struct LilacPressButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.modifier(LilacPressFeedback(pressed: configuration.isPressed))
    }
}

/// Enter only: outgoing pages disappear immediately, so their controls cannot intercept the new page.
struct LilacAppearMotion: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduced
    @State private var appeared = false
    let x: CGFloat
    let y: CGFloat
    init(x: CGFloat = 0, y: CGFloat = 8) { self.x = x; self.y = y }
    func body(content: Content) -> some View {
        content.opacity(appeared || reduced ? 1 : 0)
            .offset(x: appeared || reduced ? 0 : x, y: appeared || reduced ? 0 : y)
            .onAppear { withAnimation(LilacMotion.animation(reduced: reduced, duration: 0.28)) { appeared = true } }
    }
}
