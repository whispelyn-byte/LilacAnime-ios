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
