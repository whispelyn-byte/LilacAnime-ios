import SwiftUI
import LilacShared

@MainActor
final class CatalogModel: ObservableObject {
    @Published var items: [Anime] = []
    @Published var error: String?
    @Published var loading = false
    @Published var query = ""
    @Published var source = "linkkf"
    @Published var mode = "browse"
    @Published var genre = ""
    @Published var year = ""
    @Published var format = ""
    @Published var status = ""
    @Published var season = ""
    @Published var studio = ""
    @Published var filters: SourceFilters?
    func loadFilters() { service.filters(sourceKey: source) { [weak self] filters, _ in self?.filters = filters } }
    private var page: Int32 = 1
    @Published var canLoadMore = true
    private let service = IosServices()
    private var generation = UUID()
    func load(reset: Bool = true) {
        if reset { service.cancel(); generation = UUID(); page = 1; items = []; canLoadMore = true }
        else if loading || !canLoadMore { return }
        loading = true; error = nil
        let token = generation
        let indexed = DesktopCatalog.shared.search(query, source: source)
        let callback: ([Anime]?, String?) -> Void = { [weak self] result, error in
            guard let self, token == self.generation else { return }
            self.loading = false; self.error = error
            if result != nil || !indexed.isEmpty {
                let result = result ?? []
                if !indexed.isEmpty { self.error = nil }
                let known = Set(self.items.map(\.id))
                self.items += (indexed + result).filter { !known.contains($0.id) }.reduce(into: [Anime]()) { list, anime in if !list.contains(where: { $0.id == anime.id }) { list.append(anime) } }
                self.canLoadMore = !result.isEmpty && self.mode == "browse"; self.page += 1
            }
        }
        let searched: ([Anime]?, String?) -> Void = { [weak self] values, failure in
            guard let self, token == self.generation else { return }
            guard (values ?? []).isEmpty, TitleCandidates.shared.isKorean(title: self.query), !SecureKeys.load("tmdb").isEmpty else { callback(values, failure); return }
            self.service.titleVariants(query: self.query, credential: SecureKeys.load("tmdb")) { variants, _ in
                guard token == self.generation else { return }
                let candidates = (variants ?? []).filter { !TitleCandidates.shared.isKorean(title: $0) }
                guard !candidates.isEmpty else { callback(values, failure); return }
                var combined: [Anime] = []; var remaining = candidates.count
                for variant in candidates {
                    self.service.browse(sourceKey: self.source, query: variant, page: self.page,
                        filter: AnimeSnapshot.shared.fullFilter(genre: self.genre, year: self.year, season: self.season, format: self.format, status: self.status, studio: self.studio)) { result, _ in
                        guard token == self.generation else { return }
                        combined += result ?? []; remaining -= 1
                        if remaining == 0 { callback(combined, combined.isEmpty ? failure : nil) }
                    }
                }
            }
        }
        if mode == "top" { service.top(period: "week", completion: callback) }
        else if mode == "schedule" { service.sourceSchedule(sourceKey: source, day: 0, completion: callback) }
        else {
            service.browse(sourceKey: source, query: query, page: page,
                filter: AnimeSnapshot.shared.fullFilter(genre: genre, year: year, season: season, format: format, status: status, studio: studio), completion: searched)
        }
    }
    deinit { service.close() }
}
struct CatalogView: View {
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var model = CatalogModel()
    @State private var filters = false
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Picker("소스", selection: $model.source) {
                        ForEach(ContentSources.keys, id: \.self) { Text(ContentSources.name($0)).tag($0) }
                    }.pickerStyle(.menu).onChange(of: model.source) { source in library.preferences.source = source; model.mode = "browse"; model.genre = ""; model.year = ""; model.format = ""; model.status = ""; model.season = ""; model.studio = ""; model.load(); model.loadFilters() }
                    if model.source == "reanime" {
                        Picker("목록", selection: $model.mode) { Text("검색").tag("browse"); Text("인기").tag("top"); Text("방영표").tag("schedule") }
                            .pickerStyle(.segmented).onChange(of: model.mode) { _ in model.load() }
                    }
                    if let error = model.error { Text(error).foregroundStyle(.red); Button("다시 시도") { model.load() } }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 145))], spacing: 18) {
                        ForEach(model.items, id: \.id) { anime in
                            NavigationLink { DetailView(summary: anime, source: model.source) } label: {
                                AnimePosterCard(anime: anime)
                            }.buttonStyle(.plain)
                        }
                    }
                    if model.loading { ProgressView() }
                    else if model.items.isEmpty && model.error == nil { LilacEmptyState(icon: "magnifyingglass", title: "검색 결과가 없습니다", message: "검색어나 필터를 바꿔서 다시 찾아보세요.") }
                    else if model.canLoadMore { Button("더 보기") { model.load(reset: false) } }
                }.padding()
            }.background(LilacStyle.background).navigationTitle("탐색")
                .searchable(text: $model.query, prompt: "애니메이션 검색").onSubmit(of: .search) { model.mode = "browse"; model.load() }
                .toolbar { Button { filters = true } label: { Image(systemName: "line.3.horizontal.decrease.circle") } }
                .sheet(isPresented: $filters) {
                    NavigationStack {
                        Form {
                            filterInput("장르", text: $model.genre, values: model.filters?.genres ?? [])
                            filterInput("연도", text: $model.year, values: model.filters?.years ?? [])
                            filterInput("형식", text: $model.format, values: model.filters?.formats ?? [])
                            if model.source == "reanime" {
                                filterInput("상태", text: $model.status, values: model.filters?.statuses ?? [])
                                filterInput("시즌", text: $model.season, values: model.filters?.seasons ?? [])
                                filterInput("스튜디오", text: $model.studio, values: model.filters?.studios ?? [])
                            }
                        }.navigationTitle("필터").toolbar { Button("적용") { filters = false; model.load() } }
                    }
                }
                .task { if model.items.isEmpty { model.source = library.preferences.source; model.load(); model.loadFilters() } }
                .onChange(of: library.preferences.source) { source in if model.source != source { model.source = source } }
        }
    }
    private func filterInput(_ title: String, text: Binding<String>, values: [String]) -> some View {
        HStack {
            TextField(title, text: text)
            if !values.isEmpty {
                Menu("선택") {
                    Button("전체") { text.wrappedValue = "" }
                    ForEach(values, id: \.self) { value in Button(value) { text.wrappedValue = value } }
                }
            }
        }
    }

}
@MainActor
final class DetailModel: ObservableObject {
    @Published var anime: Anime?
    @Published var servers: [EpisodeServer] = []
    @Published var extras: SourceExtras?
    @Published var error: String?
    @Published var loading = false
    private let service = IosServices()
    func load(_ summary: Anime, source: String) {
        loading = true; error = nil
        service.detail(summary: summary, sourceKey: source) { [weak self] detail, error in
            Task { @MainActor in
                if let anime = detail?.anime {
                    Task { await DesktopCatalog.shared.enrich(anime, source: source, cast: true) }
                    if source == "linkkf" { self?.service.sourceExtras(anime: anime) { value, _ in self?.extras = value } }
                }
                self?.anime = detail?.anime; self?.servers = detail?.servers ?? []; self?.error = error; self?.loading = false
            }
        }
    }
    deinit { service.close() }
}
struct DetailView: View {
    let summary: Anime
    let source: String
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var model = DetailModel()
    @EnvironmentObject private var downloads: DownloadStore
    @ObservedObject private var names = DesktopCatalog.shared
    @State private var selectedTab = 0
    @State private var serverID: Int32 = 0
    private var anime: Anime { model.anime ?? summary }
    private var server: EpisodeServer? { model.servers.first { $0.id == serverID } ?? model.servers.first { $0.name == library.preferences.preferredServer } ?? model.servers.first }
    private var resume: (EpisodeServer, Int)? {
        for entry in library.history where entry.anime.id == source + ":" + anime.id {
            for server in model.servers {
                if let index = server.episodes.firstIndex(where: { $0.id == entry.episodeID }) { return (server, index) }
            }
        }
        if let server, !server.episodes.isEmpty { return (server, 0) }
        return nil
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                ZStack(alignment: .bottomLeading) {
                    AnimeArtwork(url: anime.backdrop.isEmpty ? anime.poster : anime.backdrop, height: 310)
                    LinearGradient(colors: [.black.opacity(0.1), .black.opacity(0.9)], startPoint: .top, endPoint: .bottom)
                    HStack(alignment: .bottom, spacing: 16) {
                        AnimeArtwork(url: anime.poster, width: 96, height: 140).clipShape(RoundedRectangle(cornerRadius: 16))
                        VStack(alignment: .leading, spacing: 9) {
                            AnimeDisplayTitle(anime: anime, source: source).font(.title2.bold()).lineLimit(3).minimumScaleFactor(0.85)
                            Text([anime.format, anime.year].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.subheadline).foregroundStyle(.white.opacity(0.8))
                            Text(anime.genres.prefix(3).joined(separator: " · ")).font(.caption).foregroundStyle(.white.opacity(0.7))
                        }
                    }.foregroundStyle(.white).padding(22)
                }.frame(height: 310).clipShape(RoundedRectangle(cornerRadius: 28)).padding(.horizontal, 16)
                HStack(spacing: 12) {
                    if let resume {
                        NavigationLink { EpisodePlayerView(item: playback(resume.0.episodes, index: resume.1)) } label: {
                            Label(resume.1 > 0 || library.history.contains(where: { $0.anime.id == source + ":" + anime.id }) ? "이어 보기" : "첫 회차 보기", systemImage: "play.fill")
                                .font(.subheadline.bold()).frame(maxWidth: .infinity).padding(.vertical, 15)
                                .foregroundStyle(.white).background(LilacStyle.accent, in: RoundedRectangle(cornerRadius: 16))
                        }.buttonStyle(.plain)
                    }
                    Button { library.toggle(anime, source: source) } label: {
                        Image(systemName: library.contains(anime, source: source) ? "heart.fill" : "heart")
                            .font(.title3).frame(minWidth: 52, minHeight: 48).background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 16))
                    }.accessibilityLabel(library.contains(anime, source: source) ? "즐겨찾기 해제" : "즐겨찾기 추가")
                }.padding(.horizontal, 20)
                HStack(spacing: 8) {
                    ForEach(Array(["회차", "작품 정보", "관련 작품"].enumerated()), id: \.offset) { index, title in
                        Button { selectedTab = index } label: { LilacChip(text: title, selected: selectedTab == index) }
                            .buttonStyle(.plain)
                    }
                }.padding(.horizontal, 20)
                if model.loading { ProgressView("회차를 불러오는 중…").frame(maxWidth: .infinity) }
                if let error = model.error {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(error).font(.subheadline).foregroundStyle(.secondary)
                        Button("다시 시도") { model.load(summary, source: source) }
                    }.padding(18).frame(maxWidth: .infinity, alignment: .leading)
                        .background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 18)).padding(.horizontal, 20)
                }
                if selectedTab == 0 { episodes }
                else if selectedTab == 1 { information }
                else { related }
            }.padding(.top, 12).padding(.bottom, 30)
        }.background(LilacStyle.background)
            .navigationTitle("작품 정보").navigationBarTitleDisplayMode(.inline)
            .task {
                if UIShowcase.enabled {
                    model.anime = summary
                    model.servers = [EpisodeServer(id: 1, name: "기본 서버", episodes: summary.episodes)]
                } else if model.anime == nil { model.load(summary, source: source) }
            }
    }
    private var episodes: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("에피소드").font(.title3.bold())
                Text("\(server?.episodes.count ?? 0)").font(.subheadline).foregroundStyle(.secondary)
                Spacer()
                if !model.servers.isEmpty {
                    Menu {
                        ForEach(model.servers, id: \.id) { server in Button(server.name) { serverID = server.id; library.preferences.preferredServer = server.name } }
                    } label: { Label(server?.name ?? "서버", systemImage: "chevron.down").font(.caption.bold()) }
                }
            }
            if let server {
                Button { downloads.enqueue(server.episodes.indices.map { playback(server.episodes, index: $0) }, quality: library.preferences.quality) } label: {
                    Label("이 서버의 전체 회차 다운로드", systemImage: "arrow.down.circle").font(.subheadline)
                }.disabled(downloads.pendingResolution > 0)
                LazyVStack(spacing: 10) {
                    ForEach(Array(server.episodes.enumerated()), id: \.element.id) { index, episode in
                        NavigationLink { EpisodePlayerView(item: playback(server.episodes, index: index)) } label: {
                            HStack(spacing: 14) {
                                Text(episode.displayNumber.isEmpty ? String(episode.number) : episode.displayNumber)
                                    .font(.headline).frame(width: 44, height: 44)
                                    .background(LilacStyle.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 12)).foregroundStyle(LilacStyle.accent)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(episode.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                                    if episode.isFiller || episode.isRecap || !episode.playable {
                                        Text(!episode.playable ? "공개 예정" : episode.isRecap ? "총집편" : "필러").font(.caption).foregroundStyle(LilacStyle.accent)
                                    }
                                    Text("에피소드 \(episode.displayNumber.isEmpty ? String(episode.number) : episode.displayNumber)")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "play.circle.fill").font(.title2).foregroundStyle(LilacStyle.accent)
                            }.padding(14).background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 18)).foregroundStyle(.primary)
                        }.buttonStyle(.plain).disabled(!episode.playable).contextMenu {
                            Button { downloads.enqueue([playback(server.episodes, index: index)], quality: library.preferences.quality) } label: { Label("회차 다운로드", systemImage: "arrow.down.circle") }
                        }
                    }
                }
            } else if !model.loading {
                LilacEmptyState(icon: "play.rectangle", title: "회차 정보가 없습니다", message: "다른 영상 소스를 확인하거나 다시 불러와 주세요.")
            }
        }.padding(.horizontal, 20)
    }
    private var information: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("작품 소개").font(.title3.bold())
            Text(names.record(SavedAnime(anime, source: source))?.overview.isEmpty == false ? names.record(SavedAnime(anime, source: source))!.overview : (anime.description.isEmpty ? "등록된 소개가 없습니다." : anime.description.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)))
                .font(.subheadline).lineSpacing(6).foregroundStyle(.secondary)
            Divider()
            if let views = model.extras?.views, !views.isEmpty { Text("조회 " + views).font(.caption).foregroundStyle(.secondary) }
            ForEach(Array([("원제", anime.native), ("로마자", anime.romaji), ("영문명", anime.english),
                ("방영", anime.airedDate), ("제작", anime.studios.joined(separator: ", ")), ("장르", anime.genres.joined(separator: " · ")),
                ("비고", anime.note)].enumerated()), id: \.offset) { _, row in
                if !row.1.isEmpty {
                    HStack(alignment: .top) { Text(row.0).font(.subheadline).foregroundStyle(.secondary).frame(width: 60, alignment: .leading); Text(row.1).font(.subheadline); Spacer(minLength: 0) }
                }
            }
        }.padding(20).background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 22)).padding(.horizontal, 20)
    }
    private var related: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let extras = model.extras, !extras.related.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140))], spacing: 18) {
                    ForEach(extras.related, id: \.id) { related in
                        NavigationLink { DetailView(summary: related, source: source) } label: { AnimePosterCard(anime: related) }.buttonStyle(.plain)
                    }
                }
            } else if anime.reAnimeRelated.isEmpty {
                LilacEmptyState(icon: "square.stack", title: "관련 작품이 없습니다", message: "관련 작품 정보가 제공되면 여기에 표시됩니다.")
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140))], spacing: 18) {
                    ForEach(anime.reAnimeRelated, id: \.id) { relation in
                        NavigationLink { DetailView(summary: AnimeSnapshot.shared.related(relation: relation), source: source == "miruro" ? "miruro" : "reanime") } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                AnimePosterCard(anime: AnimeSnapshot.shared.related(relation: relation))
                                Text(relation.relationType).font(.caption).foregroundStyle(LilacStyle.accent)
                            }
                        }.buttonStyle(.plain)
                    }
                }
            }
        }.padding(.horizontal, 20)
    }
    private func playback(_ episodes: [Episode], index: Int) -> PlaybackItem {
        let saved = SavedAnime(AnimeSnapshot.shared.withEpisodes(anime: anime, episodes: episodes), source: source)
        var item = PlaybackItem(anime: saved, episode: episodes[index])
        item.next = episodes.dropFirst(index + 1).map { PlaybackItem(anime: saved, episode: $0) }
        return item
    }
}
