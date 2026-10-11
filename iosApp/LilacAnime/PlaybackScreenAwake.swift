import UIKit

/// Keep the display awake only while a foreground player is playing. Ownership
/// prevents an outgoing player from unlocking the screen for its replacement.
@MainActor
final class PlaybackScreenAwake {
    private static var owners = Set<UUID>()
    private static var previous = false
    private let id = UUID()

    func update(playing: Bool, active: Bool? = nil) {
        let foreground = active ?? (UIApplication.shared.applicationState == .active)
        if playing && foreground {
            if Self.owners.isEmpty { Self.previous = UIApplication.shared.isIdleTimerDisabled }
            Self.owners.insert(id)
        } else {
            guard Self.owners.remove(id) != nil else { return }
        }
        UIApplication.shared.isIdleTimerDisabled = !Self.owners.isEmpty || Self.previous
    }

    func release() { update(playing: false) }
}
