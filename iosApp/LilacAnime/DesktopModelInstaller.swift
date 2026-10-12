import Foundation
import SwiftUI

struct DesktopModelPreset: Codable, Identifiable {
    let id: String; let label: String; let note: String; let repo: String; let file: String; let size: Int64; let legacy: Bool
    var url: URL { URL(string: "https://huggingface.co/" + repo + "/resolve/main/" + file.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed)!)! }
    static var all: [DesktopModelPreset] { guard let url = Bundle.main.url(forResource: "desktop-models", withExtension: "json"), let data = try? Data(contentsOf: url) else { return [] }; return (try? JSONDecoder().decode([DesktopModelPreset].self, from: data)) ?? [] }
}
@MainActor
final class DesktopModelInstaller: NSObject, ObservableObject {
    static let shared = DesktopModelInstaller()
    @Published var progress: [String: Double] = [:]
    @Published var error: String?
    @Published var revision = 0
    private var tasks: [String: URLSessionDownloadTask] = [:]
    private let delegate = ModelDownloadDelegate()
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: "com.lilac.model-downloads")
        config.sessionSendsLaunchEvents = true; config.isDiscretionary = false
        return URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }()
    override init() {
        super.init(); delegate.owner = self
        session.getAllTasks { [weak self] list in Task { @MainActor in
            for task in list { if let task = task as? URLSessionDownloadTask, let id = task.taskDescription { self?.tasks[id] = task; self?.progress[id] = 0 } }
        } }
    }
    func installed(_ model: DesktopModelPreset) -> Bool { FileManager.default.fileExists(atPath: LocalModelFiles.directory.appendingPathComponent(model.file).path) }
    func install(_ model: DesktopModelPreset) {
        guard tasks[model.id] == nil else { return }; error = nil
        do {
            try FileManager.default.createDirectory(at: LocalModelFiles.directory, withIntermediateDirectories: true)
            let resumed = LocalModelFiles.directory.appendingPathComponent(model.id + ".resume")
            let task: URLSessionDownloadTask
            if let data = try? Data(contentsOf: resumed) { task = session.downloadTask(withResumeData: data) }
            else {
                var request = URLRequest(url: model.url)
                let token = SecureKeys.load("huggingface")
                if !token.isEmpty { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
                task = session.downloadTask(with: request)
            }
            task.taskDescription = model.id; tasks[model.id] = task; progress[model.id] = 0; task.resume()
        } catch { self.error = error.localizedDescription }
    }
    func cancel(_ id: String) {
        tasks[id]?.cancel { data in
            if let data { try? data.write(to: LocalModelFiles.directory.appendingPathComponent(id + ".resume"), options: .atomic) }
        }
    }
    func remove(_ model: DesktopModelPreset) {
        if tasks[model.id] != nil { cancel(model.id); return }
        do { if installed(model) { try FileManager.default.removeItem(at: LocalModelFiles.directory.appendingPathComponent(model.file)) }; revision += 1 } catch { self.error = error.localizedDescription }
    }
    func finished(_ id: String, staged: URL?, failure: String?) {
        defer { tasks[id] = nil; progress[id] = nil; revision += 1 }
        if let failure { error = failure; return }
        guard let staged, let model = DesktopModelPreset.all.first(where: { $0.id == id }) else { return }
        defer { try? FileManager.default.removeItem(at: staged) }
        do {
            let named = staged.deletingLastPathComponent().appendingPathComponent(UUID().uuidString + ".gguf")
            try FileManager.default.moveItem(at: staged, to: named)
            defer { try? FileManager.default.removeItem(at: named) }
            try LocalModelFiles.validate(named)
            let target = LocalModelFiles.directory.appendingPathComponent(model.file)
            if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
            try FileManager.default.moveItem(at: named, to: target)
            try? FileManager.default.removeItem(at: LocalModelFiles.directory.appendingPathComponent(id + ".resume"))
        } catch { self.error = error.localizedDescription }
    }
}
private final class ModelDownloadDelegate: NSObject, URLSessionDownloadDelegate {
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in if let id = session.configuration.identifier { BackgroundEvents.completions.removeValue(forKey: id)?() } }
    }
    weak var owner: DesktopModelInstaller?
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription else { return }
        Task { @MainActor in owner?.progress[id] = totalBytesExpectedToWrite > 0 ? Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) : 0 }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription else { return }
        guard let response = downloadTask.response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else {
            Task { @MainActor in owner?.finished(id, staged: nil, failure: "모델 서버 응답 오류. Hugging Face 토큰과 모델 접근 권한을 확인하세요.") }; return
        }
        let staged = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        do { try FileManager.default.moveItem(at: location, to: staged); Task { @MainActor in owner?.finished(id, staged: staged, failure: nil) } }
        catch { Task { @MainActor in owner?.finished(id, staged: nil, failure: error.localizedDescription) } }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let id = task.taskDescription else { return }
        let failure = error as NSError
        if let data = failure.userInfo["NSURLSessionDownloadTaskResumeData"] as? Data { try? data.write(to: LocalModelFiles.directory.appendingPathComponent(id + ".resume"), options: .atomic) }
        Task { @MainActor in owner?.finished(id, staged: nil, failure: failure.code == NSURLErrorCancelled ? nil : failure.localizedDescription) }
    }
}
/// The models the desktop offers, to fetch, use or remove; and the token for the gated ones.
struct DesktopModelsSection: View {
    @EnvironmentObject private var library: LibraryStore
    @ObservedObject private var installer = DesktopModelInstaller.shared
    @State private var token = ""
    var body: some View {
        SettingBlock(title: "데스크탑 제공 모델") {
            ForEach(DesktopModelPreset.all.filter { !$0.legacy || installer.installed($0) }) { model in
                let using = library.preferences.selectedGGUF == model.file
                VStack(alignment: .leading, spacing: 8) {
                    Text(model.label).font(.system(size: 13, weight: .semibold)).foregroundStyle(LilacStyle.text)
                    Text(model.note).font(.system(size: 12)).foregroundStyle(LilacStyle.muted).fixedSize(horizontal: false, vertical: true)
                    if let value = installer.progress[model.id] {
                        ProgressView(value: value).tint(LilacStyle.accent)
                        SettingButtons { Button("다운로드 중단") { installer.cancel(model.id) } }
                    } else if installer.installed(model) {
                        SettingButtons {
                            Button(using ? "사용 중" : "사용") { library.preferences.selectedGGUF = model.file }.disabled(using)
                            Button("삭제") { installer.remove(model); if using { library.preferences.selectedGGUF = "" } }.buttonStyle(SettingButtonStyle(danger: true))
                        }
                    } else { SettingButtons { Button("받기 / 이어받기") { installer.install(model) } } }
                }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(LilacStyle.background, in: RoundedRectangle(cornerRadius: LilacStyle.radius))
                    .overlay(RoundedRectangle(cornerRadius: LilacStyle.radius).stroke(using ? LilacStyle.accent : LilacStyle.line))
            }
            Text("Hugging Face 토큰").font(.system(size: 13, weight: .semibold)).foregroundStyle(LilacStyle.text).padding(.top, 6)
            HStack(spacing: 6) {
                SecureField("접근 제한 모델을 받을 때만 필요합니다", text: $token).textInputAutocapitalization(.never).autocorrectionDisabled().settingField()
                Button("저장") { do { try SecureKeys.save(token, name: "huggingface"); token = "" } catch { installer.error = error.localizedDescription } }
                    .buttonStyle(SettingButtonStyle())
            }
            if let error = installer.error { SettingNote(error, color: .red) }
        }
    }
}
