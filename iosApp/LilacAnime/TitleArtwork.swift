import SwiftUI
import UIKit
import LilacShared

/// A work's artwork, loaded (desktop v0.5.43-44 readyArtwork): the wide header picture (TMDB backdrop or AniList banner)
/// and its Korean title logos with how light their lettering is (0 black … 1 white). '' / empty when there is none or it
/// does not load.
struct LoadedArtwork: Codable, Equatable {
    struct Logo: Codable, Equatable { let url: String; let light: Double }
    var banner: String
    var logos: [Logo]
    var time: Date
    var complete: Bool { !banner.isEmpty && !logos.isEmpty }
    /// The logo that stands out on the theme: the lightest on the dark page, the darkest on the light one. One that still
    /// blends in (dark on the dark theme, white on the light one) gets an outline: contrast.
    func logo(dark: Bool) -> (url: String, contrast: Bool)? {
        guard let pick = dark ? logos.max(by: { $0.light < $1.light }) : logos.min(by: { $0.light < $1.light }) else { return nil }
        return (pick.url, dark ? pick.light < 0.35 : pick.light > 0.7)
    }
}

/// Asked once per work: the detail page and the home spotlight share it, and the spotlight's picks are asked ahead.
/// Kept on disk for a month (a miss for a week), like the desktop's artwork-cache.json.
@MainActor final class TitleArtworkStore: ObservableObject {
    static let shared = TitleArtworkStore()
    @Published private(set) var loaded: [String: LoadedArtwork] = [:]
    private var requests: [String: Task<LoadedArtwork, Never>] = [:]
    private let images = NSCache<NSString, UIImage>()
    private lazy var service = IosServices()
    private let file = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("artwork-cache.json")

    private init() {
        images.countLimit = 30
        if let data = try? Data(contentsOf: file), let saved = try? JSONDecoder().decode([String: LoadedArtwork].self, from: data) { loaded = saved.filter { Self.fresh($0.value) } }
    }
    private static func fresh(_ art: LoadedArtwork, now: Date = Date()) -> Bool {
        now.timeIntervalSince(art.time) < (art.banner.isEmpty && art.logos.isEmpty ? 7 : 30) * 86400
    }
    /// A list card has only its own titles; the detail page also the original one and the AniList id, which find more:
    /// the two are kept apart, so a card's miss does not stand for the detail page.
    private func key(_ anime: Anime, source: String) -> String {
        (anime.source.isEmpty ? source : anime.source) + ":" + anime.id + ":" + (SecureKeys.load("tmdb").isEmpty ? "free" : "tmdb")
            + (anime.native.isEmpty ? "" : ":ja") + (anime.anilistId == nil ? "" : ":al")
    }
    func cached(_ anime: Anime, source: String) -> LoadedArtwork? { loaded[key(anime, source: source)] }
    func image(_ url: String) -> UIImage? { url.isEmpty ? nil : images.object(forKey: url as NSString) }

    @discardableResult func artwork(_ anime: Anime, source: String) async -> LoadedArtwork {
        let key = key(anime, source: source)
        if let art = loaded[key], Self.fresh(art) {
            // Kept from an earlier launch: its pictures are loaded again before the page shows them.
            _ = await load(art.banner)
            for logo in art.logos { _ = await load(logo.url) }
            return art
        }
        if let request = requests[key] { return await request.value }
        let request = Task { await self.fetch(anime, source: source) }
        requests[key] = request
        let art = await request.value
        requests[key] = nil
        loaded[key] = art
        if let data = try? JSONEncoder().encode(loaded) { try? data.write(to: file, options: .atomic) }
        return art
    }
    private func fetch(_ anime: Anime, source: String) async -> LoadedArtwork {
        // The Korean title: the site's own (애니24, 링크애니), else the one found for it.
        let korean = TitleCandidates.shared.isKorean(title: anime.title) ? anime.title : DesktopCatalog.shared.record(anime, source: source)?.korean ?? ""
        let found: TitleArtwork? = await withCheckedContinuation { continuation in
            service.artwork(anime: anime, korean: korean, credential: SecureKeys.load("tmdb")) { value, _ in continuation.resume(returning: value) }
        }
        let banner = await load(found?.banner ?? "") != nil ? found?.banner ?? "" : ""
        var logos: [LoadedArtwork.Logo] = []
        for url in found?.logos ?? [] { if let image = await load(url), let light = Self.lightness(image) { logos.append(.init(url: url, light: light)) } }
        return LoadedArtwork(banner: banner, logos: logos, time: Date())
    }
    private func load(_ url: String) async -> UIImage? {
        if let image = image(url) { return image }
        guard let address = URL(string: url), ["http", "https"].contains(address.scheme ?? ""),
              let result = try? await URLSession.shared.data(from: address), ((result.1 as? HTTPURLResponse)?.statusCode ?? 200) < 300,
              let image = UIImage(data: result.0) else { return nil }
        images.setObject(image, forKey: url as NSString)
        return image
    }
    /// How light a logo's lettering is: the mean luminance of its opaque pixels (0 black … 1 white).
    nonisolated static func lightness(_ image: UIImage) -> Double? {
        guard let cg = image.cgImage, cg.width > 0, cg.height > 0 else { return nil }
        let width = 96, height = max(1, Int((Double(cg.height) / Double(cg.width) * 96).rounded()))
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        var sum = 0.0, count = 0
        for at in stride(from: 0, to: pixels.count, by: 4) where pixels[at + 3] >= 160 {
            let alpha = Double(pixels[at + 3])
            sum += (0.2126 * Double(pixels[at]) + 0.7152 * Double(pixels[at + 1]) + 0.0722 * Double(pixels[at + 2])) / alpha
            count += 1
        }
        return count > 0 ? (sum / Double(count) * 100).rounded() / 100 : nil
    }
}

/// The Korean title logo (#detailDialog .detail-logo, #homeView .hero-logo), outlined in the opposite colour when its
/// lettering blends into the page.
struct TitleLogo: View {
    let art: LoadedArtwork
    var maxWidth: CGFloat
    var maxHeight: CGFloat
    @Environment(\.colorScheme) private var scheme
    @ObservedObject private var store = TitleArtworkStore.shared
    var body: some View {
        if let logo = art.logo(dark: scheme != .light), let image = store.image(logo.url) {
            let outline: Color = scheme == .light ? .black.opacity(0.8) : .white.opacity(0.87)
            Image(uiImage: image).resizable().scaledToFit().frame(maxWidth: maxWidth, maxHeight: maxHeight, alignment: .bottomLeading)
                .shadow(color: logo.contrast ? outline : .black.opacity(scheme == .light ? 0.25 : 0.6), radius: logo.contrast ? 1 : 8, y: logo.contrast ? 0 : 2)
                .shadow(color: logo.contrast ? outline : .clear, radius: 1)
                .accessibilityHidden(true)
        }
    }
}
