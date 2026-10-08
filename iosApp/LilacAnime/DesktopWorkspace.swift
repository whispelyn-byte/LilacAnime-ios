import SwiftUI
import LilacShared
import UniformTypeIdentifiers

struct DesktopWorkspace: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var section: String? = "home"
    var body: some View {
        NavigationSplitView {
            List(selection: $section) {
                Label("홈", systemImage: "house").tag("home")
                Label("전체 카탈로그", systemImage: "square.grid.2x2").tag("catalog")
                Label("검색·필터", systemImage: "magnifyingglass").tag("search")
                Label("방영·추천", systemImage: "calendar").tag("airing")
                Label("시청 기록", systemImage: "clock").tag("history")
                Label("즐겨찾기", systemImage: "heart").tag("favorites")
                Label("다운로드", systemImage: "arrow.down.circle").tag("downloads")
                Label("로컬 영상", systemImage: "folder").tag("local")
                Label("설정", systemImage: "gear").tag("settings")
            }.navigationTitle("LilacAnime")
        } detail: {
            switch section {
            case "catalog": DesktopFullCatalog()
            case "search": CatalogView()
            case "airing": DesktopSourceView()
            case "history": DesktopLibraryView(history: true)
            case "favorites": DesktopLibraryView(history: false)
            case "downloads": DownloadsView()
            case "local": LocalVideoView()
            case "settings": SettingsView()
            default: HomeView { section = "search" }
            }
        }.task(id: library.preferences.source) {
            if !UIShowcase.enabled && !DesktopCatalog.shared.running && (DesktopCatalog.shared.catalogs[library.preferences.source] ?? []).isEmpty {
                DesktopCatalog.shared.start(library.preferences.source)
            }
        }
    }
}
struct DesktopFullCatalog: View {
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var catalog = DesktopCatalog.shared
    @State private var query = ""
    @State private var sort = "title"
    @State private var format = ""
    @State private var year = ""
    private var values: [SavedAnime] {
        let wanted = DesktopTitleRules.shared.key(title: query)
        let result = (catalog.catalogs[library.preferences.source] ?? []).filter { item in
            (format.isEmpty || item.anime.format == format) && (year.isEmpty || item.anime.year == year) &&
            (wanted.isEmpty || ([item.title, catalog.record(item)?.korean ?? "", catalog.record(item)?.english ?? ""] + (catalog.record(item)?.aliases ?? []))
                .contains { DesktopTitleRules.shared.key(title: $0).contains(wanted) })
        }
        return result.sorted { a, b in
            switch sort {
            case "year": if a.anime.year != b.anime.year { return a.anime.year > b.anime.year }
            case "score": if a.anime.score != b.anime.score { return a.anime.score > b.anime.score }
            case "popular": if a.anime.popularity != b.anime.popularity { return a.anime.popularity > b.anime.popularity }
            default: break
            }
            return catalog.title(a.anime, source: a.source, language: library.preferences.titleLanguage)
                .localizedStandardCompare(catalog.title(b.anime, source: b.source, language: library.preferences.titleLanguage)) == .orderedAscending
        }
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Picker("소스", selection: $library.preferences.source) { ForEach(ContentSources.keys, id: \.self) { Text(ContentSources.name($0)).tag($0) } }
                        Picker("정렬", selection: $sort) {
                            Text("제목").tag("title"); Text("방영 연도").tag("year"); Text("평점").tag("score"); Text("인기").tag("popular")
                        }
                        NavigationLink { CatalogIndexView() } label: { Image(systemName: "arrow.triangle.2.circlepath") }
                    }
                    HStack {
                        Picker("형식", selection: $format) {
                            Text("모든 형식").tag("")
                            ForEach(Array(Set((catalog.catalogs[library.preferences.source] ?? []).map { $0.anime.format }.filter { !$0.isEmpty })).sorted(), id: \.self) { Text($0).tag($0) }
                        }
                        Picker("연도", selection: $year) {
                            Text("모든 연도").tag("")
                            ForEach(Array(Set((catalog.catalogs[library.preferences.source] ?? []).map { $0.anime.year }.filter { !$0.isEmpty })).sorted(by: >), id: \.self) { Text($0).tag($0) }
                        }
                    }
                    Text("\(values.count)개 · \(catalog.status)").font(.caption).foregroundStyle(.secondary)
                    if let error = catalog.error { Text(error).foregroundStyle(.red) }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 145))], spacing: 18) {
                        ForEach(values) { item in
                            NavigationLink { DetailView(summary: item.anime, source: item.source) } label: { AnimePosterCard(anime: item.anime) }.buttonStyle(.plain)
                        }
                    }
                }.padding()
            }.background(LilacStyle.background).navigationTitle("전체 카탈로그")
                .searchable(text: $query, prompt: "한국어·원제·영어 검색")
                .task(id: library.preferences.source) {
                    if !UIShowcase.enabled && (catalog.catalogs[library.preferences.source] ?? []).isEmpty { catalog.start(library.preferences.source) }
                }
        }
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
                        NavigationLink { EpisodePlayerView(item: PlaybackItem(entry: entry)) } label: {
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
                .navigationDestination(isPresented: Binding(get: { item != nil }, set: { if !$0 { item = nil } })) { if let item { EpisodePlayerView(item: item) } }
        }
    }
}
