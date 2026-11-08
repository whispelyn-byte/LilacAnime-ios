package com.lilac.anime.android.player

import com.lilac.anime.android.data.SavedAnime
import com.lilac.anime.android.data.WatchEntry
import com.lilac.anime.shared.Episode
import kotlinx.coroutines.*
import java.net.URL

class PlaybackFailure(message: String) : Exception(message)

/** Resolves `value` against `base` as a browser would; null when it is not a usable address. */
fun resolveURL(base: String, value: String): String? = runCatching { URL(URL(base), value.trim()).toString() }.getOrNull()
fun urlPath(value: String): String = runCatching { URL(value).path }.getOrElse { if (value.startsWith("/")) value else "" }
fun urlHost(value: String): String = runCatching { URL(value).host }.getOrDefault("")
fun urlExtension(value: String): String = urlPath(value).substringAfterLast('/').substringAfterLast('.', "").lowercase()
fun isFileURL(value: String) = value.startsWith("file:") || value.startsWith("/")
internal fun mpvAddress(value: String): String =
    if (value.startsWith("file:")) android.net.Uri.parse(value).path ?: value else value

/** A stream found for an episode (iOS ResolvedStream). */
data class ResolvedStream(
    val label: String,
    val url: String,
    val referer: String,
    val headers: Map<String, String>,
    val manifestKey: String? = null,
    val subtitles: List<RemoteSubtitle> = emptyList(),
    val hlsManifest: String? = null,
    val burnedKorean: Boolean? = null,
) { val id: String get() = url }

/** A subtitle track a site offers (iOS RemoteSubtitle). */
data class RemoteSubtitle(
    val label: String,
    val url: String,
    val language: String,
    val headers: Map<String, String>? = null,
    val origin: String? = null,
) { val id: String get() = url }

/** One episode to play, with the ones after and before it to step through (iOS PlaybackItem). */
data class PlaybackItem(
    val anime: SavedAnime,
    val episodeID: String,
    val title: String,
    val number: Int,
    val displayNumber: String,
    val watchURL: String,
    val directURL: String? = null,
    val next: List<PlaybackItem> = emptyList(),
    val preceding: List<PlaybackItem> = emptyList(),
    /** A downloaded episode's subtitle files (absolute paths), the first the one chosen when it was saved. */
    val localSubtitles: List<String> = emptyList(),
) {
    companion object {
        fun of(anime: SavedAnime, episode: Episode) = PlaybackItem(anime, episode.id, episode.title, episode.number, episode.displayNumber,
            episode.videoUrl ?: anime.anime.detailUrl.ifEmpty { "https://linkkf.app/" })
        fun of(entry: WatchEntry): PlaybackItem {
            val episodes = entry.anime.anime.episodes
            val index = episodes.indexOfFirst { it.id == entry.episodeID }
            return PlaybackItem(entry.anime, entry.episodeID, entry.episodeTitle, entry.number, entry.episodeLabel, entry.watchURL.ifEmpty { "https://linkkf.app/" },
                entry.directURL?.takeIf { isFileURL(it) },
                next = if (index >= 0) episodes.drop(index + 1).map { of(entry.anime, it) } else emptyList(),
                preceding = if (index >= 0) episodes.take(index).map { of(entry.anime, it) } else emptyList())
        }
        /** An episode of a list with the ones after and before it. */
        fun inSeries(anime: SavedAnime, episodes: List<Episode>, index: Int) = of(anime, episodes[index]).copy(
            next = episodes.drop(index + 1).map { of(anime, it) }, preceding = episodes.take(index).map { of(anime, it) })
    }
}

/** A video server in the player (player.js renderVideoServers): a resolved stream or a server page still to open. */
data class ServerChoice(val label: String, val kind: String, val stream: ResolvedStream? = null, val server: com.lilac.anime.shared.DesktopPlaybackServer? = null) {
    val id: String get() = stream?.id ?: server?.url ?: label
    companion object {
        private val order = listOf("sub", "soft", "raw", "dub")
        fun kind(label: String, given: String = "") = (if (given.isEmpty()) label.split("-").first() else given).trim().lowercase()
        fun all(streams: List<ResolvedStream>, servers: List<com.lilac.anime.shared.DesktopPlaybackServer>) =
            streams.map { ServerChoice(it.label, kind(it.label), stream = it) } +
                servers.filter { server -> streams.none { it.label == server.label } }.map { ServerChoice(it.label, kind(it.label, it.kind), server = it) }
        fun kinds(choices: List<ServerChoice>): List<String> {
            val seen = choices.map { it.kind }.distinct()
            return seen.withIndex().sortedWith(compareBy({ order.indexOf(it.value).let { i -> if (i < 0) order.size else i } }, { it.index })).map { it.value }
        }
        fun title(kind: String) = mapOf("sub" to "SUB · 영어 자막", "soft" to "SOFT · 자막 따로", "raw" to "RAW · 자막 없음", "dub" to "DUB · 영어 더빙")[kind] ?: kind.uppercase()
        fun note(kind: String) = mapOf(
            "sub" to "영어 자막이 영상에 박혀 있어요. 한국어 자막을 입히면 겹쳐 보여요.",
            "soft" to "자막 없는 영상에 자막을 따로 입혀요. 자막 트랙을 바꾸거나 한국어로 번역할 수 있어요.",
            "raw" to "자막이 없는 원본이에요. 한국어 자막을 입혀 보기에 좋아요.", "dub" to "영어 더빙이에요.")[kind]
        /** "SUB - animepahe animepahe" -> "animepahe". */
        fun name(label: String): String {
            val name = label.replace(Regex("^[A-Za-z]+\\s*-\\s*"), "")
            val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
            return if (words.size == 2 && words[0].equals(words[1], true)) words[0] else name
        }
    }
}

object SubtitlePolicy {
    fun isKorean(track: RemoteSubtitle): Boolean {
        val text = track.language + " " + track.label
        return Regex("kor|korean|한국", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("^ko(?:[-_]|$)", RegexOption.IGNORE_CASE).containsMatchIn(track.language) ||
            Regex("_kor_", RegexOption.IGNORE_CASE).containsMatchIn(track.url)
    }
}

object DesktopStreamPolicy {
    private fun english(stream: ResolvedStream) = stream.subtitles.any {
        it.language.lowercase().startsWith("en") && !Regex("forced|sign", RegexOption.IGNORE_CASE).containsMatchIn(it.label)
    }
    fun priority(stream: ResolvedStream, preferRaw: Boolean): Double {
        val kind = ServerChoice.kind(stream.label)
        val clean = kind == "raw" || kind == "soft"
        val english = kind == "soft" && english(stream)
        val korean = kind == "soft" && stream.subtitles.any(SubtitlePolicy::isKorean)
        return if (korean) 0.5 else if (preferRaw) (if (clean) 1.0 else if (kind == "sub") 2.0 else 3.0) else (if (english) 1.0 else if (kind == "sub") 2.0 else if (clean) 3.0 else 4.0)
    }
    fun ordered(streams: List<ResolvedStream>, preferRaw: Boolean, preferred: String?, workingProvider: String?): List<ResolvedStream> {
        val providers = listOf("anikoto", "kickassanime", "icarus", "aniwaves")
        fun rank(stream: ResolvedStream): Double {
            val provider = stream.label.split(" - ").drop(1).joinToString(" - ").split(" ").first()
            return (if (stream.label == preferred) 0.0 else priority(stream, preferRaw) * 100) + (if (provider == workingProvider) 0 else 10) +
                providers.indexOf(provider).let { if (it < 0) providers.size else it }
        }
        return streams.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }
}

/** Ohli's player starts an advertising MP4 before its episode playlist: only the selected player's episode media count. */
object OhliPlayback {
    fun isEpisodePlaylist(text: String): Boolean {
        if (!text.trim().startsWith("#EXTM3U") || text.contains("#EXT-X-STREAM-INF:")) return false
        val seconds = text.lines().sumOf { line ->
            if (!line.startsWith("#EXTINF:")) 0.0 else line.drop(8).substringBefore(",").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 0.0
        }
        return seconds > 30
    }
    fun isPlayerFrame(frame: String?, selected: String?): Boolean {
        if (frame == null || selected == null) return false
        val a = runCatching { URL(frame) }.getOrNull() ?: return false
        val b = runCatching { URL(selected) }.getOrNull() ?: return false
        return a.protocol == b.protocol && a.host == b.host && a.path == b.path
    }
    fun accepts(kind: String, frame: String?, selected: String?) = kind in listOf("ohliVariant", "ohliMedia", "ohliCaption") && isPlayerFrame(frame, selected)
}

/** Matches desktop resolveStreamPage's FlixCloud collection and key deadlines (iOS DesktopFlixReadiness). */
class FlixReadiness {
    private var firstSeen: Double? = null
    private var stableSince = 0.0
    private var count = -1
    private var keyDeadline: Double? = null
    private var collected: List<ResolvedStream>? = null

    fun select(streams: List<ResolvedStream>, now: Double): ResolvedStream? {
        if (streams.isEmpty()) return null
        val first = firstSeen ?: now.also { firstSeen = it }
        if (count != streams.size) { count = streams.size; stableSince = now }
        val key = streams.mapNotNull { it.manifestKey }.firstOrNull { it.isNotEmpty() }
        val completeMaster = streams.any { urlPath(it.url).lowercase().contains("master") }
        val complete = completeMaster || (streams.any { track(it.url, "video") } && streams.any { track(it.url, "audio") })
        if (keyDeadline == null) {
            if (!((key != null && (complete || now - stableSince >= 1.5)) || now - first >= 10)) return null
            keyDeadline = now + 15; collected = streams
        }
        if (key == null) { if (now >= keyDeadline!!) throw PlaybackFailure("FlixCloud 복호화 키를 찾지 못했습니다."); return null }
        val candidates = collected ?: streams
        val master = candidates.firstOrNull { urlPath(it.url).lowercase().let { p -> p.endsWith("master.m3u8") || p.contains("/master") } }
        val video = candidates.firstOrNull { track(it.url, "video") }
        val audio = candidates.firstOrNull { track(it.url, "audio") }
        var selected = master ?: video ?: candidates.firstOrNull { urlPath(it.url).lowercase().let { p -> !p.contains("/audio/") && !p.endsWith("/audio.m3u8") } }
            ?: candidates.firstOrNull { !urlPath(it.url).lowercase().endsWith("/audio.m3u8") } ?: candidates[0]
        selected = selected.copy(manifestKey = key)
        // Desktop createFlixAvProxyUrl: separate video and audio playlists joined in one master served by the proxy.
        if (video != null && audio != null) {
            selected = video.copy(manifestKey = key, url = video.url.trimEnd('/') + "/__lilac_av_master.m3u8",
                hlsManifest = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Default\",DEFAULT=YES,AUTOSELECT=YES,URI=\"${audio.url}\"\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,AUDIO=\"audio\"\n${video.url}\n")
        }
        return selected
    }
    private fun track(url: String, name: String) = Regex("/$name(?:/|\\.|$)", RegexOption.IGNORE_CASE).containsMatchIn(urlPath(url))
}

/** Desktop firstAnswering: the first item, another after six seconds of silence (or at once when every attempt failed),
 *  the losers cancelled (iOS StaggeredResolver). */
suspend fun <I, V> firstAnswering(items: List<I>, delayMillis: Long = 6_000, attempt: suspend (I) -> V): V {
    if (items.isEmpty()) throw PlaybackFailure("영상 서버가 없습니다.")
    return coroutineScope {
        val answers = kotlinx.coroutines.channels.Channel<Result<V>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val jobs = mutableListOf<Job>()
        var started = 0; var active = 0
        var failure: Throwable = PlaybackFailure("모든 영상 서버 연결에 실패했습니다.")
        val timer = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        fun launchNext() {
            if (started >= items.size) return
            val item = items[started]; started++; active++
            val ordinal = started
            jobs += launch { answers.send(runCatching { attempt(item) }.onFailure { if (it is CancellationException) throw it }) }
            if (started < items.size) jobs += launch { delay(delayMillis); timer.send(ordinal) }
        }
        launchNext()
        try {
            while (true) {
                val outcome = kotlinx.coroutines.selects.select<Pair<Int?, Result<V>?>> {
                    timer.onReceive { it to null }
                    answers.onReceive { null to it }
                }
                val ordinal = outcome.first
                val answer = outcome.second
                if (ordinal != null) { if (ordinal == started) launchNext(); continue }
                if (answer == null) continue
                if (answer.isSuccess) return@coroutineScope answer.getOrThrow()
                active--; failure = answer.exceptionOrNull() ?: failure
                if (active == 0) { if (started == items.size) throw failure; launchNext() }
            }
            @Suppress("UNREACHABLE_CODE") throw failure
        } finally { jobs.forEach { it.cancel() } }
    }
}

/** Playback advancement excludes wall time spent paused or buffering, in watching seconds; a seek starts again. */
class SkipCountdown(private var position: Double, private var speed: Double, private var revision: Long) {
    var elapsed = 0.0; private set
    fun update(position: Double, speed: Double, revision: Long) {
        elapsed = if (revision == this.revision && position.isFinite() && this.position.isFinite() && position >= this.position && this.speed.isFinite() && this.speed > 0)
            elapsed + (position - this.position) / this.speed else 0.0
        this.position = position; this.speed = speed; this.revision = revision
    }
}

/** An OP/ED or recap span (iOS OfflineChapter). */
data class Chapter(val type: String, val start: Double, val end: Double) {
    val id: String get() = type + start
    companion object {
        /** The skip button (desktop #skipNow): 요약 스킵 for a recap, ED 스킵 for an ending, else OP 스킵. */
        fun skipLabel(type: String): String { val kind = type.lowercase(); return if (kind == "recap") "요약 스킵" else if (kind.endsWith("ed")) "ED 스킵" else "OP 스킵" }
    }
}

/** Keeps repeated key presses ahead of the asynchronous mpv position updates (iOS PlayerSeekHint). */
data class SeekHint(val seconds: Double?, val target: Double, val at: Long, val id: Long = System.nanoTime()) {
    fun active(now: Long) = now >= at && now - at < 800
    companion object {
        fun relative(seconds: Double, position: Double, duration: Double, previous: SeekHint?, now: Long = System.currentTimeMillis()): SeekHint? {
            if (!seconds.isFinite() || seconds == 0.0 || !position.isFinite()) return null
            val recent = previous?.takeIf { it.active(now) }
            val base = recent?.target ?: maxOf(0.0, position)
            val limit = if (duration.isFinite() && duration > 0) duration else Double.MAX_VALUE
            val prior = recent?.seconds
            return SeekHint(if (prior != null && (prior > 0) == (seconds > 0)) prior + seconds else seconds, minOf(limit, maxOf(0.0, base + seconds)), now)
        }
        fun jump(digit: Int, duration: Double, now: Long = System.currentTimeMillis()): SeekHint? =
            if (digit !in 0..9 || !duration.isFinite() || duration <= 0) null else SeekHint(null, duration * digit / 10, now)
    }
}

enum class PlayerAspect(val key: String, val title: String, val ratio: Double?) {
    Original("original", "원본", null), Wide("16:9", "16:9", 16.0 / 9), Ultrawide("21:9", "21:9", 21.0 / 9), Standard("4:3", "4:3", 4.0 / 3), Fill("fill", "화면 채우기", null);
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Original }
}
enum class PlayerFit(val key: String, val title: String) {
    Contain("contain", "맞춤"), Cover("cover", "채움"), Stretch("stretch", "늘림");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Contain }
}
/** The picture's shape on screen as mpv options (iOS PlayerPresentation). */
data class PlayerPresentation(val aspect: PlayerAspect, val fit: PlayerFit) {
    val effectiveFit get() = if (aspect == PlayerAspect.Fill) PlayerFit.Stretch else fit
    val mpvOptions: Map<String, String> get() = mapOf(
        "video-aspect-override" to (if (aspect.ratio == null) "no" else aspect.key),
        "keepaspect" to (if (effectiveFit == PlayerFit.Stretch) "no" else "yes"),
        "panscan" to (if (effectiveFit == PlayerFit.Cover) "1" else "0"))
}

object SeekStep {
    val choices = listOf(5, 10, 15, 30, 60, 85, 90)
    fun clamped(value: Double) = value.coerceIn(1.0, 120.0).let { Math.round(it).toDouble() }
}

/** Notices when Miruro's stream has stood still while buffering for 15 seconds, to move to another server. */
class MiruroStallMonitor {
    private var context = ""; private var position = 0.0; private var since = 0.0; private var seek = 0L
    fun update(context: String, position: Double, seek: Long, eligible: Boolean, buffering: Boolean, now: Double): Boolean {
        if (this.context != context || this.seek != seek || !eligible || kotlin.math.abs(position - this.position) > 0.1) {
            this.context = context; this.position = position; this.seek = seek; since = now; return false
        }
        if (buffering && now - since >= 15) { since = now; return true }
        return false
    }
}
