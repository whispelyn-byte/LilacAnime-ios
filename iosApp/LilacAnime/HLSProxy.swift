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

    static func fetch(_ url: URL, stream: ResolvedStream, range: String? = nil) async throws -> (Data, HTTPURLResponse) {
        try Task.checkCancellation()
        if url == stream.url, let manifest = stream.hlsManifest {
            return (Data(manifest.utf8), HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/vnd.apple.mpegurl"])!)
        }
        var request = URLRequest(url: url)
        for (key, value) in stream.headers where key.lowercased() != "cookie" || url.host == stream.url.host { request.setValue(value, forHTTPHeaderField: key) }
        request.setValue(stream.referer, forHTTPHeaderField: "Referer")
        if let range { request.setValue(range, forHTTPHeaderField: "Range") }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw SubtitleFiles.failure("영상 요청 실패") }
        return (data, http)
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
    static func validatedFragment(_ raw: Data) throws -> Data {
        let decoded = fragment(raw)
        if isMedia(decoded) { return decoded }
        let bytes = Array(decoded), limit = min(bytes.count - 376, 4096)
        if limit > 1 {
            for index in 1..<limit where bytes[index] == 71 && bytes[index + 188] == 71 && bytes[index + 376] == 71 { return Data(bytes[index...]) }
        }
        throw SubtitleFiles.failure("영상 조각 복호화에 실패했습니다.")
    }

    private enum FetchEvent {
        case timer
        case answer(Result<(Data, HTTPURLResponse), Error>)
    }
    private static func hedged(_ url: URL, stream: ResolvedStream, start: Int, end: Int) async throws -> (Data, HTTPURLResponse) {
        try await withThrowingTaskGroup(of: FetchEvent.self) { group in
            var attempts = 0, active = 0
            var failure: Error = SubtitleFiles.failure("영상 조각을 받지 못했습니다.")
            func launch() {
                guard attempts < 3 else { return }
                attempts += 1; active += 1
                group.addTask {
                    do {
                        let result = try await fetch(url, stream: stream, range: "bytes=\(start)-\(end)")
                        if start > 0 && (result.1.statusCode != 206 || result.0.count != end - start + 1) { throw SubtitleFiles.failure("영상 조각 범위 응답 오류") }
                        return .answer(.success(result))
                    } catch { return .answer(.failure(error)) }
                }
                if attempts < 3 { group.addTask { try await Task.sleep(nanoseconds: 8_000_000_000); return .timer } }
            }
            launch()
            while let event = try await group.next() {
                try Task.checkCancellation()
                switch event {
                case .timer: launch()
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
                let (head, response) = try await hedged(url, stream: stream, start: 0, end: 1048575)
                let total = Int(response.value(forHTTPHeaderField: "Content-Range")?.components(separatedBy: "/").last ?? "") ?? 0
                var raw = head
                if response.statusCode == 206 && total > head.count {
                    let ranges = stride(from: head.count, to: total, by: 1048576).map { ($0, min($0 + 1048576, total) - 1) }
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
        self.quality = quality
        self.stream = stream
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in self?.receive(connection) }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            var resumed = false
            listener.stateUpdateHandler = { [weak self] state in
                if case .ready = state, !resumed { resumed = true; self?.port = listener.port?.rawValue ?? 0; continuation.resume() }
                if case .failed(let error) = state, !resumed { resumed = true; continuation.resume(throwing: error) }
            }
            listener.start(queue: queue)
        }
        let url = queue.sync { register(stream.url) }
        var proxied = stream; proxied.url = url; proxied.headers = [:]; proxied.manifestKey = nil; proxied.hlsManifest = nil
        return proxied
    }
    private func register(_ url: URL) -> URL {
        let key = SubtitleFiles.key(url.absoluteString)
        routes[key] = url
        let original = url.pathExtension.lowercased()
        let ext = url == stream?.url && stream?.hlsManifest != nil ? "m3u8" : original == "html" ? "ts" : original.isEmpty ? "bin" : original
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
                let segment = ["png", "webp", "html"].contains(url.pathExtension.lowercased())
                let (data, response) = segment ? try await media.load(url, stream: stream) : try await HLSData.fetch(url, stream: stream, range: stream.manifestKey == nil ? range : nil)
                var body = data
                var contentType = response.mimeType ?? "application/octet-stream"
                if url.pathExtension.lowercased() == "m3u8" || contentType.contains("mpegurl") || (url == stream.url && stream.hlsManifest != nil) {
                    let text = HLSData.selectVariant(try HLSData.manifest(data, key: stream.manifestKey), quality: self.quality)
                    await media.order(HLSData.references(text, base: url).filter { ["png", "webp", "html"].contains($0.pathExtension.lowercased()) })
                    body = Data(self.queue.sync { HLSData.rewrite(text, base: url) { self.register($0).absoluteString } }.utf8)
                    contentType = "application/vnd.apple.mpegurl"
                } else if segment { contentType = body.first == 71 ? "video/mp2t" : "video/mp4" }
                else if stream.manifestKey != nil && (url.pathExtension == "ts" || contentType.hasPrefix("image/")) { body = HLSData.fragment(data); contentType = "video/mp2t" }
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

private actor HLSMediaCache {
    private var tasks: [URL: Task<(Data, HTTPURLResponse), Error>] = [:]
    private var used: [URL] = []
    private var sequence: [URL] = []
    func order(_ urls: [URL]) { sequence = urls }
    private func task(_ url: URL, stream: ResolvedStream) -> Task<(Data, HTTPURLResponse), Error> {
        used.removeAll { $0 == url }; used.append(url)
        if let task = tasks[url] { return task }
        let task = Task { try await HLSData.mediaSegment(url, stream: stream) }; tasks[url] = task
        while used.count > 16 { let old = used.removeFirst(); tasks.removeValue(forKey: old)?.cancel() }
        return task
    }
    func load(_ url: URL, stream: ResolvedStream) async throws -> (Data, HTTPURLResponse) {
        let current = task(url, stream: stream)
        if let index = sequence.firstIndex(of: url) { for next in sequence.dropFirst(index + 1).prefix(5) { _ = task(next, stream: stream) } }
        do { return try await current.value }
        catch { tasks[url] = nil; used.removeAll { $0 == url }; throw error }
    }
    func stop() { tasks.values.forEach { $0.cancel() }; tasks = [:]; used = []; sequence = [] }
}
