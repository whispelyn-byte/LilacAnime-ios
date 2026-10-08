import SwiftUI
@main
struct LilacAnimeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var library = LibraryStore()
    @StateObject private var downloads = DownloadStore.shared
    @StateObject private var playback = PlaybackRouter()
    @StateObject private var navigation = DesktopNavigation()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            Group {
                if UIShowcase.enabled && UIShowcase.screen == "detail" {
                    NavigationStack { DetailView(summary: UIShowcase.anime, source: "linkkf") }
                } else if UIShowcase.enabled && UIShowcase.screen == "player" {
                    NavigationStack { EpisodePlayerView(item: PlaybackItem(anime: SavedAnime(UIShowcase.anime, source: "linkkf"), episode: UIShowcase.anime.episodes[0])) }
                } else if UIShowcase.enabled && UIShowcase.screen == "models" {
                    NavigationStack { LocalModelsView() }
                } else if UIShowcase.enabled && UIShowcase.screen == "catalog" {
                    DesktopFullCatalog()
                } else if UIShowcase.enabled && UIShowcase.screen == "settings" {
                    SettingsView()
                } else {
                    DesktopWorkspace()
                }
            }.environmentObject(library).environmentObject(downloads).environmentObject(playback).environmentObject(navigation)
                .fullScreenCover(item: $playback.presentation) { presentation in
                    EpisodePlayerView(item: presentation.item)
                        .environmentObject(library).environmentObject(downloads)
                }
                .tint(LilacStyle.accent)
                .preferredColorScheme(UIShowcase.enabled || library.preferences.theme == "system" ? nil : (library.preferences.theme == "light" ? .light : .dark))
                .task { SubtitleFiles.restoreFonts(); downloads.library = library; if !UIShowcase.enabled { DesktopUpdater.shared.automaticCheck() } }
                .onChange(of: scenePhase) { phase in if phase == .active && !UIShowcase.enabled { DesktopUpdater.shared.automaticCheck() } }
        }
    }
}
