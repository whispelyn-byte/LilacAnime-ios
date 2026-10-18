import Foundation

/// Selection rules from desktop app.js/ensureSubtitle and download-manager.cjs/attachTracks.
enum DesktopSubtitlePolicy {
    static func source(_ value: String?) -> String {
        switch value { case nil, "auto", "한국어 트랙": return "reanime"; case "선택": return "user"; default: return value! }
    }
    static func providers(_ preferred: String?) -> [String] {
        let all = ["kairan", "csora", "anissia"]
        let preferred = source(preferred)
        return all.contains(preferred) ? [preferred] + all.filter { $0 != preferred } : all
    }
    static func isKorean(_ track: RemoteSubtitle) -> Bool {
        let text = track.language + " " + track.label
        return text.range(of: "kor|korean|한국", options: [.regularExpression, .caseInsensitive]) != nil ||
            track.language.range(of: "^ko(?:[-_]|$)", options: [.regularExpression, .caseInsensitive]) != nil ||
            track.url.absoluteString.range(of: "_kor_", options: [.regularExpression, .caseInsensitive]) != nil
    }
    static func translationTrack(_ tracks: [RemoteSubtitle], language: String? = nil) -> RemoteSubtitle? {
        func pick(_ lang: String) -> RemoteSubtitle? {
            let code = lang == "ja" ? #"^(?:ja|jpn)(?:[-_]|$)"# : #"^(?:en|eng)(?:[-_]|$)"#
            let name = lang == "ja" ? "japanese|日本|일본" : "english|영어"
            func rank(_ item: RemoteSubtitle) -> Int {
                if item.label.range(of: #"dubtitle|\(ai\)|\bai\b"#, options: [.regularExpression, .caseInsensitive]) != nil { return 2 }
                return item.label.range(of: "dialogue|full", options: [.regularExpression, .caseInsensitive]) != nil ? 0 : 1
            }
            return tracks.enumerated().filter { _, item in
                item.label.range(of: "signs|songs|forced", options: [.regularExpression, .caseInsensitive]) == nil &&
                (item.language.range(of: code, options: [.regularExpression, .caseInsensitive]) != nil ||
                 (item.label + " " + item.language).range(of: name, options: [.regularExpression, .caseInsensitive]) != nil)
            }.min { rank($0.element) == rank($1.element) ? $0.offset < $1.offset : rank($0.element) < rank($1.element) }?.element
        }
        if let language { return pick(language) }
        return pick("ja") ?? pick("en")
    }
    static func preferredSaved(_ saved: [SavedSubtitle], preferred: String?) -> SavedSubtitle? {
        let preferred = source(preferred)
        if ["reanime", "jimaku"].contains(preferred), let first = saved.first, first.translated { return first }
        return saved.first { source($0.provider) == preferred && !$0.translated }
    }
    /// main.cjs DOWNLOAD_KOREAN_SOURCES: the saved subtitles a download counts as Korean (a machine translation among them).
    static func downloadKorean(_ saved: SavedSubtitle) -> Bool {
        saved.translated || ["kairan", "csora", "anissia", "linkkf", "user"].contains(saved.provider)
    }
    static func burnedKorean(source: String, stream: ResolvedStream? = nil) -> Bool {
        // A stream said to have it in the picture has; 애니24's does, except a raw video whose player puts the Korean
        // subtitle over it from a file (desktop: stream.burnedKorean || (ohli24 && !stream.subtitleUrl)).
        if stream?.burnedKorean == true { return true }
        if source == "ohli24" { return !(stream?.subtitles.contains(where: isKorean) ?? false) }
        if let value = stream?.burnedKorean { return value }
        return source == "linkani" && !(stream?.subtitles.contains(where: isKorean) ?? false)
    }
    static func siteKorean(source: String, tracks: [RemoteSubtitle], stream: ResolvedStream? = nil) -> Bool {
        burnedKorean(source: source, stream: stream) || source == "linkkf" || tracks.contains(where: isKorean)
    }
    static func shouldTranslateAlongside(source: String, siteKorean: Bool) -> Bool {
        false
    }
    static func shouldPrefetch(provider: String, cloud: Bool, siteKorean: Bool, prefersAI: Bool) -> Bool {
        (provider == "local" || cloud) && (!siteKorean || prefersAI)
    }
    static func cacheRemovable(age: TimeInterval, all: Bool, protected: Bool) -> Bool {
        (all || !protected) && age > (all ? 600 : 3600)
    }
}

extension AppPreferences {
    /// Desktop automatically uses a configured API, then the installed local model.
    func translationPreferences(automatic: Bool = false, localOnly: Bool = false, models: [URL]? = nil, hasKey: (String) -> Bool = { !SecureKeys.load($0).isEmpty }) -> AppPreferences? {
        if automatic && !autoTranslation { return nil }
        var result = self
        let clouds = ["gemini", "openai", "deepl", "qwen"]
        let wanted = translationProvider
        let files = models ?? (try? FileManager.default.contentsOfDirectory(at: LocalModelFiles.directory, includingPropertiesForKeys: nil))?.filter { $0.pathExtension.lowercased() == "gguf" } ?? []
        let installedFile = files.first(where: { $0.lastPathComponent == selectedGGUF }) ?? files.first(where: { $0.lastPathComponent == DesktopModelPreset.all.first(where: { $0.id == "gemma-4-e4b" })?.file }) ?? files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }).first
        let installed = installedFile != nil
        if let installedFile { result.selectedGGUF = installedFile.lastPathComponent }
        if localOnly { return wanted == "local" && installed ? result : nil }
        if wanted == "local" && installed { return result }
        let preferred = clouds.contains(wanted) ? [wanted] + clouds.filter { $0 != wanted } : clouds
        if let provider = preferred.first(where: hasKey) {
            result.translationProvider = provider
            result.translationModel = translationModels?[provider] ?? (wanted == provider ? translationModel : "")
            return result
        }
        if installed { result.translationProvider = "local"; return result }
        return nil
    }
}
enum TranslationFallbackPolicy {
    static func cloudAfterLocal(localOnly: Bool, enabled: Bool, tried: Set<String>, hasKey: (String) -> Bool) -> String? {
        guard !localOnly && enabled else { return nil }
        return ["gemini", "openai", "deepl", "qwen"].first { !tried.contains($0) && hasKey($0) }
    }
}
