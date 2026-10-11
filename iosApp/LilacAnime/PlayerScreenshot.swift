import Foundation
import Photos
import CoreImage

/// 스크린샷 (desktop player:screenshot): the frame goes to the Photos app, named after the episode and the time in it.
enum PlayerScreenshot {
    static func write(_ pixel: CVPixelBuffer, to file: URL) throws {
        // MPVKit ships without FFmpeg's PNG encoder; encode the software frame with Core Image.
        let context = CIContext(options: [.useSoftwareRenderer: true])
        guard let png = context.pngRepresentation(of: CIImage(cvPixelBuffer: pixel), format: .RGBA8,
                                                 colorSpace: CGColorSpaceCreateDeviceRGB(), options: [:]) else {
            throw SubtitleFiles.failure("스크린샷 PNG를 만들지 못했습니다.")
        }
        try png.write(to: file)
    }
    static func name(title: String, episode: String, position: Double) -> String {
        let seconds = max(0, Int(position.isFinite ? position : 0))
        let time = seconds >= 3600 ? String(format: "%d-%02d-%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String(format: "%02d-%02d", seconds / 60, seconds % 60)
        let raw = [title, episode, time].filter { !$0.isEmpty }.joined(separator: " ").precomposedStringWithCompatibilityMapping
        let safe = String(raw.unicodeScalars.map { #"<>:"/\|?*"#.unicodeScalars.contains($0) || $0.value < 32 ? "_" : Character($0) })
        let trimmed = String(safe.trimmingCharacters(in: .whitespaces).prefix(150)).trimmingCharacters(in: CharacterSet(charactersIn: ". "))
        return trimmed.isEmpty ? "LilacAnime" : trimmed
    }
    static func save(_ file: URL, name: String) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw SubtitleFiles.failure("사진 앱에 저장하려면 설정에서 LilacAnime의 사진 추가를 허용하세요.") }
        try await PHPhotoLibrary.shared().performChanges {
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = name + ".png"
            PHAssetCreationRequest.forAsset().addResource(with: .photo, fileURL: file, options: options)
        }
    }
}
