package com.lilac.anime.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lilac.anime.android.data.DesktopCatalog
import com.lilac.anime.android.data.SecureKeys
import com.lilac.anime.android.data.await
import com.lilac.anime.android.player.HlsData
import com.lilac.anime.android.player.await as awaitCall
import com.lilac.anime.shared.Anime
import com.lilac.anime.shared.IosServices
import com.lilac.anime.shared.TitleArtwork
import com.lilac.anime.shared.TitleCandidates
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/** A work's artwork, loaded (iOS LoadedArtwork, desktop readyArtwork): the wide header picture and its Korean title logos
 *  with how light their lettering is (0 black … 1 white). */
@Serializable
data class LoadedArtwork(val banner: String, val logos: List<Logo>, val time: Long) {
    @Serializable data class Logo(val url: String, val light: Double)
    val complete get() = banner.isNotEmpty() && logos.isNotEmpty()
    /** The logo that stands out on the theme; one that still blends in gets an outline (contrast). */
    fun logo(dark: Boolean): Pair<String, Boolean>? {
        val pick = (if (dark) logos.maxByOrNull { it.light } else logos.minByOrNull { it.light }) ?: return null
        return pick.url to if (dark) pick.light < 0.35 else pick.light > 0.7
    }
}

/** iOS TitleArtworkStore: asked once per work, kept on disk for a month (a miss for a week). */
object TitleArtworkStore {
    var loaded by mutableStateOf(emptyMap<String, LoadedArtwork>()); private set
    private val requests = mutableMapOf<String, Deferred<LoadedArtwork>>()
    private val images = LruCache<String, Bitmap>(30)
    private val service by lazy { IosServices() }
    private lateinit var file: File
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), LoadedArtwork.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(context: Context) {
        file = File(context.cacheDir, "artwork-cache.json")
        loaded = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap()).filterValues(::fresh)
    }
    private fun fresh(art: LoadedArtwork) = System.currentTimeMillis() - art.time < (if (art.banner.isEmpty() && art.logos.isEmpty()) 7L else 30L) * 86_400_000
    private fun key(anime: Anime, source: String) = anime.source.ifEmpty { source } + ":" + anime.id + ":" + (if (SecureKeys.load("tmdb").isEmpty()) "free" else "tmdb") +
        (if (anime.native.isEmpty()) "" else ":ja") + (if (anime.anilistId == null) "" else ":al")
    fun cached(anime: Anime, source: String) = loaded[key(anime, source)]
    fun image(url: String): Bitmap? = if (url.isEmpty()) null else images.get(url)

    suspend fun artwork(anime: Anime, source: String): LoadedArtwork {
        val key = key(anime, source)
        loaded[key]?.takeIf(::fresh)?.let { art ->
            // Kept from an earlier launch: its pictures are loaded again before the page shows them.
            load(art.banner); art.logos.forEach { load(it.url) }
            return art
        }
        requests[key]?.let { return it.await() }
        val request = scope.async { fetch(anime, source) }
        requests[key] = request
        val art = try { request.await() } finally { requests.remove(key) }
        loaded = loaded + (key to art)
        val snapshot = loaded
        withContext(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(serializer, snapshot)) } }
        return art
    }
    private suspend fun fetch(anime: Anime, source: String): LoadedArtwork {
        // The Korean title: the site's own (애니24, 링크애니), else the one found for it.
        val korean = if (TitleCandidates.isKorean(anime.title)) anime.title else DesktopCatalog.record(anime, source)?.korean.orEmpty()
        val found = service.await<TitleArtwork> { artwork(anime, korean, SecureKeys.load("tmdb"), it) }.getOrNull()
        val banner = found?.banner?.takeIf { load(it) != null }.orEmpty()
        val logos = found?.logos.orEmpty().mapNotNull { url -> load(url)?.let { image -> lightness(image)?.let { LoadedArtwork.Logo(url, it) } } }
        return LoadedArtwork(banner, logos, System.currentTimeMillis())
    }
    private suspend fun load(url: String): Bitmap? {
        if (url.isEmpty()) return null
        images.get(url)?.let { return it }
        if (!url.startsWith("http")) return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { HlsData.client.newCall(Request.Builder().url(url).build()).awaitCall { if (it.code in 200..299) it.body?.bytes() else null } }.getOrNull()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } ?: return null
        images.put(url, bitmap)
        return bitmap
    }
    /** How light a logo's lettering is: the mean luminance of its opaque pixels. */
    private fun lightness(image: Bitmap): Double? {
        val width = 96; val height = maxOf(1, Math.round(image.height.toDouble() / image.width * 96).toInt())
        val small = Bitmap.createScaledBitmap(image, width, height, true)
        val pixels = IntArray(width * height).also { small.getPixels(it, 0, width, 0, 0, width, height) }
        var sum = 0.0; var count = 0
        for (pixel in pixels) {
            if ((pixel ushr 24) < 160) continue
            sum += (0.2126 * ((pixel shr 16) and 0xff) + 0.7152 * ((pixel shr 8) and 0xff) + 0.0722 * (pixel and 0xff)) / 255
            count++
        }
        return if (count > 0) Math.round(sum / count * 100) / 100.0 else null
    }
}

/** The Korean title logo (#detailDialog .detail-logo, #homeView .hero-logo), outlined when its lettering blends in. */
@Composable
fun TitleLogo(art: LoadedArtwork, maxWidth: Dp, maxHeight: Dp, modifier: Modifier = Modifier) {
    val dark = LilacStyle.palette.dark
    val (url, contrast) = art.logo(dark) ?: return
    val bitmap = TitleArtworkStore.image(url) ?: return
    val outline = if (dark) Color.White.copy(alpha = 0.87f) else Color.Black.copy(alpha = 0.8f)
    Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
        modifier = modifier.widthIn(max = maxWidth).heightIn(max = maxHeight)
            .shadow(if (contrast) 1.dp else 8.dp, ambientColor = if (contrast) outline else Color.Black, spotColor = if (contrast) outline else Color.Black, clip = false))
}
