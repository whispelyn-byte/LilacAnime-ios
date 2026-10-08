import Foundation
import LilacShared

@MainActor
final class DesktopSubtitlePreparer {
    private let service = IosServices()
    private let titleLookup = TitleLookup()
    func prepare(_ item: PlaybackItem, tracks: [RemoteSubtitle], preferences: AppPreferences) async throws -> (URL, String)? {
        if let saved = EpisodeSubtitleStore.shared.list(item).first(where: { !$0.translated }), let file = saved.file { return (file, saved.provider) }
        var offsets: [Int] = []
        func select(_ files: [URL]) -> URL? {
            let wanted = [item.number] + offsets.map { item.number + $0 }
            return files.first { file in
                let parsed = SubtitleEpisodeMatcher.shared.parse(name: file.lastPathComponent)
                if let season = parsed?.season, season.intValue != Int(DesktopTitleRules.shared.season(title: item.anime.title)) { return false }
                if parsed?.episode == nil && files.count == 1 { return true }
                return wanted.contains { SubtitleEpisodeMatcher.shared.matches(name: file.lastPathComponent, episodeNumber: Int32($0), expectedSeason: nil) }
            }
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
        let offsetValues: [KotlinInt] = await withCheckedContinuation { continuation in
            service.episodeOffsets(anilistId: item.anime.anime.anilistId?.int32Value ?? Int32(metadata?.anilist ?? 0), title: title) { values, _ in continuation.resume(returning: values ?? []) }
        }
        offsets = offsetValues.map { $0.intValue }
        var providers = ["kairan", "csora", "anissia"]
        if let preferred = preferences.subtitleProvider, let index = providers.firstIndex(of: preferred) { providers.remove(at: index); providers.insert(preferred, at: 0) }
        if preferences.autoTranslation { providers.append("jimaku") }
        for provider in providers {
            try Task.checkCancellation()
            let results: [SubtitleAsset] = await withCheckedContinuation { continuation in
                service.findSubtitles(provider: provider, title: title, episode: Int32(item.number), episodeKey: item.displayNumber,
                    anilistId: item.anime.anime.anilistId?.int32Value ?? Int32(metadata?.anilist ?? 0)) { values, _ in continuation.resume(returning: values ?? []) }
            }
            let previous = EpisodeSubtitleStore.shared.records.filter { $0.episodeKey.hasPrefix(item.anime.id + "#") && !$0.translated && $0.file != nil }.sorted { $0.date > $1.date }.first?.name ?? ""
            let ordered = provider == "jimaku" ? JimakuRules.shared.rank(files: results, title: title, episode: Int32(item.number), preferred: previous) : results
            for asset in ordered where asset.source != "post" && (provider != "jimaku" || asset.score > 0) {
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
