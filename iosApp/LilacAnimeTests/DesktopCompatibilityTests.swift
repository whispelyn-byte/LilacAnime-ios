import XCTest
import LilacLocalAI
import LilacShared
@testable import LilacAnime

final class DesktopCompatibilityTests: XCTestCase {
    func testOldPreferencesDecodeWithoutNewFields() throws {
        let original = AppPreferences()
        let encoded = try JSONEncoder().encode(original)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        for key in ["titleLanguage", "desktopWorkspace", "preferredServer", "modelSampling", "pretranslateNext", "downloadSubtitles", "translationModels", "cloudFallback", "localGPU", "translateDownloads"] { object.removeValue(forKey: key) }
        object["selectedGGUF"] = "existing.gguf"; object["source"] = "miruro"; object["subtitleOffset"] = 1.7
        let restored = try JSONDecoder().decode(AppPreferences.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(restored.selectedGGUF, "existing.gguf")
        XCTAssertEqual(restored.source, "miruro"); XCTAssertEqual(restored.subtitleOffset, 1.7)
        XCTAssertNil(restored.desktopWorkspace); XCTAssertNil(restored.titleLanguage)
    }
    func testActualGemma4TemplateKeepsSystemAndUserAndDisablesThinking() throws {
        let file = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "gemma4-template", withExtension: "txt"))
        let template = try String(contentsOf: file, encoding: .utf8)
        let output = try format(template, "표기 기준\u{1e}こんにちは")
        XCTAssertTrue(output.contains("표기 기준")); XCTAssertTrue(output.contains("こんにちは"))
        XCTAssertTrue(output.contains("<|turn>model"))
        XCTAssertTrue(output.hasSuffix("<|turn>model\n")); XCTAssertFalse(output.contains("<|think>"))
    }
    func testChatMLTemplateAndUnicodeToJSON() throws {
        let template = "{% for message in messages %}<|im_start|>{{ message.role }}\n{{ message.content }}<|im_end|>\n{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}"
        XCTAssertEqual(try format(template, "규칙\u{1e}こんにちは"), "<|im_start|>system\n규칙<|im_end|>\n<|im_start|>user\nこんにちは<|im_end|>\n<|im_start|>assistant\n")
        let json = try format("{{messages|tojson}}", "한글")
        let messages = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: String]])
        XCTAssertEqual(messages.last?["content"], "한글")
    }
    func testHistoryKeepsNextEpisodeAndSuffixIdentity() throws {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: """
        {"id":"a","title":"보존","episodes":[{"id":"4","number":4,"title":"4화"},{"id":"4a","number":4,"title":"4a화","displayNumber":"4a"},{"id":"5","number":5,"title":"5화"}]}
        """), source: "reanime")
        let item = PlaybackItem(entry: WatchEntry(id: "reanime:a#4", anime: anime, episodeID: "4", episodeTitle: "4화", number: 4,
            watchURL: "https://example.test/watch", position: 100, duration: 1400, updatedAt: Date()))
        XCTAssertEqual(item.next.map(\.episodeID), ["4a", "5"])
    }
    func testPortableDownloadRejectsTraversingPaths() {
        XCTAssertFalse(DownloadTransfer.safeName("../video.mp4")); XCTAssertFalse(DownloadTransfer.safeName("C:\\secret"))
        XCTAssertFalse(DownloadTransfer.safeName("https://example.test")); XCTAssertTrue(DownloadTransfer.safeName("subtitle-한국어.ass"))
    }
    func testRealTinyGGUFCPUInferenceAndMetrics() throws {
        let file = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "stories260K", withExtension: "gguf"))
        let model = try XCTUnwrap(lilac_model_open_with_backend(file.path, 512, 2, 0))
        defer { lilac_model_close(model) }
        XCTAssertEqual(String(cString: lilac_backend(model)), "CPU")
        let output = try XCTUnwrap(lilac_generate(model, "Once upon a time", 16, 0, 1, 40, 1), String(cString: lilac_error(model)))
        defer { lilac_string_free(output) }
        XCTAssertFalse(String(cString: output).isEmpty)
        XCTAssertGreaterThan(lilac_output_tokens(model), 0)
        XCTAssertGreaterThan(lilac_generation_seconds(model), 0)
        let second = try XCTUnwrap(lilac_generate(model, "A little girl", 8, 0, 1, 40, 1))
        defer { lilac_string_free(second) }
        XCTAssertFalse(String(cString: second).isEmpty)
    }
    func testSubtitleArchivesRead7zRARAndLegacyKoreanZIPWithoutTraversal() throws {
        for (name, ext) in [("subtitle", "7z"), ("subtitle", "rar"), ("subtitle-cp949", "zip")] {
            let file = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: ext))
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: folder) }
            let extracted = try SubtitleArchive.extract(file, into: folder)
            XCTAssertTrue(extracted.contains { $0.lastPathComponent.hasSuffix("한국어.srt") }, "\(ext) filename")
            for item in extracted {
                XCTAssertEqual(item.deletingLastPathComponent().standardizedFileURL, folder.standardizedFileURL)
                XCTAssertTrue(try String(contentsOf: item, encoding: .utf8).contains("테스트 자막"))
            }
            XCTAssertEqual(extracted.count, ext == "zip" ? 2 : 1)
        }
    }
    func testDownloadedEpisodeSuffixIsNotSkipped() throws {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: """
        {"id":"a","title":"보존","episodes":[{"id":"4","number":4,"title":"4화"},{"id":"4a","number":4,"title":"4a화","displayNumber":"4a"},{"id":"5","number":5,"title":"5화"}]}
        """), source: "reanime")
        let stream = ResolvedStream(label: "Auto", url: URL(string: "https://example.test/video.mp4")!, referer: "", headers: [:])
        func entry(_ id: String, _ number: Int) -> DownloadEntry {
            DownloadEntry(id: SubtitleFiles.key(anime.id + "#" + id), anime: anime, episodeID: id, title: id, number: number, watchURL: stream.url.absoluteString,
                stream: stream, localFile: "video.mp4")
        }
        let current = entry("4", 4)
        XCTAssertEqual(current.following([entry("5", 5), current, entry("4a", 4)]).map(\.episodeID), ["4a", "5"])
    }
    func testPortableExportIncludesNestedHLSAndExcludesResumeSecrets() throws {
        let anime = SavedAnime(AnimeSnapshot.shared.decode(content: "{\"id\":\"a\",\"title\":\"보존\"}"), source: "reanime")
        let stream = ResolvedStream(label: "Auto", url: URL(string: "https://example.test/master.m3u8")!, referer: "", headers: [:], subtitles: [])
        let entry = DownloadEntry(id: SubtitleFiles.key(anime.id + "#1"), anime: anime, episodeID: "1", title: "1화", number: 1,
            watchURL: "https://example.test/watch", stream: stream, localFile: "root.m3u8", subtitleFiles: ["ko.srt"],
            rootFile: "root.m3u8", parts: [DownloadPart(url: stream.url, name: "part.ts", done: true)], fontFiles: ["font.ttf"])
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        try "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nchild.m3u8\n".write(to: folder.appendingPathComponent("root.m3u8"), atomically: true, encoding: .utf8)
        try "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST\n".write(to: folder.appendingPathComponent("child.m3u8"), atomically: true, encoding: .utf8)
        try Data("private cookies".utf8).write(to: folder.appendingPathComponent("part.ts.resume"))
        XCTAssertEqual(try DownloadTransfer.files(entry, in: folder), Set(["root.m3u8", "child.m3u8", "part.ts", "key.bin", "ko.srt", "font.ttf"]))
    }
    private func format(_ template: String, _ prompt: String) throws -> String {
        var error: UnsafeMutablePointer<CChar>?
        let result = lilac_format_prompt(template, prompt, "<bos>", "<eos>", &error)
        defer { if let error { lilac_string_free(error) }; if let result { lilac_string_free(result) } }
        guard let result else { throw SubtitleFiles.failure(error.map { String(cString: $0) } ?? "템플릿 실패") }
        return String(cString: result)
    }
}
