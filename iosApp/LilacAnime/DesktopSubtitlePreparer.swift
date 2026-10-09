import Foundation
import LilacShared

@MainActor
final class DesktopSubtitlePreparer {
    private let service = IosServices()
    private let titleLookup = TitleLookup()
    private var searches: [String: Task<(URL, String)?, Never>] = [:]
    private var contexts: [String: (String, Int32, [Int])] = [:]

    func preferRaw(_ item: PlaybackItem, preferences: AppPreferences, downloading: Bool = false) async -> Bool {
        guard ["miruro", "animenosub"].contains(item.anime.source) else { return false }
        let known = UserDefaults.standard.stringArray(forKey: "desktop.korean-series") ?? []
        if known.contains(item.anime.id) || EpisodeSubtitleStore.shared.list(item).contains(where: { !["provider", "reanime"].contains($0.provider) }) { return true }
        var finished = false
        let task = Task { @MainActor in
            defer { finished = true }
            let found = try? await self.community(item, preferences: preferences)
            if found != nil {
                var known = UserDefaults.standard.stringArray(forKey: "desktop.korean-series") ?? []
                known.removeAll { $0 == item.anime.id }; known.append(item.anime.id)
                UserDefaults.standard.set(Array(known.suffix(300)), forKey: "desktop.korean-series")
            }
            return found != nil
        }
        if downloading {
            let found = await task.value
            if found { return true }
            return (try? await translationSource(item, tracks: [], preferences: preferences)) != nil
        }
        let deadline = Date().addingTimeInterval(8)
        while !finished && Date() < deadline && !Task.isCancelled { try? await Task.sleep(nanoseconds: 100_000_000) }
        if finished { return await task.value }
        return false
    }

    func prepare(_ item: PlaybackItem, tracks: [RemoteSubtitle], preferences: AppPreferences, prefersAI: Bool = false, skipSaved: Bool = false) async throws -> (URL, String)? {
        guard preferences.subtitleProvider != "manual", !DesktopSubtitlePolicy.burnedKorean(source: item.anime.source) else { return nil }
        let saved = skipSaved ? [] : EpisodeSubtitleStore.shared.list(item)
        if prefersAI {
            if let translated = saved.first(where: \.translated), let file = translated.file { return (file, translated.provider) }
            if let result = try await translationSource(item, tracks: tracks, preferences: preferences) { return result }
        }
        if let preferred = DesktopSubtitlePolicy.preferredSaved(saved, preferred: preferences.subtitleProvider), let file = preferred.file { return (file, preferred.provider) }
        for track in tracks where DesktopSubtitlePolicy.isKorean(track) {
            try Task.checkCancellation()
            if let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]), let file = select(files, item: item, offsets: []) { return (file, "reanime") }
        }
        if let first = saved.first, let file = first.file { return (file, first.provider) }
        if let local = item.localSubtitles.first(where: SubtitleFiles.isKorean) ?? item.localSubtitles.first { return (local, "download") }
        guard item.anime.source != "local" && item.anime.source != "linkkf" else { return nil }
        if let found = try await community(item, preferences: preferences) { return found }
        return try await translationSource(item, tracks: tracks, preferences: preferences)
    }

    /// Start all searches together, but consume in preference order.
    func community(_ item: PlaybackItem, preferences: AppPreferences) async throws -> (URL, String)? {
        let context = await context(item)
        let pending = DesktopSubtitlePolicy.providers(preferences.subtitleProvider).map { provider -> Task<(URL, String)?, Never> in
            let key = item.anime.id + "#" + item.episodeID + "#" + context.0 + "#" + provider
            if let previous = searches[key] { return previous }
            let task = Task { [weak self] () -> (URL, String)? in
                guard let self else { return nil }
                let assets = await self.find(provider, item: item, context: context)
                var candidates: [URL] = []
                for asset in assets where asset.source != "post" {
                    if Task.isCancelled { return nil }
                    if let url = URL(string: asset.url), let files = try? await SubtitleFiles.prepare(url) { candidates += files }
                }
                if let first = assets.first(where: { $0.source != "post" }), let file = self.select(candidates, item: item, offsets: context.2, episode: first.episode?.intValue, strict: first.strict) { return (file, provider) }
                if provider == "anissia" {
                    for asset in assets where asset.source == "post" {
                        if Task.isCancelled { return nil }
                        if let url = URL(string: asset.url), let file = try? await WinPNGReader.subtitle(url, episode: item.number, matched: true) { return (file, provider) }
                    }
                }
                return nil
            }
            searches[key] = task
            return task
        }
        for task in pending {
            let result = await task.value
            try Task.checkCancellation()
            if let result { return result }
        }
        return nil
    }

    func translationSource(_ item: PlaybackItem, tracks: [RemoteSubtitle], preferences: AppPreferences, requireAutomatic: Bool = true) async throws -> (URL, String)? {
        guard !requireAutomatic || preferences.translationPreferences(automatic: true) != nil else { return nil }
        if let saved = EpisodeSubtitleStore.shared.list(item).first(where: { !$0.translated && $0.provider == "jimaku" }), let file = saved.file { return (file, "jimaku") }
        if item.anime.source != "local" {
            let context = await context(item)
            let files = await find("jimaku", item: item, context: context)
            let previous = EpisodeSubtitleStore.shared.records.filter { $0.episodeKey.hasPrefix(item.anime.id + "#") && $0.provider == "jimaku" && !$0.translated && $0.file != nil }.max { $0.date < $1.date }?.name ?? ""
            for asset in JimakuRules.shared.rank(files: files, title: context.0, episode: Int32(item.number), preferred: previous) where asset.source != "post" && asset.score > 0 {
                try Task.checkCancellation()
                if let url = URL(string: asset.url), let files = try? await SubtitleFiles.prepare(url), let file = select(files, item: item, offsets: context.2) { return (file, "jimaku") }
            }
        }
        if let track = DesktopSubtitlePolicy.translationTrack(tracks), let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]), let file = select(files, item: item, offsets: []) { return (file, "reanime") }
        return nil
    }

    private func context(_ item: PlaybackItem) async -> (String, Int32, [Int]) {
        if let previous = contexts[item.anime.id] { return previous }
        await DesktopCatalog.shared.enrich(item.anime.anime, source: item.anime.source)
        let metadata = DesktopCatalog.shared.record(item.anime)
        var title = metadata?.korean.isEmpty == false ? metadata!.korean : item.anime.title
        if !TitleCandidates.shared.isKorean(title: title), let found = await titleLookup.resolve(title, aliases: [item.anime.anime.native, item.anime.anime.romaji, item.anime.anime.english]) { title = found }
        let anilist = item.anime.anime.anilistId?.int32Value ?? Int32(metadata?.anilist ?? 0)
        let values: [KotlinInt] = await withCheckedContinuation { continuation in
            service.episodeOffsets(anilistId: anilist, title: title) { values, _ in continuation.resume(returning: values ?? []) }
        }
        let result = (title, anilist, values.map(\.intValue))
        contexts[item.anime.id] = result
        return result
    }
    private func find(_ provider: String, item: PlaybackItem, context: (String, Int32, [Int])) async -> [SubtitleAsset] {
        await withCheckedContinuation { continuation in
            service.findSubtitles(provider: provider, title: context.0, episode: Int32(item.number), episodeKey: item.displayNumber, anilistId: context.1) { values, _ in continuation.resume(returning: values ?? []) }
        }
    }
    private func select(_ files: [URL], item: PlaybackItem, offsets: [Int], episode: Int? = nil, strict: Bool = false) -> URL? {
        let wanted = [episode ?? item.number] + offsets.map { item.number + $0 }
        let compatible = files.filter { file in
            guard let season = SubtitleEpisodeMatcher.shared.parse(name: file.lastPathComponent)?.season else { return true }
            return season.intValue == Int(DesktopTitleRules.shared.season(title: item.anime.title))
        }
        let matching = compatible.first { file in
            let parsed = SubtitleEpisodeMatcher.shared.parse(name: file.lastPathComponent)
            if let season = parsed?.season, season.intValue != Int(DesktopTitleRules.shared.season(title: item.anime.title)) { return false }
            if parsed?.episode == nil && files.count == 1 && !strict { return true }
            return wanted.contains { SubtitleEpisodeMatcher.shared.matches(name: file.lastPathComponent, episodeNumber: Int32($0), expectedSeason: nil) }
        }
        if let matching { return matching }
        if !strict { return compatible.first }
        if item.number == 1 && !compatible.contains(where: { SubtitleEpisodeMatcher.shared.matches(name: $0.lastPathComponent, episodeNumber: 2, expectedSeason: nil) }) {
            return compatible.max { ((try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) < ((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        }
        return nil
    }
    func cancel() { searches.values.forEach { $0.cancel() }; searches.removeAll(); service.cancel(); titleLookup.cancel() }
    deinit { service.close() }
}
