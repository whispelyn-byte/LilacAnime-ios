import Foundation

enum DownloadStreamSelector {
    static func choose(_ streams: [ResolvedStream], quality: String) async -> ResolvedStream? {
        var fastest: (ResolvedStream, Double)?
        for stream in streams.prefix(6) {
            do {
                try Task.checkCancellation()
                let target = try await segment(stream, quality: quality)
                var request = URLRequest(url: target)
                for (key, value) in stream.headers where key.lowercased() != "cookie" || target.host == stream.url.host { request.setValue(value, forHTTPHeaderField: key) }
                request.setValue(stream.referer, forHTTPHeaderField: "Referer")
                request.setValue("bytes=0-262143", forHTTPHeaderField: "Range")
                let speed = try await BoundedSpeedProbe.measure(request)
                if speed >= 300 * 1024 { return stream }
                if fastest == nil || fastest!.1 < speed { fastest = (stream, speed) }
            } catch is CancellationError { return nil }
            catch { continue }
        }
        return fastest?.0 ?? streams.first
    }
    private static func segment(_ stream: ResolvedStream, quality: String) async throws -> URL {
        var url = stream.url
        for _ in 0..<5 {
            let (data, _) = try await HLSData.fetch(url, stream: stream)
            let text = HLSData.selectVariant(try HLSData.manifest(data, key: stream.manifestKey), quality: quality)
            let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && !$0.hasPrefix("#") }
            guard !lines.isEmpty else { throw SubtitleFiles.failure("빈 재생목록") }
            if text.contains("#EXT-X-STREAM-INF") {
                guard let next = URL(string: lines[0], relativeTo: url)?.absoluteURL else { throw SubtitleFiles.failure("영상 주소 오류") }; url = next
            } else {
                guard let next = URL(string: lines[min(3, lines.count - 1)], relativeTo: url)?.absoluteURL else { throw SubtitleFiles.failure("영상 조각 주소 오류") }; return next
            }
        }
        throw SubtitleFiles.failure("재생목록이 너무 깊습니다.")
    }
}
private final class BoundedSpeedProbe: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var pending: CheckedContinuation<Double, Error>?
    private var session: URLSession?
    private var started = Date()
    private var bytes = 0
    private var cancelled = false
    static func measure(_ request: URLRequest) async throws -> Double {
        let probe = BoundedSpeedProbe()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { pending in probe.start(request, pending: pending) }
        } onCancel: { probe.cancel() }
    }
    private func start(_ request: URLRequest, pending: CheckedContinuation<Double, Error>) {
        lock.lock()
        if cancelled { lock.unlock(); pending.resume(throwing: CancellationError()); return }
        self.pending = pending; started = Date()
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 15; config.timeoutIntervalForResource = 15
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        self.session = session; let task = session.dataTask(with: request); lock.unlock()
        task.resume()
    }
    private func cancel() { lock.lock(); cancelled = true; lock.unlock(); finish(.failure(CancellationError())) }
    private func finish(_ result: Result<Double, Error>) {
        lock.lock(); let pending = self.pending; self.pending = nil; let session = self.session; self.session = nil; lock.unlock()
        session?.invalidateAndCancel(); pending?.resume(with: result)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) { completionHandler(.allow) }
        else { completionHandler(.cancel); finish(.failure(SubtitleFiles.failure("속도 측정 서버 오류"))) }
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        bytes += data.count
        if bytes >= 262144 { finish(.success(Double(bytes) / max(0.05, Date().timeIntervalSince(started)))) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(.failure(error)) }
        else { finish(.success(Double(bytes) / max(0.05, Date().timeIntervalSince(started)))) }
    }
}
