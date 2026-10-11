import Foundation

enum SubtitleAppearance {
    // Desktop's macOS 기본체 uses AppleSDGothicNeo.ttc; use that family on iOS too.
    static let defaultFont = "Apple SD Gothic Neo"
    static func font(_ selected: String) -> String { selected.isEmpty ? defaultFont : selected }
    static func assOverride(extension ext: String?, translated: Bool, effects: Bool, keepStyle: Bool) -> String {
        if translated { return "strip" }
        if ["ass", "ssa"].contains(ext?.lowercased() ?? ""), effects { return "no" }
        return keepStyle ? "yes" : "strip"
    }
}
