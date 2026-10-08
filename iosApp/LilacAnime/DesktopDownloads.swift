import Foundation
import LilacShared

/// Reads the desktop application's downloads.json alongside copied series folders.
enum DesktopDownloads {
    static func load(_ root: URL, skipping existing: Set<String>) throws -> [DownloadEntry] {
        let manifest = root.appendingPathComponent("downloads.json")
        guard (try manifest.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 16_000_000,
              let jobs = try JSONSerialization.jsonObject(with: Data(contentsOf: manifest)) as? [[String: Any]], jobs.count <= 10000 else { throw SubtitleFiles.failure("PC 다운로드 목록을 읽을 수 없습니다.") }
        var imported: [DownloadEntry] = []
        for job in jobs where job["status"] as? String == "completed" {
            try Task.checkCancellation()
            guard let originalVideo = job["filePath"] as? String else { continue }
            let video = try locate(originalVideo, root: root)
            let raw = job["anime"] as? [String: Any] ?? [:]
            let episode = job["episode"] as? [String: Any] ?? [:]
            let source = (raw["provider"] as? String ?? job["resolveKind"] as? String ?? "local")
            let rawID = String(describing: raw["id"] ?? raw["mal_id"] ?? job["key"] ?? originalVideo)
            let animeID = source == "reanime" && !rawID.hasPrefix("reanime:") ? "reanime:" + rawID : rawID
            let title = raw["title"] as? String ?? job["title"] as? String ?? "애니메이션"
            let number = (job["episodeNumber"] as? NSNumber)?.intValue ?? (episode["number"] as? NSNumber)?.intValue ?? 1
            let episodeID = String(describing: episode["id"] ?? episode["token"] ?? episode["url"] ?? number)
            let images = raw["images"] as? [String: Any] ?? [:]
            let webp = images["webp"] as? [String: Any] ?? [:]
            let snapshot: [String: Any] = ["id": animeID, "title": title, "source": source,
                "poster": webp["large_image_url"] as? String ?? job["image"] as? String ?? "",
                "year": String(describing: raw["year"] ?? ""), "format": raw["type"] as? String ?? "",
                "english": raw["title_english"] as? String ?? "", "description": raw["synopsis"] as? String ?? "",
                "detailUrl": raw["url"] as? String ?? ""]
            let anime = SavedAnime(AnimeSnapshot.shared.decode(content: String(decoding: try JSONSerialization.data(withJSONObject: snapshot), as: UTF8.self)), source: source)
            let id = SubtitleFiles.key(anime.id + "#" + episodeID)
            if existing.contains(id) { continue }
            var assets: [String] = [originalVideo]
            for name in ["subtitlePath", "subtitleAssPath"] { if let value = job[name] as? String, !value.isEmpty { assets.append(value) } }
            assets += job["subtitleOriginals"] as? [String] ?? []
            let fonts = job["subtitleFonts"] as? [String] ?? []
            assets += fonts
            for track in job["subtitleTracks"] as? [[String: Any]] ?? [] {
                for name in ["translatedPath", "assPath", "path"] { if let value = track[name] as? String, !value.isEmpty { assets.append(value) } }
            }
            let staging = DownloadStore.directory.appendingPathComponent("import-" + UUID().uuidString)
            let target = DownloadStore.directory.appendingPathComponent(id)
            try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
            do {
                var names: [String: String] = [:]
                for path in Set(assets) {
                    let file = path == originalVideo ? video : try locate(path, root: root)
                    let name = SubtitleFiles.key(path) + "." + file.pathExtension.lowercased()
                    guard DownloadTransfer.safeName(name) else { throw SubtitleFiles.failure("잘못된 PC 다운로드 파일 이름입니다.") }
                    try FileManager.default.copyItem(at: file, to: staging.appendingPathComponent(name)); names[path] = name
                }
                guard let localFile = names[originalVideo] else { throw SubtitleFiles.failure("PC 영상이 없습니다.") }
                // Desktop downloads are merged media files, not remote or portable HLS manifests.
                guard video.pathExtension.lowercased() != "m3u8" else { throw SubtitleFiles.failure("PC HLS는 하나의 MP4/MKV/TS 파일로 저장한 뒤 가져오세요.") }
                let chapters = (job["skipSegments"] as? [[String: Any]] ?? []).compactMap { raw -> OfflineChapter? in
                    guard let start = raw["start"] as? Double, let end = raw["end"] as? Double, start.isFinite, end.isFinite, end > start else { return nil }
                    return OfflineChapter(type: raw["type"] as? String ?? "OP", start: start, end: end, score: raw["score"] as? Double ?? 1)
                }
                let stream = ResolvedStream(label: "PC 다운로드", url: target.appendingPathComponent(localFile), referer: "", headers: [:])
                let entry = DownloadEntry(id: id, anime: anime, episodeID: episodeID, title: episode["name"] as? String ?? "\(number)화", number: number,
                    watchURL: episode["url"] as? String ?? "", stream: stream, localFile: localFile,
                    subtitleFiles: assets.filter { $0 != originalVideo && !fonts.contains($0) }.compactMap { names[$0] }.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } },
                    fontFiles: fonts.compactMap { names[$0] }, chapters: chapters, bytes: (try video.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init),
                    status: "완료", date: Date(timeIntervalSince1970: (job["updated"] as? Double ?? Date().timeIntervalSince1970 * 1000) / 1000))
                try JSONEncoder().encode(entry).write(to: staging.appendingPathComponent("download-entry.json"))
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: staging) }
                else { try FileManager.default.moveItem(at: staging, to: target) }
                imported.append(entry)
            } catch { try? FileManager.default.removeItem(at: staging); throw error }
        }
        return imported
    }
    static func locate(_ original: String, root: URL) throws -> URL {
        let parts = original.replacingOccurrences(of: "\\", with: "/").split(separator: "/").map(String.init)
        guard !parts.contains(".."), !parts.contains("."), !parts.isEmpty else { throw SubtitleFiles.failure("잘못된 PC 파일 경로입니다.") }
        let base = root.standardizedFileURL
        for count in stride(from: min(parts.count, 8), through: 1, by: -1) {
            let suffix = Array(parts.suffix(count))
            guard suffix.allSatisfy(DownloadTransfer.safeName) else { continue }
            var candidate = base; var safe = true
            for name in suffix {
                candidate.appendPathComponent(name)
                if (try? candidate.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true { safe = false; break }
            }
            if safe, (try? candidate.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true { return candidate }
        }
        throw SubtitleFiles.failure("PC 다운로드 파일을 찾을 수 없습니다: " + (parts.last ?? ""))
    }
}
