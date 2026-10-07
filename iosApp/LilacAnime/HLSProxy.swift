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
        return Array(Set(urls)).sorted { $0.absoluteString < $1.absoluteString }
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
        var proxied = stream; proxied.url = url; proxied.headers = [:]; proxied.manifestKey = nil
        return proxied
    }
    private func register(_ url: URL) -> URL {
        let key = SubtitleFiles.key(url.absoluteString)
        routes[key] = url
        return URL(string: "http://127.0.0.1:\(port)/\(token)/\(key).\(url.pathExtension.isEmpty ? "bin" : url.pathExtension)")!
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
                let (data, response) = try await HLSData.fetch(url, stream: stream, range: stream.manifestKey == nil ? range : nil)
                var body = data
                var contentType = response.mimeType ?? "application/octet-stream"
                if url.pathExtension.lowercased() == "m3u8" || contentType.contains("mpegurl") {
                    let text = HLSData.selectVariant(try HLSData.manifest(data, key: stream.manifestKey), quality: self.quality)
                    body = Data(self.queue.sync { HLSData.rewrite(text, base: url) { self.register($0).absoluteString } }.utf8)
                    contentType = "application/vnd.apple.mpegurl"
                } else if stream.manifestKey != nil && (url.pathExtension == "ts" || contentType.hasPrefix("image/")) { body = HLSData.fragment(data); contentType = "video/mp2t" }
                var headers = ["Content-Type": contentType, "Accept-Ranges": "bytes"]
                if let value = response.value(forHTTPHeaderField: "Content-Range") { headers["Content-Range"] = value }
                var status = response.statusCode
                if stream.manifestKey != nil, let range, range.hasPrefix("bytes="), !contentType.contains("mpegurl") {
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
    func stop() { listener?.cancel(); listener = nil }
}
