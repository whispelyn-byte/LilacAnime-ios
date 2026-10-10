import Foundation

enum SubtitleNames {
    // HTTP header strings may expose UTF-8 bytes as Latin-1. Repair only a
    // reversible conversion to East Asian text; ordinary accented names stay intact.
    static func readable(_ name: String) -> String {
        guard !containsEastAsian(name) else { return name }
        for encoding in [String.Encoding.isoLatin1, .windowsCP1252] {
            if let bytes = name.data(using: encoding, allowLossyConversion: false),
               let decoded = String(data: bytes, encoding: .utf8), containsEastAsian(decoded) {
                return decoded
            }
        }
        return name
    }

    static func downloadFilename(_ disposition: String?, fallback: String) -> String {
        guard let disposition else { return readable(fallback) }
        let pattern = #"(?:^|;)\s*(filename\*?)\s*=\s*(?:"((?:\\.|[^"])*)"|([^;]*))"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return readable(fallback) }
        let source = disposition as NSString
        var ordinary: String?
        for match in regex.matches(in: disposition, range: NSRange(location: 0, length: source.length)) {
            let key = source.substring(with: match.range(at: 1)).lowercased()
            let quoted = match.range(at: 2)
            let value = (quoted.location != NSNotFound ? source.substring(with: quoted)
                .replacingOccurrences(of: #"\\(.)"#, with: "$1", options: .regularExpression) : source.substring(with: match.range(at: 3)))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if key == "filename*" {
                let parts = value.split(separator: "'", maxSplits: 2, omittingEmptySubsequences: false)
                if parts.count == 3, ["utf-8", "utf8"].contains(parts[0].lowercased()),
                   let decoded = String(parts[2]).removingPercentEncoding, !decoded.isEmpty { return readable(decoded) }
            } else if !value.isEmpty { ordinary = readable(value) }
        }
        return ordinary ?? readable(fallback)
    }

    static func label(_ name: String, url: String = "", fallback: String = "자막 파일") -> String {
        let value = readable(name).trimmingCharacters(in: .whitespacesAndNewlines)
        if meaningful(value) { return value }
        if let file = URL(string: url)?.lastPathComponent, meaningful(file),
           ["ass", "ssa", "srt", "smi", "vtt", "zip", "7z", "rar", "sub", "ttml"].contains(URL(fileURLWithPath: file).pathExtension.lowercased()) {
            return readable(file)
        }
        return fallback
    }

    static func meaningful(_ value: String) -> Bool {
        value.unicodeScalars.contains { CharacterSet.alphanumerics.contains($0) }
    }
    private static func containsEastAsian(_ value: String) -> Bool {
        value.unicodeScalars.contains { scalar in
            (0x3040...0x30ff).contains(scalar.value) || (0x3400...0x9fff).contains(scalar.value) || (0xac00...0xd7af).contains(scalar.value)
        }
    }
}

/// The site's subtitle tracks as app.js renderSubtitleTracks lists them: Korean, English and Japanese first, and a label
/// like "Chinese (Chinese (Han, Simplified) - Full Subtitles)" as the language with the rest underneath.
enum SiteTrackOrder {
    static func sorted(_ tracks: [RemoteSubtitle]) -> [RemoteSubtitle] {
        func rank(_ track: RemoteSubtitle) -> Int {
            let text = track.label + " " + track.language
            if DesktopSubtitlePolicy.isKorean(track) { return 0 }
            if text.range(of: "english|(?:^|\\s)en(?:[-_]|$)", options: [.regularExpression, .caseInsensitive]) != nil { return 1 }
            if text.range(of: "japanese|(?:^|\\s)ja(?:[-_]|$)", options: [.regularExpression, .caseInsensitive]) != nil { return 2 }
            return 3
        }
        return tracks.enumerated().sorted { rank($0.element) == rank($1.element) ? $0.offset < $1.offset : rank($0.element) < rank($1.element) }.map(\.element)
    }
    private static func parts(_ track: RemoteSubtitle) -> (String, String) {
        guard let match = track.label.range(of: #"^(.*?)\s*\((.*)\)$"#, options: .regularExpression) else { return (track.label, "") }
        let label = String(track.label[match])
        guard let open = label.firstIndex(of: "(") else { return (track.label, "") }
        let name = label[..<open].trimmingCharacters(in: .whitespaces)
        var detail = String(label[label.index(after: open)..<label.index(before: label.endIndex)])
        if let range = detail.range(of: name) { detail.removeSubrange(range) }
        detail = detail.replacingOccurrences(of: #"^[\s-]+"#, with: "", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        return (name.isEmpty ? track.label : name, detail)
    }
    static func name(_ track: RemoteSubtitle) -> String { parts(track).0 }
    static func detail(_ track: RemoteSubtitle) -> String { parts(track).1 }
}

/// Subtitle names as the desktop shows them (app.js SUBTITLE_SOURCE_LABELS, communityLabel, translatedLabel):
/// by where they came from, not by file name; the user's own files keep their name.
enum SubtitleLabels {
    static func provider(_ value: String) -> String {
        ["linkkf": "Linkkf", "reanime": "사이트", "kairan": "Kairan", "csora": "Csora", "anissia": "Anissia", "jimaku": "Jimaku",
         "provider": "제공", "download": "다운로드", "user": "내"][value] ?? value
    }
    static func engine(_ value: String) -> String {
        ["gemini": "Gemini", "openai": "OpenAI", "deepl": "DeepL", "qwen": "Qwen", "local": "로컬 AI"][value] ?? "AI"
    }
    static func label(provider: String, translated: Bool, name: String, maker: String? = nil) -> String {
        if translated { return engine(provider) + " 번역" }
        switch provider {
        case "user": return SubtitleNames.label(name)
        case "anissia": return "Anissia" + (maker.map { " · " + $0 } ?? "") + " 자막"
        case "reanime": return "사이트 자막 트랙 · " + SubtitleNames.label(name)
        default: return self.provider(provider) + " 자막"
        }
    }
    static func label(_ record: SavedSubtitle, maker: String? = nil) -> String {
        label(provider: record.provider, translated: record.translated, name: record.name, maker: maker)
    }
}
