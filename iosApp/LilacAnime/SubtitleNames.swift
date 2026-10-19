import Foundation

enum SubtitleNames {
    // Headers and saved names can mix Latin-1 bytes, Windows-1252 punctuation
    // and already-correct Korean. Decode byte sequences locally, never the whole
    // filename through one legacy encoding. Keep changes only when Asian text
    // is recovered; genuine accented filenames are left alone.
    static func readable(_ name: String) -> String {
        // Apple file URLs can decompose í into i + a combining acute accent.
        // Recompose before interpreting legacy byte values, including between
        // passes when a name was misdecoded more than once.
        var candidate = name.precomposedStringWithCanonicalMapping, result = name
        let originalCount = eastAsianCount(candidate)
        for _ in 0..<3 {
            let decoded = repairBytes(candidate).precomposedStringWithCanonicalMapping
            if decoded == candidate { break }
            if eastAsianCount(decoded) > originalCount { result = decoded }
            candidate = decoded
        }
        return result
    }

    // Windows-1252's printable substitutes for C1 bytes. Some responses use
    // these and literal C1 characters together, which String.data(using:) rejects.
    private static let windowsBytes: [UInt32: UInt8] = [
        0x20ac: 0x80, 0x201a: 0x82, 0x0192: 0x83, 0x201e: 0x84,
        0x2026: 0x85, 0x2020: 0x86, 0x2021: 0x87, 0x02c6: 0x88,
        0x2030: 0x89, 0x0160: 0x8a, 0x2039: 0x8b, 0x0152: 0x8c,
        0x017d: 0x8e, 0x2018: 0x91, 0x2019: 0x92, 0x201c: 0x93,
        0x201d: 0x94, 0x2022: 0x95, 0x2013: 0x96, 0x2014: 0x97,
        0x02dc: 0x98, 0x2122: 0x99, 0x0161: 0x9a, 0x203a: 0x9b,
        0x0153: 0x9c, 0x017e: 0x9e, 0x0178: 0x9f
    ]
    private static func repairBytes(_ name: String) -> String {
        let scalars = Array(name.unicodeScalars)
        func byte(_ scalar: Unicode.Scalar) -> UInt8? {
            scalar.value <= 0xff ? UInt8(scalar.value) : windowsBytes[scalar.value]
        }
        var output = "", index = 0
        while index < scalars.count {
            let first = byte(scalars[index]) ?? 0
            let count = (0xc2...0xdf).contains(first) ? 2 : (0xe0...0xef).contains(first) ? 3 : (0xf0...0xf4).contains(first) ? 4 : 0
            if count > 0, index + count <= scalars.count {
                let bytes = scalars[index..<(index + count)].compactMap(byte)
                if bytes.count == count, bytes.dropFirst().allSatisfy({ (0x80...0xbf).contains($0) }),
                   let decoded = String(data: Data(bytes), encoding: .utf8) {
                    output += decoded; index += count; continue
                }
            }
            output.unicodeScalars.append(scalars[index]); index += 1
        }
        return output
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
        if displayable(value) { return value }
        if let file = URL(string: url)?.lastPathComponent, displayable(readable(file)),
           ["ass", "ssa", "srt", "smi", "vtt", "zip", "7z", "rar", "sub", "ttml"].contains(URL(fileURLWithPath: file).pathExtension.lowercased()) {
            return readable(file)
        }
        return fallback
    }

    static func meaningful(_ value: String) -> Bool {
        value.unicodeScalars.contains { CharacterSet.alphanumerics.contains($0) }
    }
    private static func displayable(_ value: String) -> Bool {
        meaningful(value) && !value.unicodeScalars.contains { $0.value == 0xfffd || (0x80...0x9f).contains($0.value) }
    }
    private static func eastAsianCount(_ value: String) -> Int {
        value.unicodeScalars.filter { scalar in
            (0x3040...0x30ff).contains(scalar.value) || (0x3400...0x9fff).contains(scalar.value) || (0xac00...0xd7af).contains(scalar.value)
        }.count
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
        ["gemini": "Gemini", "openai": "OpenAI", "deepl": "DeepL", "qwen": "Qwen", "claude": "Claude", "local": "로컬 AI"][value] ?? "AI"
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
            + (SubtitleAutoSync.isSynced(URL(fileURLWithPath: record.name)) ? SubtitleAutoSync.label : "")
    }
}
