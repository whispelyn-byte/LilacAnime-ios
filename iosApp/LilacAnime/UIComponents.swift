import SwiftUI
import LilacShared

enum LilacStyle {
    static let accent = Color(red: 0.72, green: 0.56, blue: 0.95)
    static let background = Color(uiColor: .systemGroupedBackground)
    static let card = Color(uiColor: .secondarySystemGroupedBackground)
    static let muted = Color.secondary
}
struct AnimeArtwork: View {
    let url: String
    var width: CGFloat? = nil
    var height: CGFloat = 210
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LinearGradient(colors: [LilacStyle.accent.opacity(0.4), Color.indigo.opacity(0.4)], startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: "sparkles").font(.system(size: 36)).foregroundStyle(.white.opacity(0.5))
                AsyncImage(url: URL(string: url)) { image in image.resizable().scaledToFill() } placeholder: { Color.clear }
            }.frame(width: geometry.size.width, height: geometry.size.height).clipped()
        }.frame(width: width, height: height).clipped().accessibilityHidden(true)
    }
}
struct AnimePosterCard: View {
    let anime: Anime
    var width: CGFloat? = nil
    var height: CGFloat = 205
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            AnimeArtwork(url: anime.poster, width: width, height: height)
                .clipShape(RoundedRectangle(cornerRadius: 18))
                .overlay(alignment: .topLeading) {
                    if !anime.format.isEmpty {
                        Text(anime.format).font(.caption2.bold()).padding(.horizontal, 8).padding(.vertical, 5)
                            .background(.ultraThinMaterial, in: Capsule()).padding(8)
                    }
                }
            AnimeDisplayTitle(anime: anime).font(.subheadline.weight(.semibold)).lineLimit(2)
                .frame(maxWidth: .infinity, minHeight: 38, alignment: .topLeading)
            Text([anime.year, anime.genres.first ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }.frame(width: width).foregroundStyle(.primary)
    }
}
struct FeaturedAnimeCard: View {
    let anime: Anime
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            AnimeArtwork(url: anime.backdrop.isEmpty ? anime.poster : anime.backdrop, height: 320)
            LinearGradient(colors: [.clear, .black.opacity(0.25), .black.opacity(0.92)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 10) {
                Text("FEATURED").font(.caption.weight(.heavy)).tracking(3).foregroundStyle(LilacStyle.accent)
                AnimeDisplayTitle(anime: anime).font(.system(size: 27, weight: .bold)).lineLimit(2)
                Text([anime.format, anime.year, anime.genres.prefix(2).joined(separator: " · ")].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.white.opacity(0.8))
                Label("지금 보기", systemImage: "play.fill").font(.subheadline.bold())
                    .padding(.horizontal, 18).padding(.vertical, 12).background(LilacStyle.accent, in: Capsule())
            }.foregroundStyle(.white).padding(22)
        }.frame(height: 320).clipShape(RoundedRectangle(cornerRadius: 28))
    }
}
struct AnimeRail: View {
    let title: String
    let items: [Anime]
    let source: String
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.title3.bold()).padding(.horizontal, 20)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(items, id: \.id) { anime in
                        NavigationLink { DetailView(summary: anime, source: source) } label: {
                            AnimePosterCard(anime: anime, width: 135, height: 190)
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal, 20)
            }
        }
    }
}
struct LilacChip: View {
    let text: String
    var selected = false
    var body: some View {
        Text(text).font(.subheadline.weight(selected ? .bold : .medium))
            .padding(.horizontal, 16).padding(.vertical, 12)
            .foregroundStyle(selected ? Color.white : Color.primary)
            .background(selected ? LilacStyle.accent : LilacStyle.card, in: Capsule())
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
