import Foundation
import Combine
import LilacShared

/// app.js loadJimakuList/openJimaku: one listing per episode, including an
/// empty result; all consumers join it, and only opening a failed list retries.
@MainActor
final class DesktopJimakuCatalog: ObservableObject {
    private final class Listing {
        var files: [SubtitleAsset]?
        var error: String?
        var task: Task<[SubtitleAsset], Never>?
    }
    @Published private var revision = 0
    private var listings: [String: Listing] = [:]
    private let service = IosServices()
    private let fetch: ((PlaybackItem) async throws -> [SubtitleAsset])?
    init(fetch: ((PlaybackItem) async throws -> [SubtitleAsset])? = nil) { self.fetch = fetch }
    private func key(_ item: PlaybackItem) -> String { item.anime.id + "#" + item.episodeID }
    func files(_ item: PlaybackItem) -> [SubtitleAsset]? { listings[key(item)]?.files }
    func error(_ item: PlaybackItem) -> String? { listings[key(item)]?.error }
    func loading(_ item: PlaybackItem) -> Bool { let entry = listings[key(item)]; return entry?.task != nil && entry?.files == nil && entry?.error == nil }

    func load(_ item: PlaybackItem, retryFailure: Bool = false) async -> [SubtitleAsset] {
        let key = key(item)
        let entry = listings[key] ?? Listing()
        listings[key] = entry
        if retryFailure && entry.error != nil { entry.task = nil; entry.error = nil }
        if let task = entry.task { return await task.value }
        entry.task = Task { @MainActor in
            do {
                let files: [SubtitleAsset]
                if let fetch { files = try await fetch(item) }
                else { files = try await request(item) }
                try Task.checkCancellation()
                guard listings[key] === entry else { return [] }
                entry.files = files; entry.error = nil; revision += 1
                return files
            } catch {
                if listings[key] === entry { entry.files = nil; entry.error = error.localizedDescription; revision += 1 }
                return []
            }
        }
        revision += 1
        return await entry.task!.value
    }
    private func request(_ item: PlaybackItem) async throws -> [SubtitleAsset] {
        let known = item.anime.anime.anilistId?.int32Value ?? 0
        var anilist = known > 0 ? known : Int32(DesktopCatalog.shared.record(item.anime)?.anilist ?? 0)
        if anilist <= 0 && !TitleCandidates.shared.isKorean(title: item.anime.title) {
            await DesktopCatalog.shared.enrich(item.anime.anime, source: item.anime.source)
            anilist = Int32(DesktopCatalog.shared.record(item.anime)?.anilist ?? 0)
        }
        try Task.checkCancellation()
        guard anilist > 0 else { throw SubtitleFiles.failure("AniList 작품을 찾지 못해 Jimaku를 검색할 수 없습니다.") }
        let files: [SubtitleAsset] = try await withCheckedThrowingContinuation { continuation in
            service.findSubtitles(provider: "jimaku", title: item.anime.title, episode: Int32(item.number), episodeKey: item.displayNumber, anilistId: anilist) { values, error in
                if let error { continuation.resume(throwing: SubtitleFiles.failure(error)) }
                else { continuation.resume(returning: values ?? []) }
            }
        }
        let picks = UserDefaults.standard.dictionary(forKey: "subtitle.jimaku.series-picks") as? [String: String] ?? [:]
        let previous = picks[item.anime.id] ?? EpisodeSubtitleStore.shared.records.filter { $0.episodeKey.hasPrefix(item.anime.id + "#") && $0.provider == "jimaku" && !$0.translated }.max { $0.date < $1.date }?.name ?? ""
        return JimakuRules.shared.prefer(files: files, preferred: previous)
    }
    func remember(_ name: String, item: PlaybackItem) {
        var picks = UserDefaults.standard.dictionary(forKey: "subtitle.jimaku.series-picks") as? [String: String] ?? [:]
        picks[item.anime.id] = name
        UserDefaults.standard.set(picks, forKey: "subtitle.jimaku.series-picks")
    }
    func cancel() {
        listings.values.forEach { $0.task?.cancel() }; listings.removeAll(); service.cancel(); revision += 1
    }
}
