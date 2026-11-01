import SwiftUI

struct PlaybackPresentation: Identifiable {
    let id = UUID()
    let item: PlaybackItem
}

@MainActor
final class PlaybackRouter: ObservableObject {
    @Published var presentation: PlaybackPresentation?
    func open(_ item: PlaybackItem) { presentation = PlaybackPresentation(item: DownloadStore.shared.offline(item) ?? item) }
}

struct PlaybackButton<Content: View>: View {
    @EnvironmentObject private var playback: PlaybackRouter
    /// Made when pressed: an episode list builds one per row on every redraw, each carrying the series' other episodes.
    let item: () -> PlaybackItem
    let content: () -> Content
    init(item: @autoclosure @escaping () -> PlaybackItem, @ViewBuilder content: @escaping () -> Content) { self.item = item; self.content = content }
    var body: some View { Button { playback.open(item()) } label: { content() } }
}

@MainActor
final class DesktopNavigation: ObservableObject {
    /// 설정 > 일반 > 시작 화면 (desktop #23): 홈 · 전체 · 시청기록 · 내 목록.
    nonisolated static let startSections = [("home", "홈"), ("catalog", "전체"), ("history", "시청기록"), ("favorites", "내 목록")]
    nonisolated static func startSection(_ defaults: UserDefaults = .standard) -> String {
        let value = defaults.string(forKey: "startView") ?? ""
        return startSections.contains { $0.0 == value } ? value : "home"
    }
    @Published var section = DesktopNavigation.startSection()
    @Published var query = ""
    @Published var listMode = "browse"
    @Published var showDownloads = false
    func search(_ value: String) { query = value; listMode = "browse"; section = "search" }
    func browse(_ mode: String) { query = ""; listMode = mode; section = "search" }
}
