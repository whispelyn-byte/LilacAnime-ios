import SwiftUI

enum SubtitleLineSpacing {
    static func points(_ percent: Double?) -> Double {
        guard let percent, percent.isFinite, percent > 0 else { return 0 }
        // mpv's line-spacing adds scaled pixels to the 36px font's normal line height.
        return 36 * (min(300, max(80, percent)) / 100 - 1)
    }
}

struct SubtitleLineSpacingControl: View {
    @Binding var value: Double?
    var body: some View {
        VStack(alignment: .leading) {
            HStack {
                Text("자막 줄 간격")
                Spacer()
                Text(value.map { "\(Int($0))%" } ?? "기본").foregroundStyle(.secondary)
                Button("기본값") { value = nil }
            }
            Slider(value: Binding(get: { value ?? 160 }, set: { value = $0 }), in: 80...300, step: 5)
                .accessibilityLabel("자막 줄 간격")
        }
    }
}
