import Foundation
import LilacShared
import CoreText
import CryptoKit
import ZIPFoundation

enum SubtitleFiles {
    static let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Subtitles")
    static let fontDirectory = root.appendingPathComponent("Fonts")
    static let translations = root.appendingPathComponent("Translations")
    static func key(_ text: String) -> String { SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined() }
    static func prepare(_ url: URL, headers: [String: String] = [:]) async throws -> [URL] {
        let data: Data
        var name = url.lastPathComponent
        if url.isFileURL {
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            data = try Data(contentsOf: url)
        } else {
            var request = URLRequest(url: url)
            headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
            var (body, response) = try await URLSession.shared.data(for: request)
            for _ in 0..<2 {
                guard let http = response as? HTTPURLResponse, http.mimeType == "text/html",
                      let html = String(data: body, encoding: .utf8) else { break }
                let confirmed = SubtitleTools.shared.driveConfirmation(html: html, baseUrl: response.url?.absoluteString ?? url.absoluteString)
                guard !confirmed.isEmpty, let target = URL(string: confirmed) else { break }
                (body, response) = try await URLSession.shared.data(from: target)
            }
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw failure("자막 다운로드가 실패했습니다.")
            }
            if http.mimeType == "text/html" { throw failure("자막 파일 대신 웹 페이지가 반환되었습니다. 원본 게시물에서 파일을 내려받아 가져오세요.") }
            data = body
            if let disposition = http.value(forHTTPHeaderField: "Content-Disposition"),
               let range = disposition.range(of: "filename=") {
                name = String(disposition[range.upperBound...]).trimmingCharacters(in: CharacterSet(charactersIn: "\" "))
            }
        }
        let folder = root.appendingPathComponent(key(url.absoluteString))
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let ext = URL(fileURLWithPath: name).pathExtension.lowercased()
        let safe = URL(fileURLWithPath: name.replacingOccurrences(of: "\\", with: "/")).lastPathComponent
        let file = folder.appendingPathComponent((safe.isEmpty || safe == "." || safe == ".." ? "source" : safe) + (ext.isEmpty ? ".ass" : ""))
        try data.write(to: file, options: .atomic)
        if ["ttf", "otf", "ttc"].contains(ext) { _ = try importFont(file); try associateFonts([file], with: folder.appendingPathComponent("asset")); return [] }
        if ["zip", "7z", "rar"].contains(ext) || data.starts(with: [0x50, 0x4b, 0x03, 0x04]) || data.starts(with: [0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c]) || data.starts(with: [0x52, 0x61, 0x72, 0x21]) {
            var results: [URL] = []
            var fonts: [URL] = []
            for extracted in try SubtitleArchive.extract(file, into: folder) {
                if ["ttf","otf","ttc"].contains(extracted.pathExtension.lowercased()) { _ = try importFont(extracted); fonts.append(extracted) }
                else { results.append(try normalize(extracted)) }
            }
            guard !results.isEmpty || !fonts.isEmpty else { throw failure("압축 파일에 지원하는 자막이 없습니다.") }
            try associateFonts(fonts, with: folder.appendingPathComponent("asset"))
            for subtitle in results { try associateFonts(fonts, with: subtitle) }
            return results
        }
        return [try normalize(file)]
    }
    static func isKorean(_ file: URL) -> Bool {
        guard let content = try? text(file) else { return false }
        let lines = SubtitleTools.shared.lines(content: content, extension: file.pathExtension).prefix(40)
        let korean = lines.filter { $0.range(of: "[가-힣]", options: .regularExpression) != nil }.count
        return !lines.isEmpty && Double(korean) / Double(lines.count) > 0.4
    }
    static func preparedFonts(_ source: URL) -> [URL] {
        fonts(for: root.appendingPathComponent(key(source.absoluteString)).appendingPathComponent("asset"))
    }
    static func fonts(for subtitle: URL) -> [URL] {
        let index = subtitle.appendingPathExtension("fonts.json")
        guard let data = try? Data(contentsOf: index), let values = try? JSONDecoder().decode([String].self, from: data) else { return [] }
        return values.compactMap { value in
            let file = root.appendingPathComponent(value).standardizedFileURL
            return file.path.hasPrefix(root.path + "/") && FileManager.default.fileExists(atPath: file.path) ? file : nil
        }
    }
    static func associateFonts(_ fonts: [URL], with subtitle: URL) throws {
        let prefix = root.path + "/"
        let files = Set(fonts.filter { $0.standardizedFileURL.path.hasPrefix(prefix) }.map { String($0.standardizedFileURL.path.dropFirst(prefix.count)) })
        try JSONEncoder().encode(files.sorted()).write(to: subtitle.appendingPathExtension("fonts.json"), options: .atomic)
    }
    static func text(_ file: URL) throws -> String {
        let data = try Data(contentsOf: file)
        if data.starts(with: [0xff, 0xfe]), let value = String(data: data.dropFirst(2), encoding: .utf16LittleEndian) { return value }
        if data.starts(with: [0xfe, 0xff]), let value = String(data: data.dropFirst(2), encoding: .utf16BigEndian) { return value }
        if let value = String(data: data, encoding: .utf8) { return value.hasPrefix("\u{FEFF}") ? String(value.dropFirst()) : value }
        let korean = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.EUC_KR.rawValue)))
        for encoding in [korean, .shiftJIS, .japaneseEUC] { if let value = String(data: data, encoding: encoding), !value.isEmpty { return value } }
        throw failure("자막 문자 인코딩을 읽을 수 없습니다.")
    }
    private static func normalize(_ file: URL) throws -> URL {
        let content = try text(file)
        let ext = SubtitleTools.shared.format(content: content, suggested: file.pathExtension)
        guard !ext.isEmpty else { throw failure("지원하는 자막 형식이 아닙니다.") }
        let output = file.deletingPathExtension().appendingPathExtension(ext)
        try content.write(to: output, atomically: true, encoding: .utf8)
        return output
    }
    static func importFont(_ url: URL) throws -> String {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        try FileManager.default.createDirectory(at: fontDirectory, withIntermediateDirectories: true)
        let target = fontDirectory.appendingPathComponent(url.lastPathComponent)
        if target != url { try Data(contentsOf: url).write(to: target, options: .atomic) }
        CTFontManagerRegisterFontsForURL(target as CFURL, .process, nil)
        let descriptors = CTFontManagerCreateFontDescriptorsFromURL(target as CFURL) as? [CTFontDescriptor]
        return descriptors?.first.flatMap { CTFontDescriptorCopyAttribute($0, kCTFontFamilyNameAttribute) as? String } ?? target.deletingPathExtension().lastPathComponent
    }
    static func restoreFonts() {
        for file in (try? FileManager.default.contentsOfDirectory(at: fontDirectory, includingPropertiesForKeys: nil)) ?? [] {
            CTFontManagerRegisterFontsForURL(file as CFURL, .process, nil)
        }
    }
    static func clearTranslationCache() { try? FileManager.default.removeItem(at: translations) }
    static func failure(_ message: String) -> NSError { NSError(domain: "Lilac", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
}
