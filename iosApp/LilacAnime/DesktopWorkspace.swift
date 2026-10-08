import SwiftUI
import LilacShared
import UniformTypeIdentifiers

struct DesktopCatalogSelection {
    var genre = ""
    var format = ""
    var year = ""
    var season = ""
    var active: Bool { [genre, format, year, season].contains { !$0.isEmpty } }
}
struct DesktopFullCatalog: View {
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var catalog = DesktopCatalog.shared
    @StateObject private var remote = CatalogModel()
    @State private var query = ""
    @State private var sort = "popular"
    @State private var draft = DesktopCatalogSelection()
    @State private var applied = DesktopCatalogSelection()
    @State private var validationError: String?
    private var sorts: [String] {
        if applied.active { return remote.filters?.sorts ?? ["default"] }
        switch library.preferences.source { case "linkkf": return ["default", "year"]; case "linkani": return ["default", "popular"]; case "ohli24": return ["default"]; default: return ["popular", "year", "score"] }
    }
    private var remoteSource: Bool { applied.active || ["miruro", "animenosub", "linkani"].contains(library.preferences.source) }
    private var values: [SavedAnime] {
        let wanted = DesktopTitleRules.shared.key(title: query)
        let items = UIShowcase.enabled ? UIShowcase.items.map { SavedAnime($0, source: library.preferences.source) } : remoteSource ? remote.items.map { SavedAnime($0, source: library.preferences.source) } : (catalog.catalogs[library.preferences.source] ?? [])
        let result = items.filter { item in
            (!UIShowcase.enabled || ((applied.format.isEmpty || item.anime.format.caseInsensitiveCompare(applied.format) == .orderedSame) && (applied.year.isEmpty || item.anime.year == applied.year) && (applied.genre.isEmpty || item.anime.genres.contains(applied.genre)))) &&
            (remoteSource || wanted.isEmpty || ([item.title, catalog.record(item)?.korean ?? "", catalog.record(item)?.english ?? ""] + (catalog.record(item)?.aliases ?? [])).contains { DesktopTitleRules.shared.key(title: $0).contains(wanted) })
        }
        if sort == "default" || remoteSource { return result }
        return result.sorted { a, b in
            switch sort {
            case "year": if a.anime.year != b.anime.year { return a.anime.year > b.anime.year }
            case "score": if a.anime.score != b.anime.score { return a.anime.score > b.anime.score }
            case "popular": if a.anime.popularity != b.anime.popularity { return a.anime.popularity > b.anime.popularity }
            default: break
            }
            return catalog.title(a.anime, source: a.source, language: library.preferences.titleLanguage).localizedStandardCompare(catalog.title(b.anime, source: b.source, language: library.preferences.titleLanguage)) == .orderedAscending
        }
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Picker("소스", selection: $library.preferences.source) { ForEach(ContentSources.keys, id: \.self) { Text(ContentSources.name($0)).tag($0) } }
                        Picker("정렬", selection: $sort) { ForEach(sorts, id: \.self) { Text($0 == "popular" ? "인기순" : $0 == "year" ? "최신순" : $0 == "score" ? "평점순" : "기본 순서").tag($0) } }
                        NavigationLink { CatalogIndexView() } label: { Image(systemName: "arrow.triangle.2.circlepath") }
                    }
                    filterPanel
                    Text("\(values.count)개 · " + (UIShowcase.enabled ? "UI PREVIEW · 예시 데이터" : remoteSource ? "선택한 소스의 전체 목록" : catalog.status)).font(.caption).foregroundStyle(.secondary)
                    if !remoteSource, let error = catalog.error { Text(error).foregroundStyle(.red) }
                    if let error = remote.error, remoteSource { Text(error).foregroundStyle(.red); Button("다시 시도") { reloadRemote() } }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 145))], spacing: 18) {
                        ForEach(values) { item in NavigationLink { DetailView(summary: item.anime, source: item.source) } label: { AnimePosterCard(anime: item.anime) }.buttonStyle(.plain) }
                    }
                    if remoteSource && !UIShowcase.enabled {
                        if remote.loading { ProgressView() }
                        else if remote.canLoadMore { Button("더 보기") { remote.load(reset: false) } }
                        else if values.isEmpty && remote.error == nil { Text("선택한 조건의 작품이 없습니다.").foregroundStyle(.secondary) }
                    }
                }.padding()
            }.background(LilacStyle.background).navigationTitle("전체")
                .onChange(of: sort) { value in UserDefaults.standard.set(value, forKey: "allSort:" + library.preferences.source); reloadRemote() }
                .searchable(text: $query, prompt: "한국어·원제·영어 검색").onSubmit(of: .search) { reloadRemote() }
                .task(id: library.preferences.source) {
                    draft = DesktopCatalogSelection(); applied = draft; validationError = nil
                    remote.source = library.preferences.source; remote.loadFilters()
                    let remembered = UserDefaults.standard.string(forKey: "allSort:" + library.preferences.source) ?? ""
                    sort = sorts.contains(remembered) ? remembered : sorts[0]; reloadRemote()
                    if !UIShowcase.enabled && (catalog.catalogs[library.preferences.source] ?? []).isEmpty { catalog.start(library.preferences.source) }
                }
        }
    }
    @ViewBuilder private var filterPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            if remote.filtersLoading { ProgressView("분류를 불러오는 중") }
            if let facets = remote.filters {
                if !facets.genres.isEmpty { picker("장르", value: $draft.genre, options: facets.genres) }
                if !facets.formats.isEmpty { picker("형태", value: $draft.format, options: facets.formats) }
                if facets.supportsYear {
                    if facets.years.isEmpty { TextField("방영 연도", text: $draft.year).keyboardType(.numberPad).accessibilityIdentifier("catalog-year") }
                    else { picker("연도", value: $draft.year, options: facets.years) }
                }
                if facets.supportsSeason { picker("분기", value: $draft.season, options: facets.seasons) }
                Text(facets.note.isEmpty ? "장르·형태·연도·분기를 함께 선택할 수 있습니다." : facets.note).font(.caption).foregroundStyle(.secondary)
            }
            if let error = remote.filterError { Text(error).font(.caption).foregroundStyle(.red); Button("분류 다시 불러오기") { remote.loadFilters() } }
            if let validationError { Text(validationError).font(.caption).foregroundStyle(.red) }
            HStack {
                Button("초기화") { draft = DesktopCatalogSelection(); applied = draft; validationError = nil; if !sorts.contains(sort) { sort = sorts[0] }; reloadRemote() }.accessibilityIdentifier("catalog-filter-reset")
                Spacer()
                Button("필터 적용", action: applyFilters).buttonStyle(.borderedProminent).disabled(remote.filters == nil).accessibilityIdentifier("catalog-filter-apply")
            }
        }.padding(16).background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 16))
    }
    private func picker(_ name: String, value: Binding<String>, options: [String]) -> some View {
        Picker(name, selection: value) { Text("전체 " + name).tag(""); ForEach(options, id: \.self) { Text(label($0)).tag($0) } }
    }
    private func label(_ value: String) -> String {
        ["TV": "TV 애니", "TV_SHORT": "단편 TV", "MOVIE": "극장판", "Movie": "극장판", "ONA": "웹 애니", "SPECIAL": "스페셜", "MUSIC": "뮤직비디오", "WINTER": "1분기", "SPRING": "2분기", "SUMMER": "3분기", "FALL": "4분기", "Action": "액션", "Adventure": "모험", "Fantasy": "판타지", "Romance": "로맨스", "Comedy": "코미디" ][value] ?? value
    }
    private func applyFilters() {
        guard let facets = remote.filters else { remote.loadFilters(); return }
        var next = draft; next.year = next.year.trimmingCharacters(in: .whitespacesAndNewlines)
        if !next.season.isEmpty && next.year.isEmpty { next.year = String(Calendar.current.component(.year, from: Date())) }
        if !next.year.isEmpty && (facets.years.isEmpty ? (Int(next.year) ?? 0) <= 0 : !facets.years.contains(next.year)) { validationError = "지원하는 연도를 선택하세요."; return }
        draft = next; applied = next; validationError = nil
        if !sorts.contains(sort) { sort = sorts[0] }; reloadRemote()
    }
    private func reloadRemote() {
        guard remoteSource && !UIShowcase.enabled else { return }
        remote.source = library.preferences.source; remote.query = query; remote.mode = "catalog"
        remote.sort = sort == "default" ? "" : sort; remote.genre = applied.genre; remote.format = applied.format; remote.year = applied.year; remote.season = applied.season
        remote.load()
    }
}

@MainActor
final class DesktopSourceModel: ObservableObject {
    @Published var sections: [SourceSection] = []
    @Published var airing: [Anime] = []
    @Published var error: String?
    @Published var loading = false
    private let service = IosServices()
    private var generation = UUID()
    func load(source: String, day: Int) {
        service.cancel(); generation = UUID(); let token = generation; loading = true; error = nil
        sections = []; airing = []
        service.sourceSchedule(sourceKey: source, day: Int32(day)) { [weak self] values, failure in
            guard let self, token == self.generation else { return }; self.airing = values ?? []; self.error = failure; self.loading = false
        }
        service.sourceSections(sourceKey: source) { [weak self] values, failure in
            guard let self, token == self.generation else { return }; self.sections = values ?? []; if let failure { self.error = failure }
        }
    }
    deinit { service.close() }
}
struct DesktopSourceView: View {
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var model = DesktopSourceModel()
    @State private var day = 0
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Picker("소스", selection: $library.preferences.source) { ForEach(ContentSources.keys, id: \.self) { Text(ContentSources.name($0)).tag($0) } }
                    if library.preferences.source == "linkkf" {
                        Picker("요일", selection: $day) { ForEach(Array(["월", "화", "수", "목", "금", "토", "일"].enumerated()), id: \.offset) { index, text in Text(text).tag(index) } }.pickerStyle(.segmented)
                    }
                    if model.loading { ProgressView() }
                    if let error = model.error { Text(error).foregroundStyle(.red) }
                    Text("방영 중").font(.title2.bold())
                    grid(model.airing)
                    ForEach(Array(model.sections.enumerated()), id: \.offset) { _, section in
                        Text(section.name).font(.title2.bold()); grid(section.items)
                    }
                }.padding()
            }.background(LilacStyle.background).navigationTitle("방영·추천")
                .task(id: library.preferences.source + String(day)) { model.load(source: library.preferences.source, day: day) }
        }
    }
    private func grid(_ items: [Anime]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 145))], spacing: 18) {
            ForEach(items, id: \.id) { anime in
                NavigationLink { DetailView(summary: anime, source: anime.source == "jikan" ? "jikan" : library.preferences.source) } label: { AnimePosterCard(anime: anime) }.buttonStyle(.plain)
            }
        }
    }
}
struct DesktopLibraryView: View {
    let history: Bool
    @EnvironmentObject private var library: LibraryStore
    var body: some View {
        NavigationStack {
            List {
                if history {
                    ForEach(library.history) { entry in
                        PlaybackButton(item: PlaybackItem(entry: entry)) {
                            VStack(alignment: .leading) { AnimeDisplayTitle(anime: entry.anime.anime, source: entry.anime.source); Text(entry.episodeTitle).font(.caption)
                                if entry.duration > 0 { ProgressView(value: min(entry.position / entry.duration, 1)) }
                            }
                        }
                    }.onDelete(perform: library.deleteHistory)
                } else {
                    ForEach(library.favorites) { item in
                        NavigationLink { DetailView(summary: item.anime, source: item.source) } label: { AnimeDisplayTitle(anime: item.anime, source: item.source) }
                            .swipeActions { Button("삭제", role: .destructive) { library.toggle(item.anime, source: item.source) } }
                    }
                }
            }.navigationTitle(history ? "시청 기록" : "즐겨찾기")
        }
    }
}
struct LocalVideoView: View {
    @EnvironmentObject private var playback: PlaybackRouter
    @State private var importer = false
    @State private var item: PlaybackItem?
    @State private var error: String?
    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Image(systemName: "folder").font(.largeTitle)
                Button("파일에서 영상 열기") { importer = true }
                if let error { Text(error).foregroundStyle(.red) }
            }.navigationTitle("로컬 영상")
                .fileImporter(isPresented: $importer, allowedContentTypes: [.movie, .video, .data]) { result in
                    do {
                        let url = try result.get(); let access = url.startAccessingSecurityScopedResource()
                        defer { if access { url.stopAccessingSecurityScopedResource() } }
                        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Videos")
                        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                        let file = folder.appendingPathComponent(SubtitleFiles.key(url.absoluteString) + "." + url.pathExtension)
                        if !FileManager.default.fileExists(atPath: file.path) { try FileManager.default.copyItem(at: url, to: file) }
                        let anime = SavedAnime(AnimeSnapshot.shared.localVideo(name: url.deletingPathExtension().lastPathComponent, url: file.absoluteString), source: "local")
                        item = PlaybackItem(entry: WatchEntry(id: anime.id, anime: anime, episodeID: "file", episodeTitle: url.lastPathComponent,
                            number: 1, watchURL: file.absoluteString, directURL: file.absoluteString, position: 0, duration: 0, updatedAt: Date()))
                    } catch { self.error = error.localizedDescription }
                }
                .onChange(of: item != nil) { ready in if ready, let item { playback.open(item); self.item = nil } }
        }
    }
}
