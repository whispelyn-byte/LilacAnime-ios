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
struct HuggingFaceSearchView: View {
    @StateObject private var model = ModelSearch()
    @Environment(\.dismiss) private var dismiss
    let onSelect: (String) -> Void
    var body: some View {
        NavigationStack {
            List {
                if model.loading { ProgressView() }
                if let error = model.error { Text(error).foregroundStyle(.red) }
                if let repo = model.repo {
                    Button("검색 결과로") { model.repo = nil }
                    Text(repo).font(.headline)
                    ForEach(model.files, id: \.name) { file in
                        Button { onSelect(file.url); dismiss() } label: {
                            VStack(alignment: .leading) {
                                Text(file.name); Text(ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file)).font(.caption)
                            }
                        }
                    }
                } else {
                    ForEach(model.models, id: \.id) { repo in
                        Button(repo.id) { model.select(repo.id) }
                    }
                }
            }.navigationTitle("Hugging Face")
                .searchable(text: $model.query, prompt: "GGUF 모델 검색")
                .onSubmit(of: .search) { model.search() }
                .toolbar { Button("닫기") { dismiss() } }
        }
    }
}
