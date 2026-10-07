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
final class CastService: NSObject, GCKRequestDelegate, GCKSessionManagerListener {
    var onError: ((String) -> Void)?
    private var request: GCKRequest?
    private var relay: LANMediaRelay?
    private var observing = false
    private var generation = UUID()
    func send(_ stream: ResolvedStream, title: String, position: Double, subtitle: URL?, subtitleOffset: Double = 0) async throws {
        guard let client = GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient else {
            throw SubtitleFiles.failure("먼저 Cast 버튼에서 기기를 연결하세요.")
        }
        stop()
        let token = generation
        let needsRelay = stream.url.isFileURL || stream.url.scheme != "https" || stream.manifestKey != nil || !stream.headers.isEmpty || !stream.referer.isEmpty || subtitle != nil
        var contentURL = stream.url
        do {
            if needsRelay {
                let server = LANMediaRelay(); relay = server
                contentURL = try await server.start(stream)
            }
            guard token == generation else { throw CancellationError() }
            let metadata = GCKMediaMetadata(metadataType: .movie)
            metadata.setString(title, forKey: kGCKMetadataKeyTitle)
            let builder = GCKMediaInformationBuilder(contentURL: contentURL)
            builder.streamType = .buffered
            builder.contentType = stream.manifestKey != nil || stream.url.pathExtension.lowercased() == "m3u8" ? "application/x-mpegURL" : "video/mp4"
            builder.metadata = metadata
            var tracks: [GCKMediaTrack] = []
            if let subtitle, let relay {
                let text = try SubtitleFiles.text(subtitle)
                let vtt = SubtitleTools.shared.castVtt(content: text, extension: subtitle.pathExtension, offsetSeconds: subtitleOffset)
                guard !SubtitleTools.shared.cues(content: text, extension: subtitle.pathExtension).isEmpty else {
                    throw SubtitleFiles.failure("Cast용으로 변환할 자막이 없습니다.")
                }
                let url = relay.subtitle(Data(vtt.utf8))
                guard let track = GCKMediaTrack(identifier: 1, contentIdentifier: url.absoluteString, contentType: "text/vtt",
                    type: .text, textSubtype: .subtitles, name: "선택한 자막", languageCode: "ko", customData: nil) else {
                    throw SubtitleFiles.failure("Cast 자막 트랙을 생성할 수 없습니다.")
                }
                tracks.append(track)
            } else {
                tracks = stream.subtitles.enumerated().compactMap { index, track in
                    guard track.url.scheme == "https", track.url.pathExtension.lowercased() == "vtt" else { return nil }
                    return GCKMediaTrack(identifier: index + 1, contentIdentifier: track.url.absoluteString, contentType: "text/vtt",
                        type: .text, textSubtype: .subtitles, name: track.label, languageCode: track.language, customData: nil)
                }
            }
            builder.mediaTracks = tracks
            let load = GCKMediaLoadRequestDataBuilder()
            load.mediaInformation = builder.build()
            if let track = tracks.first { load.activeTrackIDs = [NSNumber(value: track.identifier)] }
            load.startTime = position; load.autoplay = NSNumber(value: true)
            GCKCastContext.sharedInstance().sessionManager.add(self); observing = true
            request = client.loadMedia(with: load.build()); request?.delegate = self
            GCKCastContext.sharedInstance().presentDefaultExpandedMediaControls()
        } catch {
            if token == generation { stop() }
            throw error
        }
    }
    nonisolated func request(_ request: GCKRequest, didFailWithError error: GCKError) {
        let message = error.localizedDescription
        DispatchQueue.main.async { [weak self] in
            guard let self, self.request === request else { return }
            self.stop(); self.onError?(message)
        }
    }
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKSession, withError error: Error?) {
        DispatchQueue.main.async { [weak self] in self?.stop() }
    }
    func stop() {
        generation = UUID(); request?.delegate = nil; request = nil
        relay?.stop(); relay = nil
        if observing { GCKCastContext.sharedInstance().sessionManager.remove(self); observing = false }
    }
}

struct CastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> GCKUICastButton { GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 28, height: 28)) }
    func updateUIView(_ view: GCKUICastButton, context: Context) {}
}
