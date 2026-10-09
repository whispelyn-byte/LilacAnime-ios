import SwiftUI
import LilacShared

struct DesktopWorkspace: View {
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.horizontalSizeClass) private var sizeClass
    @EnvironmentObject private var navigation: DesktopNavigation
    @State private var menu = false
    @State private var columns: NavigationSplitViewVisibility = .all
    @State private var search = ""
    private let destinations: [(String, String, String)] = [
        ("home", "홈", "house"), ("catalog", "전체", "square.grid.2x2"),
        ("search", "검색", "magnifyingglass"), ("history", "시청기록", "clock.arrow.circlepath"),
        ("favorites", "내 목록", "heart"), ("settings", "설정", "gearshape")
    ]
    var body: some View {
        VStack(spacing: 0) {
            searchBar
            Group {
            if sizeClass == .regular {
                NavigationSplitView(columnVisibility: $columns) {
                    sidebar.navigationTitle("LilacAnime")
                        .navigationSplitViewColumnWidth(min: 180, ideal: 210, max: 240)
                } detail: { page.id(navigation.section) }
                .navigationSplitViewStyle(.balanced)
                // Reset the column path too when replacing the workspace page.
                .id(navigation.section)
            } else { page.id(navigation.section) }
            }
        }.environmentObject(navigation)
            .sheet(isPresented: $menu) {
                NavigationStack { sidebar.navigationTitle("LilacAnime").toolbar { Button("닫기") { menu = false } } }
                    .presentationDetents([.medium, .large])
            }
            .task(id: library.preferences.source) {
                if !UIShowcase.enabled && !DesktopCatalog.shared.running &&
                    (DesktopCatalog.shared.catalogs[library.preferences.source] ?? []).isEmpty {
                    DesktopCatalog.shared.start(library.preferences.source)
                }
            }
            .onChange(of: navigation.query) { search = $0 }
    }
    private var sidebar: some View {
        List {
            Section {
                ForEach(destinations, id: \.0) { entry in
                    Button { navigation.section = entry.0; menu = false } label: {
                        Label(entry.1, systemImage: entry.2).font(.subheadline.weight(.semibold))
                            .foregroundStyle(navigation.section == entry.0 ? LilacStyle.accent : Color.primary)
                    }.listRowBackground(navigation.section == entry.0 ? LilacStyle.accent.opacity(0.12) : Color.clear)
                        .accessibilityIdentifier("workspace-route-" + entry.0)
                }
            }
            Section {
                Button { navigation.section = "local"; menu = false } label: { Label("로컬 영상 열기", systemImage: "folder") }
                Picker("영상 소스", selection: $library.preferences.source) {
                    ForEach(ContentSources.keys, id: \.self) { Text(ContentSources.name($0)).tag($0) }
                }
            }
            Section { VStack(alignment: .leading) { Text("Your anime.").bold(); Text("Your way.").foregroundStyle(.secondary) }.font(.caption) }
        }.listStyle(.sidebar)
    }
    private var searchBar: some View {
        HStack(spacing: 12) {
            Button {
                if sizeClass == .regular { withAnimation { columns = columns == .detailOnly ? .all : .detailOnly } }
                else { menu = true }
            } label: { Image(systemName: "line.3.horizontal").frame(width: 36, height: 36) }
                .accessibilityLabel("메뉴").accessibilityIdentifier("workspace-menu")
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("보고 싶은 애니메이션을 검색해 보세요", text: $search)
                    .submitLabel(.search).onSubmit { navigation.search(search) }
                if !search.isEmpty { Button { search = "" } label: { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("검색어 지우기") }
            }.padding(12).background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 14))
            Button { navigation.section = "settings" } label: { Image(systemName: "gearshape").frame(width: 36, height: 36) }
                .accessibilityLabel("설정").accessibilityIdentifier("workspace-settings")
        }.padding(.horizontal, 16).padding(.vertical, 8).background(LilacStyle.background)
    }
    @ViewBuilder private var page: some View {
        switch navigation.section {
        case "catalog": DesktopFullCatalog()
        case "search": CatalogView()
        case "history": DesktopHistoryView()
        case "favorites": DesktopSavedView()
        case "local": LocalVideoView()
        case "settings": SettingsView()
        default: HomeView(workspace: true) { navigation.section = "search" }
        }
    }
}

struct DesktopSavedView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: DownloadStore
    @State private var tab = 0
    @State private var order = "saved"
    @ObservedObject private var recent = DesktopRecentUpdates.shared
    var body: some View {
        VStack(spacing: 0) {
            Picker("내 목록", selection: $tab) {
                Text("내 목록").tag(0)
                Text("다운로드 완료 \(downloads.entries.filter { $0.localFile != nil }.count)").tag(1)
            }.pickerStyle(.segmented).padding(16)
            if tab == 1 { DownloadsView() }
            else {
                NavigationStack {
                    ScrollView {
                        Picker("정렬", selection: $order) { Text("저장순").tag("saved"); Text("회차 업데이트순").tag("updated") }.pickerStyle(.segmented).padding(.horizontal, 20)
                        if order == "updated" { Text(recent.libraryNote).font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20) }
                        if library.favorites.isEmpty {
                            LilacEmptyState(icon: "heart", title: "아직 담은 작품이 없어요", message: "작품의 하트 버튼을 눌러 내 목록에 추가해 보세요.").padding(30)
                        } else {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140))], spacing: 20) {
                                ForEach(order == "updated" ? recent.ordered(library.favorites) : library.favorites) { saved in
                                    NavigationLink { DetailView(summary: saved.anime, source: saved.source) } label: { AnimePosterCard(anime: saved.anime) }
                                        .buttonStyle(.plain).contextMenu { Button("내 목록에서 삭제", role: .destructive) { library.toggle(saved.anime, source: saved.source) } }
                                }
                            }.padding(20)
                        }
                    }.background(LilacStyle.background).navigationTitle("내 목록")
                        .task(id: order + library.favorites.map(\.id).joined(separator: "|")) { if order == "updated" && !UIShowcase.enabled { await recent.loadLibrary(library.favorites) } }
                }
            }
        }
    }
}

struct DesktopHistoryView: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var selecting = false
    @State private var selected: Set<String> = []
    @State private var clear = false
    var body: some View {
        NavigationStack {
            List {
                if selecting {
                    HStack {
                        Text("\(selected.count)개 선택")
                        Spacer()
                        Button("전체 선택") { selected = Set(library.history.map(\.id)) }
                        Button("삭제", role: .destructive) { library.deleteHistory(ids: selected); selected = []; selecting = false }.disabled(selected.isEmpty)
                    }.buttonStyle(.borderless)
                }
                if library.history.isEmpty { LilacEmptyState(icon: "clock", title: "시청 기록이 없습니다", message: "재생한 작품이 여기에 표시됩니다.") }
                ForEach(library.history) { entry in
                    HStack(spacing: 14) {
                        if selecting { Button { if !selected.insert(entry.id).inserted { selected.remove(entry.id) } } label: { Image(systemName: selected.contains(entry.id) ? "checkmark.circle.fill" : "circle") } }
                        PlaybackButton(item: PlaybackItem(entry: entry)) {
                            HStack(spacing: 14) {
                                AnimeArtwork(url: entry.anime.poster, width: 66, height: 92).clipShape(RoundedRectangle(cornerRadius: 10))
                                VStack(alignment: .leading, spacing: 8) {
                                    AnimeDisplayTitle(anime: entry.anime.anime, source: entry.anime.source).font(.subheadline.bold())
                                    Text(entry.episodeTitle).font(.caption).foregroundStyle(.secondary)
                                    if entry.duration > 0 { ProgressView(value: min(entry.position / entry.duration, 1)) }
                                    Text(entry.updatedAt, style: .relative).font(.caption2).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "play.circle.fill").font(.title2)
                            }.foregroundStyle(.primary)
                        }.disabled(selecting)
                    }.padding(.vertical, 5)
                }.onDelete(perform: library.deleteHistory)
            }.listStyle(.plain).navigationTitle("시청기록")
                .toolbar {
                    Button(selecting ? "취소" : "선택") { selecting.toggle(); selected = [] }
                    Button("전체 삭제", role: .destructive) { clear = true }.disabled(library.history.isEmpty)
                }
                .confirmationDialog("시청 기록을 모두 삭제할까요?", isPresented: $clear, titleVisibility: .visible) {
                    Button("전체 삭제", role: .destructive) { library.clearHistory(); selected = [] }
                }
        }
    }
}
