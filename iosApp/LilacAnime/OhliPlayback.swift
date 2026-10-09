import Foundation

/// Ohli's player starts an advertising MP4 before requesting its episode master.txt.
/// Only candidates read from the selected player and checked as episode media may escape the resolver.
enum OhliPlayback {
    static func variants(_ text: String, base: URL) -> [URL] {
        let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }
        var values: [(URL, Int)] = []
        for index in lines.indices where lines[index].hasPrefix("#EXT-X-STREAM-INF:") {
            guard let next = lines.dropFirst(index + 1).first(where: { !$0.isEmpty && !$0.hasPrefix("#") }),
                  let url = URL(string: next, relativeTo: base)?.absoluteURL,
                  ["http", "https"].contains(url.scheme ?? "") else { continue }
            let bandwidth = lines[index].range(of: "(?:^|[:,])BANDWIDTH=[0-9]+", options: .regularExpression)
                .map { Int(lines[index][$0].split(separator: "=").last ?? "0") ?? 0 } ?? 0
            values.append((url, bandwidth))
        }
        return values.sorted { $0.1 > $1.1 }.map(\.0)
    }
    static func isEpisodePlaylist(_ text: String) -> Bool {
        guard text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("#EXTM3U"),
              !text.contains("#EXT-X-STREAM-INF:") else { return false }
        let seconds = text.components(separatedBy: .newlines).reduce(0.0) { sum, line in
            guard line.hasPrefix("#EXTINF:"), let duration = Double(line.dropFirst(8).split(separator: ",").first ?? ""), duration.isFinite, duration > 0 else { return sum }
            return sum + duration
        }
        return seconds > 30
    }
    static func isPlayerFrame(_ frame: URL?, selected: URL?) -> Bool {
        guard let frame, let selected else { return false }
        return frame.scheme == selected.scheme && frame.host == selected.host && frame.path == selected.path
    }
    static func accepts(kind: String, frame: URL?, selected: URL?) -> Bool {
        ["ohliVariant", "ohliMedia"].contains(kind) && isPlayerFrame(frame, selected: selected)
    }
}
