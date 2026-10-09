import Foundation

enum DesktopStreamPolicy {
    static func ordered(_ streams: [ResolvedStream], preferRaw: Bool, preferred: String?, workingProvider: String?) -> [ResolvedStream] {
        let providers = ["anikoto", "kickassanime", "icarus", "aniwaves"]
        func rank(_ stream: ResolvedStream) -> Double {
            let kind = stream.label.components(separatedBy: " - ").first?.lowercased() ?? ""
            let provider = stream.label.components(separatedBy: " - ").dropFirst().joined(separator: " - ").components(separatedBy: " ").first ?? ""
            let clean = ["raw", "soft"].contains(kind)
            let english = kind == "soft" && stream.subtitles.contains { $0.language.lowercased().hasPrefix("en") || $0.label.localizedCaseInsensitiveContains("english") }
            let korean = kind == "soft" && stream.subtitles.contains(where: DesktopSubtitlePolicy.isKorean)
            let kindRank: Double = korean ? 0.5 : preferRaw ? (clean ? 1 : kind == "sub" ? 2 : 3) : (english ? 1 : kind == "sub" ? 2 : clean ? 3 : 4)
            return (stream.label == preferred ? 0 : kindRank * 100) + (provider == workingProvider ? 0 : 10) + Double(providers.firstIndex(of: provider) ?? providers.count)
        }
        return streams.enumerated().sorted { rank($0.element) == rank($1.element) ? $0.offset < $1.offset : rank($0.element) < rank($1.element) }.map(\.element)
    }
}
