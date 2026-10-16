import Foundation

/// A discovered URL is not yet a playable server. In particular, FlixCloud may
/// expose its encrypted playlist before the key arrives from its bootstrap.
enum PlaybackStreamProbe {
    typealias Fetch = (URL, ResolvedStream, String?) async throws -> Data
    static func validate(_ stream: ResolvedStream, fetch: Fetch = { url, stream, range in
        try await HLSData.fetch(url, stream: stream, range: range, timeout: 8).0
    }) async throws {
        var url = stream.url
        if url.pathExtension.lowercased() != "m3u8" && stream.manifestKey == nil && stream.hlsManifest == nil {
            let bytes = try await fetch(url, stream, "bytes=0-131071")
            guard HLSData.isMedia(bytes) || bytes.starts(with: [0x1a, 0x45, 0xdf, 0xa3]) else { throw SubtitleFiles.failure("영상 서버가 재생 가능한 데이터를 보내지 않았습니다.") }
            return
        }
        var visited = Set<URL>()
        for _ in 0..<4 {
            try Task.checkCancellation()
            guard visited.insert(url).inserted else { throw SubtitleFiles.failure("영상 재생목록이 반복됩니다.") }
            let data = try await fetch(url, stream, nil)
            let text = try HLSData.manifest(data, key: stream.manifestKey)
            let next = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .first { !$0.isEmpty && !$0.hasPrefix("#") }
            guard let next, let target = URL(string: next, relativeTo: url)?.absoluteURL else {
                throw SubtitleFiles.failure("영상 재생목록에 회차 데이터가 없습니다.")
            }
            if text.contains("#EXT-X-STREAM-INF:") { url = target; continue }
            guard text.contains("#EXTINF:") else { throw SubtitleFiles.failure("영상 재생목록을 읽지 못했습니다.") }
            try Task.checkCancellation()
            let bytes = try await fetch(target, stream, "bytes=0-131071")
            guard !bytes.isEmpty else { throw SubtitleFiles.failure("영상 서버가 빈 조각을 보냈습니다.") }
            // Standard HLS AES decryption belongs to the player; image wrappers
            // used by FlixCloud can be checked before selecting a race winner.
            if !text.components(separatedBy: .newlines).contains(where: { $0.hasPrefix("#EXT-X-KEY:") && !$0.contains("METHOD=NONE") }) {
                _ = try HLSData.validatedFragment(bytes)
            }
            return
        }
        throw SubtitleFiles.failure("영상 재생목록 단계가 너무 많습니다.")
    }
}
