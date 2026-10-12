import SwiftUI
import LilacShared

/// The desktop layout (ott.css): a header across the top with the brand, the destinations as words, search and
/// settings, and the page under it. A phone gets the same header, as the desktop's narrow window does.
struct DesktopWorkspace: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: DownloadStore
    @EnvironmentObject private var playback: PlaybackRouter
    @Environment(\.horizontalSizeClass) private var sizeClass
    @EnvironmentObject private var navigation: DesktopNavigation
    @State private var search = ""
    private let destinations: [(String, String)] = [("home", "홈"), ("catalog", "전체"), ("history", "시청기록"), ("favorites", "내 목록")]
    var body: some View {
        VStack(spacing: 0) {
            header
            page.id(navigation.section).frame(maxWidth: .infinity, maxHeight: .infinity)
        }.background(LilacStyle.background)
            .environmentObject(navigation)
            .task(id: library.preferences.source) {
                // The saved catalog is read after launch: until then it looks empty and would be gathered again from the start.
                if !UIShowcase.enabled { await DesktopCatalog.shared.load() }
                if !UIShowcase.enabled && !DesktopCatalog.shared.running &&
                    ((DesktopCatalog.shared.catalogs[library.preferences.source] ?? []).isEmpty || DesktopCatalog.shared.needsRefresh(library.preferences.source)) {
                    DesktopCatalog.shared.start(library.preferences.source)
                }
            }
            .onChange(of: navigation.query) { search = $0 }
            .alert("다운로드", isPresented: Binding(get: { downloads.notice != nil && playback.presentation == nil }, set: { if !$0 { downloads.notice = nil } })) {
                Button("확인") { downloads.notice = nil }
                Button("다운로드 보기") { downloads.notice = nil; navigation.section = "favorites"; navigation.showDownloads = true }
            } message: { Text(downloads.notice ?? "") }
    }
    private var header: some View {
        HStack(spacing: sizeClass == .regular ? 24 : 10) {
            Button { navigation.section = "home" } label: {
                Text("LiLac").font(.system(size: sizeClass == .regular ? 24 : 20, weight: .heavy)).foregroundStyle(LilacStyle.accent)
            }.buttonStyle(.plain).accessibilityLabel("홈")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) {
                    ForEach(destinations, id: \.0) { key, title in
                        let active = navigation.section == key
                        Button { navigation.section = key } label: {
                            Text(title).font(.system(size: 14, weight: active ? .bold : .medium))
                                .foregroundStyle(active ? LilacStyle.text : LilacStyle.muted)
                                .padding(.horizontal, sizeClass == .regular ? 12 : 8).padding(.vertical, 8)
                        }.buttonStyle(.plain).accessibilityIdentifier("workspace-route-" + key)
                    }
                }
            }
            if sizeClass == .regular {
                HStack(spacing: 9) {
                    Image(systemName: "magnifyingglass").foregroundStyle(LilacStyle.muted)
                    TextField("보고 싶은 애니메이션을 검색해 보세요", text: $search).font(.system(size: 14))
                        .submitLabel(.search).onSubmit { navigation.search(search) }
                    if !search.isEmpty { Button { search = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(LilacStyle.muted) }.accessibilityLabel("검색어 지우기") }
                }.padding(.horizontal, 14).frame(width: 270, height: 38)
                    .background(LilacStyle.card, in: Capsule()).overlay(Capsule().stroke(LilacStyle.line))
            } else {
                headerIcon("magnifyingglass", label: "검색", active: navigation.section == "search") { navigation.section = "search" }
                    .accessibilityIdentifier("workspace-route-search")
            }
            headerIcon("gearshape", label: "설정", active: navigation.section == "settings") { navigation.section = "settings" }
                .accessibilityIdentifier("workspace-settings")
            Menu {
                Button { navigation.section = "local" } label: { Label("로컬 영상 열기", systemImage: "folder") }
                Picker("영상 소스", selection: $library.preferences.source) {
                    ForEach(ContentSources.keys, id: \.self) { Text(ContentSources.name($0)).tag($0) }
                }
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 18, weight: .semibold)).foregroundStyle(LilacStyle.text.opacity(0.85)).frame(width: 36, height: 36)
            }.accessibilityLabel("더보기").accessibilityIdentifier("workspace-menu")
        }.padding(.horizontal, LilacStyle.gutter(sizeClass)).frame(height: 54)
            .background(LilacStyle.background)
            .overlay(alignment: .bottom) { if navigation.section != "home" { LilacStyle.line.frame(height: 1) } }
    }
    private func headerIcon(_ name: String, label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name).font(.system(size: 18, weight: .semibold))
                .foregroundStyle(active ? LilacStyle.accent : LilacStyle.text.opacity(0.85)).frame(width: 36, height: 36)
        }.buttonStyle(.plain).accessibilityLabel(label)
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

/// 내 목록 (desktop libraryView): the saved series and the finished downloads, chosen with the desktop's chips.
struct DesktopSavedView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: DownloadStore
    @EnvironmentObject private var navigation: DesktopNavigation
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var tab = 0
    @State private var order = "saved"
    @ObservedObject private var recent = DesktopRecentUpdates.shared
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 2) {
                Button { tab = 0 } label: { LilacChip(text: "내 목록", selected: tab == 0) }.buttonStyle(.plain)
                Button { tab = 1 } label: { LilacChip(text: "다운로드 완료 \(downloads.entries.filter { $0.localFile != nil }.count)", selected: tab == 1) }.buttonStyle(.plain)
            }.padding(3).background(LilacStyle.card2, in: RoundedRectangle(cornerRadius: 8))
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, LilacStyle.gutter(sizeClass)).padding(.vertical, 12)
            if tab == 1 { NavigationStack { DownloadsView().toolbar(.hidden, for: .navigationBar) } }
            else {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack(spacing: 6) {
                                Button { order = "saved" } label: { LilacChip(text: "저장순", selected: order == "saved") }.buttonStyle(.plain)
                                Button { order = "updated" } label: { LilacChip(text: "회차 업데이트순", selected: order == "updated") }.buttonStyle(.plain)
                            }
                            if order == "updated" { Text(recent.libraryNote).font(.caption).foregroundStyle(LilacStyle.muted) }
                            if library.favorites.isEmpty {
                                LilacEmptyState(icon: "heart", title: "아직 담은 작품이 없어요", message: "작품의 하트 버튼을 눌러 내 목록에 추가해 보세요.")
                            } else {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 26) {
                                    ForEach(order == "updated" ? recent.ordered(library.favorites) : library.favorites) { saved in
                                        NavigationLink { DetailView(summary: saved.anime, source: saved.source) } label: { AnimePosterCard(anime: saved.anime) }
                                            .buttonStyle(.plain).contextMenu { Button("내 목록에서 삭제", role: .destructive) { library.toggle(saved.anime, source: saved.source) } }
                                    }
                                }
                            }
                        }.padding(.horizontal, LilacStyle.gutter(sizeClass)).padding(.bottom, 30)
                    }.background(LilacStyle.background).toolbar(.hidden, for: .navigationBar)
                        .task(id: order + library.favorites.map(\.id).joined(separator: "|")) { if order == "updated" && !UIShowcase.enabled { await recent.loadLibrary(library.favorites) } }
                }
            }
        }.background(LilacStyle.background)
            .onAppear { if navigation.showDownloads { tab = 1; navigation.showDownloads = false } }
            .onChange(of: navigation.showDownloads) { if $0 { tab = 1; navigation.showDownloads = false } }
    }
}

/// 시청기록 (desktop historyView, mobile-parity .history-list): wide stills in a grid, the progress inside them, the
/// title and episode under them; 선택 picks several to remove.
struct DesktopHistoryView: View {
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var selecting = false
    @State private var selected: Set<String> = []
    @State private var clear = false
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(alignment: .bottom) {
                        Text("시청기록").font(.system(size: 28, weight: .heavy)).foregroundStyle(LilacStyle.text)
                        Spacer()
                        if selecting {
                            Button("전체 선택") { selected = Set(library.history.map(\.id)) }.buttonStyle(LilacOutlineButtonStyle())
                            Button("삭제", role: .destructive) { library.deleteHistory(ids: selected); selected = []; selecting = false }
                                .buttonStyle(LilacOutlineButtonStyle()).disabled(selected.isEmpty)
                        }
                        Button(selecting ? "취소" : "선택") { selecting.toggle(); selected = [] }.buttonStyle(LilacOutlineButtonStyle()).disabled(library.history.isEmpty)
                        if !selecting {
                            Button("전체 삭제", role: .destructive) { clear = true }.buttonStyle(LilacOutlineButtonStyle()).disabled(library.history.isEmpty)
                        }
                    }
                    if selecting { Text("\(selected.count)개 선택").font(.caption).foregroundStyle(LilacStyle.muted) }
                    if library.history.isEmpty { LilacEmptyState(icon: "clock", title: "시청 기록이 없습니다", message: "재생한 작품이 여기에 표시됩니다.") }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: sizeClass == .regular ? 220 : 150), spacing: 10)], spacing: 16) {
                        ForEach(library.history) { entry in
                            if selecting {
                                Button { if !selected.insert(entry.id).inserted { selected.remove(entry.id) } } label: { card(entry) }.buttonStyle(.plain)
                            } else {
                                PlaybackButton(item: PlaybackItem(entry: entry)) { card(entry) }.buttonStyle(.plain)
                                    .contextMenu { Button("기록에서 삭제", role: .destructive) { library.deleteHistory(ids: [entry.id]) } }
                            }
                        }
                    }
                }.padding(.horizontal, LilacStyle.gutter(sizeClass)).padding(.vertical, 20)
            }.background(LilacStyle.background).toolbar(.hidden, for: .navigationBar)
                .confirmationDialog("시청 기록을 모두 삭제할까요?", isPresented: $clear, titleVisibility: .visible) {
                    Button("전체 삭제", role: .destructive) { library.clearHistory(); selected = [] }
                }
        }
    }
    private func card(_ entry: WatchEntry) -> some View {
        let progress = entry.duration > 0 ? min(entry.position / entry.duration, 1) : (entry.progressPercent ?? 0) / 100
        return VStack(alignment: .leading, spacing: 0) {
            GeometryReader { geometry in
                AnimeArtwork(url: entry.anime.anime.backdrop.isEmpty ? entry.anime.poster : entry.anime.anime.backdrop, width: geometry.size.width, height: geometry.size.height, alignment: .posterFocus)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay {
                        Image(systemName: selecting ? (selected.contains(entry.id) ? "checkmark.circle.fill" : "circle") : "play.fill")
                            .font(.system(size: selecting ? 24 : 18)).foregroundStyle(selecting && selected.contains(entry.id) ? LilacStyle.accent : .white)
                            .frame(width: 44, height: 44).background(.black.opacity(0.6), in: Circle())
                    }
                    .overlay(alignment: .bottom) {
                        ZStack(alignment: .leading) {
                            Capsule().fill(.white.opacity(0.36))
                            Capsule().fill(LilacStyle.accent).frame(width: max(0, (geometry.size.width - 16) * progress))
                        }.frame(height: 4).padding(8)
                    }
            }.aspectRatio(16 / 9, contentMode: .fit)
            AnimeDisplayTitle(anime: entry.anime.anime, source: entry.anime.source).font(.system(size: 14, weight: .semibold)).foregroundStyle(LilacStyle.text).lineLimit(1).padding(.top, 8)
            Text(entry.episodeTitle).font(.system(size: 12)).foregroundStyle(LilacStyle.accent).lineLimit(1).padding(.top, 3)
            Text(entry.updatedAt, style: .relative).font(.system(size: 11)).foregroundStyle(LilacStyle.muted).padding(.top, 2)
        }
    }
}
