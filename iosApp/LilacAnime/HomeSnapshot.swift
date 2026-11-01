import Foundation
import LilacShared

/// A source's home lists (desktop 14e689d): kept in memory ten minutes, so switching sources back and forth does not
/// load them again, and on disk a day, so at startup or a source switch the last ones show at once while the fresh ones
/// load (a source that does not answer keeps them). Up to 40 works a list.
enum HomeSnapshot {
    struct Lists { var sections: [SourceSection]; var airing: [Anime] }
    private struct Stored: Codable { var time: Date; var sections: [Section]; var airing: [String] }
    private struct Section: Codable { var name: String; var items: [String] }
    static let memoryLife: TimeInterval = 10 * 60
    static let diskLife: TimeInterval = 24 * 60 * 60
    @MainActor private static var memory: [String: (time: Date, lists: Lists)] = [:]
    static var directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("HomeSnapshot")

    /// Lists this session loaded in the last ten minutes: used instead of asking again.
    @MainActor static func fresh(_ key: String, now: Date = Date()) -> Lists? {
        guard let kept = memory[key], now.timeIntervalSince(kept.time) < memoryLife else { return nil }
        return kept.lists
    }
    /// The last lists kept on disk within a day: shown while the fresh ones load.
    static func read(_ key: String, now: Date = Date()) -> Lists? {
        guard let data = try? Data(contentsOf: file(key)), let stored = try? JSONDecoder().decode(Stored.self, from: data),
              now.timeIntervalSince(stored.time) < diskLife else { return nil }
        let sections = stored.sections.map { SourceSection(name: $0.name, items: $0.items.map { AnimeSnapshot.shared.decode(content: $0) }) }
        guard sections.contains(where: { !$0.items.isEmpty }) else { return nil }
        return Lists(sections: sections, airing: stored.airing.map { AnimeSnapshot.shared.decode(content: $0) })
    }
    @MainActor static func save(_ key: String, _ lists: Lists, now: Date = Date()) {
        memory[key] = (now, lists)
        let stored = Stored(time: now, sections: lists.sections.map { Section(name: $0.name, items: $0.items.prefix(40).map { AnimeSnapshot.shared.encode(anime: $0) }) },
                            airing: lists.airing.prefix(40).map { AnimeSnapshot.shared.encode(anime: $0) })
        guard let data = try? JSONEncoder().encode(stored) else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? data.write(to: file(key), options: .atomic)
    }
    @MainActor static func forget() { memory = [:] }
    private static func file(_ key: String) -> URL { directory.appendingPathComponent(SubtitleFiles.key(key) + ".json") }
}
