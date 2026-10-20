import SwiftUI
import LilacShared

/// The desktop app's palette (styles.css :root and body.light, ott.css): one set for dark, one for light.
enum LilacStyle {
    private static func dynamic(_ dark: UInt32, _ light: UInt32) -> Color {
        Color(uiColor: UIColor { $0.userInterfaceStyle == .light ? UIColor(rgb: light) : UIColor(rgb: dark) })
    }
    static let accent = dynamic(0xC8A2C8, 0x8A6B8A)       // --lilac
    static let background = dynamic(0x121212, 0xFFFFFF)   // --bg
    static let card = dynamic(0x1E1E1E, 0xF5F5F5)         // --panel
    static let card2 = dynamic(0x252525, 0xECECEC)        // --panel2
    static let line = dynamic(0x2E2E2E, 0xE0E0E0)         // --line
    static let muted = dynamic(0xA3A0A8, 0x6F6F73)        // --muted
    static let text = dynamic(0xF0EDF6, 0x1C1B1F)         // --text
    static let score = Color(uiColor: UIColor(rgb: 0xF4CF72)) // .hero-score
    static let onAccent = Color(uiColor: UIColor(rgb: 0x1C1B1F))
    static let radius: CGFloat = 6                        // --ott-radius
    static func gutter(_ sizeClass: UserInterfaceSizeClass?) -> CGFloat { sizeClass == .regular ? 32 : 16 }
}
extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat(rgb >> 16 & 0xFF) / 255, green: CGFloat(rgb >> 8 & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}
/// The desktop's filled white button (.primary: 자세히 보기, ○화 재생); dark on the light theme.
struct LilacPrimaryButtonStyle: ButtonStyle {
    @Environment(\.colorScheme) private var scheme
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 15, weight: .bold)).lineLimit(1)
            .padding(.horizontal, 20).padding(.vertical, 11)
            .foregroundStyle(scheme == .light ? Color.white : Color(white: 0.08))
            .background((scheme == .light ? LilacStyle.onAccent : Color.white).opacity(configuration.isPressed ? 0.8 : 1), in: RoundedRectangle(cornerRadius: LilacStyle.radius))
            .modifier(LilacPressFeedback(pressed: configuration.isPressed))
    }
}
/// The desktop's grey see-through button beside it (.ghost: 내 목록).
struct LilacGhostButtonStyle: ButtonStyle {
    @Environment(\.colorScheme) private var scheme
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 15, weight: .bold)).lineLimit(1)
            .padding(.horizontal, 18).padding(.vertical, 11)
            .foregroundStyle(scheme == .light ? LilacStyle.text : Color.white)
            .background(scheme == .light ? Color.black.opacity(configuration.isPressed ? 0.16 : 0.1) : Color(white: 0.43).opacity(configuration.isPressed ? 0.5 : 0.7),
                        in: RoundedRectangle(cornerRadius: LilacStyle.radius))
            .modifier(LilacPressFeedback(pressed: configuration.isPressed))
    }
}
/// The desktop's small bordered tool button (오래된화순 · 전체 저장).
struct LilacOutlineButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13, weight: .semibold)).lineLimit(1)
            .padding(.horizontal, 13).frame(height: 34)
            .foregroundStyle(LilacStyle.text)
            .background(configuration.isPressed ? LilacStyle.card2 : Color.clear, in: RoundedRectangle(cornerRadius: LilacStyle.radius))
            .overlay(RoundedRectangle(cornerRadius: LilacStyle.radius).stroke(LilacStyle.line))
            .modifier(LilacPressFeedback(pressed: configuration.isPressed))
    }
}
/// A row's title with the desktop's lilac bar in front and 전체 보기 → on the right (ott.css .section-title).
struct LilacSectionTitle: View {
    let title: String
    var seeAll: (() -> Void)? = nil
    var body: some View {
        HStack(alignment: .center) {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 2).fill(LilacStyle.accent).frame(width: 4, height: 17)
                Text(title).font(.system(size: 20, weight: .bold)).foregroundStyle(LilacStyle.text)
            }
            Spacer(minLength: 8)
            if let seeAll {
                Button(action: seeAll) { Text("전체 보기 →") }.font(.system(size: 13, weight: .semibold)).foregroundStyle(LilacStyle.accent)
            }
        }
    }
}
extension VerticalAlignment {
    /// 22% from the top, as the desktop's `background-position: center 22%`: a cropped poster keeps its upper part.
    private struct PosterFocus: AlignmentID { static func defaultValue(in context: ViewDimensions) -> CGFloat { context.height * 0.22 } }
    static let posterFocus = VerticalAlignment(PosterFocus.self)
}
extension Alignment { static let posterFocus = Alignment(horizontal: .center, vertical: .posterFocus) }
struct AnimeArtwork: View {
    let url: String
    var width: CGFloat? = nil
    var height: CGFloat = 210
    var alignment: Alignment = .center
    /// A picture already loaded (TitleArtworkStore), shown at once instead of being fetched again.
    var preloaded: UIImage? = nil
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LilacStyle.card2
                if let preloaded {
                    Image(uiImage: preloaded).resizable().scaledToFill()
                } else if let file = URL(string: url), file.isFileURL, let data = try? Data(contentsOf: file), let image = UIImage(data: data) {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    AsyncImage(url: URL(string: url)) { image in image.resizable().scaledToFill() } placeholder: { Color.clear }
                }
            }.frame(width: geometry.size.width, height: geometry.size.height, alignment: alignment).clipped()
        }.frame(width: width, height: height).clipped().accessibilityHidden(true)
    }
}
/// A poster as the desktop's card: the picture with its score, the title and a line of facts under it.
struct AnimePosterCard: View {
    let anime: Anime
    var width: CGFloat? = nil
    var height: CGFloat = 205
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            AnimeArtwork(url: anime.poster, width: width, height: height)
                .clipShape(RoundedRectangle(cornerRadius: LilacStyle.radius))
                .overlay(alignment: .topTrailing) {
                    // The source's score on the poster (app.js card: ★ 8.5).
                    if anime.score > 0 {
                        Text("★ " + String(format: "%g", (anime.score * 100).rounded() / 100)).font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                            .padding(.horizontal, 6).padding(.vertical, 3).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 4)).padding(6)
                    }
                }
            AnimeDisplayTitle(anime: anime).font(.system(size: 14, weight: .semibold)).foregroundStyle(LilacStyle.text).lineLimit(2)
                .frame(maxWidth: .infinity, minHeight: 36, alignment: .topLeading).padding(.top, 9)
            Text([anime.year, anime.format, anime.genres.first ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                .font(.system(size: 12)).foregroundStyle(LilacStyle.muted).lineLimit(1).padding(.top, 2)
            if !DesktopAnimeMetadata.shared.episodeLabel(anime: anime).isEmpty {
                Text(DesktopAnimeMetadata.shared.episodeLabel(anime: anime)).font(.system(size: 11, weight: .semibold)).foregroundStyle(LilacStyle.accent).padding(.top, 2)
            }
        }.frame(width: width).modifier(LilacAppearMotion())
    }
}
/// The desktop home's spotlight (ott.css #homeView .hero): across the whole width, the shown poster blurred into the
/// background under a lilac glow, the copy on the left, and on a wide screen the picks on the right.
struct HomeHero: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let picks: [Anime]
    @Binding var index: Int
    let source: String
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var names = DesktopCatalog.shared
    @ObservedObject private var artworks = TitleArtworkStore.shared
    /// Picks whose logo is loaded this session (one kept on disk shows once its picture is loaded again).
    @State private var artReady: Set<String> = []
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.colorScheme) private var scheme
    private var anime: Anime { picks[min(max(index, 0), picks.count - 1)] }
    private var detailSource: String { anime.source == "jikan" ? "jikan" : source }
    private var saved: SavedAnime { SavedAnime(anime, source: anime.source.isEmpty ? source : anime.source) }
    private var wide: Bool { sizeClass == .regular }
    private var height: CGFloat { wide ? 520 : 420 }
    private var ink: Color { scheme == .light ? LilacStyle.text : .white }
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            ZStack { backdrop.id(anime.id).transition(.opacity) }
            HStack(alignment: .bottom, spacing: 24) {
                ZStack(alignment: .bottomLeading) { copy.id(anime.id).transition(.opacity) }
                if wide && picks.count > 1 { Spacer(minLength: 0); picksRow }
            }.padding(.horizontal, LilacStyle.gutter(sizeClass)).padding(.bottom, wide ? 52 : 24)
        }.frame(height: height).frame(maxWidth: .infinity).clipped()
            .animation(LilacMotion.animation(reduced: reduceMotion, duration: 0.28), value: anime.id)
            .contentShape(Rectangle())
            .simultaneousGesture(DragGesture(minimumDistance: 30).onEnded { value in
                // The picks by swiping on a phone, where they are not shown (desktop: hidden below 860px).
                guard picks.count > 1, abs(value.translation.width) > abs(value.translation.height) * 1.5, abs(value.translation.width) > 50 else { return }
                index = (index + (value.translation.width < 0 ? 1 : picks.count - 1)) % picks.count
            })
            .task(id: saved.id) { if !UIShowcase.enabled { await names.enrich(anime, source: saved.source, cast: true) } }
            // Every pick's artwork is asked ahead, so a pick switched to shows its logo at once.
            .task(id: picks.map(\.id).joined(separator: "|")) {
                guard !UIShowcase.enabled else { return }
                for pick in picks {
                    await artworks.artwork(pick, source: pick.source == "jikan" ? "jikan" : source)
                    if Task.isCancelled { return }
                    artReady.insert(pick.id)
                }
            }
    }
    @ViewBuilder private var backdrop: some View {
        // .hero-backdrop (desktop v0.5.45): the picture sharp, the wide one (TMDB backdrop, AniList banner) when there is
        // one, else the poster.
        if let banner = artworks.cached(anime, source: detailSource)?.banner, !banner.isEmpty, artReady.contains(anime.id) {
            AnimeArtwork(url: banner, height: height, preloaded: artworks.image(banner))
        } else {
            AnimeArtwork(url: anime.poster, height: height, alignment: .posterFocus)
        }
        // .hero-shade: the page colour coming in from the left (the copy), the bottom and the top, without a tint.
        LinearGradient(stops: [.init(color: LilacStyle.background, location: 0), .init(color: LilacStyle.background.opacity(0.8), location: 0.26),
                               .init(color: LilacStyle.background.opacity(0.25), location: 0.55), .init(color: .clear, location: 0.75)],
                       startPoint: .leading, endPoint: .trailing)
        LinearGradient(stops: [.init(color: LilacStyle.background, location: 0), .init(color: LilacStyle.background.opacity(0.4), location: 0.22), .init(color: .clear, location: 0.45)],
                       startPoint: .bottom, endPoint: .top)
        LinearGradient(stops: [.init(color: LilacStyle.background.opacity(0.6), location: 0), .init(color: .clear, location: 0.2)], startPoint: .top, endPoint: .bottom)
    }
    private var copy: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                Text("이번 시즌 추천").font(.system(size: 13, weight: .bold)).foregroundStyle(LilacStyle.accent)
                if picks.count > 1 {
                    HStack(spacing: 12) {
                        Rectangle().frame(width: 22, height: 1)
                        Text(String(format: "%02d / %02d", min(index, picks.count - 1) + 1, picks.count)).monospacedDigit()
                    }.font(.system(size: 13, weight: .semibold)).foregroundStyle(ink.opacity(0.6))
                }
            }.padding(.bottom, 14)
            // The spotlight's Korean title logo, with the text title kept small under it (it tells the season).
            if let art = artworks.cached(anime, source: detailSource), art.logo(dark: scheme != .light) != nil, artReady.contains(anime.id) {
                TitleLogo(art: art, maxWidth: wide ? 480 : 300, maxHeight: wide ? 170 : 96).padding(.bottom, 10)
                AnimeDisplayTitle(anime: anime).font(.system(size: 18, weight: .bold)).foregroundStyle(ink).lineLimit(1).padding(.bottom, 10)
            } else {
                AnimeDisplayTitle(anime: anime).font(.system(size: wide ? 52 : 30, weight: .black)).foregroundStyle(ink)
                    .lineLimit(3).minimumScaleFactor(0.75).shadow(color: .black.opacity(scheme == .light ? 0 : 0.5), radius: 9, y: 2).padding(.bottom, 12)
            }
            meta.padding(.bottom, 12)
            if let overview = overview {
                Text(overview).font(.system(size: 15)).lineSpacing(4).lineLimit(3).foregroundStyle(ink.opacity(0.9)).padding(.bottom, 20)
            }
            HStack(spacing: 10) {
                NavigationLink { DetailView(summary: anime, source: detailSource) } label: { Label("자세히 보기", systemImage: "info.circle") }
                    .buttonStyle(LilacPrimaryButtonStyle())
                Button { library.toggle(anime, source: detailSource) } label: {
                    Label("내 목록", systemImage: library.contains(anime, source: detailSource) ? "heart.fill" : "heart")
                }.buttonStyle(LilacGhostButtonStyle())
                    .accessibilityLabel(library.contains(anime, source: detailSource) ? "내 목록에서 제거" : "내 목록에 추가")
            }
        }.frame(maxWidth: wide ? 540 : .infinity, alignment: .leading)
    }
    private var overview: String? {
        let text = DisplayText.shared.plain(value: names.record(saved)?.overview.isEmpty == false ? names.record(saved)!.overview : anime.description_)
        return text.isEmpty ? nil : text
    }
    private var meta: some View {
        HStack(spacing: 12) {
            if anime.score > 0 { Text("★ " + String(format: "%g", (anime.score * 100).rounded() / 100)).foregroundStyle(LilacStyle.score) }
            if !anime.year.isEmpty { Text(anime.year) }
            if !anime.format.isEmpty {
                Text(anime.format).font(.system(size: 12, weight: .semibold)).padding(.horizontal, 7).padding(.vertical, 1)
                    .overlay(RoundedRectangle(cornerRadius: 3).stroke(ink.opacity(0.5)))
            }
            let episodes = DesktopAnimeMetadata.shared.episodeLabel(anime: anime)
            if !episodes.isEmpty { Text(episodes) }
        }.font(.system(size: 14, weight: .semibold)).foregroundStyle(ink.opacity(0.85)).lineLimit(1)
    }
    private var picksRow: some View {
        HStack(alignment: .bottom, spacing: 12) {
            ForEach(Array(picks.enumerated()), id: \.element.id) { offset, candidate in
                let active = offset == min(index, picks.count - 1)
                Button { index = offset } label: {
                    AnimeArtwork(url: candidate.poster, width: active ? 200 : 92, height: active ? 300 : 138)
                        .clipShape(RoundedRectangle(cornerRadius: LilacStyle.radius))
                        .overlay(RoundedRectangle(cornerRadius: LilacStyle.radius).stroke(.white.opacity(active ? 0.12 : 0)))
                        .shadow(color: .black.opacity(active ? 0.7 : 0), radius: 30, y: 28)
                        .opacity(active ? 1 : 0.5)
                }.buttonStyle(.plain).accessibilityLabel(candidate.title)
            }
        }
    }
}
struct AnimeRail: View {
    let title: String
    let items: [Anime]
    let source: String
    var seeAll: (() -> Void)? = nil
    @Environment(\.horizontalSizeClass) private var sizeClass
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            LilacSectionTitle(title: title, seeAll: seeAll).padding(.horizontal, LilacStyle.gutter(sizeClass))
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 10) {
                    ForEach(items, id: \.id) { anime in
                        NavigationLink { DetailView(summary: anime, source: anime.source == "jikan" ? "jikan" : source) } label: {
                            AnimePosterCard(anime: anime, width: sizeClass == .regular ? 170 : 130, height: sizeClass == .regular ? 255 : 195)
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal, LilacStyle.gutter(sizeClass))
            }
        }
    }
}
/// The desktop's sort chips: the chosen one filled lilac with dark text, the others quiet.
struct LilacChip: View {
    let text: String
    var selected = false
    var body: some View {
        Text(text).font(.system(size: 13, weight: selected ? .bold : .semibold)).lineLimit(1)
            .padding(.horizontal, 14).frame(height: 32)
            .foregroundStyle(selected ? LilacStyle.onAccent : LilacStyle.muted)
            .background(selected ? LilacStyle.accent : LilacStyle.card2, in: RoundedRectangle(cornerRadius: LilacStyle.radius))
    }
}
struct LilacEmptyState: View {
    let icon: String
    let title: String
    let message: String
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 36)).foregroundStyle(LilacStyle.accent)
            Text(title).font(.headline)
            Text(message).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }.frame(maxWidth: .infinity).padding(.vertical, 40).padding(.horizontal, 24)
    }
}
enum UIShowcase {
    static var enabled: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("--ui-preview")
        #else
        return false
        #endif
    }
    static var screen: String { ProcessInfo.processInfo.arguments.last ?? "home" }
    static var anime: Anime { items[0] }
    static var items: [Anime] {
        ["별빛을 따라", "푸른 여름의 기록", "시간 너머의 편지", "우리들의 작은 우주"].enumerated().map { index, title in
            AnimeSnapshot.shared.decode(content: """
            {"id":"preview-\(index)","title":"\(title)","year":"2026","format":"TV","genres":["판타지","모험"],"description":"서로 다른 세상에서 만난 두 사람이 자신만의 길을 찾아가는 이야기. 작은 약속에서 시작된 모험이 새로운 계절로 이어집니다.","episodes":[{"id":"preview-\(index)-1","number":1,"title":"새로운 시작"},{"id":"preview-\(index)-2","number":2,"title":"멀리서 들려오는 목소리"},{"id":"preview-\(index)-3","number":3,"title":"우리의 약속"}]}
            """)
        }
    }
}

enum ContentSources {
    static let keys = ["linkkf", "ohli24", "linkani", "animenosub", "reanime", "miruro"]
    static func name(_ key: String) -> String {
        switch key { case "ohli24": return "애니24"; case "linkani": return "링크애니"; case "reanime": return "RE:Anime"; case "miruro": return "Miruro"; case "animenosub": return "Animenosub"; default: return "Linkkf" }
    }
}
