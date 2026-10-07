import SwiftUI
@main
struct LilacAnimeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var library = LibraryStore()
    @StateObject private var downloads = DownloadStore.shared
    var body: some Scene {
        WindowGroup {
            TabView {
                CatalogView().tabItem { Label("탐색", systemImage: "magnifyingglass") }
                LibraryView().tabItem { Label("보관함", systemImage: "heart") }
                DownloadsView().tabItem { Label("다운로드", systemImage: "arrow.down.circle") }
                SettingsView().tabItem { Label("설정", systemImage: "gear") }
            }.environmentObject(library).environmentObject(downloads)
                .tint(Color(red: 0.71, green: 0.52, blue: 0.90))
                .preferredColorScheme(library.preferences.theme == "system" ? nil : (library.preferences.theme == "light" ? .light : .dark))
                .task { SubtitleFiles.restoreFonts() }
        }
    }
}
