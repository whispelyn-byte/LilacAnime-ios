import SwiftUI
import LilacShared

/// 회차 목록 (desktop 0.5.55 player.js renderEpisodePicks): every episode of the work, to jump to any of them from the
/// player's 회차 panel and from a card of 계속 시청하기 without opening the work's page. Each row has how far it was
/// watched (시청 기록); the order (첫회부터 / 최신회부터) is remembered for both.
enum EpisodeOrder {
    static let key = "episodePickerNewestFirst"
}

extension PlaybackItem {
    /// The episode at [index] of [episodes], with the ones before and after it to step through.
    static func inSeries(_ anime: SavedAnime, episodes: [Episode], at index: Int) -> PlaybackItem {
        var item = PlaybackItem(anime: anime, episode: episodes[index])
        item.next = episodes.dropFirst(index + 1).map { PlaybackItem(anime: anime, episode: $0) }
        item.preceding = episodes.prefix(index).map { PlaybackItem(anime: anime, episode: $0) }
        return item
    }
}

/// Where an episode of a series is in a list: by its id, else by its label, else by its number (app.js episodeIndex).
enum EpisodeMatch {
    static func index(_ episodes: [Episode], id: String, label: String, number: Int) -> Int? {
        episodes.firstIndex { $0.id == id } ?? episodes.firstIndex { $0.displayNumber == label } ?? episodes.firstIndex { Int($0.number) == number }
    }
    /// app.js episodeTitle: "3화", or the label itself when it is a name ("OVA 1").
    static func title(_ label: String) -> String {
        label.range(of: #"^\d+(?:\.\d+)?[a-z]?$"#, options: [.regularExpression, .caseInsensitive]) != nil ? label + "화" : label
    }
}

/// Pictures for the episodes the source gives none (AniList's or Kitsu's, by the work; see EpisodeThumbnailRepository):
/// asked once a work for the session, the lists drawn again when they come. A lookup that fails is tried again next time.
@MainActor
final class EpisodeThumbnailStore: ObservableObject {
    static let shared = EpisodeThumbnailStore()
    @Published private(set) var pictures: [String: [Int: String]] = [:]
    private var asked: Set<String> = []
    private let service = IosServices()
    private static func key(_ anime: SavedAnime) -> String { anime.source + "|" + anime.anime.id }
    func pictures(for anime: SavedAnime) -> [Int: String] { pictures[Self.key(anime)] ?? [:] }
    func load(_ anime: SavedAnime, episodes: [Episode]) {
        let key = Self.key(anime)
        guard !UIShowcase.enabled, episodes.contains(where: { !$0.thumbnailUrl.hasPrefix("http") }), asked.insert(key).inserted else { return }
        service.episodeThumbnails(anime: anime.anime) { [weak self] map, _ in
            Task { @MainActor in
                guard let self else { return }
                guard let map else { self.asked.remove(key); return }
                var pictures: [Int: String] = [:]
                for (number, url) in map { pictures[number.intValue] = url }
                if !pictures.isEmpty { self.pictures[key] = pictures }
            }
        }
    }
}

/// The rows (player-android.css .episode-pick): the picture with the watched part under it, or the number; the title and
/// what else is known (재생 중, 더빙, 방영일, 저장됨, 재생 불가) beside it.
struct EpisodePickList: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: DownloadStore
    @ObservedObject private var thumbnails = EpisodeThumbnailStore.shared
    @AppStorage(EpisodeOrder.key) private var newestFirst = false
    let items: [PlaybackItem]
    let current: Int?
    var currentLabel = "재생 중"
    /// The episode last watched is picked to resume it (계속 시청하기); the one playing is not picked again (the player).
    var pickCurrent = false
    var dark = true
    let pick: (Int) -> Void
    private var episodes: [String: Episode] {
        Dictionary((items.first?.anime.anime.episodes ?? []).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }
    var body: some View {
        let known = episodes
        let pictures = items.first.map { thumbnails.pictures(for: $0.anime) } ?? [:]
        let order = newestFirst ? Array(items.indices.reversed()) : Array(items.indices)
        ScrollViewReader { reader in
            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(order, id: \.self) { index in
                        row(index, episode: known[items[index].episodeID], pictures: pictures).id(index)
                    }
                }.padding(6)
            }.onAppear { if let current { reader.scrollTo(current, anchor: .center) } }
        }.task(id: items.first?.anime.id) {
            if let first = items.first { thumbnails.load(first.anime, episodes: first.anime.anime.episodes) }
        }
    }
    private func row(_ index: Int, episode: Episode?, pictures: [Int: String]) -> some View {
        let item = items[index], playing = index == current
        let label = EpisodeMatch.title(item.displayNumber)
        let named = item.title.isEmpty || item.title == label || item.title == "Episode \(item.number)" ? "" : item.title
        let playable = episode?.playable ?? true
        let saved = downloads.state(item) == .completed
        let meta = [playing ? currentLabel : "", episode?.dubbed == true ? "더빙" : "", String(episode?.airedDate.split(separator: "T").first ?? ""),
                    saved ? "저장됨" : "", playable ? "" : "재생 불가"].filter { !$0.isEmpty }.joined(separator: " · ")
        let picture = episode.map(\.thumbnailUrl).flatMap { $0.hasPrefix("http") ? $0 : nil } ?? pictures[item.number] ?? ""
        let progress = watched(item)
        let muted = dark ? Color.white.opacity(0.5) : LilacStyle.muted
        return Button { pick(index) } label: {
            HStack(spacing: 12) {
                if !picture.isEmpty {
                    AnimeArtwork(url: picture, width: 128, height: 72).clipShape(RoundedRectangle(cornerRadius: 6))
                        .overlay(alignment: .bottom) { if progress > 0 { bar(progress).frame(height: 3) } }
                        .overlay { if playing { RoundedRectangle(cornerRadius: 6).stroke(LilacStyle.accent, lineWidth: 2) } }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(named.isEmpty ? label : named).font(.system(size: 13, weight: .semibold)).lineLimit(2)
                        let detail = [named.isEmpty ? "" : label, meta].filter { !$0.isEmpty }.joined(separator: " · ")
                        if !detail.isEmpty { Text(detail).font(.system(size: 11)).foregroundStyle(muted).lineLimit(1) }
                    }
                } else {
                    Text(label).font(.system(size: 14, weight: .bold)).monospacedDigit().foregroundStyle(playing ? LilacStyle.accent : Color.primary)
                        .frame(minWidth: 48, alignment: .leading)
                    VStack(alignment: .leading, spacing: 2) {
                        if !named.isEmpty { Text(named).font(.system(size: 13, weight: .semibold)).lineLimit(1) }
                        if !meta.isEmpty { Text(meta).font(.system(size: 11)).foregroundStyle(muted).lineLimit(1) }
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(picture.isEmpty ? EdgeInsets(top: 8, leading: 12, bottom: 10, trailing: 12) : EdgeInsets(top: 6, leading: 6, bottom: 6, trailing: 6))
            .frame(minHeight: picture.isEmpty ? 52 : 0)
            .background(playing ? LilacStyle.accent.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 10))
            .overlay(alignment: .bottom) { if picture.isEmpty && progress > 0 { bar(progress).frame(height: 3).padding(.horizontal, 12).padding(.bottom, 4) } }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!playable || (playing && !pickCurrent))
        .opacity(!playable && !playing ? 0.45 : 1)
        .accessibilityIdentifier("episode-pick")
    }
    private func bar(_ progress: Double) -> some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Rectangle().fill(dark ? Color.black.opacity(0.5) : LilacStyle.line)
                Rectangle().fill(Color(red: 0.9, green: 0.04, blue: 0.08)).frame(width: geometry.size.width * progress)
            }
        }.clipShape(RoundedRectangle(cornerRadius: 2))
    }
    /// How far it was watched (0–1), from 시청 기록.
    private func watched(_ item: PlaybackItem) -> Double {
        guard let entry = library.history.first(where: { $0.anime.id == item.anime.id && $0.episodeID == item.episodeID }) else { return 0 }
        return entry.duration > 0 ? min(1, entry.position / entry.duration) : min(1, (entry.progressPercent ?? 0) / 100)
    }
}

/// 첫회부터 / 최신회부터, the same setting in the player and on the home page.
struct EpisodeOrderButton: View {
    @AppStorage(EpisodeOrder.key) private var newestFirst = false
    var body: some View {
        Button { newestFirst.toggle() } label: {
            Text(newestFirst ? "최신회부터" : "첫회부터").font(.system(size: 12)).padding(.horizontal, 12).frame(height: 30)
                .overlay(Capsule().stroke(Color.primary.opacity(0.18), lineWidth: 1)).contentShape(Capsule())
        }.buttonStyle(.plain).accessibilityIdentifier("episode-order")
    }
}

/// The player's 회차 panel (index.html #episodePanel): dark like 플레이어 설정, at the same place.
struct PlayerEpisodePanel: View {
    let title: String
    let items: [PlaybackItem]
    let current: Int
    let close: () -> Void
    let pick: (Int) -> Void
    var body: some View {
        GeometryReader { geometry in
            let insets = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.windows.first(where: \.isKeyWindow)?.safeAreaInsets ?? .zero
            let top = max(geometry.size.height < 500 ? 12.0 : 72.0, insets.top + 8)
            let bottom = max(12, insets.bottom + 8)
            let right = max(16, insets.right + 12)
            ZStack(alignment: .topTrailing) {
                Button(action: close) { Color.black.opacity(0.16).contentShape(Rectangle()) }
                    .buttonStyle(.plain).accessibilityLabel("회차 목록 닫기")
                VStack(spacing: 0) {
                    HStack(spacing: 10) {
                        Image(systemName: "list.bullet").font(.system(size: 17)).frame(width: 36, height: 36)
                            .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                        VStack(alignment: .leading, spacing: 1) {
                            Text("회차").font(.system(size: 15, weight: .bold))
                            Text(title + " · \(items.count)화").font(.system(size: 11)).foregroundStyle(.white.opacity(0.5)).lineLimit(1)
                        }
                        Spacer(minLength: 4)
                        EpisodeOrderButton()
                        Button(action: close) { Image(systemName: "xmark").font(.system(size: 17)).frame(width: 42, height: 42).contentShape(Circle()) }
                            .buttonStyle(.plain).accessibilityLabel("닫기").keyboardShortcut(.escape, modifiers: [])
                    }.padding(.leading, 16).padding(.trailing, 10).padding(.vertical, 8)
                    PS.line.frame(height: 1)
                    EpisodePickList(items: items, current: current, pick: pick)
                }.frame(width: min(380, max(0, geometry.size.width - right - max(16, insets.left + 12))))
                    .frame(maxHeight: max(0, geometry.size.height - top - bottom))
                    .background(Color(red: 0.067, green: 0.067, blue: 0.086).opacity(0.97), in: RoundedRectangle(cornerRadius: 22))
                    .overlay(RoundedRectangle(cornerRadius: 22).stroke(.white.opacity(0.1), lineWidth: 1))
                    .shadow(color: .black.opacity(0.7), radius: 30, y: 12)
                    .modifier(LilacAppearMotion(x: 18, y: 0))
                    .padding(.top, top).padding(.trailing, right).padding(.bottom, bottom)
                    .accessibilityElement(children: .contain).accessibilityAddTraits(.isModal)
                    .accessibilityIdentifier("player-episode-panel")
            }.frame(width: geometry.size.width, height: geometry.size.height)
        }.foregroundStyle(.white).environment(\.colorScheme, .dark)
    }
}

/// 계속 시청하기 > 회차 (app.js openContinueEpisodes, Netflix's episodes from a card): the work's episodes, loaded as for
/// playing it (the newest entry keeps the list, an older one asks the source), to start any of them; one watched before
/// resumes where it was.
struct ContinueEpisodesSheet: View {
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    let entry: WatchEntry
    /// Called with the episode picked, to open once the sheet is gone.
    let start: (PlaybackItem) -> Void
    @State private var items: [PlaybackItem]? = nil
    @State private var current: Int? = nil
    @State private var service = IosServices()
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    Text(meta).font(.system(size: 12)).foregroundStyle(LilacStyle.muted).lineLimit(2)
                    Spacer(minLength: 4)
                    EpisodeOrderButton()
                }.padding(.horizontal, 20).padding(.vertical, 10)
                Divider()
                if let items {
                    EpisodePickList(items: items, current: current, currentLabel: "마지막으로 본 회차", pickCurrent: true, dark: false, pick: picked)
                } else {
                    ProgressView("회차를 불러오는 중…").frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(LilacStyle.background)
            .navigationTitle(DesktopCatalog.shared.title(entry.anime.anime, source: entry.anime.source, language: library.preferences.titleLanguage))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .task { await load() }
        .onDisappear { service.cancel() }
    }
    private var meta: String {
        guard let items else { return "회차를 불러오는 중…" }
        return items.count > 1 ? "\(items.count)화 · 마지막으로 본 회차 \(EpisodeMatch.title(entry.episodeLabel))" : "회차 목록을 불러오지 못해 마지막으로 본 회차만 보여요."
    }
    private func load() async {
        let saved = entry.anime.anime.episodes
        if let index = EpisodeMatch.index(saved, id: entry.episodeID, label: entry.episodeLabel, number: entry.number), !entry.byNumber {
            show(entry.anime, episodes: saved, index: index); return
        }
        var detail: SourceDetail? = nil
        if entry.anime.source != "local" {
            detail = await withCheckedContinuation { (continuation: CheckedContinuation<SourceDetail?, Never>) in
                service.detail(summary: entry.anime.anime, sourceKey: entry.anime.source) { detail, _ in continuation.resume(returning: detail) }
            }
        }
        let servers = detail?.servers ?? []
        let server = servers.first { $0.episodes.contains { $0.id == entry.episodeID } }
            ?? servers.first { EpisodeMatch.index($0.episodes, id: entry.episodeID, label: entry.episodeLabel, number: entry.number) != nil } ?? servers.first
        if let detail, let episodes = server?.episodes, !episodes.isEmpty {
            let anime = SavedAnime(AnimeSnapshot.shared.withEpisodes(anime: detail.anime, episodes: episodes), source: entry.anime.source)
            show(anime, episodes: episodes, index: EpisodeMatch.index(episodes, id: entry.episodeID, label: entry.episodeLabel, number: entry.number))
        } else {
            items = entry.byNumber ? [] : [PlaybackItem(entry: entry)]; current = items?.isEmpty == false ? 0 : nil
        }
    }
    private func show(_ anime: SavedAnime, episodes: [Episode], index: Int?) {
        items = episodes.indices.map { PlaybackItem.inSeries(anime, episodes: episodes, at: $0) }
        current = index
    }
    /// One watched before resumes where it was (EpisodePlayerModel.begin asks 시청 기록 for every episode).
    private func picked(_ index: Int) {
        guard let items, items.indices.contains(index) else { return }
        start(items[index]); dismiss()
    }
}
