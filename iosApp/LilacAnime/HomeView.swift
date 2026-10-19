import SwiftUI
import LilacShared

/// The desktop home (ott.css #homeView): the spotlight across the top, then rows of posters that scroll sideways.
struct HomeView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var navigation: DesktopNavigation
    @StateObject private var model = CatalogModel()
    @StateObject private var home = DesktopSourceModel()
    @ObservedObject private var recent = DesktopRecentUpdates.shared
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var homeOrder = "season"
    @State private var featured = 0
    @State private var day = ScheduleWeek.today
    @State private var scheduleRefresh = 0
    /// app.js renderHero: up to five shows with art; the one shown moves on every nine seconds.
    private var heroItems: [Anime] {
        let pool = home.sections.first { $0.name == "이번 시즌" }?.items.isEmpty == false ? home.sections.first { $0.name == "이번 시즌" }!.items : items
        let withArt = pool.filter { !$0.poster.isEmpty || !$0.backdrop.isEmpty }
        return Array((withArt.isEmpty ? pool : withArt).prefix(5))
    }
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var workspace = false
    let browse: () -> Void
    private var items: [Anime] { UIShowcase.enabled ? UIShowcase.items : model.items }
    private var scheduleKnown: [Anime] {
        items + home.airing + home.sections.flatMap { $0.items } + library.favorites.filter { $0.source == library.preferences.source }.map { $0.anime }
    }
    private var gutter: CGFloat { LilacStyle.gutter(sizeClass) }
    private var continuing: [WatchEntry] {
        var seen: Set<String> = []
        return Array(library.history.filter { seen.insert($0.anime.id).inserted }.prefix(6))
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    if !heroItems.isEmpty {
                        HomeHero(picks: heroItems, index: $featured, source: model.source)
                    } else if model.loading {
                        Rectangle().fill(LilacStyle.card).frame(height: 420).overlay { ProgressView("작품을 불러오는 중…") }
                    }
                    if !library.history.isEmpty { continueWatching }
                    if DesktopRecentUpdates.supported.contains(library.preferences.source) {
                        orderChips
                        if homeOrder == "updated" {
                            Text(recent.homeNote).font(.caption).foregroundStyle(LilacStyle.muted).padding(.horizontal, gutter)
                            AnimeRail(title: "최근 업데이트", items: recent.home, source: model.source) {
                                UserDefaults.standard.set("updated", forKey: "allSort:" + library.preferences.source); navigation.section = "catalog"
                            }
                        }
                    }
                    ForEach(Array(home.sections.enumerated()), id: \.offset) { _, section in
                        if !section.items.isEmpty && !(homeOrder == "updated" && section.name == "이번 시즌") { AnimeRail(title: section.name, items: section.items, source: model.source) { navigation.browse(section.name == "인기 작품" ? "top" : section.name == "이번 시즌" ? "season" : section.name == "PV" ? "pv" : section.name == "극장판" ? "movie" : "adult") } }
                    }
                    if model.source == "linkkf" && !items.isEmpty { AnimeRail(title: "최신 애니메이션", items: items, source: model.source) { navigation.section = "catalog" } }
                    if model.source != "linkkf" && !home.airing.isEmpty { AnimeRail(title: "방영 중", items: home.airing, source: model.source) { navigation.browse("schedule") } }
                    if UIShowcase.enabled { AnimeRail(title: "이번 시즌 신작", items: items, source: model.source) { navigation.browse("season") }; AnimeRail(title: "인기 작품", items: Array(items.reversed()), source: model.source) { navigation.browse("top") } }
                    if !library.favorites.isEmpty { favorites }
                    if let error = model.error {
                        VStack(spacing: 12) {
                            LilacEmptyState(icon: "wifi.exclamationmark", title: "작품을 불러오지 못했어요", message: error)
                            Button("다시 시도") { model.load() }.buttonStyle(LilacPrimaryButtonStyle())
                        }.padding(.horizontal, gutter)
                    } else if items.isEmpty && !model.loading {
                        LilacEmptyState(icon: "sparkles", title: "새로운 작품을 찾아보세요", message: "다른 영상 소스를 선택하거나 탐색에서 검색해 보세요.")
                    }
                    // The weekly schedule last, under the rails (asked for by testers: it pushed the rails down).
                    WeeklyScheduleView(source: library.preferences.source, known: scheduleKnown, refresh: scheduleRefresh).padding(.horizontal, gutter)
                    Button(action: browse) { Label("전체 작품 둘러보기", systemImage: "square.grid.2x2").frame(maxWidth: .infinity) }
                        .buttonStyle(LilacOutlineButtonStyle()).padding(.horizontal, gutter)
                    if UIShowcase.enabled { Text("UI PREVIEW · 예시 데이터").font(.caption).foregroundStyle(LilacStyle.muted).padding(.horizontal, gutter) }
                }.padding(.bottom, 40)
            }.background(LilacStyle.background)
                .toolbar(.hidden, for: .navigationBar)
                .refreshable { scheduleRefresh += 1; if !UIShowcase.enabled { model.load(); home.load(source: model.source, day: max(day, 0)) } }
                .onChange(of: day) { value in if !UIShowcase.enabled { home.load(source: model.source, day: max(value, 0)) } }
                .task(id: library.preferences.source) { if !UIShowcase.enabled { featured = 0; home.load(source: library.preferences.source, day: max(day, 0)) } }
                .task(id: "\(featured)/\(heroItems.count)") {
                    guard !UIShowcase.enabled, !reduceMotion, heroItems.count > 1 else { return }
                    do { try await Task.sleep(nanoseconds: 9_000_000_000) } catch { return }
                    featured = (min(featured, heroItems.count - 1) + 1) % heroItems.count
                }
                .task(id: library.preferences.source + homeOrder) { if homeOrder == "updated" && !UIShowcase.enabled { await recent.loadHome(library.preferences.source) } }
                .task { if !UIShowcase.enabled && model.items.isEmpty { model.source = library.preferences.source; model.load() } }
                .onChange(of: library.preferences.source) { source in if !UIShowcase.enabled && model.source != source { model.source = source; model.load() } }
        }
    }
    /// 이번 시즌 신작 / 최근 업데이트 as the desktop's sort chips.
    private var orderChips: some View {
        HStack(spacing: 2) {
            ForEach([("season", "이번 시즌 신작"), ("updated", "최근 업데이트")], id: \.0) { value, title in
                Button { homeOrder = value } label: { LilacChip(text: title, selected: homeOrder == value) }.buttonStyle(.plain)
            }
        }.padding(3).background(LilacStyle.card2, in: RoundedRectangle(cornerRadius: 8)).padding(.horizontal, gutter)
    }
    /// Continue watching (ott.css .continue-card): wide stills with the progress inside them, the title and episode under.
    private var continueWatching: some View {
        VStack(alignment: .leading, spacing: 12) {
            LilacSectionTitle(title: "계속 시청하기") { navigation.section = "history" }.padding(.horizontal, gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 10) {
                    ForEach(continuing) { entry in
                        let width: CGFloat = sizeClass == .regular ? 300 : 230
                        let progress = entry.duration > 0 ? min(entry.position / entry.duration, 1) : (entry.progressPercent ?? 0) / 100
                        PlaybackButton(item: PlaybackItem(entry: entry)) {
                            VStack(alignment: .leading, spacing: 0) {
                                AnimeArtwork(url: entry.anime.anime.backdrop.isEmpty ? entry.anime.poster : entry.anime.anime.backdrop, width: width, height: width * 9 / 16, alignment: .posterFocus)
                                    .clipShape(RoundedRectangle(cornerRadius: LilacStyle.radius))
                                    .overlay { Image(systemName: "play.fill").font(.system(size: 18)).foregroundStyle(.white).frame(width: 44, height: 44).background(.black.opacity(0.6), in: Circle()) }
                                    .overlay(alignment: .bottom) {
                                        ZStack(alignment: .leading) {
                                            Capsule().fill(.white.opacity(0.36))
                                            Capsule().fill(LilacStyle.accent).frame(width: max(0, (width - 16) * progress))
                                        }.frame(height: 4).padding(8)
                                    }
                                AnimeDisplayTitle(anime: entry.anime.anime, source: entry.anime.source).font(.system(size: 14, weight: .semibold)).foregroundStyle(LilacStyle.text).lineLimit(1).padding(.top, 8)
                                Text(entry.episodeTitle).font(.system(size: 12)).foregroundStyle(LilacStyle.accent).lineLimit(1).padding(.top, 3)
                            }.frame(width: width)
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal, gutter)
            }
        }
    }
    private var favorites: some View {
        VStack(alignment: .leading, spacing: 12) {
            LilacSectionTitle(title: "내 목록") { navigation.section = "favorites" }.padding(.horizontal, gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 10) {
                    ForEach(Array(library.favorites.prefix(10))) { saved in
                        NavigationLink { DetailView(summary: saved.anime, source: saved.source) } label: {
                            AnimePosterCard(anime: saved.anime, width: sizeClass == .regular ? 170 : 130, height: sizeClass == .regular ? 255 : 195)
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal, gutter)
            }
        }
    }
}
