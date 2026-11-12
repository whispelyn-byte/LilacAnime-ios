import SwiftUI
import LilacShared

@MainActor
final class CatalogModel: ObservableObject {
    @Published var items: [Anime] = []
    @Published var error: String?
    @Published var loading = false
    @Published var query = ""
    @Published var source = "reanime"
    @Published var mode = "browse"
    @Published var genre = ""
    @Published var year = ""
    @Published var format = ""
    @Published var status = ""
    @Published var season = ""
    @Published var studio = ""
    @Published var sort = ""
    @Published var extras: [String: String] = [:]
    @Published var filters: SourceFilters?
    @Published var filterError: String?
    @Published var filtersLoading = false
    @Published var searchNote: String?
    private let filterService = IosServices()
    func loadFilters() {
        filters = nil; filterError = nil; filtersLoading = true; let requestedSource = source
        if UIShowcase.enabled {
            filters = SourceFilters(genres: ["판타지", "모험"], years: ["2026"], formats: ["TV", "MOVIE"], statuses: [], seasons: ["WINTER", "SPRING", "SUMMER", "FALL"], studios: [], supportsYear: true, supportsSeason: true, sorts: ["popular", "year", "score"], note: "", seasonValues: [], extraFilters: [])
            filtersLoading = false; return
        }
        filterService.cancel()
        filterService.filters(sourceKey: source) { [weak self] filters, error in
            guard let self, self.source == requestedSource else { return }
            self.filters = filters; self.filterError = error; self.filtersLoading = false
        }
    }
    private var page: Int32 = 1
    @Published var canLoadMore = true
    private let service = IosServices()
    private var generation = UUID()
    private var catalogSignature: String?
    private var indexSearch: Task<Void, Never>?
    func load(reset: Bool = true) {
        if reset { indexSearch?.cancel(); service.cancel(); generation = UUID(); page = 1; items = []; canLoadMore = true; catalogSignature = nil }
        else if loading || !canLoadMore { return }
        loading = true; error = nil
        let token = generation
        if mode == "catalog" {
            service.catalog(sourceKey: source, query: query, page: page,
                filter: AnimeSnapshot.shared.extendedFilter(genre: genre, year: year, season: season, format: format, sort: sort, extras: extras)) { [weak self] result, failure in
                guard let self, token == self.generation else { return }
                self.loading = false; self.error = failure
                guard let result else { return }
                let known = Set(self.items.map(\.id))
                self.items += result.items.filter { !known.contains($0.id) }
                self.canLoadMore = result.hasMore && result.signature != self.catalogSignature
                self.catalogSignature = result.signature; self.page += 1
            }
            return
        }
        // Resolve the local index off the UI thread before combining it with the site's results.
        let source = source, query = query
        let useIndex = [genre, year, format, status, season, studio].allSatisfy(\.isEmpty)
        indexSearch = Task { [weak self] in
            let indexed = useIndex ? await DesktopCatalog.shared.search(query, source: source) : []
            guard let self, token == self.generation else { return }
            self.loadRemote(indexed: indexed, token: token)
        }
    }
    private func loadRemote(indexed: [Anime], token: UUID) {
        let callback: ([Anime]?, String?) -> Void = { [weak self] result, error in
            guard let self, token == self.generation else { return }
            self.loading = false; self.error = error
            if result != nil || !indexed.isEmpty {
                let result = result ?? []
                if !indexed.isEmpty { self.error = nil }
                let known = Set(self.items.map(\.id))
                self.items += (indexed + result).filter { !known.contains($0.id) }.reduce(into: [Anime]()) { list, anime in if !list.contains(where: { $0.id == anime.id }) { list.append(anime) } }
                self.canLoadMore = !result.isEmpty && ["browse", "top", "season", "pv", "movie", "adult"].contains(self.mode); self.page += 1
            }
        }
        // app.js doSearch: the first page shows at once, then the query is searched again under its other-language titles.
        let typed = query.trimmingCharacters(in: .whitespacesAndNewlines), first = page == 1
        let searched: ([Anime]?, String?) -> Void = { [weak self] values, failure in
            guard let self, token == self.generation else { return }
            callback(values, failure)
            guard first, !typed.isEmpty, self.mode == "browse", [self.genre, self.year, self.format, self.status, self.season, self.studio].allSatisfy(\.isEmpty) else { return }
            if self.items.isEmpty { self.searchNote = "다른 언어 제목으로 찾는 중..." }
            Task { await self.searchVariants(typed, token: token) }
        }
        searchNote = nil
        if mode == "top" { service.browse(sourceKey: source, query: "", page: page, filter: AnimeSnapshot.shared.sortedFilter(genre: "", year: "", season: "", format: "", status: "", studio: "", sort: "popular"), completion: callback) }
        else if mode == "schedule" { service.sourceSchedule(sourceKey: source, day: Int32((Calendar.current.component(.weekday, from: Date()) + 5) % 7), completion: callback) }
        // Animenosub's and Miruro's own searches are English only: a Korean query returns their newest posts instead.
        else if Self.englishOnly(source, query: typed) { searched([], nil) }
        else {
            service.browse(sourceKey: source, query: query, page: page,
                filter: AnimeSnapshot.shared.sortedFilter(genre: genre, year: year, season: season, format: format, status: status, studio: studio, sort: sort), completion: searched)
        }
    }
    static func englishOnly(_ source: String, query: String) -> Bool { ["animenosub", "miruro"].contains(source) && TitleCandidates.shared.isKorean(title: query) }
    /// The query's other-language titles (the names already known, then TMDB), searched six at a time; new results are added.
    private func searchVariants(_ query: String, token: UUID) async {
        var variants = await DesktopCatalog.shared.variants(query)
        guard token == generation else { return }
        let credential = SecureKeys.load("tmdb"), korean = TitleCandidates.shared.isKorean(title: query)
        if !credential.isEmpty {
            let remote: [String] = await withCheckedContinuation { continuation in
                service.titleVariants(query: query, credential: credential) { values, _ in continuation.resume(returning: values ?? []) }
            }
            for value in remote where TitleCandidates.shared.isKorean(title: value) != korean && !variants.contains(value) { variants.append(value) }
        }
        let key = TitleKeys.key(query)
        let usable = Array(variants.filter { TitleKeys.key($0) != key && !Self.englishOnly(source, query: $0) }.prefix(30))
        guard token == generation else { return }
        guard !usable.isEmpty else { searchNote = nil; return }
        var added = 0
        for start in stride(from: 0, to: usable.count, by: 6) {
            let chunk = Array(usable[start..<min(usable.count, start + 6)])
            var lists = Array(repeating: [Anime](), count: chunk.count)
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                var remaining = chunk.count
                for (index, text) in chunk.enumerated() {
                    service.browse(sourceKey: source, query: text, page: 1, filter: AnimeSnapshot.shared.fullFilter(genre: "", year: "", season: "", format: "", status: "", studio: "")) { values, _ in
                        lists[index] = values ?? []; remaining -= 1
                        if remaining == 0 { done.resume() }
                    }
                }
            }
            guard token == generation else { return }
            var known = Set(items.map(\.id))
            let fresh = lists.flatMap { $0 }.filter { known.insert($0.id).inserted }
            items += fresh; added += fresh.count
        }
        searchNote = added > 0 ? "다른 언어 제목 " + (usable.count > 3 ? usable.prefix(3).joined(separator: ", ") + " 외 \(usable.count - 3)개" : usable.joined(separator: ", ")) + " 포함" : nil
    }
    deinit { service.close(); filterService.close() }
}
/// The desktop's search (index.html #searchView): a heading, the big search box, the tag filters behind 태그 필터, the posters.
struct CatalogView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var model = CatalogModel()
    @EnvironmentObject private var navigation: DesktopNavigation
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var filters = false
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        // desktop doSearch: once searched, the page's title says what was searched.
                        let searched = navigation.query.trimmingCharacters(in: .whitespacesAndNewlines)
                        Text(searched.isEmpty ? "어떤 이야기를 찾고 있나요?" : "“" + searched + "”").font(.system(size: sizeClass == .regular ? 34 : 26, weight: .heavy)).foregroundStyle(LilacStyle.text).lineLimit(2)
                        if searched.isEmpty { Text("제목을 검색해 작품 정보와 평점을 확인하세요.").font(.system(size: 14)).foregroundStyle(LilacStyle.muted) }
                    }
                    // .big-search: one wide field with the button inside its end.
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass").foregroundStyle(LilacStyle.muted)
                        TextField("애니메이션 제목 입력", text: $model.query).submitLabel(.search).onSubmit(search)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("search-field")
                        Button("검색", action: search).buttonStyle(LilacPrimaryButtonStyle())
                    }.font(.system(size: 16)).padding(.leading, 14).padding(.trailing, 5).padding(.vertical, 5)
                        .background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 8)).overlay(RoundedRectangle(cornerRadius: 8).stroke(LilacStyle.line))
                    HStack(spacing: 8) {
                        Button { withAnimation(LilacMotion.animation(reduced: reduceMotion)) { filters.toggle() } } label: {
                            Label("태그 필터", systemImage: filters ? "chevron.up" : "line.3.horizontal.decrease")
                        }.buttonStyle(LilacOutlineButtonStyle())
                    }
                    if filters { filterPanel }
                    if let error = model.error {
                        Text(error).font(.system(size: 13)).foregroundStyle(.red)
                        Button("다시 시도") { model.load() }.buttonStyle(LilacGhostButtonStyle())
                    }
                    if let note = model.searchNote { Text(note).font(.system(size: 13)).foregroundStyle(LilacStyle.muted) }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 30) {
                        ForEach(model.items, id: \.id) { anime in
                            NavigationLink { DetailView(summary: anime, source: model.source) } label: {
                                AnimePosterCard(anime: anime)
                            }.buttonStyle(.plain)
                        }
                    }
                    if model.loading { ProgressView().tint(LilacStyle.accent).frame(maxWidth: .infinity) }
                    else if model.items.isEmpty && model.error == nil { LilacEmptyState(icon: "magnifyingglass", title: "검색 결과가 없습니다", message: "검색어나 필터를 바꿔서 다시 찾아보세요.") }
                    else if model.canLoadMore { Button("더 불러오기") { model.load(reset: false) }.buttonStyle(LilacGhostButtonStyle()).frame(maxWidth: .infinity) }
                }.padding(.horizontal, LilacStyle.gutter(sizeClass)).padding(.vertical, 20)
            }.background(LilacStyle.background).navigationTitle("검색").toolbar(.hidden, for: .navigationBar)
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: model.source) { source in library.preferences.source = source; model.mode = "browse"; clearFilters(); model.load(); model.loadFilters() }
                .onChange(of: navigation.query) { _ in applyNavigation() }
                .onChange(of: navigation.listMode) { _ in applyNavigation() }
                .task { if model.items.isEmpty { model.source = library.preferences.source; applyNavigation(); model.loadFilters() } }
                .onChange(of: library.preferences.source) { source in if model.source != source { model.source = source } }
        }
    }
    private func search() { navigation.search(model.query); model.mode = "browse"; model.load() }
    private func clearFilters() { model.genre = ""; model.year = ""; model.format = ""; model.status = ""; model.season = ""; model.studio = "" }
    /// .filter-panel: each tag group as a row of chips, then 초기화 and 적용.
    private var filterPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            filterGroup("시즌 타입", text: $model.format, values: model.filters?.formats ?? [])
            filterGroup("장르", text: $model.genre, values: model.filters?.genres ?? [])
            filterGroup("연도", text: $model.year, values: model.filters?.years ?? [])
            if ["reanime", "miruro", "animenosub"].contains(model.source) {
                filterGroup("상태", text: $model.status, values: model.filters?.statuses ?? ["RELEASING", "FINISHED"])
                filterGroup("분기", text: $model.season, values: model.filters?.seasons ?? ["WINTER", "SPRING", "SUMMER", "FALL"])
            }
            if model.source == "reanime" { filterGroup("스튜디오", text: $model.studio, values: model.filters?.studios ?? []) }
            HStack(spacing: 8) {
                Spacer()
                Button("초기화") { clearFilters(); model.mode = "browse"; model.load() }.buttonStyle(LilacGhostButtonStyle())
                Button("적용") { model.load() }.buttonStyle(LilacPrimaryButtonStyle())
            }
        }.padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(LilacStyle.line))
    }
    /// .filter-group: its name, then 전체 and its values as chips (typed in when the source lists none).
    @ViewBuilder private func filterGroup(_ title: String, text: Binding<String>, values: [String]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 13, weight: .bold)).foregroundStyle(LilacStyle.text)
            if values.isEmpty {
                TextField(title, text: text).font(.system(size: 13)).padding(.horizontal, 10).frame(height: 36)
                    .background(LilacStyle.card2, in: RoundedRectangle(cornerRadius: 7)).overlay(RoundedRectangle(cornerRadius: 7).stroke(LilacStyle.line))
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach([""] + values, id: \.self) { value in
                            Button { text.wrappedValue = value } label: { LilacChip(text: value.isEmpty ? "전체" : value, selected: text.wrappedValue == value) }.buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }
    private func applyNavigation() {
        model.query = navigation.query; model.mode = navigation.listMode
        model.genre = ""; model.year = ""; model.season = ""; model.format = ""; model.status = ""; model.studio = ""
        if model.mode == "season" {
            model.year = String(Calendar.current.component(.year, from: Date()))
            model.season = ["WINTER", "SPRING", "SUMMER", "FALL"][(Calendar.current.component(.month, from: Date()) - 1) / 3]
        }
        if model.source == "linkkf" { model.format = ["pv": "5086", "movie": "5061", "adult": "5085"][model.mode] ?? "" }
        if UIShowcase.enabled { model.items = UIShowcase.items; model.canLoadMore = false } else { model.load() }
    }
}
@MainActor
final class DetailModel: ObservableObject {
    @Published var anime: Anime?
    @Published var servers: [EpisodeServer] = []
    @Published var extras: SourceExtras?
    /// 작품 정보 더 보기 (desktop 19110b2): AniList's and MAL's extras, filled in when they come (nil without an entry).
    @Published var anilist: AnimeExtras?
    @Published var error: String?
    @Published var loading = false
    private let service = IosServices()
    private var generation = UUID()
    /// Only the latest ask is shown (desktop openDetail.token).
    func load(_ summary: Anime, source: String) {
        generation = UUID(); let token = generation
        loading = true; error = nil
        let done: (SourceDetail?, String?) -> Void = { [weak self] detail, error in
            Task { @MainActor in
                guard let self, token == self.generation else { return }
                if let anime = detail?.anime {
                    Task { await DesktopCatalog.shared.enrich(anime, source: source, cast: true) }
                    if source == "linkkf" { self.service.sourceExtras(anime: anime) { [weak self] value, _ in self?.extras = value } }
                    self.service.animeExtras(anime: anime, sourceKey: source) { [weak self] value, _ in
                        Task { @MainActor in guard let self, token == self.generation else { return }; self.anilist = value }
                    }
                }
                self.anime = detail?.anime; self.servers = detail?.servers ?? []; self.error = error; self.loading = false
            }
        }
        service.detail(summary: summary, sourceKey: source, completion: done)
    }
    func recordView() async {
        guard let anime, anime.source == "linkkf" else { return }
        do { try await Task.sleep(nanoseconds: 9_000_000_000); try Task.checkCancellation() } catch { return }
        service.recordView(anime: anime) { [weak self] value, _ in Task { @MainActor in self?.extras = value } }
    }
    deinit { service.close() }
}
struct DetailView: View {
    let summary: Anime
    let source: String
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var navigation: DesktopNavigation
    @StateObject private var model = DetailModel()
    @EnvironmentObject private var downloads: DownloadStore
    @ObservedObject private var names = DesktopCatalog.shared
    @State private var selectedTab = 0
    @State private var serverID: Int32 = 0
    @ObservedObject private var artworks = TitleArtworkStore.shared
    @State private var art: LoadedArtwork?
    @State private var artReady = false
    @Environment(\.colorScheme) private var scheme
    private var anime: Anime { model.anime ?? summary }
    private var server: EpisodeServer? { model.servers.first { $0.id == serverID } ?? model.servers.first { $0.name == library.preferences.preferredServer } ?? model.servers.first }
    @State private var page = 0
    @State private var newestFirst = false
    /// Detail 재생 (app.js resumeEpisode): the last played episode of this series, found by its id or else by its number,
    /// resumed below 95% and started over from there on; without one, the first playable episode.
    private var resume: (EpisodeServer, Int, Bool)? {
        if let last = library.history.first(where: { $0.anime.id == source + ":" + anime.id }) {
            for server in model.servers {
                if let index = server.episodes.firstIndex(where: { $0.id == last.episodeID }) { return (server, index, resumable(last)) }
            }
            for server in model.servers {
                if let index = server.episodes.firstIndex(where: { Int($0.number) == last.number && $0.playable }) { return (server, index, resumable(last)) }
            }
        }
        if let server, let index = server.episodes.firstIndex(where: \.playable) { return (server, index, false) }
        return nil
    }
    private func resumable(_ entry: WatchEntry) -> Bool { entry.position > 0 && (entry.duration <= 0 || entry.position / entry.duration < 0.95) }
    private var pageSize: Int { source == "reanime" ? 100 : 50 }
    private var sortKey: String { "episodeNewestFirst:" + source + ":" + anime.id }
    private func episodeName(_ episode: Episode) -> String { episode.displayNumber.isEmpty ? String(episode.number) : episode.displayNumber }
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var wide: Bool { sizeClass == .regular }
    private var gutter: CGFloat { LilacStyle.gutter(sizeClass) }
    private var heroHeight: CGFloat { wide ? 460 : 300 }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // ott.css #detailDialog: a tall still on top fading into the page, the poster and title coming up over it.
                // The wide header picture (TMDB backdrop, AniList banner) when there is one. The page is drawn at once with
                // what is at hand (desktop 1ba25c7: it waited up to 1.5 s); a picture still on its way is filled in when it comes.
                ZStack {
                    if let banner = art?.banner, !banner.isEmpty {
                        AnimeArtwork(url: banner, height: heroHeight, preloaded: artworks.image(banner))
                    } else if artReady {
                        AnimeArtwork(url: anime.poster, height: heroHeight, alignment: .posterFocus)
                    } else { LilacStyle.card2 }
                    LinearGradient(stops: [.init(color: LilacStyle.background, location: 0), .init(color: LilacStyle.background.opacity(0.55), location: 0.32), .init(color: .clear, location: 0.7)],
                                   startPoint: .bottom, endPoint: .top)
                }.frame(height: heroHeight).clipped()
                VStack(alignment: .leading, spacing: 16) {
                    titleBlock
                    if !anime.genres.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(anime.genres, id: \.self) { genre in
                                    Text(genre).font(.system(size: 11, weight: .medium)).foregroundStyle(LilacStyle.text)
                                        .padding(.horizontal, 10).padding(.vertical, 5).background(LilacStyle.card2, in: Capsule())
                                }
                            }
                        }
                    }
                    let overview = DisplayText.shared.plain(value: names.record(anime, source: source)?.overview.isEmpty == false ? names.record(anime, source: source)!.overview : anime.description_)
                    if !overview.isEmpty {
                        Text(overview).font(.system(size: 14)).lineSpacing(5).foregroundStyle(LilacStyle.muted).lineLimit(4)
                    }
                    ViewThatFits(in: .horizontal) { actions(labels: true); actions(labels: false) }
                    tabs
                    if model.loading { ProgressView("회차를 불러오는 중…").frame(maxWidth: .infinity) }
                    if let error = model.error {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(error).font(.subheadline).foregroundStyle(LilacStyle.muted)
                            Button("다시 시도") { model.load(summary, source: source) }.buttonStyle(LilacOutlineButtonStyle())
                        }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
                            .background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 8))
                    }
                    if selectedTab == 0 { episodes }
                    else if selectedTab == 1 { information }
                    else { related }
                }.padding(.horizontal, gutter).padding(.top, wide ? -200 : -130).padding(.bottom, 30)
            }
        }.background(LilacStyle.background)
            .navigationTitle("").navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .task(id: model.anime?.id) { if !UIShowcase.enabled { await model.recordView() } }
            .task(id: model.anime == nil ? "summary" : "detail") { await loadArtwork() }
            .task {
                if UIShowcase.enabled {
                    model.anime = summary
                    model.servers = [EpisodeServer(id: 1, name: "기본 서버", episodes: summary.episodes)]
                } else if model.anime == nil { model.load(summary, source: source) }
                newestFirst = UserDefaults.standard.bool(forKey: sortKey)
            }
    }
    /// The artwork asked for alongside the work's details (from its card first); when the card's titles do not find both
    /// pictures, the details' (with the original title) are tried too. A picture already kept is shown at once, the rest
    /// filled in when it comes.
    private func loadArtwork() async {
        if art == nil, let kept = artworks.cached(summary, source: source) { art = kept }
        artReady = true
        guard !UIShowcase.enabled else { return }
        var found = await artworks.artwork(summary, source: source)
        if !found.complete, let full = model.anime { found = await artworks.artwork(full, source: source) }
        guard !Task.isCancelled else { return }
        if found.complete || art?.complete != true { art = found }
    }
    /// The poster beside the title, the original title and a line of facts (#detailDialog .detail-body). Under the Korean
    /// title logo the text title stays, small: the logo is the series', the title says which season it is.
    private var titleBlock: some View {
        HStack(alignment: .bottom, spacing: wide ? 30 : 16) {
            AnimeArtwork(url: anime.poster, width: wide ? 170 : 108, height: wide ? 255 : 162)
                .clipShape(RoundedRectangle(cornerRadius: LilacStyle.radius)).shadow(color: .black.opacity(0.5), radius: 16, y: 8)
            VStack(alignment: .leading, spacing: 6) {
                if let art, art.logo(dark: scheme != .light) != nil {
                    TitleLogo(art: art, maxWidth: wide ? 440 : 230, maxHeight: wide ? 150 : 90).padding(.bottom, 4)
                    AnimeDisplayTitle(anime: anime, source: source).font(.system(size: wide ? 16 : 14, weight: .bold)).foregroundStyle(LilacStyle.muted)
                        .lineLimit(2)
                } else {
                    AnimeDisplayTitle(anime: anime, source: source).font(.system(size: wide ? 40 : 25, weight: .black)).foregroundStyle(LilacStyle.text)
                        .lineLimit(3).minimumScaleFactor(0.7)
                }
                if !anime.native.isEmpty { Text(anime.native).font(.system(size: 14)).foregroundStyle(LilacStyle.muted).lineLimit(1) }
                HStack(spacing: 8) {
                    if anime.score > 0 { Text("★ " + String(format: "%g", (anime.score * 100).rounded() / 100)).foregroundStyle(LilacStyle.score) }
                    Text([anime.format, anime.year, anime.status, anime.studios.first ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")).foregroundStyle(LilacStyle.muted)
                }.font(.system(size: 13, weight: .medium)).lineLimit(2)
                if let airing = airingLine { Text(airing).font(.system(size: 12, weight: .semibold)).foregroundStyle(LilacStyle.accent).lineLimit(2) }
            }.padding(.bottom, 4)
        }
    }
    /// ○화 재생 filled white, 내 목록 and 작품 정보 beside it; on a narrow screen the last two as icons.
    private func actions(labels: Bool) -> some View {
        HStack(spacing: 8) {
            if let resume {
                PlaybackButton(item: playback(resume.0.episodes, index: resume.1)) {
                    Label(episodeName(resume.0.episodes[resume.1]) + "화 " + (resume.2 ? "이어보기" : "재생"), systemImage: "play.fill")
                }.buttonStyle(LilacPrimaryButtonStyle()).accessibilityIdentifier("detail-play")
            }
            let saved = library.contains(anime, source: source)
            Button { library.toggle(anime, source: source) } label: {
                if labels { Label("내 목록", systemImage: saved ? "heart.fill" : "heart") } else { Image(systemName: saved ? "heart.fill" : "heart") }
            }.buttonStyle(LilacGhostButtonStyle()).foregroundStyle(saved ? LilacStyle.accent : LilacStyle.text)
                .accessibilityLabel(saved ? "즐겨찾기 해제" : "즐겨찾기 추가")
            // app.js openDetail: the source's own page for the work (작품 정보).
            if let page = URL(string: ReAnimeHost.shared.live(url: anime.detailUrl)), ["http", "https"].contains(page.scheme ?? "") {
                Link(destination: page) {
                    if labels { Label("작품 정보", systemImage: "arrow.up.right.square") } else { Image(systemName: "arrow.up.right.square") }
                }.buttonStyle(LilacGhostButtonStyle()).accessibilityLabel("사이트에서 작품 정보 보기")
            }
            if let trailer = model.anilist?.trailer, !trailer.isEmpty, let url = URL(string: trailer) {
                Link(destination: url) {
                    if labels { Label("PV", systemImage: "play.rectangle") } else { Image(systemName: "play.rectangle") }
                }.buttonStyle(LilacGhostButtonStyle()).accessibilityLabel("PV 보기")
            }
        }.fixedSize(horizontal: true, vertical: false)
    }
    /// Tabs underlined in lilac (#detailDialog tabs).
    private var tabs: some View {
        HStack(spacing: 22) {
            ForEach(Array(["회차", "작품 정보", "관련 작품"].enumerated()), id: \.offset) { index, title in
                Button { selectedTab = index } label: {
                    VStack(spacing: 9) {
                        Text(title).font(.system(size: 14, weight: selectedTab == index ? .bold : .semibold))
                            .foregroundStyle(selectedTab == index ? LilacStyle.text : LilacStyle.muted)
                        Rectangle().fill(selectedTab == index ? LilacStyle.accent : .clear).frame(height: 2)
                    }.fixedSize()
                }.buttonStyle(.plain)
            }
            Spacer()
        }.overlay(alignment: .bottom) { LilacStyle.line.frame(height: 1) }
    }
    private func episodeCount(_ server: EpisodeServer) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 5) {
            Text("회차").font(.system(size: 13, weight: .bold)).foregroundStyle(LilacStyle.accent)
            Text("\(server.episodes.count)화").font(.system(size: 11)).foregroundStyle(LilacStyle.muted)
        }
    }
    /// The server when there are several, the order and 전체 저장 (#detailDialog .episode-sort, .batch-download).
    private func episodeTools(_ server: EpisodeServer) -> some View {
        HStack(spacing: 8) {
            if model.servers.count > 1 {
                Menu {
                    ForEach(model.servers, id: \.id) { server in Button(server.name) { serverID = server.id; page = 0; library.preferences.preferredServer = server.name } }
                } label: {
                    HStack(spacing: 5) { Text(server.name).lineLimit(1); Image(systemName: "chevron.down").font(.system(size: 10, weight: .bold)) }
                        .font(.system(size: 13, weight: .semibold)).foregroundStyle(LilacStyle.text).padding(.horizontal, 13).frame(height: 34)
                        .overlay(RoundedRectangle(cornerRadius: LilacStyle.radius).stroke(LilacStyle.line))
                }
            }
            Button { newestFirst.toggle(); UserDefaults.standard.set(newestFirst, forKey: sortKey); page = 0 } label: {
                Text(newestFirst ? "최신화순" : "오래된화순")
            }.buttonStyle(LilacOutlineButtonStyle())
            Button {
                let targets = server.episodes.indices.filter { server.episodes[$0].playable }.map { playback(server.episodes, index: $0) }.filter { downloads.state($0) == .idle }
                downloads.enqueue(targets, quality: library.preferences.quality)
            } label: { Label("전체 저장", systemImage: "icloud.and.arrow.down") }.buttonStyle(LilacOutlineButtonStyle())
        }.fixedSize()
    }
    private var episodes: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let server {
                // app.js mountEpisodeList: newest first per series, pages of 100 (Re:Anime) or 50, 전체 저장 of what is not saved yet.
                let ordered = newestFirst ? Array(server.episodes.reversed()) : server.episodes
                let pages = max(1, (ordered.count + pageSize - 1) / pageSize)
                let current = min(page, pages - 1)
                let saved = savedAnime(server.episodes)
                let watched = watchProgress(server.episodes)
                // The count on the left, the tools on the right; on a narrow screen the tools go under it.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { episodeCount(server); Spacer(minLength: 4); episodeTools(server) }
                    VStack(alignment: .leading, spacing: 10) { episodeCount(server); episodeTools(server) }
                }
                if pages > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(0..<pages, id: \.self) { index in
                                Button { page = index } label: {
                                    LilacChip(text: episodeName(ordered[index * pageSize]) + "–" + episodeName(ordered[min(ordered.count, (index + 1) * pageSize) - 1]), selected: index == current)
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                }
                LazyVStack(spacing: 8) {
                    ForEach(Array(ordered[(current * pageSize)..<min(ordered.count, (current + 1) * pageSize)]), id: \.id) { episode in
                        let index = server.episodes.firstIndex { $0.id == episode.id } ?? 0
                        let item = PlaybackItem(anime: saved, episode: episode)
                        // #detailDialog .episode-row.rich: one bordered card, its download button inside it.
                        HStack(spacing: 0) {
                            PlaybackButton(item: playback(server.episodes, index: index, saved: saved)) {
                                HStack(spacing: 14) {
                                    Text(episodeName(episode)).font(.system(size: 15, weight: .semibold)).foregroundStyle(LilacStyle.accent)
                                        .lineLimit(1).minimumScaleFactor(0.5).frame(width: 40)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(episode.title.isEmpty || episode.title == "Episode \(episode.number)" ? episodeName(episode) + "화" : episode.title)
                                            .font(.system(size: 14, weight: .semibold)).foregroundStyle(LilacStyle.text).lineLimit(2)
                                        if !episode.nativeTitle.isEmpty { Text(episode.nativeTitle).font(.system(size: 11)).foregroundStyle(LilacStyle.muted).lineLimit(1) }
                                        let badges = [episode.dubbed ? "더빙" : "", episode.isFiller ? "FILLER" : "", episode.isRecap ? "RECAP" : "",
                                            String(episode.airedDate.split(separator: "T").first ?? ""), episode.playable ? "" : "재생 불가"].filter { !$0.isEmpty }
                                        if !badges.isEmpty { Text(badges.joined(separator: " · ")).font(.system(size: 11)).foregroundStyle(LilacStyle.muted) }
                                        // 본 회차 (desktop 8065f4a): checked from 90%, how far a started one got.
                                        if let percent = watched[episode.id], percent > 0 {
                                            Text(percent >= 90 ? "✓ 시청함" : "\(Int(percent))% 시청").font(.system(size: 11, weight: .semibold))
                                                .foregroundStyle(percent >= 90 ? LilacStyle.accent : LilacStyle.muted)
                                        }
                                    }
                                    Spacer(minLength: 0)
                                }.padding(.vertical, 12).padding(.leading, 8).contentShape(Rectangle())
                            }.buttonStyle(.plain).disabled(!episode.playable).opacity(episode.playable ? 1 : 0.5)
                            Button { downloads.toggle(item, quality: library.preferences.quality) } label: {
                                Image(systemName: downloadIcon(downloads.state(item))).font(.system(size: 17)).foregroundStyle(LilacStyle.muted).frame(width: 38, height: 38)
                            }.buttonStyle(.plain).disabled(!episode.playable).accessibilityLabel(episode.title + " " + downloadLabel(downloads.state(item)))
                        }.padding(.trailing, 8)
                            .background(LilacStyle.card2, in: RoundedRectangle(cornerRadius: LilacStyle.radius))
                            .overlay(RoundedRectangle(cornerRadius: LilacStyle.radius).stroke(LilacStyle.line))
                            .overlay(alignment: .bottomLeading) {
                                if let percent = watched[episode.id], percent > 0, percent < 90 {
                                    GeometryReader { size in Rectangle().fill(LilacStyle.accent).frame(width: size.size.width * percent / 100, height: 3) }
                                        .frame(height: 3).clipShape(RoundedRectangle(cornerRadius: 1.5)).padding(.horizontal, 2).padding(.bottom, 1)
                                }
                            }
                    }
                }
            } else if !model.loading {
                // desktop openDetail: a provider's series without episodes (unavailable).
                if model.error == nil {
                    LilacEmptyState(icon: "play.rectangle", title: "현재 제공처에 영상이 없는 작품입니다", message: "설정에서 다른 콘텐츠 소스를 선택해 주세요.")
                }
            }
        }
    }
    private var information: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("작품 소개").font(.title3.bold())
            let overview = DisplayText.shared.plain(value: names.record(anime, source: source)?.overview.isEmpty == false ? names.record(anime, source: source)!.overview : anime.description_)
            Text(overview.isEmpty ? "등록된 소개가 없습니다." : overview)
                .font(.subheadline).lineSpacing(6).foregroundStyle(.secondary)
            Divider()
            if let views = model.extras?.views, !views.isEmpty { Text("조회 " + views).font(.caption).foregroundStyle(.secondary) }
            // app.js openDetail: the source's own page for the work (작품 정보).
            if let page = URL(string: ReAnimeHost.shared.live(url: anime.detailUrl)), ["http", "https"].contains(page.scheme ?? "") {
                Link(destination: page) { Label("사이트에서 작품 정보 보기", systemImage: "arrow.up.right.square") }.font(.subheadline)
            }
            ForEach(Array([("원제", anime.native), ("로마자", anime.romaji), ("영문명", anime.english),
                ("방영", anime.airedDate), ("제작", anime.studios.joined(separator: ", ")), ("장르", anime.genres.joined(separator: " · ")),
                ("비고", anime.note)].enumerated()), id: \.offset) { _, row in
                if !row.1.isEmpty {
                    HStack(alignment: .top) { Text(row.0).font(.subheadline).foregroundStyle(.secondary).frame(width: 60, alignment: .leading); Text(row.1).font(.subheadline); Spacer(minLength: 0) }
                }
            }
            if let extras = model.anilist { anilistInformation(extras) }
        }.padding(18).background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 8))
    }
    /// 다음 화 · 8화 일본 방영 11월 13일 (금) 오후 10:30 (2일 후), while one is announced.
    private var airingLine: String? {
        guard let extras = model.anilist, extras.nextAiringAt > 0, extras.nextEpisode > 0 else { return nil }
        let at = Date(timeIntervalSince1970: TimeInterval(extras.nextAiringAt)), left = at.timeIntervalSinceNow
        guard left > -60 else { return nil }
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "ko_KR"); formatter.dateFormat = "M월 d일 (E) a h:mm"
        let hours = Int(left / 3600)
        let soon = left < 3600 ? "곧" : hours < 24 ? "\(hours)시간 후" : "\(hours / 24)일 후"
        return "다음 화 · \(extras.nextEpisode)화 일본 방영 \(formatter.string(from: at)) (\(soon))"
    }
    /// 캐릭터 · 성우, the songs (MAL), tags and sites: AniList's and MAL's part of 작품 정보.
    @ViewBuilder private func anilistInformation(_ extras: AnimeExtras) -> some View {
        if !extras.characters.isEmpty {
            Divider()
            Text("캐릭터 · 성우").font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: 12)], alignment: .leading, spacing: 12) {
                ForEach(Array(extras.characters.enumerated()), id: \.offset) { _, character in
                    HStack(spacing: 10) {
                        AnimeArtwork(url: character.image, width: 44, height: 60).clipShape(RoundedRectangle(cornerRadius: 6))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(character.native.isEmpty ? character.name : character.native).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                            let role = character.role == "MAIN" ? "주연" : character.role == "SUPPORTING" ? "조연" : ""
                            let detail = [character.native.isEmpty ? "" : character.name, role].filter { !$0.isEmpty }.joined(separator: " · ")
                            if !detail.isEmpty { Text(detail).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
                            let voice = character.voiceNative.isEmpty ? character.voice : character.voiceNative
                            if !voice.isEmpty { Text("CV " + voice).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
                        }
                        Spacer(minLength: 0)
                    }
                }
            }
        }
        ForEach(Array([("오프닝 곡", extras.openings), ("엔딩 곡", extras.endings)].enumerated()), id: \.offset) { _, songs in
            if !songs.1.isEmpty {
                Divider()
                Text(songs.0).font(.headline)
                VStack(alignment: .leading, spacing: 4) { ForEach(songs.1, id: \.self) { Text("• " + $0).font(.subheadline).foregroundStyle(.secondary) } }
            }
        }
        if !extras.tags.isEmpty {
            Divider()
            Text("태그").font(.headline)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) { ForEach(extras.tags, id: \.self) { Text($0).font(.system(size: 11, weight: .medium)).padding(.horizontal, 10).padding(.vertical, 5).background(LilacStyle.card2, in: Capsule()) } }
            }
        }
        let links = extraLinks(extras)
        if !links.isEmpty {
            Divider()
            Text("공식 · 정보 사이트").font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 8)], alignment: .leading, spacing: 8) {
                ForEach(Array(links.enumerated()), id: \.offset) { _, link in
                    if let url = URL(string: link.1) { Link(link.0, destination: url).font(.subheadline).lineLimit(1) }
                }
            }
        }
    }
    private func extraLinks(_ extras: AnimeExtras) -> [(String, String)] {
        var links: [(String, String)] = []
        if !extras.url.isEmpty { links.append(("AniList", extras.url)) }
        if extras.malId > 0 { links.append(("MyAnimeList", "https://myanimelist.net/anime/\(extras.malId)")) }
        for link in extras.links { links.append((link.site, link.url)) }
        return links
    }
    /// AniList's related works (for the sources that list none of their own) and the works recommended with this one.
    /// They are not works of the source playing: one picked is searched for on it.
    private var anilistGroups: [(String, [ExtraWork])] {
        guard let extras = model.anilist else { return [] }
        var groups: [(String, [ExtraWork])] = []
        if !["reanime", "miruro", "linkkf"].contains(source) {
            for work in extras.relations {
                let name = extras.relationName(type: work.relationType)
                if let index = groups.firstIndex(where: { $0.0 == name }) { groups[index].1.append(work) } else { groups.append((name, [work])) }
            }
        }
        if !extras.recommendations.isEmpty { groups.append(("비슷한 작품", extras.recommendations)) }
        return groups
    }
    private func anilistRelated(_ groups: [(String, [ExtraWork])]) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(group.0).font(.system(size: 13, weight: .bold)).foregroundStyle(LilacStyle.accent)
                    Text("AniList · 누르면 지금 소스에서 검색").font(.system(size: 11)).foregroundStyle(LilacStyle.muted)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(group.1, id: \.anilistId) { work in extraCard(work) }
                    }
                }
            }
        }
    }
    private func extraCard(_ work: ExtraWork) -> some View {
        Button { navigation.search(work.title) } label: {
            VStack(alignment: .leading, spacing: 4) {
                AnimeArtwork(url: work.image, width: 120, height: 170).clipShape(RoundedRectangle(cornerRadius: LilacStyle.radius))
                    .overlay(alignment: .topTrailing) {
                        if work.score > 0 {
                            Text("★ " + String(format: "%g", work.score)).font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                                .padding(.horizontal, 6).padding(.vertical, 3).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 4)).padding(6)
                        }
                    }
                Text(work.title).font(.system(size: 13, weight: .semibold)).foregroundStyle(LilacStyle.text).lineLimit(2).frame(width: 120, alignment: .leading)
                Text([work.year, work.format.replacingOccurrences(of: "_", with: " ")].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.system(size: 11)).foregroundStyle(LilacStyle.muted).lineLimit(1)
            }
        }.buttonStyle(.plain).accessibilityLabel(work.title + " 검색")
    }
    private var related: some View {
        VStack(alignment: .leading, spacing: 16) {
            let groups = anilistGroups
            if let extras = model.extras, !extras.related.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 26) {
                    ForEach(extras.related, id: \.id) { related in
                        NavigationLink { DetailView(summary: related, source: source) } label: { AnimePosterCard(anime: related) }.buttonStyle(.plain)
                    }
                }
            } else if anime.reAnimeRelated.isEmpty {
                if groups.isEmpty { LilacEmptyState(icon: "square.stack", title: "관련 작품이 없습니다", message: "관련 작품 정보가 제공되면 여기에 표시됩니다.") }
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 26) {
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
            if !groups.isEmpty { anilistRelated(groups) }
        }
    }
    /// How far each listed episode was watched (episode id -> 0–100), from 시청 기록: the same episode, or for an entry of
    /// this work whose episode is not in the list (the phone's, an older list) the episode of the same number
    /// (desktop 8065f4a episodeWatchProgress). Once watched it stays 100 though it is watched again.
    private func watchProgress(_ episodes: [Episode]) -> [String: Double] {
        let work = source + ":" + anime.id, ids = Set(episodes.map(\.id))
        var progress: [String: Double] = [:]
        for entry in library.history where entry.anime.id == work {
            let id = ids.contains(entry.episodeID) ? entry.episodeID : episodes.first { Int($0.number) == entry.number && !$0.dubbed }?.id
            guard let id else { continue }
            progress[id] = max(progress[id] ?? 0, entry.watchedPercent)
        }
        return progress
    }
    private func downloadIcon(_ state: DownloadStore.EpisodeState) -> String {
        switch state { case .completed: return "trash"; case .active: return "xmark.circle"; case .stopped: return "arrow.clockwise.circle"; case .idle: return "arrow.down.circle" }
    }
    private func downloadLabel(_ state: DownloadStore.EpisodeState) -> String {
        switch state { case .completed: return "다운로드 삭제"; case .active: return "다운로드 취소"; case .stopped: return "다운로드 다시 시작"; case .idle: return "다운로드" }
    }
    private func savedAnime(_ episodes: [Episode]) -> SavedAnime { SavedAnime(AnimeSnapshot.shared.withEpisodes(anime: anime, episodes: episodes), source: source) }
    private func playback(_ episodes: [Episode], index: Int, saved: SavedAnime? = nil) -> PlaybackItem {
        let saved = saved ?? savedAnime(episodes)
        var item = PlaybackItem(anime: saved, episode: episodes[index])
        item.next = episodes.dropFirst(index + 1).map { PlaybackItem(anime: saved, episode: $0) }
        item.preceding = episodes.prefix(index).map { PlaybackItem(anime: saved, episode: $0) }
        return item
    }
}
