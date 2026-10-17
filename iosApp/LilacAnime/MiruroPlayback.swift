import Foundation

struct MiruroProbeResult { let speed: Double; let requiredSpeed: Double }
enum MiruroPlayback {
    static func choose(_ streams: [ResolvedStream], preferRaw: Bool, preferred: String?, excluded: Set<String> = [],
                       probe: @escaping (ResolvedStream) async throws -> MiruroProbeResult = { try await inspect($0) }) async throws -> ResolvedStream {
        let dubbed = streams.first { $0.label == preferred }.map { ServerChoice.kind($0.label) == "dub" } ?? false
        let candidates = streams.filter { !excluded.contains($0.label) && (ServerChoice.kind($0.label) == "dub") == dubbed }
        guard !candidates.isEmpty else { throw SubtitleFiles.failure("사용 가능한 영상 서버가 없습니다. 다른 서버를 선택해 주세요.") }
        if let manual = candidates.first(where: { $0.label == preferred }) {
            do { _ = try await probe(manual); return manual } catch { try Task.checkCancellation() }
        }
        var slow: [(ResolvedStream, Double)] = []
        let groups = Set(candidates.map { DesktopStreamPolicy.priority($0, preferRaw: preferRaw) }).sorted()
        for priority in groups {
            let group = candidates.filter { $0.label != preferred && DesktopStreamPolicy.priority($0, preferRaw: preferRaw) == priority }
            let winner = await withTaskGroup(of: (ResolvedStream, MiruroProbeResult)?.self) { tasks -> ResolvedStream? in
                var next = 0
                func launch() {
                    guard next < group.count else { return }
                    let stream = group[next]; next += 1
                    tasks.addTask { do { return (stream, try await probe(stream)) } catch { return nil } }
                }
                for _ in 0..<min(3, group.count) { launch() }
                while let value = await tasks.next() {
                    if let (stream, result) = value {
                        if result.speed >= max(300 * 1024, result.requiredSpeed) { tasks.cancelAll(); return stream }
                        slow.append((stream, result.speed))
                    }
                    if !Task.isCancelled { launch() }
                }
                return nil
            }
            try Task.checkCancellation()
            if let winner { return winner }
        }
        if let fastest = slow.max(by: { $0.1 < $1.1 }) { return fastest.0 }
        throw SubtitleFiles.failure("모든 영상 서버 연결에 실패했습니다.")
    }
    static func inspect(_ stream: ResolvedStream, timeout: TimeInterval = 6,
                        fetch: @escaping (URL) async throws -> (Data, HTTPURLResponse)? = { _ in nil }) async throws -> MiruroProbeResult {
        func get(_ url: URL) async throws -> (Data, HTTPURLResponse) {
            try await withThrowingTaskGroup(of: (Data, HTTPURLResponse).self) { tasks in
                tasks.addTask {
                    if let value = try await fetch(url) { return value }
                    return try await HLSData.fetch(url, stream: stream, timeout: timeout)
                }
                tasks.addTask {
                    try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                    throw SubtitleFiles.failure("영상 확인 시간 초과")
                }
                defer { tasks.cancelAll() }
                return try await tasks.next()!
            }
        }
        var url = stream.url; var bandwidth = 0.0
        for _ in 0..<5 {
            let (data, response) = try await get(url); url = response.url ?? url
            let text = try HLSData.manifest(data, key: stream.manifestKey)
            if text.contains("#EXT-X-STREAM-INF") {
                let regex = try! NSRegularExpression(pattern: #"#EXT-X-STREAM-INF:([^\r\n]*)\r?\n\s*([^#\r\n]+)"#)
                let value = text as NSString
                let variants = regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
                func number(_ match: NSTextCheckingResult, _ pattern: String) -> Double {
                    let attributes = value.substring(with: match.range(at: 1))
                    let expression = try! NSRegularExpression(pattern: pattern)
                    guard let found = expression.firstMatch(in: attributes, range: NSRange(attributes.startIndex..., in: attributes)) else { return 0 }
                    return Double((attributes as NSString).substring(with: found.range(at: 1))) ?? 0
                }
                guard let selected = variants.max(by: {
                    let a = number($0, #"RESOLUTION=\d+x(\d+)"#), b = number($1, #"RESOLUTION=\d+x(\d+)"#)
                    return a == b ? number($0, #"(?:^|,)BANDWIDTH=(\d+)"#) < number($1, #"(?:^|,)BANDWIDTH=(\d+)"#) : a < b
                }), let next = URL(string: value.substring(with: selected.range(at: 2)).trimmingCharacters(in: .whitespaces), relativeTo: url)?.absoluteURL else { throw SubtitleFiles.failure("영상 재생목록 오류") }
                bandwidth = number(selected, #"(?:^|,)BANDWIDTH=(\d+)"#)
                url = next; continue
            }
            let samples = segments(text)
            guard !samples.isEmpty else { throw SubtitleFiles.failure("빈 영상 재생목록") }
            let first = samples.count > 4 ? 3 : 0
            var rates: [Double] = []; var size = 0.0; var duration = 0.0
            for sample in samples.dropFirst(first).prefix(2) {
                guard let target = URL(string: sample.1, relativeTo: url)?.absoluteURL else { throw SubtitleFiles.failure("영상 조각 주소 오류") }
                let start = Date(); let (bytes, _) = try await get(target)
                guard !bytes.isEmpty else { throw SubtitleFiles.failure("빈 영상 조각") }
                size += Double(bytes.count); duration += sample.0
                rates.append(Double(bytes.count) / max(0.05, Date().timeIntervalSince(start)))
            }
            return MiruroProbeResult(speed: rates.min() ?? 0, requiredSpeed: max(bandwidth / 8, duration > 0 ? size / duration : 0) * 1.5)
        }
        throw SubtitleFiles.failure("재생목록이 너무 깊습니다.")
    }
    static func segments(_ text: String) -> [(Double, String)] {
        let regex = try! NSRegularExpression(pattern: #"#EXTINF:([\d.]+)[^\r\n]*\r?\n(?:#[^\r\n]*\r?\n)*([^#\r\n]+)"#)
        let source = text as NSString
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).map {
            (Double(source.substring(with: $0.range(at: 1))) ?? 0, source.substring(with: $0.range(at: 2)).trimmingCharacters(in: .whitespaces))
        }
    }
}
struct MiruroStallMonitor {
    private var context = ""; private var position = 0.0; private var since = 0.0; private var seek: UInt64 = 0
    mutating func update(context: String, position: Double, seek: UInt64, eligible: Bool, buffering: Bool, now: Double) -> Bool {
        if self.context != context || self.seek != seek || !eligible || abs(position - self.position) > 0.1 {
            self.context = context; self.position = position; self.seek = seek; since = now; return false
        }
        if buffering && now - since >= 15 { since = now; return true }
        return false
    }
}
