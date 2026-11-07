import Foundation
import LilacShared

// 다른 사이트 자막 트랙 (desktop 77e3d7e): every site's episode also gets RE:Anime's and Miruro's subtitle tracks of the
// same episode, found by the work's AniList entry (its Japanese title and air year, else its English title; see
// shared CrossSiteTracks.kt). They are timed for that site's video: put on, a track is synced to this episode's audio
// like any subtitle, and none is ever the timing reference for another subtitle (SubtitleAutoSync.referenceTracks).

extension RemoteSubtitle {
    /// Another site's track of the episode (origin "reanime" or "miruro"), not the playing site's own.
    var crossSite: Bool { origin != nil }
}

enum CrossSiteTrackPolicy {
    static let originLabels = ["reanime": "Re:Anime", "miruro": "Miruro", "ohli24": "애니24", "linkani": "링크애니", "linkkf": "Linkkf"]
    /// The sites whose subtitles are a track list (desktop subtitleTracks).
    static let trackSites: Set<String> = ["reanime", "miruro"]
    /// The Korean sites whose own Korean subtitle file joins every other site's tracks (desktop d8f7978).
    static let koreanSites: Set<String> = ["ohli24", "linkani", "linkkf"]

    /// The site's own tracks as a track list: RE:Anime's and Miruro's. Linkkf's, 애니24's and 링크애니's own Korean subtitle
    /// is the episode's subtitle, not a track (desktop subtitleUrl): it comes first all the same, and the other sites'
    /// tracks are all listed beside it. The other sites' tracks a download kept stay listed.
    static func listed(own: [RemoteSubtitle], source: String) -> [RemoteSubtitle] {
        trackSites.contains(source) ? own : own.filter(\.crossSite)
    }

    /// The tracks listed for an episode: the site's own, then the other sites' — all of them when the site has none,
    /// only a Korean one beside the site's own, and none when the site has a Korean track already.
    /// A downloaded episode's own list holds the other sites' tracks kept with it (marked by their origin) too. The Korean
    /// sites' files (애니24, 링크애니, Linkkf) are always listed, beside a site's own Korean track too (desktop d8f7978).
    static func merged(own: [RemoteSubtitle], other: [RemoteSubtitle]) -> [RemoteSubtitle] {
        let mine = own.filter { !$0.crossSite }
        var others: [RemoteSubtitle] = []
        for track in own.filter(\.crossSite) + other where !mine.contains(where: { $0.url == track.url }) && !others.contains(where: { $0.url == track.url }) { others.append(track) }
        if mine.contains(where: DesktopSubtitlePolicy.isKorean) { return mine + others.filter { koreanSites.contains($0.origin ?? "") } }
        return mine.isEmpty ? others : mine + others.filter(DesktopSubtitlePolicy.isKorean)
    }
    /// The site a track is named after ("Miruro"): its own origin, else the playing site's.
    static func label(_ track: RemoteSubtitle, playing source: String) -> String {
        originLabels[track.origin ?? ""] ?? originLabels[source] ?? "Re:Anime"
    }
    /// The sites the listed tracks come from ("Re:Anime", "Re:Anime·Miruro"): the playing site's first.
    static func sourceLabel(playing source: String, tracks: [RemoteSubtitle]) -> String {
        var origins: [String] = []
        if originLabels[source] != nil && (tracks.isEmpty || tracks.contains { !$0.crossSite }) { origins.append(source) }
        for track in tracks { if let origin = track.origin, originLabels[origin] != nil, !origins.contains(origin) { origins.append(origin) } }
        return origins.compactMap { originLabels[$0] }.joined(separator: "·").nilIfEmpty ?? "Re:Anime"
    }
    /// The best track to translate from once Jimaku had none: the site's Japanese, another site's Japanese, the site's
    /// English, another site's English (desktop subtitle-source.js resolve; the site's Japanese is tried before Jimaku).
    static func translationOrder(_ tracks: [RemoteSubtitle]) -> [RemoteSubtitle] {
        let own = tracks.filter { !$0.crossSite }, other = tracks.filter(\.crossSite)
        var result: [RemoteSubtitle] = []
        for pick in [DesktopSubtitlePolicy.translationTrack(own, language: "ja"), DesktopSubtitlePolicy.translationTrack(other, language: "ja"),
                     DesktopSubtitlePolicy.translationTrack(own, language: "en"), DesktopSubtitlePolicy.translationTrack(other, language: "en")] {
            if let pick, !result.contains(where: { $0.url == pick.url }) { result.append(pick) }
        }
        return result
    }
    /// The episode number the other sites are asked for, and whether it has none of its own: a half or lettered episode
    /// ("9.5", "4a") or a special. A label that is the episode's own title counts by its number.
    static func number(_ item: PlaybackItem) -> (number: Int, special: Bool) {
        let label = item.displayNumber.trimmingCharacters(in: .whitespaces)
        if let value = Int(label) { return (value, false) }
        if label.range(of: #"^\d+(?:\.\d+|[a-z])$"#, options: [.regularExpression, .caseInsensitive]) != nil { return (item.number, true) }
        if label.range(of: #"^(?:ova|ona|sp|special|특별|극장)"#, options: [.regularExpression, .caseInsensitive]) != nil { return (item.number, true) }
        return (item.number, false)
    }
    /// A dubbed episode (its address or id says so): the other sites' tracks are of the subbed one.
    static func dub(_ item: PlaybackItem) -> Bool {
        (item.watchURL.absoluteString + " " + item.episodeID).range(of: #"(?:^|[^a-z])dub(?:bed)?(?:[^a-z]|$)"#, options: [.regularExpression, .caseInsensitive]) != nil
    }
}

/// The other sites' tracks of an episode, asked once per episode for ten minutes (desktop crossSiteTracksFor): started
/// as the episode opens, so a Korean one can choose Animenosub's and Miruro's clean video, and taken up by the player,
/// downloads and the next episode made ready.
@MainActor
final class CrossSiteTracks {
    static let shared = CrossSiteTracks()
    private let service = IosServices()
    private var requests: [String: (at: Date, task: Task<[RemoteSubtitle], Never>)] = [:]

    func tracks(_ item: PlaybackItem) -> Task<[RemoteSubtitle], Never> {
        let key = item.anime.id + "#" + item.episodeID
        if let known = requests[key], Date().timeIntervalSince(known.at) < 600 { return known.task }
        let (number, special) = CrossSiteTrackPolicy.number(item), dub = CrossSiteTrackPolicy.dub(item)
        let anime = item.anime.anime, source = item.anime.source
        let task = Task { @MainActor [service] () -> [RemoteSubtitle] in
            guard source != "local", !special, !dub, number > 0 else { return [] }
            let found: [CrossSiteTrack] = await withCheckedContinuation { continuation in
                service.crossSiteTracks(anime: anime, provider: source, number: Int32(number), special: special, dub: dub) { values, _ in continuation.resume(returning: values ?? []) }
            }
            return found.compactMap { track in
                guard let url = URL(string: track.url) else { return nil }
                return RemoteSubtitle(label: track.label, url: url, language: track.language, headers: track.headers.isEmpty ? nil : track.headers, origin: track.origin)
            }
        }
        requests[key] = (Date(), task)
        if requests.count > 20, let oldest = requests.min(by: { $0.value.at < $1.value.at })?.key { requests[oldest] = nil }
        // An empty answer (a lookup that failed, a site that did not answer) is asked again next time.
        Task { @MainActor in if await task.value.isEmpty, requests[key]?.task == task { requests[key] = nil } }
        return task
    }
    /// 애니24's, 링크애니's and Linkkf's Korean files of the episode (never the playing site's; desktop d8f7978), slower
    /// than RE:Anime's and Miruro's: their shows are searched by the work's Korean title. Asked once per episode for ten
    /// minutes; an empty answer is asked again next time.
    private var koreanRequests: [String: (at: Date, task: Task<[RemoteSubtitle], Never>)] = [:]
    func koreanTracks(_ item: PlaybackItem) -> Task<[RemoteSubtitle], Never> {
        let key = item.anime.id + "#" + item.episodeID
        if let known = koreanRequests[key], Date().timeIntervalSince(known.at) < 600 { return known.task }
        let (number, special) = CrossSiteTrackPolicy.number(item), dub = CrossSiteTrackPolicy.dub(item)
        let anime = item.anime.anime, source = item.anime.source
        let task = Task { @MainActor [service] () -> [RemoteSubtitle] in
            guard source != "local", !special, !dub, number > 0 else { return [] }
            let titles = await Self.koreanTitles(item)
            guard !titles.isEmpty else { return [] }
            let found: [CrossSiteTrack] = await withCheckedContinuation { continuation in
                service.koreanSiteTracks(anime: anime, provider: source, number: Int32(number), special: special, dub: dub, koreanTitles: titles) { values, _ in continuation.resume(returning: values ?? []) }
            }
            var tracks: [RemoteSubtitle] = []
            for track in found {
                if let content = track.contentBase64 {
                    if let file = try? await SubtitleFiles.providerFile(content, source: track.url, format: track.format) {
                        tracks.append(RemoteSubtitle(label: track.label, url: file, language: track.language, origin: track.origin))
                    }
                } else if let url = URL(string: track.url) {
                    tracks.append(RemoteSubtitle(label: track.label, url: url, language: track.language, headers: track.headers.isEmpty ? nil : track.headers, origin: track.origin))
                }
            }
            return tracks
        }
        koreanRequests[key] = (Date(), task)
        if koreanRequests.count > 20, let oldest = koreanRequests.min(by: { $0.value.at < $1.value.at })?.key { koreanRequests[oldest] = nil }
        Task { @MainActor in if await task.value.isEmpty, koreanRequests[key]?.task == task { koreanRequests[key] = nil } }
        return task
    }
    /// Both together, for what needs one answer (the clean video's choice, a download, the next episode): RE:Anime's and
    /// Miruro's, then the Korean sites' when those have no Korean track (desktop allOtherSiteTracksFor / otherSiteKoreanTrack).
    func all(_ item: PlaybackItem) -> Task<[RemoteSubtitle], Never> {
        let fast = tracks(item), korean = koreanTracks(item)
        return Task { @MainActor in
            let found = await fast.value
            if found.contains(where: DesktopSubtitlePolicy.isKorean) { return found }
            return found + (await korean.value).filter { track in !found.contains { $0.url == track.url } }
        }
    }
    /// The work's Korean titles, what the Korean sites' searches take: the catalog's Korean name and aliases, the title
    /// shown, else one found by TMDB / AniList / Wikidata (desktop koreanTitleCandidates).
    private static func koreanTitles(_ item: PlaybackItem) async -> [String] {
        await DesktopCatalog.shared.enrich(item.anime.anime, source: item.anime.source)
        let record = DesktopCatalog.shared.record(item.anime)
        var titles: [String] = []
        for title in [record?.korean ?? "", item.anime.title] + (record?.aliases ?? []) where TitleCandidates.shared.isKorean(title: title) && !titles.contains(title) { titles.append(title) }
        if titles.isEmpty {
            let lookup = TitleLookup()
            defer { lookup.cancel() }
            let anime = item.anime.anime
            if let found = await lookup.resolve(item.anime.title, aliases: [anime.native, anime.romaji, anime.english]), TitleCandidates.shared.isKorean(title: found) { titles.append(found) }
        }
        return Array(titles.prefix(3))
    }
    /// The answer within `seconds`, or what is known by then (nothing): the automatic subtitle does not wait longer.
    static func value(_ task: Task<[RemoteSubtitle], Never>, within seconds: Double) async -> [RemoteSubtitle] {
        var result: [RemoteSubtitle]?
        let waiter = Task { @MainActor in result = await task.value }
        let deadline = Date().addingTimeInterval(seconds)
        while result == nil && Date() < deadline && !Task.isCancelled { try? await Task.sleep(nanoseconds: 100_000_000) }
        if result == nil { waiter.cancel() }
        return result ?? []
    }
    /// 설정 off: the next asks start over.
    func clear() { requests.removeAll(); koreanRequests.removeAll() }
}

/// Linkkf's Korean subtitle for another site's episode (shared KoreanSiteBrowserRegistry): its player page opened in a
/// hidden, muted resolver apart from the player's, 15 s at most (desktop resolveLinkkfStream in a partition of its own).
final class KoreanSiteBrowserBridge: NSObject, KoreanSiteBrowser {
    static let shared = KoreanSiteBrowserBridge()
    @MainActor private var jobs: [String: Task<Void, Never>] = [:]
    func linkkfSubtitle(id: String, url: String, completion: @escaping (String?, String?) -> Void) {
        Task { @MainActor in
            jobs[id] = Task { @MainActor in
                defer { jobs[id] = nil }
                guard let page = URL(string: url), ["http", "https"].contains(page.scheme ?? "") else { completion(nil, nil); return }
                let found = await PlaybackResolver.pageSubtitles(page)
                guard !Task.isCancelled else { return }
                guard let track = found.first(where: DesktopSubtitlePolicy.isKorean) ?? found.first,
                      let data = try? JSONSerialization.data(withJSONObject: ["url": track.url.absoluteString, "headers": track.headers ?? ["Referer": url]]) else { completion(nil, nil); return }
                completion(String(data: data, encoding: .utf8), nil)
            }
        }
    }
    func cancel(id: String) {
        Task { @MainActor in jobs[id]?.cancel(); jobs[id] = nil }
    }
}

/// The AniList and Miruro matches kept between launches (shared CrossSiteCacheRegistry): 30 days when found, a day when
/// not, in the app's caches.
final class CrossSiteDiskCache: NSObject, CrossSiteCacheStore {
    static let shared = CrossSiteDiskCache()
    private let lock = NSLock()
    private let file = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("cross-site-anilist.json")
    func read() -> String? {
        lock.lock(); defer { lock.unlock() }
        return try? String(contentsOf: file, encoding: .utf8)
    }
    func write(value: String) {
        lock.lock(); defer { lock.unlock() }
        try? value.write(to: file, atomically: true, encoding: .utf8)
    }
}
