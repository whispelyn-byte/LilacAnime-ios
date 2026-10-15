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
/// The full catalog's last list and what it was made from.
@MainActor final class CatalogListCache {
    private var signature: [AnyHashable]?
    private var list: [SavedAnime] = []
    func value(for signature: [AnyHashable], make: () -> [SavedAnime]) -> [SavedAnime] {
        if signature == self.signature { return list }
        list = make(); self.signature = signature; return list
    }
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
    @State private var listCache = CatalogListCache()
    private var sorts: [String] {
        if applied.active { return remote.filters?.sorts ?? ["default"] }
        switch library.preferences.source { case "linkkf": return ["default", "year"]; case "linkani": return ["default", "updated"]; case "ohli24": return ["default", "updated"]; default: return ["popular", "year", "updated", "score"] }
    }
    private var remoteSource: Bool { (applied.active && !(library.preferences.source == "reanime" && sort == "year")) || sort == "updated" || ["miruro", "animenosub", "linkani"].contains(library.preferences.source) }
    /// The list is worked out again only when what it is made from changes: the catalog index publishes its names and
    /// progress as it gathers, and each of those redraws filtered and sorted every series on the main thread.
    private var values: [SavedAnime] {
        let wanted = TitleKeys.key(query)
        let date = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        let today = (date.year ?? 0) * 10000 + (date.month ?? 0) * 100 + (date.day ?? 0)
        var signature: [AnyHashable] = [UIShowcase.enabled, remoteSource, library.preferences.source, wanted, sort]
        signature += [applied.genre, applied.format, applied.year, applied.season, today]
        if remoteSource {
            let first: String = remote.items.first?.id ?? "", last: String = remote.items.last?.id ?? ""
            signature += [remote.items.count, first, last]
        } else {
            signature.append(catalog.catalogsRevision)
            if !wanted.isEmpty { signature.append(catalog.namesRevision) }
        }
        return listCache.value(for: signature) { makeValues(wanted: wanted, today: today) }
    }
    private func makeValues(wanted: String, today: Int) -> [SavedAnime] {
        let items = UIShowcase.enabled ? UIShowcase.items.map { SavedAnime($0, source: library.preferences.source) } : remoteSource ? remote.items.map { SavedAnime($0, source: library.preferences.source) } : (catalog.catalogs[library.preferences.source] ?? [])
        let result = items.filter { item in
            (!(UIShowcase.enabled || !remoteSource) || ((applied.format.isEmpty || item.anime.format.caseInsensitiveCompare(applied.format) == .orderedSame) && (applied.year.isEmpty || item.anime.year == applied.year) && (applied.genre.isEmpty || item.anime.genres.contains(applied.genre)) && (applied.season.isEmpty || item.anime.season.caseInsensitiveCompare(applied.season) == .orderedSame))) &&
            (remoteSource || wanted.isEmpty || ([item.title, catalog.record(item)?.korean ?? "", catalog.record(item)?.english ?? ""] + (catalog.record(item)?.aliases ?? [])).contains { TitleKeys.key($0).contains(wanted) })
        }
        if sort == "default" || remoteSource { return result }
        // The sorted list points back at the saved items: encoding thousands of them again on every redraw was slow.
        let saved = Dictionary(result.map { ($0.id, $0) }) { first, _ in first }
        let source = library.preferences.source
        return DesktopAnimeMetadata.shared.sortedCatalog(items: result.map(\.anime), sort: sort, today: Int32(today)).map { saved[source + ":" + $0.id] ?? SavedAnime($0, source: source) }
    }
    @Environment(\.horizontalSizeClass) private var sizeClass
    var body: some View {
        let values = self.values
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    // ott.css .all-head: the title and where the list comes from, the order as the desktop's sort chips.
                    VStack(alignment: .leading, spacing: 6) {
                        Text("전체 애니메이션").font(.system(size: 28, weight: .heavy)).foregroundStyle(LilacStyle.text)
                        Text(UIShowcase.enabled ? "UI PREVIEW · 예시 데이터" : remoteSource ? "선택한 콘텐츠 소스의 작품입니다." : catalog.progress(for: library.preferences.source))
                            .font(.system(size: 13)).foregroundStyle(LilacStyle.muted)
                    }
                    HStack(spacing: 8) {
                        CatalogSourceMenu(source: $library.preferences.source)
                        HStack(spacing: 6) {
                            Image(systemName: "magnifyingglass").foregroundStyle(LilacStyle.muted)
                            TextField("한국어·원제·영어 검색", text: $query).submitLabel(.search).onSubmit { reloadRemote() }
                                .textInputAutocapitalization(.never).autocorrectionDisabled()
                        }.font(.system(size: 14)).padding(.horizontal, 10).frame(height: 38)
                            .background(LilacStyle.card2, in: RoundedRectangle(cornerRadius: 7)).overlay(RoundedRectangle(cornerRadius: 7).stroke(LilacStyle.line))
                        NavigationLink { CatalogIndexView() } label: {
                            Image(systemName: "arrow.triangle.2.circlepath").frame(width: 38, height: 38)
                                .overlay(RoundedRectangle(cornerRadius: 7).stroke(LilacStyle.line))
                        }.foregroundStyle(LilacStyle.text).accessibilityLabel("한국어 전체 카탈로그 · 인덱스")
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 2) {
                            ForEach(sorts, id: \.self) { value in
                                Button { sort = value } label: { LilacChip(text: value == "popular" ? "인기순" : value == "year" ? "최신순" : value == "score" ? "평점순" : value == "updated" ? "회차 업데이트순" : "기본 순서", selected: sort == value) }.buttonStyle(.plain)
                            }
                        }.padding(3).background(LilacStyle.card2, in: RoundedRectangle(cornerRadius: 8))
                    }
                    filterPanel
                    Text("\(values.count)개").font(.system(size: 13)).foregroundStyle(LilacStyle.muted)
                    if !remoteSource, let error = catalog.failure(for: library.preferences.source) { Text(error).font(.system(size: 13)).foregroundStyle(.red) }
                    if let error = remote.error, remoteSource {
                        Text(error).font(.system(size: 13)).foregroundStyle(.red)
                        Button("다시 시도") { reloadRemote() }.buttonStyle(LilacGhostButtonStyle())
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 30) {
                        ForEach(values) { item in NavigationLink { DetailView(summary: item.anime, source: item.source) } label: { AnimePosterCard(anime: item.anime) }.buttonStyle(.plain) }
                    }
                    if remoteSource && !UIShowcase.enabled {
                        if remote.loading { ProgressView().tint(LilacStyle.accent).frame(maxWidth: .infinity) }
                        else if remote.canLoadMore { Button("더 불러오기") { remote.load(reset: false) }.buttonStyle(LilacGhostButtonStyle()).frame(maxWidth: .infinity) }
                        else if values.isEmpty && remote.error == nil { LilacEmptyState(icon: "square.grid.2x2", title: "선택한 조건의 작품이 없습니다", message: "필터를 바꾸거나 초기화해 보세요.") }
                    }
                }.padding(.horizontal, LilacStyle.gutter(sizeClass)).padding(.vertical, 20)
            }.background(LilacStyle.background).navigationTitle("전체").toolbar(.hidden, for: .navigationBar)
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: sort) { value in UserDefaults.standard.set(value, forKey: "allSort:" + library.preferences.source); reloadRemote() }
                .task(id: library.preferences.source) {
                    draft = DesktopCatalogSelection(); applied = draft; validationError = nil
                    remote.source = library.preferences.source; remote.loadFilters()
                    let remembered = UserDefaults.standard.string(forKey: "allSort:" + library.preferences.source) ?? ""
                    sort = sorts.contains(remembered) ? remembered : sorts[0]; reloadRemote()
                    if !UIShowcase.enabled && ((catalog.catalogs[library.preferences.source] ?? []).isEmpty || catalog.needsRefresh(library.preferences.source)) { catalog.start(library.preferences.source) }
                }
        }
    }
    /// .catalog-filters: 상세 필터 with each field as a labelled select, then 초기화 and 필터 적용.
    @ViewBuilder private var filterPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("상세 필터").font(.system(size: 14, weight: .bold)).foregroundStyle(LilacStyle.text)
            if remote.filtersLoading { ProgressView("분류를 불러오는 중").tint(LilacStyle.accent).font(.system(size: 13)) }
            if let facets = remote.filters {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12, alignment: .top)], alignment: .leading, spacing: 12) {
                    if !facets.genres.isEmpty { picker("장르", value: $draft.genre, options: facets.genres) }
                    if !facets.formats.isEmpty { picker("형태", value: $draft.format, options: facets.formats) }
                    if facets.supportsYear {
                        if facets.years.isEmpty {
                            VStack(alignment: .leading, spacing: 7) {
                                Text("연도").font(.system(size: 12)).foregroundStyle(LilacStyle.muted)
                                TextField("전체 연도", text: $draft.year).keyboardType(.numberPad).font(.system(size: 13)).padding(.horizontal, 10).frame(height: 38)
                                    .background(LilacStyle.card2, in: RoundedRectangle(cornerRadius: 7)).overlay(RoundedRectangle(cornerRadius: 7).stroke(LilacStyle.line))
                                    .accessibilityIdentifier("catalog-year")
                            }
                        } else { picker("연도", value: $draft.year, options: facets.years) }
                    }
                    if facets.supportsSeason { picker("분기", value: $draft.season, options: facets.seasons) }
                }
                Text(facets.note.isEmpty ? "장르·형태·연도·분기를 함께 선택할 수 있습니다." : facets.note).font(.system(size: 12)).foregroundStyle(LilacStyle.muted)
            }
            if let error = remote.filterError {
                Text(error).font(.system(size: 12)).foregroundStyle(.red)
                Button("분류 다시 불러오기") { remote.loadFilters() }.buttonStyle(LilacOutlineButtonStyle())
            }
            if let validationError { Text(validationError).font(.system(size: 12)).foregroundStyle(.red) }
            HStack(spacing: 8) {
                Spacer()
                Button("초기화") { draft = DesktopCatalogSelection(); applied = draft; validationError = nil; if !sorts.contains(sort) { sort = sorts[0] }; reloadRemote() }
                    .buttonStyle(LilacGhostButtonStyle()).accessibilityIdentifier("catalog-filter-reset")
                Button("필터 적용", action: applyFilters).buttonStyle(LilacPrimaryButtonStyle()).disabled(remote.filters == nil).accessibilityIdentifier("catalog-filter-apply")
            }
        }.padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(LilacStyle.line))
    }
    private func picker(_ name: String, value: Binding<String>, options: [String]) -> some View {
        CatalogSelect(title: name, value: value, options: [("", "전체 " + name)] + options.map { ($0, label($0, field: name)) })
    }
    private func label(_ value: String, field: String) -> String {
        if field == "장르" {
            let key = value.lowercased().replacingOccurrences(of: "-", with: " ")
            return ["action": "액션", "adventure": "모험", "comedy": "코미디", "drama": "드라마", "fantasy": "판타지", "horror": "공포", "mystery": "미스터리", "romance": "로맨스", "sci fi": "SF", "slice of life": "일상", "sports": "스포츠", "supernatural": "초자연", "psychological": "심리", "thriller": "스릴러", "music": "음악", "mecha": "메카", "ecchi": "에치", "mahou shoujo": "마법소녀"][key] ?? value
        }
        return ["TV": "TV 애니", "TV_SHORT": "단편 TV", "MOVIE": "극장판", "ONA": "웹 애니", "SPECIAL": "스페셜", "MUSIC": "뮤직비디오", "LIVE ACTION": "실사", "BD": "BD", "WINTER": "1분기", "SPRING": "2분기", "SUMMER": "3분기", "FALL": "4분기"][value.uppercased()] ?? value
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
struct LocalVideoView: View {
    @EnvironmentObject private var playback: PlaybackRouter
    @State private var importer = false
    @State private var item: PlaybackItem?
    @State private var error: String?
    @State private var copying = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Image(systemName: "film.stack").font(.system(size: 46, weight: .light)).foregroundStyle(LilacStyle.accent)
                Text("내 영상 재생").font(.system(size: 24, weight: .heavy)).foregroundStyle(LilacStyle.text)
                Text("파일 앱에 있는 영상을 앱으로 복사해 재생합니다. 같은 폴더의 자막 파일은 플레이어 설정 > 자막에서 열 수 있어요.")
                    .font(.system(size: 14)).foregroundStyle(LilacStyle.muted).multilineTextAlignment(.center).frame(maxWidth: 420)
                Button { importer = true } label: { Label("파일에서 영상 열기", systemImage: "folder") }.buttonStyle(LilacPrimaryButtonStyle()).disabled(copying)
                if copying { ProgressView("영상을 앱으로 복사하는 중…").tint(LilacStyle.accent).font(.system(size: 13)) }
                if let error { Text(error).font(.system(size: 13)).foregroundStyle(.red).multilineTextAlignment(.center) }
            }.padding(24).frame(maxWidth: .infinity, maxHeight: .infinity).background(LilacStyle.background)
                .navigationTitle("로컬 영상").toolbar(.hidden, for: .navigationBar)
                .fileImporter(isPresented: $importer, allowedContentTypes: [.movie, .video, .data]) { result in
                    let url: URL
                    do { url = try result.get() } catch { self.error = error.localizedDescription; return }
                    copying = true; error = nil
                    Task {
                        defer { copying = false }
                        do {
                            // A video can be gigabytes: copying it on the main thread froze the app until iOS stopped it.
                            let file = try await Task.detached(priority: .userInitiated) { () throws -> URL in
                                let access = url.startAccessingSecurityScopedResource()
                                defer { if access { url.stopAccessingSecurityScopedResource() } }
                                let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Videos")
                                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                                let file = folder.appendingPathComponent(SubtitleFiles.key(url.absoluteString) + "." + url.pathExtension)
                                if !FileManager.default.fileExists(atPath: file.path) {
                                    // Under another name until complete: a copy cut short is not taken for the video next time.
                                    let partial = file.appendingPathExtension("part")
                                    try? FileManager.default.removeItem(at: partial)
                                    try FileManager.default.copyItem(at: url, to: partial)
                                    try FileManager.default.moveItem(at: partial, to: file)
                                }
                                return file
                            }.value
                            let anime = SavedAnime(AnimeSnapshot.shared.localVideo(name: url.deletingPathExtension().lastPathComponent, url: file.absoluteString), source: "local")
                            item = PlaybackItem(entry: WatchEntry(id: anime.id, anime: anime, episodeID: "file", episodeTitle: url.lastPathComponent,
                                number: 1, watchURL: file.absoluteString, directURL: file.absoluteString, position: 0, duration: 0, updatedAt: Date()))
                        } catch { self.error = error.localizedDescription }
                    }
                }
                .onChange(of: item != nil) { ready in if ready, let item { playback.open(item); self.item = nil } }
        }
    }
}

/// The source as the desktop's select: its name with a chevron, the sources in a menu.
struct CatalogSourceMenu: View {
    @Binding var source: String
    var body: some View {
        Menu {
            Picker("콘텐츠 소스", selection: $source) { ForEach(ContentSources.keys, id: \.self) { Text(ContentSources.name($0)).tag($0) } }
        } label: {
            HStack(spacing: 6) {
                Text(ContentSources.name(source)).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 11, weight: .semibold))
            }.foregroundStyle(LilacStyle.text).padding(.horizontal, 12).frame(height: 38)
                .background(LilacStyle.card2, in: RoundedRectangle(cornerRadius: 7)).overlay(RoundedRectangle(cornerRadius: 7).stroke(LilacStyle.line))
        }.accessibilityLabel("콘텐츠 소스 " + ContentSources.name(source))
    }
}

/// .catalog-filter-fields label: the field's name over a select of its values.
struct CatalogSelect: View {
    let title: String
    @Binding var value: String
    let options: [(String, String)]
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title).font(.system(size: 12)).foregroundStyle(LilacStyle.muted)
            Menu {
                ForEach(options.indices, id: \.self) { index in
                    Button { value = options[index].0 } label: {
                        if value == options[index].0 { Label(options[index].1, systemImage: "checkmark") } else { Text(options[index].1) }
                    }
                }
            } label: {
                HStack {
                    Text(options.first { $0.0 == value }?.1 ?? value).font(.system(size: 13)).lineLimit(1)
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.down").font(.system(size: 11, weight: .semibold)).foregroundStyle(LilacStyle.muted)
                }.foregroundStyle(LilacStyle.text).padding(.horizontal, 10).frame(maxWidth: .infinity, minHeight: 38)
                    .background(LilacStyle.card2, in: RoundedRectangle(cornerRadius: 7)).overlay(RoundedRectangle(cornerRadius: 7).stroke(LilacStyle.line))
            }.accessibilityLabel(title)
        }
    }
}
