package com.lilac.anime.android.player

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.lilac.anime.android.subtitle.SubtitleFiles
import com.lilac.anime.shared.SubtitleTools
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** The Cast options (iOS: GCKCastContext with the default media receiver). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** iOS CastService: the playing episode sent to a Chromecast, through a relay on this device when the receiver cannot
 *  fetch it itself (a file here, a stream that needs headers, a FlixCloud playlist, the subtitle chosen). */
class CastService(private val context: Context) {
    private var relay: HlsProxy? = null
    private val cast: CastContext? get() = runCatching { CastContext.getSharedInstance(context.applicationContext) }.getOrNull()
    val connected: Boolean get() = cast?.sessionManager?.currentCastSession?.isConnected == true

    suspend fun send(stream: ResolvedStream, title: String, position: Double, subtitle: File?, subtitleOffset: Double = 0.0) {
        val client = cast?.sessionManager?.currentCastSession?.remoteMediaClient ?: throw PlaybackFailure("먼저 Cast 버튼에서 기기를 연결하세요.")
        stop()
        val local = isFileURL(stream.url)
        val needsRelay = local || !stream.url.startsWith("https") || stream.manifestKey != null || stream.hlsManifest != null || stream.headers.isNotEmpty() || stream.referer.isNotEmpty() || subtitle != null
        var content = stream.url
        val hls = stream.manifestKey != null || stream.hlsManifest != null || urlExtension(stream.url) == "m3u8"
        if (needsRelay) {
            val address = wifiAddress() ?: throw PlaybackFailure("Wi-Fi에 연결된 뒤 Cast하세요 (Chromecast가 이 기기에서 영상을 받아 갑니다).")
            val server = HlsProxy(address); relay = server
            content = if (local) server.startLocal(File(stream.url.removePrefix("file://"))) else server.start(stream).url
        }
        val tracks = mutableListOf<MediaTrack>()
        if (subtitle != null && relay != null) {
            val text = SubtitleFiles.text(subtitle)
            if (SubtitleTools.cues(text, subtitle.extension).isEmpty()) throw PlaybackFailure("Cast용으로 변환할 자막이 없습니다.")
            val url = relay!!.blob(SubtitleTools.castVtt(text, subtitle.extension, subtitleOffset).toByteArray(), "text/vtt", "vtt")
            tracks += MediaTrack.Builder(1, MediaTrack.TYPE_TEXT).setContentId(url).setContentType("text/vtt").setSubtype(MediaTrack.SUBTYPE_SUBTITLES).setName("선택한 자막").setLanguage("ko").build()
        } else stream.subtitles.forEachIndexed { index, track ->
            if (track.url.startsWith("https") && urlExtension(track.url) == "vtt")
                tracks += MediaTrack.Builder(index + 1L, MediaTrack.TYPE_TEXT).setContentId(track.url).setContentType("text/vtt").setSubtype(MediaTrack.SUBTYPE_SUBTITLES).setName(track.label).setLanguage(track.language).build()
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, title) }
        val info = MediaInfo.Builder(content).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(if (hls || (local && urlExtension(stream.url) == "m3u8")) "application/x-mpegURL" else "video/mp4")
            .setMetadata(metadata).setMediaTracks(tracks).build()
        val load = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime((position * 1000).toLong())
            .apply { tracks.firstOrNull()?.let { setActiveTrackIds(longArrayOf(it.id)) } }.build()
        client.load(load)
    }
    fun stop() { relay?.stop(); relay = null }

    /** This device's Wi-Fi (or other LAN) IPv4 address, for the receiver to fetch from. */
    private fun wifiAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}

/** The Cast button (iOS CastButton, GCKUICastButton): Android's media route button, in an AppCompat theme it needs. */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(factory = { context ->
        MediaRouteButton(ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat)).apply {
            runCatching { CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this) }
        }
    }, modifier = modifier)
}
