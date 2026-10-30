import Foundation
import Network

enum HLSData {
    static func selectVariant(_ text: String, quality: String) -> String {
        let lines = text.components(separatedBy: .newlines)
        var choices: [(index: Int, bandwidth: Int, height: Int)] = []
        let bandwidthPattern = try! NSRegularExpression(pattern: "(?:^|,)BANDWIDTH=([0-9]+)")
        let heightPattern = try! NSRegularExpression(pattern: "RESOLUTION=[0-9]+x([0-9]+)", options: .caseInsensitive)
        func number(_ regex: NSRegularExpression, _ text: String) -> Int {
            guard let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
                  let range = Range(match.range(at: 1), in: text) else { return 0 }
            return Int(text[range]) ?? 0
        }
        for index in lines.indices where lines[index].hasPrefix("#EXT-X-STREAM-INF") && index + 1 < lines.count {
            let attributes = String(lines[index].dropFirst("#EXT-X-STREAM-INF:".count))
            choices.append((index, number(bandwidthPattern, attributes), number(heightPattern, attributes)))
        }
        guard !choices.isEmpty else { return text }
        let preferred = Int(quality.replacingOccurrences(of: "p", with: ""))
        let pool = preferred.map { limit in choices.filter { $0.height > 0 && $0.height <= limit } } ?? choices
        let selected = (pool.isEmpty ? choices : pool).max { $0.bandwidth < $1.bandwidth }?.index
        let omitted = Set(choices.filter { $0.index != selected }.flatMap { [$0.index, $0.index + 1] })
        return lines.enumerated().filter { !omitted.contains($0.offset) }.map(\.element).joined(separator: "\n")
    }

    /// The video's requests go out over several sessions, each with its own connections. One session speaks HTTP/2 to
    /// Cloudflare (RE:Anime's FlixCloud CDN) and carries every request over a single connection, which the far-away edge
    /// gives a few hundred KB/s: the ranges and segments fetched at once all shared it, and the one the player waited
    /// for got a small part of it (playback stopped to buffer, most of all after starting or a seek). Desktop's fetch
    /// opens a connection per request (HTTP/1.1), which is what the ranges were made for.
    private static let sessions: [URLSession] = (0..<6).map { _ in
        // The shared cookie store, as URLSession.shared: a CDN's cookie from the playlist goes with its segments.
        let configuration = URLSessionConfiguration.default
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpMaximumConnectionsPerHost = 8
        return URLSession(configuration: configuration)
    }
    private static let turn = SessionTurn()
    private final class SessionTurn: @unchecked Sendable {
        private let lock = NSLock(); private var value = 0
        func next(_ count: Int) -> Int { lock.lock(); defer { lock.unlock() }; value = (value + 1) % count; return value }
    }
    static func session() -> URLSession { sessions[turn.next(sessions.count)] }

    static func fetch(_ url: URL, stream: ResolvedStream, range: String? = nil, timeout: TimeInterval = 60) async throws -> (Data, HTTPURLResponse) {
        try Task.checkCancellation()
        if url == stream.url, let manifest = stream.hlsManifest {
            return (Data(manifest.utf8), HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/vnd.apple.mpegurl"])!)
        }
        var fetchRequest = request(url, stream: stream, range: range); fetchRequest.timeoutInterval = timeout
        let (data, response) = try await session().data(for: fetchRequest)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw SubtitleFiles.failure("영상 요청 실패") }
        return (data, http)
    }
    private static func request(_ url: URL, stream: ResolvedStream, range: String?) -> URLRequest {
        var request = URLRequest(url: url)
        for (key, value) in stream.headers where key.lowercased() != "cookie" || url.host == stream.url.host { request.setValue(value, forHTTPHeaderField: key) }
        request.setValue(stream.referer, forHTTPHeaderField: "Referer")
        if let range { request.setValue(range, forHTTPHeaderField: "Range") }
        return request
    }
    /// When a range last had data, and whether its ask is over.
    private final class Heard: @unchecked Sendable {
        private let lock = NSLock(); private var at = Date(); private var over = false
        func touch() { lock.lock(); at = Date(); lock.unlock() }
        func finish() { lock.lock(); over = true; lock.unlock() }
        var quiet: TimeInterval { lock.lock(); defer { lock.unlock() }; return Date().timeIntervalSince(at) }
        var done: Bool { lock.lock(); defer { lock.unlock() }; return over }
    }
    /// A range read as it arrives, so a request that has gone quiet can be told from a slow one still receiving.
    private static func watchedFetch(_ url: URL, stream: ResolvedStream, range: String, heard: Heard) async throws -> (Data, HTTPURLResponse) {
        defer { heard.finish() }
        let (bytes, response) = try await session().bytes(for: request(url, stream: stream, range: range))
        heard.touch()
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw SubtitleFiles.failure("영상 요청 실패") }
        var data = Data(), chunk: [UInt8] = []
        chunk.reserveCapacity(16384)
        for try await byte in bytes {
            chunk.append(byte)
            if chunk.count == 16384 { data.append(contentsOf: chunk); chunk.removeAll(keepingCapacity: true); heard.touch() }
        }
        data.append(contentsOf: chunk)
        return (data, http)
    }
    /// AES-128 segments must reach the HLS decoder byte-for-byte, even if named .png or .ts.
    static func encryptedSegments(_ text: String, base: URL) -> Set<URL> {
        var encrypted = false, result: Set<URL> = []
        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("#EXT-X-KEY:") { encrypted = line.range(of: #"(?:^|,)METHOD=AES-128(?:,|$)"#, options: .regularExpression, range: line.index(line.startIndex, offsetBy: 11)..<line.endIndex) != nil }
            else if encrypted, !line.isEmpty, !line.hasPrefix("#"), let url = URL(string: line, relativeTo: base)?.absoluteURL { result.insert(url) }
        }
        return result
    }
    static func readable(_ stream: ResolvedStream) async throws -> Bool {
        var url = stream.url
        for _ in 0..<5 {
            let (data, response) = try await fetch(url, stream: stream, timeout: 6)
            url = response.url ?? url
            let text = try manifest(data, key: stream.manifestKey)
            guard let entry = text.components(separatedBy: .newlines).map({ $0.trimmingCharacters(in: .whitespacesAndNewlines) }).first(where: { !$0.isEmpty && !$0.hasPrefix("#") }),
                  let target = URL(string: entry, relativeTo: url)?.absoluteURL else { return true }
            if text.contains("#EXTINF") {
                if encryptedSegments(text, base: url).contains(target) { return true }
                let (bytes, _) = try await fetch(target, stream: stream, range: "bytes=0-4095", timeout: 6)
                return isMedia(transportStream(bytes))
            }
            url = target
        }
        return true
    }
    static func manifest(_ data: Data, key: String?) throws -> String {
        if let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines), text.hasPrefix("#EXTM3U") { return text }
        guard let key, let bytes = Data(base64Encoded: key), !bytes.isEmpty,
              let text = String(data: data, encoding: .utf8), let encrypted = Data(base64Encoded: text, options: .ignoreUnknownCharacters) else {
            throw SubtitleFiles.failure("HLS 재생목록을 읽을 수 없습니다.")
        }
        let keyBytes = Array(bytes)
        let plain = Data(encrypted.enumerated().map { $0.element ^ keyBytes[$0.offset % keyBytes.count] })
        guard let result = String(data: plain, encoding: .utf8), result.hasPrefix("#EXTM3U") else { throw SubtitleFiles.failure("FlixCloud 재생목록 복호화 실패") }
        return result
    }
    static func fragment(_ data: Data) -> Data {
        let bytes = Array(data)
        var payload: [UInt8]
        if bytes.count >= 12 && String(bytes: bytes.prefix(4), encoding: .ascii) == "RIFF" && String(bytes: bytes[8..<12], encoding: .ascii) == "WEBP" { payload = Array(bytes.dropFirst(12)) }
        else if bytes.starts(with: [137,80,78,71,13,10,26,10]) { payload = Array(bytes.dropFirst(8)) }
        else { return data }
        if payload.isEmpty || payload.first == 71 { return Data(payload) }
        let key: [UInt8] = [157,42,241,71,179,142,92,112,166,25,228,59,216,98,15,197]
        for i in payload.indices { payload[i] ^= key[i % key.count] }
        return Data(payload)
    }
    static func isMedia(_ data: Data) -> Bool {
        if data.first == 71 && (data.count <= 188 || data[data.startIndex + 188] == 71) { return true }
        guard data.count >= 8 else { return false }
        return ["ftyp", "styp", "moof", "sidx", "emsg"].contains(String(data: data.dropFirst(4).prefix(4), encoding: .isoLatin1) ?? "")
    }
    private static func transportPayload(_ data: Data) -> Data? {
        let bytes = Array(data), limit = min(bytes.count - 377, 65535)
        guard limit >= 0 else { return nil }
        for index in 0...limit where bytes[index] == 71 && bytes[index + 188] == 71 && bytes[index + 376] == 71 { return Data(bytes[index...]) }
        return nil
    }
    // Inspect padding before the image wrapper: an ordinary padded PNG is not XOR encrypted.
    // Preserve opaque segments so HLS AES decryption can still be handled by the player.
    static func transportStream(_ raw: Data) -> Data {
        if let payload = transportPayload(raw) { return payload }
        let decoded = fragment(raw)
        return transportPayload(decoded) ?? decoded
    }
    static func validatedFragment(_ raw: Data) throws -> Data {
        let decoded = transportStream(raw)
        if isMedia(decoded) { return decoded }
        throw SubtitleFiles.failure("영상 조각 복호화에 실패했습니다.")
    }

    private enum FetchEvent {
        case timer
        case idle
        case answer(Result<(Data, HTTPURLResponse), Error>)
    }
    /// The CDN behind FlixCloud gives each file a few hundred KB/s and now and then stalls a request for a minute. A range
    /// is asked for up to three times; another ask goes out when one fails, when one has had nothing for `stall` seconds
    /// (a slow ask still receiving is left alone), or `hedge` seconds after the last (desktop flix-proxy fetchHedged).
    private static func hedged(_ url: URL, stream: ResolvedStream, start: Int, end: Int, stall: TimeInterval = 3, hedge: TimeInterval = 8) async throws -> (Data, HTTPURLResponse) {
        try await withThrowingTaskGroup(of: FetchEvent.self) { group in
            var attempts = 0, active = 0
            var failure: Error = SubtitleFiles.failure("영상 조각을 받지 못했습니다.")
            func launch() {
                guard attempts < 3 else { return }
                attempts += 1; active += 1
                let heard = Heard()
                group.addTask {
                    do {
                        let result = try await watchedFetch(url, stream: stream, range: "bytes=\(start)-\(end)", heard: heard)
                        if start > 0 && (result.1.statusCode != 206 || result.0.count != end - start + 1) { throw SubtitleFiles.failure("영상 조각 범위 응답 오류") }
                        return .answer(.success(result))
                    } catch { return .answer(.failure(error)) }
                }
                // This ask's watch: the next ask once, when it has gone quiet or the hedge time has passed.
                if attempts < 3 {
                    group.addTask {
                        let begun = Date()
                        while !heard.done {
                            try await Task.sleep(nanoseconds: 250_000_000)
                            if heard.done { break }
                            if heard.quiet >= stall || Date().timeIntervalSince(begun) >= hedge { return .timer }
                        }
                        return .idle
                    }
                }
            }
            launch()
            while let event = try await group.next() {
                try Task.checkCancellation()
                switch event {
                case .timer: launch()
                case .idle: break
                case .answer(.success(let result)): group.cancelAll(); return result
                case .answer(.failure(let error)):
                    active -= 1; failure = error
                    if active == 0 { if attempts == 3 { group.cancelAll(); throw failure }; launch() }
                }
            }
            throw failure
        }
    }
    static func mediaSegment(_ url: URL, stream: ResolvedStream) async throws -> (Data, HTTPURLResponse) {
        var failure: Error = SubtitleFiles.failure("영상 조각을 받지 못했습니다.")
        for _ in 0..<3 {
            do {
                // The first range only has to tell the segment's size before the rest can be split up (a whole first MB
                // took ~3 s before anything else went out); the rest goes in half-MB ranges, six at a time.
                let (head, response) = try await hedged(url, stream: stream, start: 0, end: 262143)
                let total = Int(response.value(forHTTPHeaderField: "Content-Range")?.components(separatedBy: "/").last ?? "") ?? 0
                var raw = head
                if response.statusCode == 206 && total > head.count {
                    let ranges = stride(from: head.count, to: total, by: 524288).map { ($0, min($0 + 524288, total) - 1) }
                    let pieces = try await withThrowingTaskGroup(of: (Int, Data).self) { group in
                        var next = 0; var result: [Int: Data] = [:]
                        func launch() {
                            guard next < ranges.count else { return }
                            let index = next; next += 1
                            group.addTask { (index, try await hedged(url, stream: stream, start: ranges[index].0, end: ranges[index].1).0) }
                        }
                        for _ in 0..<min(6, ranges.count) { launch() }
                        while let (index, data) = try await group.next() { result[index] = data; launch() }
                        return result
                    }
                    for index in ranges.indices { raw.append(pieces[index]!) }
                }
                return (try validatedFragment(raw), response)
            } catch is CancellationError { throw CancellationError() }
            catch { failure = error }
        }
        throw failure
    }
    /// What the player asks for that is not a disguised segment (playlists, keys, the .ts / .jpg segments of other
    /// sources): one request that has had nothing for 12 seconds is asked for again, up to three times. With the 60
    /// seconds a request was allowed to stay silent, one stalled segment stopped playback for a minute and then failed.
    static func playerFetch(_ url: URL, stream: ResolvedStream, range: String?) async throws -> (Data, HTTPURLResponse) {
        var failure: Error = SubtitleFiles.failure("영상 요청 실패")
        for attempt in 0..<3 {
            try Task.checkCancellation()
            do { return try await fetch(url, stream: stream, range: range, timeout: attempt < 2 ? 12 : 30) }
            catch is CancellationError { throw CancellationError() }
            catch { failure = error }
        }
        throw failure
    }
    static func references(_ text: String, base: URL) -> [URL] {
        var urls: [URL] = []
        let regex = try! NSRegularExpression(pattern: "URI=\"([^\"]+)\"", options: [.caseInsensitive])
        for line in text.components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty && !trimmed.hasPrefix("#"), let url = URL(string: trimmed, relativeTo: base)?.absoluteURL { urls.append(url) }
            for match in regex.matches(in: line, range: NSRange(line.startIndex..., in: line)) {
                if let range = Range(match.range(at: 1), in: line), let url = URL(string: String(line[range]), relativeTo: base)?.absoluteURL { urls.append(url) }
            }
        }
        var seen = Set<URL>()
        return urls.filter { seen.insert($0).inserted }
    }
    static func rewrite(_ text: String, base: URL, map: (URL) -> String) -> String {
        let regex = try! NSRegularExpression(pattern: "URI=\"([^\"]+)\"", options: [.caseInsensitive])
        return text.components(separatedBy: .newlines).map { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty && !trimmed.hasPrefix("#"), let url = URL(string: trimmed, relativeTo: base)?.absoluteURL { return map(url) }
            var result = line
            for match in regex.matches(in: line, range: NSRange(line.startIndex..., in: line)).reversed() {
                if let range = Range(match.range(at: 1), in: line), let url = URL(string: String(line[range]), relativeTo: base)?.absoluteURL { result.replaceSubrange(range, with: map(url)) }
            }
            return result
        }.joined(separator: "\n")
    }
}
final class HLSProxy {
    private let queue = DispatchQueue(label: "Lilac.HLS")
    private var listener: NWListener?
    private var routes: [String: URL] = [:]
    private let token = UUID().uuidString
    private var stream: ResolvedStream?
    private var port: UInt16 = 0
    private var quality = "Auto"
    private let media = HLSMediaCache()
    func start(_ stream: ResolvedStream, quality: String = "Auto") async throws -> ResolvedStream {
        try Task.checkCancellation()
        self.quality = quality
        self.stream = stream
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in self?.receive(connection) }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                var resumed = false
                listener.stateUpdateHandler = { [weak self] state in
                    if case .ready = state, !resumed { resumed = true; self?.port = listener.port?.rawValue ?? 0; continuation.resume() }
                    if case .failed(let error) = state, !resumed { resumed = true; continuation.resume(throwing: error) }
                    if case .cancelled = state, !resumed { resumed = true; continuation.resume(throwing: CancellationError()) }
                }
                listener.start(queue: queue)
            }
        } onCancel: { listener.cancel() }
        try Task.checkCancellation()
        let url = queue.sync { register(stream.url) }
        var proxied = stream; proxied.url = url; proxied.headers = [:]; proxied.manifestKey = nil; proxied.hlsManifest = nil
        return proxied
    }
    private var encrypted: Set<URL> = []
    private func register(_ url: URL) -> URL {
        let key = SubtitleFiles.key(url.absoluteString)
        routes[key] = url
        let original = url.pathExtension.lowercased()
        // FFmpeg (mpv) refuses HLS segments named .html/.png/.webp (allowed_segment_extensions): the playlist then
        // reads as empty and the video never starts. They are served decoded as transport stream, so they are named so.
        let ext = url == stream?.url && stream?.hlsManifest != nil ? "m3u8" : ["html", "png", "webp"].contains(original) ? "ts" : original.isEmpty ? "bin" : original
        return URL(string: "http://127.0.0.1:\(port)/\(token)/\(key).\(ext)")!
    }
    private func receive(_ connection: NWConnection) {
        connection.start(queue: queue)
        read(connection, buffer: Data())
    }
    private func read(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, done, error in
            guard let self else { connection.cancel(); return }
            var request = buffer; if let data { request.append(data) }
            guard request.count < 32768 else { connection.cancel(); return }
            if let header = String(data: request, encoding: .utf8), header.contains("\r\n\r\n") { self.respond(connection, header: header) }
            else if done || error != nil { connection.cancel() }
            else { self.read(connection, buffer: request) }
        }
    }
    private func respond(_ connection: NWConnection, header: String) {
        let lines = header.components(separatedBy: "\r\n")
        let parts = lines.first?.split(separator: " ") ?? []
        guard parts.count >= 2, ["GET","HEAD"].contains(String(parts[0])) else { send(connection, status: 405, body: Data()); return }
        let path = String(parts[1]).split(separator: "/")
        guard path.count == 2, path[0] == Substring(token), let url = routes[String(path[1]).components(separatedBy: ".")[0]], let stream else {
            send(connection, status: 404, body: Data()); return
        }
        let range = lines.first { $0.lowercased().hasPrefix("range:") }.map { String($0.dropFirst(6)).trimmingCharacters(in: .whitespaces) }
        Task {
            do {
                let aes = self.queue.sync { self.encrypted.contains(url) }
                // Ordinary AAC, MP3 and byte-range media remain on the original passthrough path.
                let segment = !aes && ["png", "webp", "html"].contains(url.pathExtension.lowercased())
                let (data, response) = segment ? try await media.load(url, stream: stream) : try await HLSData.playerFetch(url, stream: stream, range: stream.manifestKey == nil ? range : nil)
                var body = data
                var contentType = response.mimeType ?? "application/octet-stream"
                if url.pathExtension.lowercased() == "m3u8" || contentType.contains("mpegurl") || (url == stream.url && stream.hlsManifest != nil) {
                    let text = HLSData.selectVariant(try HLSData.manifest(data, key: stream.manifestKey), quality: self.quality)
                    let encrypted = HLSData.encryptedSegments(text, base: url)
                    let references = HLSData.references(text, base: url)
                    self.queue.sync { self.encrypted.subtract(references); self.encrypted.formUnion(encrypted) }
                    await media.order(references.filter { !encrypted.contains($0) && ["png", "webp", "html"].contains($0.pathExtension.lowercased()) })
                    body = Data(self.queue.sync { HLSData.rewrite(text, base: url) { self.register($0).absoluteString } }.utf8)
                    contentType = "application/vnd.apple.mpegurl"
                } else if segment { contentType = body.first == 71 ? "video/mp2t" : "video/mp4" }
                else if !aes && stream.manifestKey != nil && (url.pathExtension == "ts" || contentType.hasPrefix("image/")) { body = try HLSData.validatedFragment(data); contentType = "video/mp2t" }
                var headers = ["Content-Type": contentType, "Accept-Ranges": "bytes"]
                if !segment, let value = response.value(forHTTPHeaderField: "Content-Range") { headers["Content-Range"] = value }
                var status = segment ? 200 : response.statusCode
                if stream.manifestKey != nil || segment, let range, range.hasPrefix("bytes="), !contentType.contains("mpegurl") {
                    let bounds = range.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false)
                    let size = body.count
                    guard bounds.count == 2, size > 0 else { send(connection, status: 416, body: Data()); return }
                    var start = Int(bounds[0]) ?? 0
                    var end = Int(bounds[1]) ?? (size - 1)
                    if bounds[0].isEmpty { start = max(0, size - (Int(bounds[1]) ?? size)); end = size - 1 }
                    end = min(end, size - 1)
                    guard start >= 0, start <= end, start < size else { send(connection, status: 416, body: Data()); return }
                    body = body.subdata(in: start..<(end + 1))
                    headers["Content-Range"] = "bytes \(start)-\(end)/\(size)"; status = 206
                }
                send(connection, status: status, body: body, headers: headers, headOnly: parts[0] == "HEAD")
            } catch { send(connection, status: 502, body: Data(error.localizedDescription.utf8)) }
        }
    }
    private func send(_ connection: NWConnection, status: Int, body: Data, headers: [String: String] = [:], headOnly: Bool = false) {
        var header = "HTTP/1.1 \(status) \(status < 400 ? "OK" : "Error")\r\nConnection: close\r\nContent-Length: \(body.count)\r\n"
        for (key, value) in headers { header += key + ": " + value + "\r\n" }
        var packet = Data((header + "\r\n").utf8); if !headOnly { packet.append(body) }
        connection.send(content: packet, completion: .contentProcessed { _ in connection.cancel() })
    }
    func stop() { listener?.cancel(); listener = nil; Task { await media.stop() } }
}

/// Desktop flix-proxy keeps each URL's place in its own playlist. Audio and
/// video manifests must not overwrite one another's prefetch sequence.
struct HLSMediaOrder {
    private var positions: [URL: (list: [URL], index: Int)] = [:]
    mutating func insert(_ urls: [URL]) {
        for (index, url) in urls.enumerated() { positions[url] = (urls, index) }
    }
    func following(_ url: URL) -> [URL] {
        guard let place = positions[url] else { return [] }
        return Array(place.list.dropFirst(place.index + 1).prefix(5))
    }
}

private actor HLSMediaCache {
    private var tasks: [URL: Task<(Data, HTTPURLResponse), Error>] = [:]
    private var used: [URL] = []
    private var sequence = HLSMediaOrder()
    func order(_ urls: [URL]) { sequence.insert(urls) }
    private func task(_ url: URL, stream: ResolvedStream) -> Task<(Data, HTTPURLResponse), Error> {
        used.removeAll { $0 == url }; used.append(url)
        if let task = tasks[url] { return task }
        let task = Task { try await HLSData.mediaSegment(url, stream: stream) }; tasks[url] = task
        while used.count > 16 { let old = used.removeFirst(); tasks.removeValue(forKey: old)?.cancel() }
        return task
    }
    /// The segments after this one are fetched once it is here: started with it, they took most of the bandwidth from
    /// the one the player was waiting for (after a seek, six segments of each playlist arrived together, late).
    func load(_ url: URL, stream: ResolvedStream) async throws -> (Data, HTTPURLResponse) {
        let current = task(url, stream: stream)
        do {
            let value = try await current.value
            for next in sequence.following(url) { _ = task(next, stream: stream) }
            return value
        } catch { tasks[url] = nil; used.removeAll { $0 == url }; throw error }
    }
    func stop() { tasks.values.forEach { $0.cancel() }; tasks = [:]; used = []; sequence = HLSMediaOrder() }
}
