import Foundation

/// Keeps repeated key presses ahead of the asynchronous mpv position updates.
struct PlayerSeekHint: Identifiable {
    static let lifetime = 0.8
    let id = UUID()
    let seconds: Double?
    let target: Double
    let at: TimeInterval

    func isActive(at now: TimeInterval) -> Bool { now >= at && now - at < Self.lifetime }

    static func relative(_ seconds: Double, position: Double, duration: Double,
                         previous: Self?, now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Self? {
        guard seconds.isFinite, seconds != 0, position.isFinite else { return nil }
        let recent = previous.flatMap { $0.isActive(at: now) ? $0 : nil }
        let base = recent?.target ?? max(0, position)
        let limit = duration.isFinite && duration > 0 ? duration : Double.infinity
        let target = min(limit, max(0, base + seconds))
        let total: Double
        if let prior = recent?.seconds, (prior > 0) == (seconds > 0) { total = prior + seconds }
        else { total = seconds }
        return Self(seconds: total, target: target, at: now)
    }

    static func jump(_ digit: Int, duration: Double,
                     now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Self? {
        guard (0...9).contains(digit), duration.isFinite, duration > 0 else { return nil }
        return Self(seconds: nil, target: duration * Double(digit) / 10, at: now)
    }
}
