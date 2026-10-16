import Foundation

enum Anime4KMode: String, CaseIterable {
    case off, light, a, b, c
    init(saved: String?) { self = Anime4KMode(rawValue: saved ?? "off") ?? .off }
    var title: String {
        switch self {
        case .off: return "끄기"
        case .light: return "가볍게"
        case .a: return "A · 선명하게"
        case .b: return "B · 부드럽게"
        case .c: return "C · 노이즈 제거"
        }
    }
    // Same 4.0.1 models/order as desktop scripts/anime4k-entry.mjs.
    var shaders: [String] {
        let clamp = "Anime4K_Clamp_Highlights"
        switch self {
        case .off: return []
        case .light: return [clamp, "Anime4K_Restore_CNN_S", "Anime4K_Upscale_CNN_x2_S"]
        case .a: return [clamp, "Anime4K_Restore_CNN_M", "Anime4K_Upscale_CNN_x2_M"]
        case .b: return [clamp, "Anime4K_Restore_CNN_Soft_M", "Anime4K_Upscale_CNN_x2_M"]
        case .c: return [clamp, "Anime4K_Upscale_Denoise_CNN_x2_M"]
        }
    }
    func paths(bundle: Bundle = .main) throws -> [String] {
        try shaders.map { name in
            let url = bundle.url(forResource: name, withExtension: "glsl")
                ?? bundle.url(forResource: name, withExtension: "glsl", subdirectory: "Anime4K")
                ?? bundle.url(forResource: name, withExtension: "glsl", subdirectory: "Resources/Anime4K")
            guard let url else { throw NSError(domain: "Anime4K", code: 1, userInfo: [NSLocalizedDescriptionKey: "Anime4K 셰이더 파일이 없습니다."]) }
            return url.path
        }
    }
    static func shaderFailed(prefix: String, text: String) -> Bool {
        let text = text.lowercased()
        return prefix.lowercased().contains("gpu")
            && ["shader", "glsl", "spir-v", "spirv", "hook"].contains(where: text.contains)
            && ["fail", "error", "unable", "could not"].contains(where: text.contains)
    }
}
