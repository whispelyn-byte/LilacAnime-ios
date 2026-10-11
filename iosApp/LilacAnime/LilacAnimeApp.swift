import SwiftUI
@main
struct LilacAnimeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var library = LibraryStore(deferLoading: true)
    @StateObject private var downloads = DownloadStore.shared
    @StateObject private var playback = PlaybackRouter()
    @StateObject private var navigation = DesktopNavigation()
    @StateObject private var updater = DesktopUpdater.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var restored = false
    var body: some Scene {
        WindowGroup {
            Group {
                if !restored {
                    ProgressView("저장된 목록 불러오는 중…")
                } else if UIShowcase.enabled && UIShowcase.screen == "detail" {
                    NavigationStack { DetailView(summary: UIShowcase.anime, source: "linkkf") }
                } else if UIShowcase.enabled && ["player", "player-settings", "player-fit-contain", "player-fit-cover", "player-fit-stretch"].contains(UIShowcase.screen) {
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
                .background {
                    if restored && !UIShowcase.enabled {
                        ProviderPlayerView(resolver: downloads.resolver).opacity(0.001)
                            .allowsHitTesting(false).accessibilityHidden(true)
                    }
                }
                .fullScreenCover(item: $playback.presentation) { presentation in
                    EpisodePlayerView(item: presentation.item)
                        .environmentObject(library).environmentObject(downloads)
                }
                // Not over the player: the notes wait until playback is closed.
                .sheet(item: Binding(get: { playback.presentation == nil ? updater.changelog : nil }, set: { updater.changelog = $0 })) { ChangelogSheet(release: $0) }
                .tint(LilacStyle.accent)
                .preferredColorScheme(UIShowcase.enabled || library.preferences.theme == "system" ? nil : (library.preferences.theme == "light" ? .light : .dark))
                .task {
                    guard !restored else { return }
                    await library.load()
                    await downloads.load()
                    await DesktopCatalog.shared.load()
                    await EpisodeSubtitleStore.shared.load()
                    await DeviceSync.shared.load()
                    await Task.detached(priority: .utility) { SubtitleFiles.restoreFonts() }.value
                    downloads.library = library
                    restored = true
                    if !UIShowcase.enabled { DesktopUpdater.shared.automaticCheck(); DeviceSync.shared.start(library: library) }
                }
                .onChange(of: scenePhase) { phase in if phase == .active && !UIShowcase.enabled { DesktopUpdater.shared.automaticCheck(); DeviceSync.shared.becameActive() } }
        }
    }
}
