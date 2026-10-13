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
    @Published var section = "home"
    @Published var query = ""
    @Published var listMode = "browse"
    @Published var showDownloads = false
    func search(_ value: String) { query = value; listMode = "browse"; section = "search" }
    func browse(_ mode: String) { query = ""; listMode = mode; section = "search" }
}
