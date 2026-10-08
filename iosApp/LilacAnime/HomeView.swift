import SwiftUI
import LilacShared

struct HomeView: View {
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var model = CatalogModel()
    let browse: () -> Void
    private var items: [Anime] { UIShowcase.enabled ? UIShowcase.items : model.items }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("안녕하세요").font(.subheadline).foregroundStyle(.secondary)
                            Text("오늘은 무엇을 볼까요?").font(.title2.bold())
                        }
                        Spacer()
                        Button(action: browse) { Image(systemName: "magnifyingglass").font(.title3).padding(12).background(LilacStyle.card, in: Circle()) }
                            .accessibilityLabel("작품 검색")
                    }.padding(.horizontal, 20)
                    HStack {
                        Label("LilacAnime", systemImage: "sparkles").font(.subheadline.bold()).foregroundStyle(LilacStyle.accent)
                        Spacer()
                        Menu {
                            ForEach(ContentSources.keys, id: \.self) { source in
                                Button(sourceName(source)) {
                                    library.preferences.source = source; model.source = source
                                    if !UIShowcase.enabled { model.load() }
                                }
                            }
                        } label: {
                            HStack(spacing: 6) { Text(sourceName(model.source)); Image(systemName: "chevron.down").font(.caption.bold()) }
                                .font(.subheadline).foregroundStyle(.primary)
                        }
                    }.padding(.horizontal, 20)
                    if let anime = items.first {
                        NavigationLink { DetailView(summary: anime, source: model.source) } label: {
                            FeaturedAnimeCard(anime: anime)
                        }.buttonStyle(.plain).padding(.horizontal, 16)
                    } else if model.loading {
                        RoundedRectangle(cornerRadius: 28).fill(LilacStyle.accent.opacity(0.1)).frame(height: 320)
                            .overlay { ProgressView("작품을 불러오는 중…") }.padding(.horizontal, 16)
                    }
                    if !library.history.isEmpty {
                        VStack(alignment: .leading, spacing: 14) {
                            Text("계속 시청하기").font(.title3.bold()).padding(.horizontal, 20)
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 14) {
                                    ForEach(Array(library.history.prefix(10))) { entry in
                                        NavigationLink { EpisodePlayerView(item: PlaybackItem(entry: entry)) } label: {
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
                    if !items.isEmpty {
                        AnimeRail(title: "최신 애니메이션", items: Array(items.prefix(18)), source: model.source)
                        let movies = items.filter { $0.format.uppercased().contains("MOVIE") || $0.format.contains("극장") }
                        if !movies.isEmpty { AnimeRail(title: "극장판", items: movies, source: model.source) }
                    }
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
                .toolbar(.hidden, for: .navigationBar)
                .refreshable { if !UIShowcase.enabled { model.load() } }
                .task { if !UIShowcase.enabled && model.items.isEmpty { model.source = library.preferences.source; model.load() } }
                .onChange(of: library.preferences.source) { source in if !UIShowcase.enabled && model.source != source { model.source = source; model.load() } }
        }
    }
    private func sourceName(_ source: String) -> String {
        ContentSources.name(source)
    }
}
