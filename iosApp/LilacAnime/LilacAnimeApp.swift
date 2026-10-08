import SwiftUI
@main
struct LilacAnimeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var library = LibraryStore()
    @StateObject private var downloads = DownloadStore.shared
    @State private var selectedTab = 0
    var body: some Scene {
        WindowGroup {
            Group {
                if UIShowcase.enabled && UIShowcase.screen == "detail" {
                    NavigationStack { DetailView(summary: UIShowcase.anime, source: "linkkf") }
                } else if UIShowcase.enabled && UIShowcase.screen == "player" {
                    NavigationStack { EpisodePlayerView(item: PlaybackItem(anime: SavedAnime(UIShowcase.anime, source: "linkkf"), episode: UIShowcase.anime.episodes[0])) }
                } else if library.preferences.desktopWorkspace == true || (UIShowcase.enabled && UIShowcase.screen == "workspace") {
                    DesktopWorkspace()
                } else {
            TabView(selection: $selectedTab) {
                HomeView { selectedTab = 1 }.tabItem { Label("홈", systemImage: "house") }.tag(0)
                CatalogView().tabItem { Label("탐색", systemImage: "magnifyingglass") }.tag(1)
                LibraryView().tabItem { Label("보관함", systemImage: "heart") }.tag(2)
                DownloadsView().tabItem { Label("다운로드", systemImage: "arrow.down.circle") }.tag(3)
                SettingsView().tabItem { Label("설정", systemImage: "gear") }.tag(4)
            }
                }
            }.environmentObject(library).environmentObject(downloads)
                .tint(LilacStyle.accent)
                .preferredColorScheme(library.preferences.theme == "system" ? nil : (library.preferences.theme == "light" ? .light : .dark))
                .task { SubtitleFiles.restoreFonts(); downloads.library = library }
        }
    }
}
