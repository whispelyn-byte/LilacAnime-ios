import Foundation
import LilacShared

@MainActor
final class DesktopSubtitlePreparer {
    private let service = IosServices()
    private let titleLookup = TitleLookup()
    func prepare(_ item: PlaybackItem, tracks: [RemoteSubtitle], preferences: AppPreferences) async throws -> (URL, String)? {
        if let saved = EpisodeSubtitleStore.shared.list(item).first(where: { !$0.translated }), let file = saved.file { return (file, saved.provider) }
        func select(_ files: [URL]) -> URL? {
            files.first { SubtitleEpisodeMatcher.shared.matches(name: $0.lastPathComponent, episodeNumber: Int32(item.number), expectedSeason: nil) } ?? (files.count == 1 ? files.first : nil)
        }
        for track in tracks where track.language.lowercased().hasPrefix("ko") || track.label.contains("한국") || track.label.lowercased().contains("korean") {
            try Task.checkCancellation()
            if let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]), let file = select(files) { return (file, "한국어 트랙") }
        }
        guard preferences.subtitleProvider != "manual", item.anime.source != "local" else { return nil }
        await DesktopCatalog.shared.enrich(item.anime.anime, source: item.anime.source)
        let metadata = DesktopCatalog.shared.record(item.anime)
        var title = metadata?.korean.isEmpty == false ? metadata!.korean : item.anime.title
        if !TitleCandidates.shared.isKorean(title: title), let found = await titleLookup.resolve(title, aliases: [item.anime.anime.native, item.anime.anime.romaji, item.anime.anime.english]) { title = found }
        var providers = ["kairan", "csora", "anissia"]
        if let preferred = preferences.subtitleProvider, let index = providers.firstIndex(of: preferred) { providers.remove(at: index); providers.insert(preferred, at: 0) }
        if preferences.autoTranslation { providers.append("jimaku") }
        for provider in providers {
            try Task.checkCancellation()
            let results: [SubtitleAsset] = await withCheckedContinuation { continuation in
                service.findSubtitles(provider: provider, title: title, episode: Int32(item.number), episodeKey: item.displayNumber,
                    anilistId: item.anime.anime.anilistId?.int32Value ?? Int32(metadata?.anilist ?? 0)) { values, _ in continuation.resume(returning: values ?? []) }
            }
            for asset in results where asset.source != "post" && (provider != "jimaku" || asset.score > 0) {
                try Task.checkCancellation()
                guard let url = URL(string: asset.url), let files = try? await SubtitleFiles.prepare(url), let file = select(files) else { continue }
                return (file, provider)
            }
        }
        if preferences.autoTranslation {
            let ordered = tracks.sorted { lhs, rhs in
                func rank(_ track: RemoteSubtitle) -> Int { track.language.lowercased().hasPrefix("ja") ? 0 : track.language.lowercased().hasPrefix("en") ? 1 : 2 }
                return rank(lhs) < rank(rhs)
            }
            for track in ordered {
                try Task.checkCancellation()
                if let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]), let file = select(files) { return (file, track.label) }
            }
        }
        return nil
    }
    deinit { service.close() }
}
