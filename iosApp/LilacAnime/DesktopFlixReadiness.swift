import Foundation

/// Matches desktop resolveStreamPage's FlixCloud collection and key deadlines.
/// Readiness is based on captured URLs and the player key, without fetching media.
struct DesktopFlixReadiness {
    private var firstSeen: TimeInterval?
    private var stableSince: TimeInterval = 0
    private var count = -1
    private var keyDeadline: TimeInterval?

    mutating func select(_ streams: [ResolvedStream], now: TimeInterval) throws -> ResolvedStream? {
        guard !streams.isEmpty else { return nil }
        if firstSeen == nil { firstSeen = now }
        if count != streams.count { count = streams.count; stableSince = now }
        let key = streams.compactMap(\.manifestKey).first { !$0.isEmpty }
        let master = streams.first { $0.url.path.lowercased().contains("/master") }
        let video = streams.first { Self.track($0.url, name: "video") }
        let audio = streams.first { Self.track($0.url, name: "audio") }
        let complete = master != nil || (video != nil && audio != nil)
        if keyDeadline == nil {
            guard (key != nil && (complete || now - stableSince >= 1.5)) || now - firstSeen! >= 10 else { return nil }
            keyDeadline = now + 15
        }
        guard let key else {
            if now >= keyDeadline! { throw SubtitleFiles.failure("FlixCloud 복호화 키를 찾지 못했습니다.") }
            return nil
        }
        var selected = master ?? video ?? streams.first { !Self.track($0.url, name: "audio") } ?? streams[0]
        selected.manifestKey = key
        // Desktop createFlixAvProxyUrl combines separate video/audio playlists.
        // Use the existing iOS in-memory manifest and proxy for the same result.
        if let video, let audio {
            selected = video
            selected.manifestKey = key
            selected.url = video.url.appendingPathComponent("__lilac_av_master.m3u8")
            selected.hlsManifest = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Default\",DEFAULT=YES,AUTOSELECT=YES,URI=\"\(audio.url.absoluteString)\"\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,AUDIO=\"audio\"\n\(video.url.absoluteString)\n"
        }
        return selected
    }

    private static func track(_ url: URL, name: String) -> Bool {
        url.path.range(of: "/" + name + "(?:/|\\.|$)", options: [.regularExpression, .caseInsensitive]) != nil
    }
}
