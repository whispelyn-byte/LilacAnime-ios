import Foundation

/// Desktop firstAnswering: prefer the first server, start another after six seconds of
/// silence (or immediately when every active attempt failed), and cancel the losing work.
enum StaggeredResolver {
    private enum Event<Value> { case timer(Int); case answer(Result<Value, Error>) }
    static func first<Item, Value>(_ items: [Item], delay: UInt64 = 6_000_000_000,
                                   attempt: @escaping (Item) async throws -> Value) async throws -> Value {
        guard !items.isEmpty else { throw SubtitleFiles.failure("영상 서버가 없습니다.") }
        return try await withThrowingTaskGroup(of: Event<Value>.self) { group in
            var started = 0, active = 0
            var failure: Error = SubtitleFiles.failure("모든 영상 서버 연결에 실패했습니다.")
            func launch() {
                guard started < items.count else { return }
                let item = items[started]; started += 1; active += 1
                let ordinal = started
                group.addTask { do { return .answer(.success(try await attempt(item))) } catch { return .answer(.failure(error)) } }
                if started < items.count { group.addTask { try await Task.sleep(nanoseconds: delay); return .timer(ordinal) } }
            }
            launch()
            while let event = try await group.next() {
                try Task.checkCancellation()
                switch event {
                case .timer(let ordinal): if ordinal == started { launch() }
                case .answer(.success(let value)): group.cancelAll(); return value
                case .answer(.failure(let error)):
                    active -= 1; failure = error
                    if active == 0 {
                        if started == items.count { group.cancelAll(); throw failure }
                        launch()
                    }
                }
            }
            throw failure
        }
    }
}
