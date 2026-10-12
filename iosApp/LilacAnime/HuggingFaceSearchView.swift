import SwiftUI
import LilacShared
@MainActor
final class ModelSearch: ObservableObject {
    @Published var query = ""
    @Published var models: [ModelSearchResult] = []
    @Published var files: [ModelFile] = []
    @Published var loading = false
    @Published var error: String?
    @Published var repo: String?
    private let services = IosServices()
    func search() {
        loading = true; error = nil; repo = nil; files = []
        services.searchModels(query: query) { [weak self] models, error in
            self?.models = models ?? []; self?.error = error; self?.loading = false
        }
    }
    func select(_ repo: String) {
        self.repo = repo; loading = true; error = nil
        services.modelFiles(repo: repo) { [weak self] files, error in
            self?.files = files ?? []; self?.error = error; self?.loading = false
        }
    }
    deinit { services.close() }
}
/// Finding a GGUF model on Hugging Face: the repositories that match, then the files of the one picked.
struct HuggingFaceSearchView: View {
    @StateObject private var model = ModelSearch()
    @Environment(\.dismiss) private var dismiss
    let onSelect: (String) -> Void
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if model.loading { ProgressView().tint(LilacStyle.accent).frame(maxWidth: .infinity).padding(.vertical, 20) }
                    if let error = model.error { SettingNote(error, color: .red) }
                    if let repo = model.repo {
                        Button { model.repo = nil } label: { Label("검색 결과로", systemImage: "chevron.left") }.buttonStyle(SettingButtonStyle())
                        Text(repo).font(.system(size: 17, weight: .bold)).foregroundStyle(LilacStyle.text).padding(.vertical, 6)
                        ForEach(model.files, id: \.name) { file in
                            Button { onSelect(file.url); dismiss() } label: {
                                SettingLinkLabel(title: file.name, detail: ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file))
                            }.buttonStyle(.plain)
                        }
                    } else {
                        if model.models.isEmpty && !model.loading { SettingNote("모델 이름을 검색하세요. 예: gemma, qwen, exaone", size: 13) }
                        ForEach(model.models, id: \.id) { repo in
                            Button { model.select(repo.id) } label: { SettingLinkLabel(title: repo.id) }.buttonStyle(.plain)
                        }
                    }
                }.padding(16)
            }.background(LilacStyle.background)
                .navigationTitle("Hugging Face").navigationBarTitleDisplayMode(.inline)
                .searchable(text: $model.query, prompt: "GGUF 모델 검색")
                .onSubmit(of: .search) { model.search() }
                .toolbar { Button("닫기") { dismiss() } }
        }
    }
}
