import Foundation
import SwiftUI
import UIKit

struct DesktopRelease: Decodable, Identifiable {
    var tag_name: String; var body: String?; var html_url: String; var assets: [Asset]
    var id: String { tag_name }
    struct Asset: Decodable { var name: String; var browser_download_url: String }
}
@MainActor
final class DesktopUpdater: ObservableObject {
    static let shared = DesktopUpdater()
    @Published private(set) var updateAvailable = false
    private var lastCheck = Date.distantPast
    @Published var release: DesktopRelease?
    @Published var latestBuild: String?
    @Published var status = "업데이트를 확인하세요."
    @Published var busy = false
    @Published var downloaded: URL?
    /// The installed version's notes, shown once on its first start (app.js showChangelogIfUpdated).
    @Published var changelog: DesktopRelease?
    var current: String { (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?") + " (" + (Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?") + ")" }
    func automaticCheck() {
        guard !busy, Date().timeIntervalSince(lastCheck) > 6 * 3600 else { return }
        check()
    }
    func check() {
        guard !busy else { return }
        busy = true; lastCheck = Date()
        Task {
            defer { busy = false }
            do {
                var request = URLRequest(url: URL(string: "https://api.github.com/repos/whispelyn-byte/LilacAnime-ios/releases/latest")!)
                request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
                let (data, response) = try await URLSession.shared.data(for: request)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw SubtitleFiles.failure("릴리즈를 확인할 수 없습니다.") }
                let result = try JSONDecoder().decode(DesktopRelease.self, from: data)
                if release?.tag_name != result.tag_name { downloaded = nil }
                release = result; latestBuild = nil; updateAvailable = false
                if let asset = result.assets.first(where: { $0.name == "source.json" }), let url = URL(string: asset.browser_download_url) {
                    let (source, _) = try await URLSession.shared.data(from: url)
                    if let root = try JSONSerialization.jsonObject(with: source) as? [String: Any],
                       let apps = root["apps"] as? [[String: Any]], let versions = apps.first?["versions"] as? [[String: Any]] {
                        latestBuild = versions.first?["buildVersion"] as? String
                    }
                }
                let current = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0"
                updateAvailable = (Int(latestBuild ?? "0") ?? 0) > (Int(current) ?? 0)
                if latestBuild == current && UserDefaults.standard.string(forKey: "update.lastSeenBuild") != current {
                    UserDefaults.standard.set(current, forKey: "update.lastSeenBuild"); changelog = result
                }
                status = updateAvailable ? "새 버전 " + result.tag_name + "을 받을 수 있습니다." : "최신 릴리즈: " + result.tag_name
            } catch { status = error.localizedDescription }
        }
    }
    func download() {
        guard let asset = release?.assets.first(where: { $0.name.hasSuffix(".ipa") }), let url = URL(string: asset.browser_download_url) else { return }
        busy = true; status = "IPA를 받는 중"
        Task {
            defer { busy = false }
            do {
                let (temporary, response) = try await URLSession.shared.download(from: url)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw SubtitleFiles.failure("IPA 다운로드 실패") }
                let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Updates")
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let target = folder.appendingPathComponent(URL(fileURLWithPath: asset.name).lastPathComponent)
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
                try FileManager.default.moveItem(at: temporary, to: target); downloaded = target
                status = "IPA를 저장했습니다. SideStore에서 같은 앱으로 업데이트하세요."
            } catch { status = error.localizedDescription }
        }
    }
}
struct DesktopUpdateView: View {
    @ObservedObject private var updater = DesktopUpdater.shared
    var body: some View {
        List {
            Section("업데이트 · 정보") {
                Text("설치 버전 " + updater.current); Text(updater.status)
                Button("새 버전 확인") { updater.check() }.disabled(updater.busy)
                if updater.release != nil { Button("릴리즈 IPA 받기") { updater.download() }.disabled(updater.busy) }
                if updater.busy { ProgressView() }
                if let file = updater.downloaded { ShareLink(item: file) { Label("IPA 공유·저장", systemImage: "square.and.arrow.up") } }
                Link("SideStore 소스 등록", destination: URL(string: "https://github.com/whispelyn-byte/LilacAnime-ios/releases/latest/download/source.json")!)
                Text("iOS에서 앱이 자기 자신을 재서명해 설치할 수는 없습니다. 같은 Bundle ID·Apple 계정으로 SideStore에서 업데이트하면 보관함과 설정을 유지합니다.").font(.caption)
            }
            if let release = updater.release {
                Section(release.tag_name + " 변경 사항") {
                    ReleaseNotesText(notes: release.body).textSelection(.enabled)
                    if let url = URL(string: release.html_url) { Link("릴리즈 페이지", destination: url) }
                }
            }
            Section("원본·크레딧") {
                Link("Android", destination: URL(string: "https://github.com/dream150/LilacAnime")!)
                Link("Desktop", destination: URL(string: "https://github.com/whispelyn-byte/LilacAnime-desktop")!)
                Link("iOS 소스", destination: URL(string: "https://github.com/whispelyn-byte/LilacAnime-ios")!)
            }
        }.navigationTitle("업데이트").task { updater.check() }
    }
}

/// GitHub release notes with their emphasis, code and links (app.js releaseNotesHtml); the version heading is left out.
struct ReleaseNotesText: View {
    let notes: String?
    var body: some View {
        let text = (notes ?? "").replacingOccurrences(of: #"^\s*#{1,6}\s*v?\d+(?:\.\d+)+\S*\s*\n"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"(?m)^#{1,6}\s+"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { Text("변경 사항을 불러오지 못했어요. GitHub 릴리스에서 확인할 수 있어요.") }
        else if let attributed = try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)) { Text(attributed) }
        else { Text(text) }
    }
}
struct ChangelogSheet: View {
    let release: DesktopRelease
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ReleaseNotesText(notes: release.body)
                    Link("GitHub 릴리스에서 보기", destination: URL(string: release.html_url) ?? URL(string: "https://github.com/whispelyn-byte/LilacAnime-ios/releases")!)
                }.frame(maxWidth: .infinity, alignment: .leading).padding(20)
            }
            .navigationTitle(release.tag_name + " 변경 사항").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("닫기") { dismiss() } } }
        }
    }
}
