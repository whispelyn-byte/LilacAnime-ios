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
        let callback: ([Anime]?, String?) -> Void = { [weak self] result, error in
            guard let self, token == self.generation else { return }
            self.loading = false; self.error = error
            if let result {
                let known = Set(self.items.map(\.id))
                self.items += result.filter { !known.contains($0.id) }
                self.canLoadMore = !result.isEmpty && self.mode == "browse"; self.page += 1
            }
        }
        if mode == "top" { service.top(period: "week", completion: callback) }
        else if mode == "schedule" { service.schedule(week: 0, completion: callback) }
        else {
            service.browse(sourceKey: source, query: query, page: page,
                filter: AnimeSnapshot.shared.fullFilter(genre: genre, year: year, season: season, format: format, status: status, studio: studio), completion: callback)
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
                        Text("Linkkf").tag("linkkf"); Text("ReAnime").tag("reanime"); Text("Animenosub").tag("animenosub")
                    }.pickerStyle(.segmented).onChange(of: model.source) { source in library.preferences.source = source; model.mode = "browse"; model.genre = ""; model.year = ""; model.format = ""; model.status = ""; model.season = ""; model.studio = ""; model.load(); model.loadFilters() }
                    if model.source == "reanime" {
                        Picker("목록", selection: $model.mode) { Text("검색").tag("browse"); Text("인기").tag("top"); Text("방영표").tag("schedule") }
                            .pickerStyle(.segmented).onChange(of: model.mode) { _ in model.load() }
                    }
                    if let error = model.error { Text(error).foregroundStyle(.red); Button("다시 시도") { model.load() } }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 145))], spacing: 18) {
                        ForEach(model.items, id: \.id) { anime in
                            NavigationLink { DetailView(summary: anime, source: model.source) } label: {
                                VStack(alignment: .leading) {
                                    AsyncImage(url: URL(string: anime.poster)) { image in image.resizable().scaledToFill() } placeholder: { Rectangle().fill(.secondary.opacity(0.15)) }
                                        .frame(height: 210).clipped().clipShape(RoundedRectangle(cornerRadius: 12))
                                    Text(anime.title).lineLimit(2).foregroundStyle(.primary)
                                }
                            }.buttonStyle(.plain)
                        }
                    }
                    if model.loading { ProgressView() }
                    else if model.items.isEmpty && model.error == nil { Text("검색 결과가 없습니다.") }
                    else if model.canLoadMore { Button("더 보기") { model.load(reset: false) } }
                }.padding()
            }.navigationTitle("LilacAnime")
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
    @Published var error: String?
    @Published var loading = false
    private let service = IosServices()
    func load(_ summary: Anime, source: String) {
        loading = true; error = nil
        service.detail(summary: summary, sourceKey: source) { [weak self] detail, error in
            self?.anime = detail?.anime; self?.servers = detail?.servers ?? []; self?.error = error; self?.loading = false
        }
    }
    deinit { service.close() }
}
struct DetailView: View {
    let summary: Anime
    let source: String
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var model = DetailModel()
    var body: some View {
        List {
            Section {
                Text((model.anime ?? summary).title).font(.title2.bold())
                Text((model.anime ?? summary).genres.joined(separator: " · ")).foregroundStyle(.secondary)
                Text((model.anime ?? summary).description.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression))
                Button(library.contains(summary, source: source) ? "즐겨찾기 해제" : "즐겨찾기 추가") { library.toggle(model.anime ?? summary, source: source) }
            }
            if model.loading { ProgressView() }
            if let error = model.error { Text(error); Button("다시 시도") { model.load(summary, source: source) } }
            let anime = model.anime ?? summary
            Section("메타데이터") {
                if !anime.native.isEmpty { Text(anime.native) }
                if !anime.romaji.isEmpty { Text(anime.romaji) }
                Text([anime.format, anime.year, anime.airedDate, anime.note].filter { !$0.isEmpty }.joined(separator: " · "))
                if !anime.studios.isEmpty { Text(anime.studios.joined(separator: ", ")) }
            }
            if !anime.reAnimeRelated.isEmpty {
                Section("관련 작품") {
                    ForEach(anime.reAnimeRelated, id: \.id) { related in
                        NavigationLink(related.title + " · " + related.relationType) {
                            DetailView(summary: AnimeSnapshot.shared.related(relation: related), source: "reanime")
                        }
                    }
                }
            }
            ForEach(model.servers, id: \.id) { server in
                Section(server.name) {
                    ForEach(Array(server.episodes.enumerated()), id: \.element.id) { index, episode in
                        NavigationLink(episode.title) {
                            EpisodePlayerView(item: playback(server.episodes, index: index))
                        }
                    }
                }
            }
        }.navigationTitle("작품 정보").task { if model.anime == nil { model.load(summary, source: source) } }
    }
    private func playback(_ episodes: [Episode], index: Int) -> PlaybackItem {
        let saved = SavedAnime(model.anime ?? summary, source: source)
        var item = PlaybackItem(anime: saved, episode: episodes[index])
        item.next = episodes.dropFirst(index + 1).map { PlaybackItem(anime: saved, episode: $0) }
        return item
    }
}
