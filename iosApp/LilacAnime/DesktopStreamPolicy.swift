import Foundation
import LilacShared

/// A video server in the player (player.js renderVideoServers): a resolved stream or a server page still to open.
struct ServerChoice {
    let label: String
    let kind: String
    var stream: ResolvedStream? = nil
    var server: DesktopPlaybackServer? = nil
    var id: String { stream?.id ?? server?.url ?? label }
    private static let order = ["sub", "soft", "raw", "dub"]
    static func kind(_ label: String, given: String = "") -> String {
        (given.isEmpty ? label.components(separatedBy: "-").first ?? "" : given).trimmingCharacters(in: .whitespaces).lowercased()
    }
    static func all(streams: [ResolvedStream], servers: [DesktopPlaybackServer]) -> [ServerChoice] {
        streams.map { ServerChoice(label: $0.label, kind: kind($0.label), stream: $0) } +
            servers.filter { server in !streams.contains { $0.label == server.label } }.map { ServerChoice(label: $0.label, kind: kind($0.label, given: $0.kind), server: $0) }
    }
    static func kinds(_ choices: [ServerChoice]) -> [String] {
        var seen: [String] = []
        for choice in choices where !seen.contains(choice.kind) { seen.append(choice.kind) }
        return seen.enumerated().sorted { (order.firstIndex(of: $0.element) ?? order.count, $0.offset) < (order.firstIndex(of: $1.element) ?? order.count, $1.offset) }.map(\.element)
    }
    static func title(_ kind: String) -> String {
        ["sub": "SUB · 영어 자막", "soft": "SOFT · 자막 따로", "raw": "RAW · 자막 없음", "dub": "DUB · 영어 더빙"][kind] ?? kind.uppercased()
    }
    static func note(_ kind: String) -> String? {
        ["sub": "영어 자막이 영상에 박혀 있어요. 한국어 자막을 입히면 겹쳐 보여요.", "soft": "자막 없는 영상에 자막을 따로 입혀요. 자막 트랙을 바꾸거나 한국어로 번역할 수 있어요.",
         "raw": "자막이 없는 원본이에요. 한국어 자막을 입혀 보기에 좋아요.", "dub": "영어 더빙이에요."][kind]
    }
    /// "SUB - animepahe animepahe" -> "animepahe".
    static func name(_ label: String) -> String {
        let name = label.replacingOccurrences(of: #"^[A-Za-z]+\s*-\s*"#, with: "", options: .regularExpression)
        let words = name.split(whereSeparator: \.isWhitespace)
        return words.count == 2 && words[0].lowercased() == words[1].lowercased() ? String(words[0]) : name
    }
}

enum DesktopStreamPolicy {
    static func ordered(_ streams: [ResolvedStream], preferRaw: Bool, preferred: String?, workingProvider: String?) -> [ResolvedStream] {
        let providers = ["anikoto", "kickassanime", "icarus", "aniwaves"]
        func rank(_ stream: ResolvedStream) -> Double {
            let kind = stream.label.components(separatedBy: " - ").first?.lowercased() ?? ""
            let provider = stream.label.components(separatedBy: " - ").dropFirst().joined(separator: " - ").components(separatedBy: " ").first ?? ""
            let clean = ["raw", "soft"].contains(kind)
            let english = kind == "soft" && stream.subtitles.contains {
                $0.language.lowercased().hasPrefix("en") && $0.label.range(of: "forced|sign", options: [.regularExpression, .caseInsensitive]) == nil
            }
            let korean = kind == "soft" && stream.subtitles.contains(where: DesktopSubtitlePolicy.isKorean)
            let kindRank: Double = korean ? 0.5 : preferRaw ? (clean ? 1 : kind == "sub" ? 2 : 3) : (english ? 1 : kind == "sub" ? 2 : clean ? 3 : 4)
            return (stream.label == preferred ? 0 : kindRank * 100) + (provider == workingProvider ? 0 : 10) + Double(providers.firstIndex(of: provider) ?? providers.count)
        }
        return streams.enumerated().sorted { rank($0.element) == rank($1.element) ? $0.offset < $1.offset : rank($0.element) < rank($1.element) }.map(\.element)
    }
}
