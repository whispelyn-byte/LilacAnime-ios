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

    private static func meaningful(_ value: String) -> Bool {
        value.unicodeScalars.contains { CharacterSet.alphanumerics.contains($0) }
    }
    private static func containsEastAsian(_ value: String) -> Bool {
        value.unicodeScalars.contains { scalar in
            (0x3040...0x30ff).contains(scalar.value) || (0x3400...0x9fff).contains(scalar.value) || (0xac00...0xd7af).contains(scalar.value)
        }
    }
}
