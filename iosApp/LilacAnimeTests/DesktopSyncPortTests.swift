import XCTest
import LilacShared
@testable import LilacAnime

/// Desktop 0.5.29–0.5.37: 자막 싱크 맞추기, 다운로드의 한국어 자막 우선, 이동 시간, 번역 프롬프트.
@MainActor
final class DesktopSyncPortTests: XCTestCase {
    func testSyncedCopiesAreNamedAndLabelledAndNotSyncedAgain() {
        XCTAssertTrue(SubtitleAutoSync.isSynced(URL(fileURLWithPath: "/x/kairan.sync-lq3abc.ass")))
        XCTAssertTrue(SubtitleAutoSync.isSynced(URL(fileURLWithPath: "/tmp/working-1.synced.vtt")))
        XCTAssertFalse(SubtitleAutoSync.isSynced(URL(fileURLWithPath: "/x/sync-notes.ass")))
        let record = SavedSubtitle(id: "a", episodeKey: "k", name: "episode.sync-abc.ass", relativeFile: "episode.sync-abc.ass", provider: "kairan", translated: false, date: Date())
        XCTAssertEqual(SubtitleLabels.label(record), "Kairan 자막 · 자동 싱크")
        let translated = SavedSubtitle(id: "b", episodeKey: "k", name: "abc.v2.vtt", relativeFile: "abc.v2.vtt", provider: "gemini", translated: true, date: Date())
        XCTAssertEqual(SubtitleLabels.label(translated), "Gemini 번역")
    }
    func testMovedTextMatchesDesktop() {
        XCTAssertEqual(SubtitleAutoSync.moved(.init(offset: -1.05, scale: 1, segments: [])), "1.05초 앞당김")
        let segments = [SyncSegment(from: 0, shift: -1.01), SyncSegment(from: 128, shift: 89.25)]
        XCTAssertEqual(SubtitleAutoSync.moved(.init(offset: 89.25, scale: 1, segments: segments)), "구간별 0:00~ 1.01초 앞당김 · 2:08~ 89.25초 늦춤")
    }
    func testReferenceTracksPutDialogueFirstAndLeaveOutSignsSongsAndDubs() {
        let tracks = ["English (S&S)", "English", "English (Dialog - ENG)", "English Dubtitle", "Signs & Songs", "English (AI)", "Japanese", "Full Subtitles", "Commentary"]
            .map { RemoteSubtitle(label: $0, url: URL(string: "https://example.test/" + $0.replacingOccurrences(of: " ", with: "_"))!, language: "en") }
        XCTAssertEqual(SubtitleAutoSync.referenceTracks(tracks).map(\.label), ["English (Dialog - ENG)", "Full Subtitles", "English", "Japanese"])
    }
    func testRetimedCopyKeepsTheOriginalAndItsFormat() async throws {
        let folder = SubtitleFiles.root.appendingPathComponent("SyncTests-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let original = folder.appendingPathComponent("episode.srt")
        try "1\n00:00:07,200 --> 00:00:10,640\n안녕\n".write(to: original, atomically: true, encoding: .utf8)
        let copy = try await SubtitleAutoSync.retimed(original, .init(offset: -3.2, scale: 1, segments: []))
        XCTAssertEqual(copy.deletingLastPathComponent().standardizedFileURL, folder.standardizedFileURL)
        XCTAssertTrue(SubtitleAutoSync.isSynced(copy)); XCTAssertEqual(copy.pathExtension, "srt")
        XCTAssertEqual(try String(contentsOf: copy, encoding: .utf8), "1\n00:00:04,000 --> 00:00:07,440\n안녕\n")
        XCTAssertEqual(try String(contentsOf: original, encoding: .utf8), "1\n00:00:07,200 --> 00:00:10,640\n안녕\n")
        // Synced again: the copy is named from the original's name, not ".sync-….sync-…".
        let again = try await SubtitleAutoSync.retimed(copy, .init(offset: 1, scale: 1, segments: []))
        XCTAssertEqual(again.lastPathComponent.components(separatedBy: ".sync-").count, 2)
    }
    func testASubtitleLateBySiteTrackIsSyncedWithoutTheAudio() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("SyncTrack-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        var starts: [Double] = [], t = 1.0, seed = 7.0
        while t < 600 { starts.append(t); seed = (seed * 16807).truncatingRemainder(dividingBy: 2147483647); t += 2 + seed / 2147483647 * 6 }
        func stamp(_ value: Double) -> String {
            let ms = Int((value * 1000).rounded())
            return String(format: "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        }
        func vtt(_ shift: Double, _ word: String) -> String {
            var cues: [String] = []
            for (i, start) in starts.enumerated() {
                let timing: String = stamp(start + shift) + " --> " + stamp(start + shift + 1.5)
                cues.append(timing + "\n" + word + " \(i)")
            }
            return "WEBVTT\n\n" + cues.joined(separator: "\n\n") + "\n"
        }
        let late = folder.appendingPathComponent("late.vtt"), track = folder.appendingPathComponent("track.vtt")
        try vtt(2.5, "대사").write(to: late, atomically: true, encoding: .utf8)
        try vtt(0, "line").write(to: track, atomically: true, encoding: .utf8)
        let outcome = try await SubtitleAutoSync.sync(subtitle: late, timing: nil, references: [track], video: nil, chapters: [], status: { _, _ in }, cancelled: { false })
        guard case let .synced(copy, fix, method) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(method, "track"); XCTAssertEqual(fix.offset, -2.5, accuracy: 0.02)
        let again = try await SubtitleAutoSync.sync(subtitle: copy, timing: nil, references: [track], video: nil, chapters: [], status: { _, _ in }, cancelled: { false })
        guard case .unchanged = again else { return XCTFail("\(again)") }
        try? FileManager.default.removeItem(at: copy)
    }
    /// The 0.4.20 crash: an ASS subtitle's spoken lines read from Swift (spokenCues → assSpeech).
    func testAnAssSubtitlesSpokenLinesAreReadFromSwift() throws {
        func stamp(_ seconds: Int) -> String { String(format: "0:%02d:%02d.00", seconds / 60, seconds % 60) }
        var rows: [String] = []
        for i in 0..<60 {
            rows.append("Dialogue: 0," + stamp(10 + i * 9) + "," + stamp(12 + i * 9) + ",Default,,0,0,0,,대사 \(i), 쉼표")
            if i % 10 == 0 { rows.append("Dialogue: 1," + stamp(10 + i * 9) + "," + stamp(13 + i * 9) + ",Signs,,0,0,0,,{\\pos(960,80)}간판") }
        }
        let ass = "[Script Info]\r\nScriptType: v4.00+\r\n\r\n[Events]\r\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n" + rows.joined(separator: "\r\n") + "\r\n"
        XCTAssertEqual(try SubtitleSync.shared.spokenCues(content: ass, extension: "ass").count, 60)
        XCTAssertGreaterThanOrEqual(try SubtitleSync.shared.allCues(content: ass, extension: "ass").count, 60)
    }
    func testDownloadsCountSavedKoreanSubtitlesFirst() {
        func saved(_ provider: String, translated: Bool = false) -> SavedSubtitle {
            SavedSubtitle(id: provider, episodeKey: "k", name: provider + ".ass", relativeFile: provider + ".ass", provider: provider, translated: translated, date: Date())
        }
        for provider in ["kairan", "csora", "anissia", "linkkf", "user"] { XCTAssertTrue(DesktopSubtitlePolicy.downloadKorean(saved(provider)), provider) }
        XCTAssertTrue(DesktopSubtitlePolicy.downloadKorean(saved("gemini", translated: true)))
        for provider in ["reanime", "jimaku", "download"] { XCTAssertFalse(DesktopSubtitlePolicy.downloadKorean(saved(provider)), provider) }
    }
    func testSeekStepIsWholeSecondsFromOneToHundredTwenty() {
        XCTAssertEqual(SeekStep.clamped(15), 15); XCTAssertEqual(SeekStep.clamped(15.6), 16)
        XCTAssertEqual(SeekStep.clamped(500), 120); XCTAssertEqual(SeekStep.clamped(-3), 1)
        XCTAssertEqual(SeekStep.clamped(0.2), 10); XCTAssertEqual(SeekStep.clamped(.nan), 10)
        XCTAssertEqual(SeekStep.choices, [5, 10, 15, 30, 60])
    }
    func testTranslationRulesAreTrimmedAndTheDefaultComesFromTheDesktop() {
        var preferences = AppPreferences()
        XCTAssertEqual(preferences.translationRules.extra, ""); XCTAssertEqual(preferences.translationRules.custom, "")
        preferences.promptExtra = "  의역 우선 \n"; preferences.promptRules = "\n규칙"
        XCTAssertEqual(preferences.translationRules.extra, "의역 우선"); XCTAssertEqual(preferences.translationRules.custom, "규칙")
        XCTAssertTrue(DesktopCloudPrompt.shared.rules.hasPrefix("- Read the lines as a scene"))
        XCTAssertTrue(DesktopCloudPrompt.shared.build(context: "", objectInput: true, extra: "EXTRA", custom: "CUSTOM").contains("TRANSLATION\nCUSTOM\n\nADDITIONAL RULES FROM THE USER"))
    }
}
