/// Playback advancement excludes wall time spent paused or buffering. Dividing by
/// speed keeps the grace period in watching seconds. Seeks start a new sample.
struct PlaybackSkipCountdown {
    private(set) var elapsed = 0.0
    private var position: Double
    private var speed: Double
    private var revision: UInt64
    init(position: Double, speed: Double, revision: UInt64) {
        self.position = position; self.speed = speed; self.revision = revision
    }
    mutating func update(position: Double, speed: Double, revision: UInt64) {
        if revision == self.revision, position.isFinite, self.position.isFinite,
           position >= self.position, self.speed.isFinite, self.speed > 0 {
            elapsed += (position - self.position) / self.speed
        } else { elapsed = 0 }
        self.position = position; self.speed = speed; self.revision = revision
    }
}

/// Deduplicates mpv's EOF property and end-file event for the same playback pass.
struct PlaybackCompletion {
    private var notified = false
    mutating func reset() { notified = false }
    mutating func resume() { notified = false }
    mutating func reachedEnd() -> Bool {
        guard !notified else { return false }
        notified = true
        return true
    }
}
