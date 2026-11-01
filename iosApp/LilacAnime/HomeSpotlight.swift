import Foundation
import LilacShared

/// 추천 (desktop app.js heroSelection, v0.5.56): five works a day, a new set each day (the same all day), taken in turn
/// from this season, what is on air and the popular list, each with its own label. Works in 시청기록 are left out
/// (계속 시청하기 has them). The first five of this season alone were the same for the whole season.
enum HomeSpotlight {
    struct Pick { let anime: Anime; let label: String }

    /// The day number in local time, as the desktop counts it.
    static func today(_ now: Date = Date(), zone: TimeZone = .current) -> Int {
        Int(floor((now.timeIntervalSince1970 + TimeInterval(zone.secondsFromGMT(for: now))) / 86_400))
    }

    static func picks(season: [Anime], airing: [Anime], top: [Anime], watchedIDs: Set<String>, watchedTitles: Set<String>, day: Int) -> [Pick] {
        let fresh = { (anime: Anime) in !watchedIDs.contains(anime.id) && !watchedTitles.contains(anime.title) }
        let pools: [(label: String, items: [Anime])] = [(season, "이번 시즌 신작"), (airing, "지금 방영 중"), (top, "인기 작품")].enumerated().compactMap { index, pool in
            let art = pool.0.filter { !$0.poster.isEmpty || !$0.backdrop.isEmpty }
            let unseen = art.filter(fresh)
            let items = shuffled(unseen.count >= 3 ? unseen : art, seed: UInt32(truncatingIfNeeded: day &* 7 &+ index))
            return items.isEmpty ? nil : (pool.1, items)
        }
        var picks: [Pick] = [], seen: Set<String> = []
        var round = 0
        while picks.count < 5 && pools.contains(where: { $0.items.count > round }) {
            for pool in pools where picks.count < 5 && round < pool.items.count {
                let anime = pool.items[round]
                if seen.insert(anime.id).inserted { picks.append(Pick(anime: anime, label: pool.label)) }
            }
            round += 1
        }
        return picks
    }

    /// A shuffle of the list's first 40 (the better known ones), seeded by the day and the list (mulberry32, as the desktop).
    static func shuffled<T>(_ list: [T], seed: UInt32) -> [T] {
        var state = seed
        func random() -> Double {
            state = state &+ 0x6D2B_79F5
            var t = (state ^ (state >> 15)) &* (1 | state)
            t = (t &+ ((t ^ (t >> 7)) &* (61 | t))) ^ t
            return Double(t ^ (t >> 14)) / 4_294_967_296
        }
        var out = Array(list.prefix(40))
        guard out.count > 1 else { return out }
        for i in stride(from: out.count - 1, to: 0, by: -1) { out.swapAt(i, Int(random() * Double(i + 1))) }
        return out
    }
}
