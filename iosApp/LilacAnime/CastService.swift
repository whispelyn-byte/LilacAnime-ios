import SwiftUI
import GoogleCast
import LilacShared

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let criteria = GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID)
        GCKCastContext.setSharedInstanceWith(GCKCastOptions(discoveryCriteria: criteria))
        return true
    }
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        if identifier == "com.lilac.downloads" {
            BackgroundEvents.completion = completionHandler
            _ = DownloadStore.shared
        } else { completionHandler() }
    }
}
@MainActor
final class CastService: NSObject, GCKRequestDelegate {
    var onError: ((String) -> Void)?
    private var request: GCKRequest?
    func send(_ stream: ResolvedStream, title: String, position: Double, subtitle: URL?) async throws {
        guard let client = GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient else {
            throw SubtitleFiles.failure("먼저 Cast 버튼에서 기기를 연결하세요.")
        }
        guard stream.url.scheme == "https", stream.manifestKey == nil else {
            throw SubtitleFiles.failure("이 영상은 직접 Cast 전송을 지원하지 않습니다.")
        }
        let metadata = GCKMediaMetadata(metadataType: .movie)
        metadata.setString(title, forKey: kGCKMetadataKeyTitle)
        let builder = GCKMediaInformationBuilder(contentURL: stream.url)
        builder.streamType = .buffered
        builder.contentType = stream.url.pathExtension.lowercased() == "m3u8" ? "application/x-mpegURL" : "video/mp4"
        builder.metadata = metadata
        builder.mediaTracks = stream.subtitles.enumerated().compactMap { index, track in
            guard track.url.scheme == "https", track.url.pathExtension == "vtt" else { return nil }
            return GCKMediaTrack(identifier: index + 1, contentIdentifier: track.url.absoluteString, contentType: "text/vtt",
                type: .text, textSubtype: .subtitles, name: track.label, languageCode: track.language, customData: nil)
        }
        let load = GCKMediaLoadRequestDataBuilder()
        load.mediaInformation = builder.build();
        if let track = builder.mediaTracks?.first { load.activeTrackIDs = [NSNumber(value: track.identifier)] }
         load.startTime = position; load.autoplay = NSNumber(value: true)
        request = client.loadMedia(with: load.build())
        request?.delegate = self
        GCKCastContext.sharedInstance().presentDefaultExpandedMediaControls()
    }
    nonisolated func request(_ request: GCKRequest, didFailWithError error: GCKError) {
        let message = error.localizedDescription
        DispatchQueue.main.async { [weak self] in self?.onError?(message) }
    }
    func stop() { request = nil }
}

struct CastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> GCKUICastButton { GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 28, height: 28)) }
    func updateUIView(_ view: GCKUICastButton, context: Context) {}
}
