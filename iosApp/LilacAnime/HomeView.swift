import SwiftUI
import LilacShared

struct HomeView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var navigation: DesktopNavigation
    @StateObject private var model = CatalogModel()
    @StateObject private var home = DesktopSourceModel()
    @State private var featured = 0
    @State private var day = (Calendar.current.component(.weekday, from: Date()) + 5) % 7
    private var heroItems: [Anime] { home.sections.first { $0.name == "이번 시즌" }?.items.isEmpty == false ? home.sections.first { $0.name == "이번 시즌" }!.items : items }
    var workspace = false
    let browse: () -> Void
    private var items: [Anime] { UIShowcase.enabled ? UIShowcase.items : model.items }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    if let anime = heroItems.isEmpty ? nil : heroItems[min(featured, heroItems.count - 1)] {
                        VStack(alignment: .leading, spacing: 12) {
                            FeaturedAnimeCard(anime: anime)
                            HStack {
                                NavigationLink { DetailView(summary: anime, source: anime.source == "jikan" ? "jikan" : model.source) } label: { Label("자세히 보기", systemImage: "play.fill") }.buttonStyle(.borderedProminent)
                                Button { library.toggle(anime, source: model.source) } label: { Label(library.contains(anime, source: model.source) ? "내 목록에서 제거" : "내 목록", systemImage: library.contains(anime, source: model.source) ? "checkmark" : "plus") }.buttonStyle(.bordered)
                            }.padding(.horizontal, 6)
                        }.padding(.horizontal, 16)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                ForEach(Array(heroItems.prefix(8).enumerated()), id: \.element.id) { index, candidate in
                                    Button { featured = index } label: {
                                        AnimeArtwork(url: candidate.poster, width: 55, height: 78)
                                            .clipShape(RoundedRectangle(cornerRadius: 8))
                                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(featured == index ? LilacStyle.accent : Color.clear, lineWidth: 2))
                                    }.accessibilityLabel(candidate.title)
                                }
                            }.padding(.horizontal, 20)
                        }
                    } else if model.loading {
                        RoundedRectangle(cornerRadius: 28).fill(LilacStyle.accent.opacity(0.1)).frame(height: 320)
                            .overlay { ProgressView("작품을 불러오는 중…") }.padding(.horizontal, 16)
                    }
                    if model.source == "linkkf" {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("방영 일정").font(.title3.bold()).padding(.horizontal, 20)
                            ScrollView(.horizontal, showsIndicators: false) { HStack {
                                ForEach(Array(["UP", "월", "화", "수", "목", "금", "토", "일"].enumerated()), id: \.offset) { index, title in
                                    Button { day = index - 1 } label: { LilacChip(text: title, selected: day == index - 1) }.buttonStyle(.plain)
                                }
                            }.padding(.horizontal, 20) }
                            AnimeRail(title: day < 0 ? "최신 업데이트" : "오늘의 애니메이션", items: day < 0 ? items : home.airing, source: model.source)
                        }
                    }
                    if !library.history.isEmpty {
                        VStack(alignment: .leading, spacing: 14) {
                            HStack { Text("계속 시청하기").font(.title3.bold()); Spacer(); Button("전체 보기") { navigation.section = "history" } }.padding(.horizontal, 20)
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 14) {
                                    ForEach(Array(library.history.prefix(10))) { entry in
                                        PlaybackButton(item: PlaybackItem(entry: entry)) {
                                            VStack(alignment: .leading, spacing: 8) {
                                                AnimeArtwork(url: entry.anime.anime.backdrop.isEmpty ? entry.anime.poster : entry.anime.anime.backdrop, width: 240, height: 132)
                                                    .clipShape(RoundedRectangle(cornerRadius: 18))
                                                    .overlay { Image(systemName: "play.circle.fill").font(.system(size: 38)).foregroundStyle(.white) }
                                                Text(entry.anime.title).font(.subheadline.bold()).lineLimit(1)
                                                Text(entry.episodeTitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                                ProgressView(value: entry.duration > 0 ? min(entry.position / entry.duration, 1) : 0)
                                            }.frame(width: 240).foregroundStyle(.primary)
                                        }.buttonStyle(.plain)
                                    }
                                }.padding(.horizontal, 20)
                            }
                        }
                    }
                    ForEach(Array(home.sections.enumerated()), id: \.offset) { _, section in
                        if !section.items.isEmpty { AnimeRail(title: section.name, items: section.items, source: model.source) { navigation.browse(section.name == "인기 작품" ? "top" : section.name == "이번 시즌" ? "season" : section.name == "PV" ? "pv" : section.name == "극장판" ? "movie" : "adult") } }
                    }
                    if model.source == "linkkf" && !items.isEmpty { AnimeRail(title: "최신 애니메이션", items: items, source: model.source) { navigation.section = "catalog" } }
                    if model.source != "linkkf" && !home.airing.isEmpty { AnimeRail(title: "방영 중", items: home.airing, source: model.source) { navigation.browse("schedule") } }
                    if UIShowcase.enabled { AnimeRail(title: "이번 시즌 신작", items: items, source: model.source) { navigation.browse("season") }; AnimeRail(title: "인기 작품", items: items.reversed(), source: model.source) { navigation.browse("top") } }
                    if !library.favorites.isEmpty {
                        VStack(alignment: .leading, spacing: 14) {
                            Text("내 목록").font(.title3.bold()).padding(.horizontal, 20)
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 14) {
                                    ForEach(Array(library.favorites.prefix(10))) { saved in
                                        NavigationLink { DetailView(summary: saved.anime, source: saved.source) } label: {
                                            AnimePosterCard(anime: saved.anime, width: 135, height: 190)
                                        }.buttonStyle(.plain)
                                    }
                                }.padding(.horizontal, 20)
                            }
                        }
                    }
                    if let error = model.error {
                        VStack(spacing: 12) {
                            LilacEmptyState(icon: "wifi.exclamationmark", title: "작품을 불러오지 못했어요", message: error)
                            Button("다시 시도") { model.load() }.buttonStyle(.borderedProminent)
                        }.padding(.horizontal, 20)
                    } else if items.isEmpty && !model.loading {
                        LilacEmptyState(icon: "sparkles", title: "새로운 작품을 찾아보세요", message: "다른 영상 소스를 선택하거나 탐색에서 검색해 보세요.")
                    }
                    Button(action: browse) { Label("전체 작품 둘러보기", systemImage: "square.grid.2x2").font(.subheadline.bold()).frame(maxWidth: .infinity).padding(18) }
                        .background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 20)).padding(.horizontal, 20)
                    if UIShowcase.enabled { Text("UI PREVIEW · 예시 데이터").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20) }
                }.padding(.top, 16).padding(.bottom, 28)
            }.background(LilacStyle.background)
                .toolbar(workspace ? .visible : .hidden, for: .navigationBar)
                .navigationTitle(workspace ? "홈" : "")
                .navigationBarTitleDisplayMode(.inline)
                .refreshable { if !UIShowcase.enabled { model.load(); home.load(source: model.source, day: max(day, 0)) } }
                .onChange(of: day) { value in if !UIShowcase.enabled { home.load(source: model.source, day: max(value, 0)) } }
                .task(id: library.preferences.source) { if !UIShowcase.enabled { featured = 0; home.load(source: library.preferences.source, day: max(day, 0)) } }
                .task { if !UIShowcase.enabled && model.items.isEmpty { model.source = library.preferences.source; model.load() } }
                .onChange(of: library.preferences.source) { source in if !UIShowcase.enabled && model.source != source { model.source = source; model.load() } }
        }
    }
    private func sourceName(_ source: String) -> String {
        ContentSources.name(source)
    }
}
