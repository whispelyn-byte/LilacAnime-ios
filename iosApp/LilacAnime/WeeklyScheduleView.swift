import SwiftUI
import LilacShared

/// Date arithmetic uses Korea even when the device is travelling or its week begins on Sunday.
enum ScheduleWeek {
    static var calendar: Calendar {
        var result = Calendar(identifier: .gregorian)
        result.timeZone = TimeZone(identifier: "Asia/Seoul")!
        result.firstWeekday = 2
        return result
    }
    static func dates(now: Date = Date()) -> [Date] {
        let cal = calendar
        let today = cal.startOfDay(for: now)
        let monday = cal.date(byAdding: .day, value: -((cal.component(.weekday, from: today) + 5) % 7), to: today)!
        return (0..<7).map { cal.date(byAdding: .day, value: $0, to: monday)! }
    }
    static func key(_ date: Date) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }
    static func label(_ date: Date) -> String {
        let c = calendar.dateComponents([.month, .day], from: date)
        return "\(c.month!).\(c.day!)"
    }
    static var today: Int { (calendar.component(.weekday, from: Date()) + 5) % 7 }
}

@MainActor final class WeeklyScheduleModel: ObservableObject {
    @Published var result: WeeklyScheduleResult?
    @Published var error: String?
    @Published var loading = false
    private let service = IosServices()
    private var generation = UUID()
    func load(source: String, date: Date, monday: Date) {
        service.cancel(); generation = UUID(); let token = generation
        result = nil; error = nil; loading = true
        service.weeklySchedule(sourceKey: source, day: Int32(ScheduleWeek.calendar.component(.weekday, from: date) - 1), date: ScheduleWeek.key(date), monday: ScheduleWeek.key(monday)) { [weak self] value, failure in
            guard let self, self.generation == token else { return }
            self.result = value; self.error = failure; self.loading = false
        }
    }
    deinit { service.close() }
}

struct WeeklyScheduleView: View {
    let source: String
    let known: [Anime]
    let refresh: Int
    @EnvironmentObject private var navigation: DesktopNavigation
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = WeeklyScheduleModel()
    @State private var dates = ScheduleWeek.dates()
    @State private var selected = ScheduleWeek.today
    @State private var expanded = false
    private let weekdays = ["월", "화", "수", "목", "금", "토", "일"]
    private var items: [WeeklyScheduleEntry] { model.result?.items ?? [] }
    private var heading: String {
        if model.result?.native == true && model.result?.timed == false { return source == "linkani" ? "요일별 애니 목록" : "요일별 방영표" }
        return "이번 주 방영표"
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(heading).font(.headline)
                Spacer()
                Button("오늘") { dates = ScheduleWeek.dates(); selected = ScheduleWeek.today; reload() }
            }
            if !(source == "linkani" && model.result?.native == true) {
                Text(ScheduleWeek.label(dates[0]) + " – " + ScheduleWeek.label(dates[6])).font(.caption).foregroundStyle(LilacStyle.muted)
            }
            HStack(spacing: 4) {
                ForEach(0..<7, id: \.self) { index in
                    Button { selected = index } label: {
                        VStack(spacing: 4) {
                            Text(weekdays[index]).font(.subheadline.bold())
                            Text(ScheduleWeek.label(dates[index])).font(.caption2)
                            Text(ScheduleWeek.calendar.isDateInToday(dates[index]) ? "오늘" : " ").font(.caption2)
                        }.frame(maxWidth: .infinity).padding(.vertical, 8)
                            .foregroundStyle(selected == index ? Color.white : LilacStyle.text)
                            .background(selected == index ? LilacStyle.accent : LilacStyle.card2, in: RoundedRectangle(cornerRadius: 8))
                    }.buttonStyle(.plain).accessibilityLabel(weekdays[index] + "요일 " + ScheduleWeek.label(dates[index]))
                }
            }
            if model.loading { ProgressView("방영표를 불러오는 중…").padding(.vertical, 12) }
            if let error = model.error { Text(error).font(.caption).foregroundStyle(LilacStyle.muted) }
            if let result = model.result {
                Text(result.note + " · \(items.count)개 일정").font(.caption).foregroundStyle(LilacStyle.muted)
                if items.isEmpty { Text("이 요일에는 등록된 일정이 없습니다.").font(.subheadline).foregroundStyle(LilacStyle.muted).padding(.vertical, 12) }
                ForEach(Array((expanded ? items : Array(items.prefix(6))).enumerated()), id: \.offset) { _, item in
                    if let anime = matched(item) {
                        NavigationLink { DetailView(summary: anime, source: source) } label: { row(item, anime: anime) }.buttonStyle(.plain)
                    } else {
                        Button { navigation.search(item.title) } label: { row(item, anime: nil) }.buttonStyle(.plain)
                    }
                }
                if items.count > 6 { Button(expanded ? "접기" : "더보기 (\(items.count)개)") { expanded.toggle() }.frame(maxWidth: .infinity) }
            }
            if model.error != nil || model.result?.native == false { Button("다시 시도") { reload() } }
        }
        .task(id: "\(source)/\(selected)/\(ScheduleWeek.key(dates[0]))/\(refresh)") { reload() }
        .onChange(of: scenePhase) { phase in
            if phase == .active && ScheduleWeek.key(dates[0]) != ScheduleWeek.key(ScheduleWeek.dates()[0]) {
                dates = ScheduleWeek.dates(); selected = ScheduleWeek.today
            }
        }
    }
    private func reload() { expanded = false; if !UIShowcase.enabled { model.load(source: source, date: dates[selected], monday: dates[0]) } }
    private func matched(_ item: WeeklyScheduleEntry) -> Anime? {
        if let anime = item.anime, anime.source == source { return anime }
        let names = [item.title, item.originalTitle].map(TitleKeys.key).filter { !$0.isEmpty }
        return known.first { anime in anime.source == source && [anime.title, anime.native, anime.english, anime.romaji].map(TitleKeys.key).contains { names.contains($0) } }
    }
    private func status(_ item: WeeklyScheduleEntry) -> String {
        if item.cancelled { return "결방" }
        if model.result?.timed == false { return "방영 목록" }
        if item.airingStatus == "aired" { return "방영됨" }
        if item.airingStatus == "delayed" { return "방영 지연" }
        return "방영 예정"
    }
    private func metadata(_ item: WeeklyScheduleEntry) -> String {
        var parts = item.genres.prefix(2).map { $0 }
        if !item.episode.isEmpty { parts.insert(item.episode + "화", at: 0) }
        if item.track == "dub" { parts.append("영어 더빙") }
        let date = ScheduleWeek.key(dates[selected])
        if !item.startDate.isEmpty && item.startDate >= date { parts.append(item.startDate == date ? "첫방송" : item.startDate + " 첫방송") }
        return parts.isEmpty ? "애니메이션" : parts.joined(separator: " · ")
    }
    private func row(_ item: WeeklyScheduleEntry, anime: Anime?) -> some View {
        HStack(spacing: 10) {
            VStack(spacing: 5) {
                Text(item.time.isEmpty ? (model.result?.timed == true ? "미정" : weekdays[selected] + "요일") : item.time).font(.subheadline.monospacedDigit().bold())
                Text(status(item)).font(.caption2).foregroundStyle(LilacStyle.muted)
            }.frame(width: 58)
            if let anime, !anime.poster.isEmpty { AnimeArtwork(url: anime.poster, width: 38, height: 55, alignment: .posterFocus).clipShape(RoundedRectangle(cornerRadius: 4)) }
            VStack(alignment: .leading, spacing: 5) {
                if let anime { AnimeDisplayTitle(anime: anime, source: source).font(.subheadline.bold()).lineLimit(2) }
                else { Text(item.title).font(.subheadline.bold()).lineLimit(2) }
                Text(metadata(item)).font(.caption).foregroundStyle(LilacStyle.muted).lineLimit(2)
            }
            Spacer(minLength: 0)
        }.foregroundStyle(LilacStyle.text).padding(10).frame(maxWidth: .infinity, alignment: .leading).background(LilacStyle.card, in: RoundedRectangle(cornerRadius: 8))
    }
}
