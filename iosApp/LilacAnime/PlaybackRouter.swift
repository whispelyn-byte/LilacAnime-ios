import SwiftUI

struct PlaybackPresentation: Identifiable {
    let id = UUID()
    let item: PlaybackItem
}

@MainActor
final class PlaybackRouter: ObservableObject {
    @Published var presentation: PlaybackPresentation?
    func open(_ item: PlaybackItem) { presentation = PlaybackPresentation(item: item) }
}

struct PlaybackButton<Content: View>: View {
    @EnvironmentObject private var playback: PlaybackRouter
    let item: PlaybackItem
    @ViewBuilder let content: () -> Content
    var body: some View { Button { playback.open(item) } label: { content() } }
}

@MainActor
final class DesktopNavigation: ObservableObject {
    @Published var section = "home"
    @Published var query = ""
    @Published var listMode = "browse"
    func search(_ value: String) { query = value; listMode = "browse"; section = "search" }
    func browse(_ mode: String) { query = ""; listMode = mode; section = "search" }
}
