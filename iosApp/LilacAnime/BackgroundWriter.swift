import Foundation
import UIKit

/// Saves app state off the main thread. Encoding a large list (the whole source catalog, the title index, the watch
/// history with its series snapshots) on the main thread every time it changed froze the app for seconds at a time;
/// changes that come close together are written once, with the latest value.
final class BackgroundWriter: @unchecked Sendable {
    static let shared = BackgroundWriter()
    private let queue = DispatchQueue(label: "Lilac.Writer", qos: .utility)
    private let lock = NSLock()
    private var pending: [URL: @Sendable () throws -> Data] = [:]
    private var observers: [NSObjectProtocol] = []

    init() {
        // What is still waiting is written before the app can be suspended.
        for name in [UIApplication.willResignActiveNotification, UIApplication.didEnterBackgroundNotification, UIApplication.willTerminateNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in self?.flush() })
        }
    }

    /// encode runs on the writer's queue: it must capture copies (value types), not the live objects.
    func write(_ url: URL, after delay: TimeInterval = 1, failed: ((String) -> Void)? = nil, encode: @escaping @Sendable () throws -> Data) {
        lock.lock()
        let scheduled = pending[url] != nil
        pending[url] = encode
        lock.unlock()
        guard !scheduled else { return }
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in self?.run(url, failed: failed) }
    }

    /// Writes everything waiting now (the app is leaving the foreground), under a background task so it can finish.
    func flush() {
        let application = UIApplication.shared
        var identifier = UIBackgroundTaskIdentifier.invalid
        identifier = application.beginBackgroundTask(withName: "LilacSave") { application.endBackgroundTask(identifier) }
        queue.async { [weak self] in
            self?.runAll()
            DispatchQueue.main.async { if identifier != .invalid { application.endBackgroundTask(identifier) } }
        }
    }
    /// For tests and callers that must see the files written.
    func waitUntilWritten() { queue.sync { runAll() } }
    private func runAll() {
        lock.lock(); let urls = Array(pending.keys); lock.unlock()
        for url in urls { run(url, failed: nil) }
    }

    private func run(_ url: URL, failed: ((String) -> Void)?) {
        lock.lock(); let job = pending.removeValue(forKey: url); lock.unlock()
        guard let job else { return }
        do {
            let data = try job()
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url, options: .atomic)
        } catch {
            let message = error.localizedDescription
            if let failed { DispatchQueue.main.async { failed(message) } }
        }
    }
}
