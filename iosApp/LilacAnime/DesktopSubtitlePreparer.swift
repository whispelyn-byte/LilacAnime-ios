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

/// The other site's Korean track and the blogs' Korean subtitle of an episode, asked at once (desktop koreanSearchFor):
/// what is there now (the other site's track first), whether both have answered, and a wait for it.
/// others: the other sites' tracks as they come; othersDone: done once they are all in.
@MainActor
final class KoreanSearch {
    struct Pick { let file: URL; let provider: String; let track: RemoteSubtitle? }
    static let slowSites: Set<String> = ["ohli24", "linkani", "linkkf"]
    private let item: PlaybackItem, preferences: AppPreferences
    private let others: () -> [RemoteSubtitle]
    private weak var preparer: DesktopSubtitlePreparer?
    private let started = Date()
    private var blogs: Task<Void, Never>?
    private var blog: (URL, String)?, blogDone = false, othersFinished: Bool
    private var files: [URL: URL] = [:], failed: Set<URL> = []
    init(item: PlaybackItem, preferences: AppPreferences, others: @escaping () -> [RemoteSubtitle], othersDone: Task<[RemoteSubtitle], Never>?, preparer: DesktopSubtitlePreparer) {
        self.item = item; self.preferences = preferences; self.others = others; self.preparer = preparer
        othersFinished = othersDone == nil
        if let othersDone { Task { @MainActor [weak self] in _ = await othersDone.value; self?.othersFinished = true } }
    }
    /// Waited for at least once (the blogs asked: a saved or the site's own subtitle needs none).
    var asked: Bool { blogs != nil }
    var settled: Bool { blogDone && othersFinished }
    var elapsed: Double { Date().timeIntervalSince(started) }
    private var track: RemoteSubtitle? {
        others().first { $0.crossSite && $0.origin != item.anime.source && DesktopSubtitlePolicy.isKorean($0) && !failed.contains($0.url) }
    }
    /// The wait ends on the other site's Korean track, or the blogs' once the other sites have answered (theirs comes first).
    private var best: Bool { track != nil || (blog != nil && othersFinished) || settled }
    func wait(_ seconds: Double) async -> Pick? {
        if blogs == nil {
            let item = item, preferences = preferences
            blogs = Task { @MainActor [weak self] in
                let found = item.anime.source == "local" ? nil : (try? await self?.preparer?.community(item, preferences: preferences)) ?? nil
                self?.blog = found; self?.blogDone = true
            }
        }
        let deadline = Date().addingTimeInterval(max(0, seconds))
        while !best && Date() < deadline && !Task.isCancelled { try? await Task.sleep(nanoseconds: 200_000_000) }
        return await now()
    }
    /// What is there now, as a file: the other site's Korean track (read once), else the blogs' subtitle.
    private func now() async -> Pick? {
        while let track {
            if let file = files[track.url] { return Pick(file: file, provider: "reanime", track: track) }
            if let file = await preparer?.file(of: track, item: item) { files[track.url] = file; return Pick(file: file, provider: "reanime", track: track) }
            failed.insert(track.url)
        }
        return blog.map { Pick(file: $0.0, provider: $0.1, track: nil) }
    }
}

@MainActor
final class DesktopSubtitlePreparer {
    private let jimaku: DesktopJimakuCatalog
    private let service = IosServices()
    private let titleLookup = TitleLookup()
    private let searches = SubtitleSearchCache()
    private var contexts: [String: (String, Int32, [Int])] = [:]
    init(jimaku: DesktopJimakuCatalog? = nil) { self.jimaku = jimaku ?? DesktopJimakuCatalog() }

    /// otherTracks: the other sites' tracks of the episode (CrossSiteTracks); a Korean one is a Korean subtitle for the
    /// video server's choice, as a blog's is (desktop 77e3d7e firstFound(blogSearch, otherSiteKorean)).
    func preferRaw(_ item: PlaybackItem, preferences: AppPreferences, downloading: Bool = false, otherTracks: Task<[RemoteSubtitle], Never>? = nil) async -> Bool {
        guard ["miruro", "animenosub"].contains(item.anime.source) else { return false }
        let known = UserDefaults.standard.stringArray(forKey: "desktop.korean-series") ?? []
        if known.contains(item.anime.id) || EpisodeSubtitleStore.shared.list(item).contains(where: { !["provider", "reanime"].contains($0.provider) }) { return true }
        var finished = false, otherKorean = false, otherDone = otherTracks == nil
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
        if let otherTracks {
            Task { @MainActor in otherKorean = await otherTracks.value.contains(where: DesktopSubtitlePolicy.isKorean); otherDone = true }
        }
        if downloading {
            while !(finished && otherDone) && !otherKorean && !Task.isCancelled { try? await Task.sleep(nanoseconds: 100_000_000) }
            if otherKorean { return true }
            if await task.value { return true }
            return (try? await translationSource(item, tracks: [], preferences: preferences, later: otherTracks)) != nil
        }
        // The first that finds a Korean subtitle decides, or both finding none.
        let deadline = Date().addingTimeInterval(8)
        while !otherKorean && !(finished && otherDone) && Date() < deadline && !Task.isCancelled { try? await Task.sleep(nanoseconds: 100_000_000) }
        if otherKorean { return true }
        if finished { return await task.value }
        return false
    }
    /// The other sites' Korean track put on for an episode (its prepared file → the track), so the player marks it in the
    /// track list and says where it is from.
    private var crossSitePicks: [URL: RemoteSubtitle] = [:]
    func crossSiteTrack(for file: URL) -> RemoteSubtitle? { crossSitePicks[file] }
    /// Another site's Korean track of this very episode (found by its AniList entry) comes before the blogs, whose posts
    /// are matched by title. Waited for 12 s at most, the blogs searched meanwhile (desktop ensureSubtitle).
    private func otherKorean(_ item: PlaybackItem, tracks: [RemoteSubtitle], otherTracks: Task<[RemoteSubtitle], Never>?, preferences: AppPreferences) async throws -> (URL, String)? {
        guard otherTracks != nil || tracks.contains(where: \.crossSite), !tracks.contains(where: { !$0.crossSite && DesktopSubtitlePolicy.isKorean($0) }) else { return nil }
        var found = tracks.filter(\.crossSite)
        if let otherTracks {
            if item.anime.source != "local" { Task { _ = try? await self.community(item, preferences: preferences) } }
            // 16 s: the Korean sites' files take longer (their pages are read when RE:Anime and Miruro have no Korean track).
            found += await CrossSiteTracks.value(otherTracks, within: 16)
        }
        try Task.checkCancellation()
        for track in found where DesktopSubtitlePolicy.isKorean(track) {
            if let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]), let file = select(files, item: item, offsets: []) {
                crossSitePicks[file] = track; return (file, "reanime")
            }
            try Task.checkCancellation()
        }
        return nil
    }

    /// koreanFirst: a download's subtitle (main.cjs findDownloadSubtitle, desktop 0.5.32). otherTracks: the other sites'
    /// tracks of the episode still loading (CrossSiteTracks; tracks may hold those already found).
    /// korean: the player's search for the episode (KoreanSearch), whose Korean subtitle can still come after this
    /// returns; status: what the player says it is doing (desktop #subtitleState).
    func prepare(_ item: PlaybackItem, tracks: [RemoteSubtitle], preferences: AppPreferences, prefersAI: Bool = false, skipSaved: Bool = false, stream: ResolvedStream? = nil, koreanFirst: Bool = false, otherTracks: Task<[RemoteSubtitle], Never>? = nil, korean: KoreanSearch? = nil, status: ((String) -> Void)? = nil) async throws -> (URL, String)? {
        guard preferences.subtitleProvider != "manual", !DesktopSubtitlePolicy.burnedKorean(source: item.anime.source, stream: stream) else { return nil }
        let saved = skipSaved ? [] : EpisodeSubtitleStore.shared.list(item)
        if koreanFirst { return try await downloadSubtitle(item, saved: saved, tracks: tracks, preferences: preferences, otherTracks: otherTracks) }
        if prefersAI {
            if let translated = saved.first(where: \.translated), let file = translated.file { return (file, translated.provider) }
            if let result = try await translationSource(item, tracks: tracks, preferences: preferences, later: otherTracks) { return result }
        }
        if let preferred = DesktopSubtitlePolicy.preferredSaved(saved, preferred: preferences.subtitleProvider), let file = preferred.file { return (file, preferred.provider) }
        // desktop ensureSubtitle: the stream's own subtitle (Linkkf's, 링크애니's and 애니24's file is the Korean one
        // itself; a downloaded episode's was picked when it was saved), then any saved file.
        let siteFile = ["linkkf", "linkani", "ohli24"].contains(item.anime.source)
        for track in tracks where !track.crossSite && (siteFile || DesktopSubtitlePolicy.isKorean(track)) {
            try Task.checkCancellation()
            if let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]), let file = select(files, item: item, offsets: []) { return (file, item.anime.source == "linkkf" ? "linkkf" : "reanime") }
        }
        for local in item.localSubtitles { if await SubtitleFiles.isKorean(local) { return (local, "download") } }
        if let local = item.localSubtitles.first { return (local, "download") }
        if let first = saved.first, let file = first.file { return (file, first.provider) }
        if let korean {
            // desktop: the other site's Korean track and the blogs' are asked at once, and whatever Korean one is there
            // after a short wait (2.5 s) goes on, the other site's first. A work whose Korean subtitle came last time from a
            // slow site (애니24, 링크애니, Linkkf: their pages are read) waits for it 16 s. The cloud translation costs a
            // request, so it waits until both have answered (16 s at most); the local AI and Whisper start at once. A
            // Korean subtitle coming after this returns takes the place of the translation, the Whisper subtitle or none
            // (the player's upgradeToLateKorean).
            status?("다른 사이트·블로그에서 한국어 자막을 찾는 중...")
            var pick = await korean.wait(KoreanSearch.slowSites.contains(koreanSource(of: item)) ? 16 : 2.5)
            if pick == nil, !korean.settled, !prefersAI, let config = preferences.translationPreferences(automatic: true), config.translationProvider != "local" {
                status?("한국어 자막을 조금 더 찾아본 뒤 번역해요...")
                pick = await korean.wait(16 - korean.elapsed)
            }
            try Task.checkCancellation()
            if let pick { rememberKoreanSource(pick, item: item); if let track = pick.track { crossSitePicks[pick.file] = track }; return (pick.file, pick.provider) }
            return try await translationSource(item, tracks: tracks, preferences: preferences, later: otherTracks)
        }
        if let found = try await otherKorean(item, tracks: tracks, otherTracks: otherTracks, preferences: preferences) { return found }
        guard item.anime.source != "local" else { return nil }
        if let found = try await community(item, preferences: preferences) { return found }
        return try await translationSource(item, tracks: tracks, preferences: preferences, later: otherTracks)
    }

    /// Which Korean source the series had last time (a site's origin, or "blog"): a slow site's file is waited for longer.
    private func koreanSource(of item: PlaybackItem) -> String { UserDefaults.standard.string(forKey: "korean-source." + item.anime.id) ?? "" }
    func rememberKoreanSource(_ pick: KoreanSearch.Pick, item: PlaybackItem) {
        let source = pick.track?.origin ?? (["kairan", "csora", "anissia"].contains(pick.provider) ? "blog" : nil)
        guard let source else { return }
        UserDefaults.standard.set(source, forKey: "korean-source." + item.anime.id)
    }
    /// The search the player keeps for an episode (desktop koreanSearchFor).
    func koreanSearch(_ item: PlaybackItem, preferences: AppPreferences, others: @escaping () -> [RemoteSubtitle], othersDone: Task<[RemoteSubtitle], Never>?) -> KoreanSearch {
        KoreanSearch(item: item, preferences: preferences, others: others, othersDone: othersDone, preparer: self)
    }
    /// An other site's Korean track read into a file, as the automatic choice reads one.
    fileprivate func file(of track: RemoteSubtitle, item: PlaybackItem) async -> URL? {
        guard let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]) else { return nil }
        return select(files, item: item, offsets: [])
    }

    /// A Korean subtitle comes first for a download: one saved for the episode (the preferred source's, a machine
    /// translation standing for the site's subtitle it was made from when it was the last one put on), the site's Korean
    /// track, another site's Korean track of the episode, then Kairan / Csora / Anissia. A saved subtitle in another
    /// language (a site's English track, Jimaku) is used only when none of them has one, so a Korean fansub keeps the
    /// episode from being translated.
    private func downloadSubtitle(_ item: PlaybackItem, saved: [SavedSubtitle], tracks: [RemoteSubtitle], preferences: AppPreferences, otherTracks: Task<[RemoteSubtitle], Never>?) async throws -> (URL, String)? {
        let preferred = DesktopSubtitlePolicy.preferredSaved(saved, preferred: preferences.subtitleProvider)
        if let preferred, DesktopSubtitlePolicy.downloadKorean(preferred), let file = preferred.file { return (file, preferred.provider) }
        for track in tracks where !track.crossSite && DesktopSubtitlePolicy.isKorean(track) {
            try Task.checkCancellation()
            if let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]), let file = select(files, item: item, offsets: []) { return (file, "reanime") }
        }
        if let korean = saved.first(where: DesktopSubtitlePolicy.downloadKorean), let file = korean.file { return (file, korean.provider) }
        if let found = try await otherKorean(item, tracks: tracks, otherTracks: otherTracks, preferences: preferences) { return found }
        if item.anime.source != "local" && item.anime.source != "linkkf", let found = try await community(item, preferences: preferences) { return found }
        if let other = preferred ?? saved.first, let file = other.file { return (file, other.provider) }
        return try await translationSource(item, tracks: tracks, preferences: preferences, later: otherTracks)
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

    /// The subtitle to translate from (desktop subtitle-source.js resolve): the site's Japanese track, Jimaku's Japanese
    /// file, another site's Japanese track, then the English tracks (the site's, another site's). later: the other sites'
    /// tracks still loading, waited for (15 s at most) only after Jimaku.
    func translationSource(_ item: PlaybackItem, tracks: [RemoteSubtitle], preferences: AppPreferences, requireAutomatic: Bool = true, later: Task<[RemoteSubtitle], Never>? = nil) async throws -> (URL, String)? {
        guard !requireAutomatic || preferences.translationPreferences(automatic: true) != nil else { return nil }
        var tried = Set<URL>()
        if let japanese = DesktopSubtitlePolicy.translationTrack(tracks.filter { !$0.crossSite }, language: "ja") {
            tried.insert(japanese.url)
            if let files = try? await SubtitleFiles.prepare(japanese.url, headers: japanese.headers ?? [:]), let file = select(files, item: item, offsets: []) { return (file, "reanime") }
        }
        try Task.checkCancellation()
        if let saved = EpisodeSubtitleStore.shared.list(item).first(where: { !$0.translated && $0.provider == "jimaku" }), let file = saved.file { return (file, "jimaku") }
        if item.anime.source != "local" {
            let files = await jimaku.load(item)
            for asset in files where asset.source != "post" && asset.score > 0 && JimakuRules.shared.validDownload(url: asset.url) && asset.name.range(of: #"(?:^|[. _-])(?:en|eng|english)(?:[. _-]|$)"#, options: [.regularExpression, .caseInsensitive]) == nil {
                try Task.checkCancellation()
                if let url = URL(string: asset.url), let files = try? await SubtitleFiles.prepare(url, japanese: true, filename: asset.name), let file = files.first { return (file, "jimaku") }
            }
        }
        // As listed in the player: the other sites' tracks all when the site has none, else only a Korean one beside its own.
        var all = tracks
        if let later {
            let found = await CrossSiteTracks.value(later, within: 15)
            try Task.checkCancellation()
            all = CrossSiteTrackPolicy.merged(own: tracks, other: found)
        }
        for track in CrossSiteTrackPolicy.translationOrder(all) where tried.insert(track.url).inserted {
            try Task.checkCancellation()
            if let files = try? await SubtitleFiles.prepare(track.url, headers: track.headers ?? [:]), let file = select(files, item: item, offsets: []) { return (file, "reanime") }
        }
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
