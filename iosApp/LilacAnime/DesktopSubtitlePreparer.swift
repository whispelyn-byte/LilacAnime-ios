import Foundation
import LilacShared

@MainActor
final class SubtitleSearchCache {
    private var searches: [String: (UUID, Task<(URL, String)?, Never>)] = [:]
    func task(_ key: String, load: @escaping @MainActor () async -> (URL, String)?) -> Task<(URL, String)?, Never> {
        if let previous = searches[key] { return previous.1 }
        let token = UUID()
        let task = Task { @MainActor in
            let result = await load()
            if result == nil, searches[key]?.0 == token { searches[key] = nil }
            return result
        }
        searches[key] = (token, task)
        return task
    }
    func cancel() { searches.values.forEach { $0.1.cancel() }; searches.removeAll() }
}

@MainActor
final class DesktopSubtitlePreparer {
    private let jimaku: DesktopJimakuCatalog
    private let service = IosServices()
    private let titleLookup = TitleLookup()
    private let searches = SubtitleSearchCache()
    private var contexts: [String: (String, Int32, [Int])] = [:]
    init(jimaku: DesktopJimakuCatalog? = nil) { self.jimaku = jimaku ?? DesktopJimakuCatalog() }

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

    /// koreanFirst: a download's subtitle (main.cjs findDownloadSubtitle, desktop 0.5.32).
    func prepare(_ item: PlaybackItem, tracks: [RemoteSubtitle], preferences: AppPreferences, prefersAI: Bool = false, skipSaved: Bool = false, stream: ResolvedStream? = nil, koreanFirst: Bool = false) async throws -> (URL, String)? {
        guard preferences.subtitleProvider != "manual", !DesktopSubtitlePolicy.burnedKorean(source: item.anime.source, stream: stream) else { return nil }
        let saved = skipSaved ? [] : EpisodeSubtitleStore.shared.list(item)
        if koreanFirst { return try await downloadSubtitle(item, saved: saved, tracks: tracks, preferences: preferences) }
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
        for local in item.localSubtitles { if await SubtitleFiles.isKorean(local) { return (local, "download") } }
        if let local = item.localSubtitles.first { return (local, "download") }
        guard item.anime.source != "local" && item.anime.source != "linkkf" else { return nil }
        if let found = try await community(item, preferences: preferences) { return found }
        return try await translationSource(item, tracks: tracks, preferences: preferences)
    }

    /// A Korean subtitle comes first for a download: one saved for the episode (the preferred source's, a machine
    /// translation standing for the site's subtitle it was made from when it was the last one put on), the site's Korean
    /// track, then Kairan / Csora / Anissia. A saved subtitle in another language (a site's English track, Jimaku) is
    /// used only when none of them has one, so a Korean fansub keeps the episode from being translated.
    private func downloadSubtitle(_ item: PlaybackItem, saved: [SavedSubtitle], tracks: [RemoteSubtitle], preferences: AppPreferences) async throws -> (URL, String)? {
        let preferred = DesktopSubtitlePolicy.preferredSaved(saved, preferred: preferences.subtitleProvider)
        if let preferred, DesktopSubtitlePolicy.downloadKorean(preferred), let file = preferred.file { return (file, preferred.provider) }
        for track in tracks where DesktopSubtitlePolicy.isKorean(track) {
            try Task.checkCancellation()
            if let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]), let file = select(files, item: item, offsets: []) { return (file, "reanime") }
        }
        if let korean = saved.first(where: DesktopSubtitlePolicy.downloadKorean), let file = korean.file { return (file, korean.provider) }
        if item.anime.source != "local" && item.anime.source != "linkkf", let found = try await community(item, preferences: preferences) { return found }
        if let other = preferred ?? saved.first, let file = other.file { return (file, other.provider) }
        return try await translationSource(item, tracks: tracks, preferences: preferences)
    }

    /// Start all searches together, but consume in preference order.
    func community(_ item: PlaybackItem, preferences: AppPreferences) async throws -> (URL, String)? {
        let context = await context(item)
        let pending = DesktopSubtitlePolicy.providers(preferences.subtitleProvider).map { communityTask($0, item: item, context: context) }
        for task in pending {
            let result = await task.value
            try Task.checkCancellation()
            if let result { return result }
        }
        return nil
    }

    /// One source's subtitle for the episode (player chip / app.js findSubtitle(source)), sharing the automatic search.
    func community(_ item: PlaybackItem, provider: String) async throws -> (URL, String)? {
        let context = await context(item)
        let result = await communityTask(provider, item: item, context: context).value
        try Task.checkCancellation()
        return result
    }

    /// An Anissia maker's subtitle (player maker list / app.js findSubtitle('anissia', …, {maker})).
    func maker(_ item: PlaybackItem, website: String) async throws -> (URL, String)? {
        let context = await context(item)
        let assets: [SubtitleAsset] = await withCheckedContinuation { continuation in
            service.makerSubtitles(title: context.0, episode: Int32(item.number), episodeKey: item.subtitleEpisodeKey, website: website, anilistId: context.1) { values, _ in continuation.resume(returning: values ?? []) }
        }
        var tried = Set<String>()
        let result = await apply(assets, provider: "anissia", item: item, tried: &tried)
        try Task.checkCancellation()
        return result
    }

    private func communityTask(_ provider: String, item: PlaybackItem, context: (String, Int32, [Int])) -> Task<(URL, String)?, Never> {
        let key = item.anime.id + "#" + item.episodeID + "#" + context.0 + "#" + provider
        return searches.task(key) { [weak self] () -> (URL, String)? in
            guard let self else { return nil }
            var tried = Set<String>()
            for round in 0..<2 {
                let assets = await self.find(provider, item: item, context: context, fresh: round > 0)
                if let found = await self.apply(assets, provider: provider, item: item, tried: &tried) { return found }
                if Task.isCancelled { return nil }
            }
            return nil
        }
    }

    /// Downloads a search's posts in rank order and picks the episode's file (desktop downloadCommunityMatch).
    private func apply(_ assets: [SubtitleAsset], provider: String, item: PlaybackItem, tried: inout Set<String>) async -> (URL, String)? {
        var postKeys: [String] = []
        let downloads = assets.filter { $0.source != "post" }
        for asset in downloads where !postKeys.contains(asset.postURL) { postKeys.append(asset.postURL) }
        for key in postKeys {
            let group = downloads.filter { $0.postURL == key }
            guard CommunityAttachmentAttempt.claim(post: key, links: group.map(\.url), tried: &tried), let first = group.first else { continue }
            // The links are tried a tier at a time (named for the episode, covering it, naming none) until one tier holds
            // the episode, so a post's other bundles are not downloaded once its own link has the file; the fonts follow.
            let subtitles = group.filter { !$0.font }
            let tiers = Array(Set(subtitles.map(\.tier))).sorted()
            var found: URL?, fonts: [URL] = []
            for tier in tiers {
                let links = subtitles.filter { $0.tier == tier }
                var candidates: [URL] = []
                for asset in links {
                    if Task.isCancelled { return nil }
                    if let url = URL(string: asset.url), let files = try? await SubtitleFiles.prepare(url, label: asset.label) { candidates += files; fonts += SubtitleFiles.preparedFonts(url) }
                }
                // Match the episode before preferring ASS and newer revisions; do not substitute another numbered file.
                // Links not named for the episode alone ("통합", a range) never fall back to an arbitrary unnumbered file.
                let planned = tier >= 0, exact = planned && links.allSatisfy(\.exact)
                if let file = select(candidates, item: item, offsets: [], episode: first.matchedEpisode?.doubleValue ?? first.episode.map { Double($0.intValue) },
                                     strict: !planned || exact ? first.strict : true, bundle: first.bundle, community: true, exact: exact, fileSeason: Int(first.fileSeason)) { found = file; break }
            }
            guard let found else { continue }
            for asset in group where asset.font {
                if Task.isCancelled { return nil }
                // The subtitle plays with the fallback font when a font link fails.
                if let url = URL(string: asset.url), (try? await SubtitleFiles.prepare(url, label: asset.label)) != nil { fonts += SubtitleFiles.preparedFonts(url) }
            }
            try? SubtitleFiles.associateFonts(SubtitleFiles.fonts(for: found) + fonts, with: found)
            return (found, provider)
        }
        if provider == "anissia" {
            for asset in assets where asset.source == "post" {
                if Task.isCancelled { return nil }
                guard CommunityAttachmentAttempt.claim(post: asset.url, links: ["WinPNG"], tried: &tried) else { continue }
                if let url = URL(string: asset.url), let file = try? await WinPNGReader.subtitle(url, episode: asset.matchedEpisode?.doubleValue ?? Double(item.displayNumber) ?? Double(item.number), matched: true) { return (file, provider) }
            }
        }
        return nil
    }

    func translationSource(_ item: PlaybackItem, tracks: [RemoteSubtitle], preferences: AppPreferences, requireAutomatic: Bool = true) async throws -> (URL, String)? {
        guard !requireAutomatic || preferences.translationPreferences(automatic: true) != nil else { return nil }
        if let japanese = DesktopSubtitlePolicy.translationTrack(tracks, language: "ja"),
           let files = try? await SubtitleFiles.prepare(japanese.url, headers: japanese.headers ?? [:]), let file = select(files, item: item, offsets: []) { return (file, "reanime") }
        try Task.checkCancellation()
        if let saved = EpisodeSubtitleStore.shared.list(item).first(where: { !$0.translated && $0.provider == "jimaku" }), let file = saved.file { return (file, "jimaku") }
        if item.anime.source != "local" {
            let files = await jimaku.load(item)
            for asset in files where asset.source != "post" && asset.score > 0 && JimakuRules.shared.validDownload(url: asset.url) && asset.name.range(of: #"(?:^|[. _-])(?:en|eng|english)(?:[. _-]|$)"#, options: [.regularExpression, .caseInsensitive]) == nil {
                try Task.checkCancellation()
                if let url = URL(string: asset.url), let files = try? await SubtitleFiles.prepare(url, japanese: true, filename: asset.name), let file = files.first { return (file, "jimaku") }
            }
        }
        if let track = DesktopSubtitlePolicy.translationTrack(tracks, language: "en"), let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]), let file = select(files, item: item, offsets: []) { return (file, "reanime") }
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
        if !result.2.isEmpty || DesktopTitleRules.shared.season(title: title) < 2 { contexts[item.anime.id] = result }
        return result
    }
    private func find(_ provider: String, item: PlaybackItem, context: (String, Int32, [Int]), fresh: Bool = false) async -> [SubtitleAsset] {
        let alternatives = provider == "jimaku" ? [] : (DesktopCatalog.shared.record(item.anime)?.aliases ?? []).filter { TitleCandidates.shared.isKorean(title: $0) }
        var tried = Set<String>()
        for title in ([context.0] + alternatives).prefix(8) where tried.insert(title).inserted {
            if Task.isCancelled { return [] }
            let assets: [SubtitleAsset] = await withCheckedContinuation { continuation in
                if fresh {
                    service.refreshSubtitles(provider: provider, title: title, episode: Int32(item.number), episodeKey: item.subtitleEpisodeKey, anilistId: context.1) { values, _ in continuation.resume(returning: values ?? []) }
                } else {
                    service.findSubtitles(provider: provider, title: title, episode: Int32(item.number), episodeKey: item.subtitleEpisodeKey, anilistId: context.1) { values, _ in continuation.resume(returning: values ?? []) }
                }
            }
            if !assets.isEmpty { return assets }
        }
        return []
    }
    private func select(_ files: [URL], item: PlaybackItem, offsets: [Int], episode: Double? = nil, strict: Bool = false, bundle: Bool = false, community: Bool = false, exact: Bool = false, fileSeason: Int = 0) -> URL? {
        if community {
            // The season the search title marks; else the provider title's ("Overlord IV"), else none: a title marking no
            // season may still be filed under a later one by its maker.
            let titleSeason = Int(DesktopTitleRules.shared.season(title: item.anime.title))
            let season = fileSeason > 0 ? fileSeason : titleSeason > 1 ? titleSeason : 0
            let index = DesktopCommunityFiles.shared.pick(names: files.map(\.lastPathComponent), sizes: files.map { KotlinLong(value: Int64((try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)) }, episode: episode ?? Double(item.number), exact: exact, strict: strict, bundle: bundle, season: Int32(season))
            return index >= 0 && Int(index) < files.count ? files[Int(index)] : nil
        }
        let wanted = [episode.map(Int.init) ?? item.number] + offsets.map { item.number + $0 }
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
    /// The Korean title the subtitle sources are searched with.
    func searchTitle(_ item: PlaybackItem) async -> String { await context(item).0 }
    /// 한국어 자막 다시 찾기 asks the sources again instead of reusing this episode's earlier answers.
    func resetSearches() { searches.cancel() }
    func cancel() { searches.cancel(); jimaku.cancel(); service.cancel(); titleLookup.cancel() }
    deinit { service.close() }
}
enum CommunityAttachmentAttempt {
    static func claim(post: String, links: [String], tried: inout Set<String>) -> Bool {
        // Keep URL boundaries unambiguous and treat changed links as a new attempt.
        guard let data = try? JSONEncoder().encode([post] + links.sorted()) else { return false }
        return tried.insert(data.base64EncodedString()).inserted
    }
}
