import Foundation
import Network
import Darwin

/// A session-scoped relay. Only registered media, manifest references and subtitle bytes are reachable.
final class LANMediaRelay {
    enum Resource {
        case media(URL)
        case bytes(Data, String)
    }
    struct ByteRange: Equatable {
        let start: Int64
        let end: Int64
        var length: Int64 { end - start + 1 }
    }
    static func byteRange(_ header: String?, size: Int64) throws -> ByteRange? {
        guard let header else { return nil }
        guard size > 0, header.hasPrefix("bytes="), !header.contains(",") else { throw SubtitleFiles.failure("Invalid byte range") }
        let values = header.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false)
        guard values.count == 2 else { throw SubtitleFiles.failure("Invalid byte range") }
        if values[0].isEmpty {
            guard let count = Int64(values[1]), count > 0 else { throw SubtitleFiles.failure("Invalid byte range") }
            return ByteRange(start: max(0, size - count), end: size - 1)
        }
        guard let start = Int64(values[0]), start >= 0, start < size,
              values[1].isEmpty || Int64(values[1]) != nil else { throw SubtitleFiles.failure("Invalid byte range") }
        let end = min(Int64(values[1]) ?? (size - 1), size - 1)
        guard end >= start else { throw SubtitleFiles.failure("Invalid byte range") }
        return ByteRange(start: start, end: end)
    }
    static func allowedLocal(_ url: URL, root: URL) -> Bool {
        url.isFileURL && url.resolvingSymlinksInPath().standardizedFileURL.path
            .hasPrefix(root.resolvingSymlinksInPath().standardizedFileURL.path + "/")
    }
    private let queue = DispatchQueue(label: "Lilac.CastRelay")
    private let token = UUID().uuidString + UUID().uuidString
    private var listener: NWListener?
    private var resources: [String: Resource] = [:]
    private var connections: [UUID: NWConnection] = [:]
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private var idleTimer: DispatchSourceTimer?
    private var lastRequest = Date()
    private var stream: ResolvedStream?
    private var localRoot: URL?
    private var address = ""
    private var port: UInt16 = 0
    private let addressProvider: () -> String?
    init(addressProvider: @escaping () -> String? = { LANMediaRelay.wifiAddress() }) {
        self.addressProvider = addressProvider
    }

    func start(_ stream: ResolvedStream) async throws -> URL {
        guard listener == nil else { throw SubtitleFiles.failure("Cast relay already started") }
        guard stream.url.isFileURL || ["https", "http"].contains(stream.url.scheme ?? "") else {
            throw SubtitleFiles.failure("지원하지 않는 Cast 주소입니다.")
        }
        guard let address = addressProvider() else { throw SubtitleFiles.failure("Cast 중계에는 Wi-Fi 연결이 필요합니다.") }
        self.address = address; self.stream = stream
        if stream.url.isFileURL { localRoot = stream.url.deletingLastPathComponent().resolvingSymlinksInPath() }
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(address), port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        listener.newConnectionHandler = { [weak self] in self?.accept($0) }
        do {
            try await withCheckedThrowingContinuation { (pending: CheckedContinuation<Void, Error>) in
                var resumed = false
                listener.stateUpdateHandler = { [weak self] state in
                    guard !resumed else { return }
                    switch state {
                    case .ready: resumed = true; self?.port = listener.port?.rawValue ?? 0; pending.resume()
                    case .failed(let error): resumed = true; pending.resume(throwing: error)
                    case .cancelled: resumed = true; pending.resume(throwing: CancellationError())
                    default: break
                    }
                }
                listener.start(queue: queue)
            }
            return try queue.sync {
                let timer = DispatchSource.makeTimerSource(queue: queue)
                timer.schedule(deadline: .now() + 60, repeating: 60)
                timer.setEventHandler { [weak self] in
                    guard let self, self.connections.isEmpty, Date().timeIntervalSince(self.lastRequest) > 600 else { return }
                    self.stopOnQueue()
                }
                idleTimer = timer; timer.resume()
                return try register(stream.url)
            }
        } catch { stop(); throw error }
    }
    func subtitle(_ data: Data) -> URL {
        queue.sync {
            let path = SubtitleFiles.key(UUID().uuidString) + ".vtt"
            resources[path] = .bytes(data, "text/vtt; charset=utf-8")
            return route(path)
        }
    }
    private func route(_ path: String) -> URL { URL(string: "http://" + address + ":" + String(port) + "/" + token + "/" + path)! }
    private func register(_ url: URL) throws -> URL {
        if url.isFileURL {
            guard let localRoot, Self.allowedLocal(url, root: localRoot) else { throw SubtitleFiles.failure("Manifest references a file outside the selected download") }
        } else {
            guard ["https", "http"].contains(url.scheme ?? ""), url.user == nil, url.password == nil else { throw SubtitleFiles.failure("Invalid media reference") }
        }
        let ext = url.pathExtension.lowercased()
        let suffix = ext.allSatisfy { $0.isLetter || $0.isNumber } && ext.count <= 10 && !ext.isEmpty ? ext : "bin"
        let path = SubtitleFiles.key(url.absoluteString) + "." + suffix
        guard resources[path] != nil || resources.count < 10000 else { throw SubtitleFiles.failure("Too many HLS resources") }
        resources[path] = .media(url)
        return route(path)
    }
    private func accept(_ connection: NWConnection) {
        guard connections.count < 8 else { connection.cancel(); return }
        let id = UUID()
        connections[id] = connection
        connection.stateUpdateHandler = { [weak self] state in
            if case .cancelled = state { self?.connections.removeValue(forKey: id); self?.tasks.removeValue(forKey: id)?.cancel() }
            if case .failed = state { connection.cancel() }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 15) { [weak self] in
            if self?.tasks[id] == nil { connection.cancel() }
        }
        read(connection, id: id, buffer: Data())
    }
    private func read(_ connection: NWConnection, id: UUID, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, done, error in
            guard let self else { connection.cancel(); return }
            var request = buffer; if let data { request.append(data) }
            guard request.count <= 16384 else { connection.cancel(); return }
            if let header = String(data: request, encoding: .utf8), header.contains("\r\n\r\n") { self.respond(connection, id: id, header: header) }
            else if done || error != nil { connection.cancel() }
            else { self.read(connection, id: id, buffer: request) }
        }
    }
    private func respond(_ connection: NWConnection, id: UUID, header: String) {
        let lines = header.components(separatedBy: "\r\n")
        let parts = lines[0].split(separator: " ")
        guard parts.count == 3 else { connection.cancel(); return }
        let method = String(parts[0])
        let path = String(parts[1]).components(separatedBy: "?")[0].split(separator: "/")
        guard path.count == 2, path[0] == Substring(token), let resource = resources[String(path[1])], let stream else {
            reply(connection, status: 404); return
        }
        guard ["GET", "HEAD", "OPTIONS"].contains(method) else { reply(connection, status: 405); return }
        lastRequest = Date()
        if method == "OPTIONS" { reply(connection, status: 204); return }
        let range = lines.first { $0.lowercased().hasPrefix("range:") }.map { String($0.dropFirst(6)).trimmingCharacters(in: .whitespaces) }
        let root = localRoot
        tasks[id] = Task { [weak self] in
            guard let self else { connection.cancel(); return }
            do {
                switch resource {
                case .bytes(let data, let type):
                    try await self.sendBytes(connection, data: data, type: type, range: range, head: method == "HEAD")
                case .media(let url):
                    if url.isFileURL {
                        guard let root, Self.allowedLocal(url, root: root) else { throw SubtitleFiles.failure("Invalid local resource") }
                        if url.pathExtension.lowercased() == "m3u8" {
                            let data = try Data(contentsOf: url)
                            try await self.sendManifest(connection, data: data, base: url, stream: stream, head: method == "HEAD")
                        } else {
                            try await self.sendFile(connection, url: url, range: range, head: method == "HEAD")
                        }
                    } else if url.pathExtension.lowercased() == "m3u8" || (url == stream.url && stream.manifestKey != nil) {
                        let (data, _) = try await HLSData.fetch(url, stream: stream)
                        try await self.sendManifest(connection, data: data, base: url, stream: stream, head: method == "HEAD")
                    } else if stream.manifestKey != nil {
                        let (data, response) = try await HLSData.fetch(url, stream: stream)
                        if response.mimeType?.lowercased().contains("mpegurl") == true {
                            try await self.sendManifest(connection, data: data, base: url, stream: stream, head: method == "HEAD")
                        } else {
                            let type = response.mimeType?.hasPrefix("image/") == true || url.pathExtension == "ts" ? "video/mp2t" : response.mimeType ?? "application/octet-stream"
                            try await self.sendBytes(connection, data: HLSData.fragment(data), type: type, range: range, head: method == "HEAD")
                        }
                    } else {
                        try await self.sendRemote(connection, url: url, stream: stream, range: range, head: method == "HEAD")
                    }
                }
                connection.cancel()
            } catch {
                if !Task.isCancelled { self.reply(connection, status: 502) }
                else { connection.cancel() }
            }
        }
    }
    private func sendManifest(_ connection: NWConnection, data: Data, base: URL, stream: ResolvedStream, head: Bool) async throws {
        let text = try HLSData.manifest(data, key: stream.manifestKey)
        let rewritten = try queue.sync {
            var mapped: [URL: String] = [:]
            for url in HLSData.references(text, base: base) { mapped[url] = try register(url).absoluteString }
            return HLSData.rewrite(text, base: base) { mapped[$0] ?? "" }
        }
        try await sendBytes(connection, data: Data(rewritten.utf8), type: "application/vnd.apple.mpegurl", range: nil, head: head)
    }
    private func sendBytes(_ connection: NWConnection, data: Data, type: String, range: String?, head: Bool) async throws {
        let selected: ByteRange?
        do { selected = try Self.byteRange(range, size: Int64(data.count)) }
        catch { reply(connection, status: 416, headers: ["Content-Range": "bytes */" + String(data.count)]); return }
        var headers = ["Content-Type": type, "Accept-Ranges": "bytes"]
        if let selected { headers["Content-Range"] = "bytes \(selected.start)-\(selected.end)/\(data.count)" }
        try await send(connection, data: makeHeader(status: selected == nil ? 200 : 206, length: selected?.length ?? Int64(data.count), headers: headers))
        if !head, !data.isEmpty {
            let body = selected.map { data.subdata(in: Int($0.start)..<(Int($0.end) + 1)) } ?? data
            try await send(connection, data: body)
        }
    }
    private func sendFile(_ connection: NWConnection, url: URL, range: String?, head: Bool) async throws {
        let size = (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        let selected: ByteRange?
        do { selected = try Self.byteRange(range, size: size) }
        catch { reply(connection, status: 416, headers: ["Content-Range": "bytes */" + String(size)]); return }
        var headers = ["Content-Type": Self.mime(url), "Accept-Ranges": "bytes"]
        if let selected { headers["Content-Range"] = "bytes \(selected.start)-\(selected.end)/\(size)" }
        try await send(connection, data: makeHeader(status: selected == nil ? 200 : 206, length: selected?.length ?? size, headers: headers))
        guard !head else { return }
        let file = try FileHandle(forReadingFrom: url); defer { try? file.close() }
        try file.seek(toOffset: UInt64(selected?.start ?? 0))
        var remaining = selected?.length ?? size
        while remaining > 0 {
            try Task.checkCancellation()
            guard let bytes = try file.read(upToCount: Int(min(65536, remaining))), !bytes.isEmpty else { throw SubtitleFiles.failure("Incomplete local media") }
            try await send(connection, data: bytes); remaining -= Int64(bytes.count)
        }
    }
    private func sendRemote(_ connection: NWConnection, url: URL, stream: ResolvedStream, range: String?, head: Bool) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = head ? "HEAD" : "GET"
        for (key, value) in stream.headers where key.lowercased() != "cookie" || url.host == stream.url.host { request.setValue(value, forHTTPHeaderField: key) }
        request.setValue(stream.referer, forHTTPHeaderField: "Referer")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        if let range { request.setValue(range, forHTTPHeaderField: "Range") }
        let (bytes, response) = try await URLSession.shared.bytes(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw SubtitleFiles.failure("Media request failed") }
        var headers = ["Content-Type": http.mimeType ?? Self.mime(url), "Accept-Ranges": http.value(forHTTPHeaderField: "Accept-Ranges") ?? "bytes"]
        if let value = http.value(forHTTPHeaderField: "Content-Range") { headers["Content-Range"] = value }
        try await send(connection, data: makeHeader(status: http.statusCode, length: http.expectedContentLength >= 0 ? http.expectedContentLength : nil, headers: headers))
        guard !head else { return }
        var chunk = Data(); chunk.reserveCapacity(65536)
        for try await byte in bytes {
            try Task.checkCancellation()
            chunk.append(byte)
            if chunk.count == 65536 { try await send(connection, data: chunk); chunk.removeAll(keepingCapacity: true) }
        }
        if !chunk.isEmpty { try await send(connection, data: chunk) }
    }
    private func makeHeader(status: Int, length: Int64?, headers: [String: String] = [:]) -> Data {
        var text = "HTTP/1.1 \(status) \(status < 400 ? "OK" : "Error")\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range\r\nAccess-Control-Expose-Headers: Content-Range, Accept-Ranges, Content-Length\r\n"
        if let length { text += "Content-Length: \(length)\r\n" }
        for (key, value) in headers { text += key + ": " + value.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "") + "\r\n" }
        return Data((text + "\r\n").utf8)
    }
    private func send(_ connection: NWConnection, data: Data) async throws {
        try Task.checkCancellation()
        try await withCheckedThrowingContinuation { (pending: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { pending.resume(throwing: error) } else { pending.resume() }
            })
        }
    }
    private func reply(_ connection: NWConnection, status: Int, headers: [String: String] = [:]) {
        connection.send(content: makeHeader(status: status, length: 0, headers: headers), completion: .contentProcessed { _ in connection.cancel() })
    }
    private static func mime(_ url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "m3u8": return "application/vnd.apple.mpegurl"
        case "ts": return "video/mp2t"
        case "mp4", "m4s": return "video/mp4"
        case "mkv": return "video/x-matroska"
        case "vtt": return "text/vtt"
        case "aac": return "audio/aac"
        default: return "application/octet-stream"
        }
    }
    private static func wifiAddress() -> String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0 else { return nil }
        defer { freeifaddrs(interfaces) }
        var cursor = interfaces
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            let info = entry.pointee
            guard String(cString: info.ifa_name) == "en0", let addr = info.ifa_addr,
                  addr.pointee.sa_family == UInt8(AF_INET), (info.ifa_flags & UInt32(IFF_UP)) != 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let value = String(cString: host)
                if value != "0.0.0.0" && value != "127.0.0.1" { return value }
            }
        }
        return nil
    }
    private func stopOnQueue() {
        listener?.cancel(); listener = nil; idleTimer?.cancel(); idleTimer = nil
        tasks.values.forEach { $0.cancel() }; tasks.removeAll()
        connections.values.forEach { $0.cancel() }; connections.removeAll()
        resources.removeAll(); stream = nil
    }
    func stop() { queue.async { [self] in stopOnQueue() } }
}
