import Foundation

enum DownloadStreamSelector {
    static func choose(_ streams: [ResolvedStream], quality: String, preferRaw: Bool = false, preferred: String? = nil) async -> ResolvedStream? {
        try? await MiruroPlayback.choose(streams, preferRaw: preferRaw, preferred: preferred)
    }
}
