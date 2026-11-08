package com.lilac.anime.android.subtitle

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lilac.anime.shared.DesktopCommunityFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File

private val extras = Regex("non-?telop|\\bNC(?:OP|ED)\\b|tokuten|\\bSP\\d|\\bPV\\b|\\bCM\\b|menu|preview|trailer", RegexOption.IGNORE_CASE)

internal object WinPngReader {
    private data class Entry(val name: String, val extension: String, val content: String)

    suspend fun subtitle(context: Context, url: String, episode: Double): File? {
        val rows = read(context, url) ?: return null
        return withContext(Dispatchers.IO) {
            val entries = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val name = row.optString("name").substringAfterLast('/').substringAfterLast('\\')
                if (extras.containsMatchIn(name)) return@mapNotNull null
                val ass = row.optString("ass").takeUnless { it == "null" }.orEmpty()
                val raw = row.optString("raw").takeUnless { it == "null" }.orEmpty()
                val smi = row.optString("smi").takeUnless { it == "null" }.orEmpty()
                val extension = name.substringAfterLast('.', "").lowercase()
                when {
                    ass.isNotBlank() -> Entry(name, "ass", ass)
                    raw.isNotBlank() && extension in setOf("ass", "ssa", "srt", "vtt") -> Entry(name, extension, raw)
                    smi.isNotBlank() -> Entry(name, "smi", smi)
                    else -> null
                }
            }
            val selected = DesktopCommunityFiles.select(
                entries.map { it.name.substringBeforeLast('.') + "." + it.extension },
                entries.map { it.content.toByteArray().size.toLong() }, episode, strict = false, bundle = false, season = 0,
            )
            val entry = entries.getOrNull(selected) ?: return@withContext null
            val folder = File(SubtitleFiles.root, SubtitleFiles.key(url)).apply { mkdirs() }
            val file = File(folder, "winpng-$episode.${entry.extension}")
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(SubtitleFiles.playable(entry.content.replace("\uFEFF", "")).toByteArray())
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
            file
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun read(context: Context, url: String): JSONArray? = withContext(Dispatchers.Main) {
        if (Uri.parse(url).scheme !in setOf("http", "https") ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return@withContext null
        val script = withContext(Dispatchers.IO) {
            context.assets.open("winpng-reader.js").bufferedReader().use { it.readText() }
        }
        val result = CompletableDeferred<JSONArray?>()
        val web = WebView(context)
        try {
            web.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
            }
            WebViewCompat.addWebMessageListener(web, "lilacWinPng", setOf("*")) { _, message, origin, mainFrame, _ ->
                val page = Uri.parse(web.url.orEmpty())
                if (mainFrame && origin.scheme == page.scheme && origin.host == page.host && origin.port == page.port) {
                    result.complete(message.data?.takeIf { it.length <= 8_000_000 }?.let { runCatching { JSONArray(it) }.getOrNull() })
                }
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme !in setOf("http", "https")

                override fun onPageFinished(view: WebView, url: String) {
                    if (url != view.url) return
                    view.evaluateJavascript(
                        "if (!window.lilacWinPngStarted) { window.lilacWinPngStarted = true;" +
                            "Promise.resolve($script).then(x => lilacWinPng.postMessage(JSON.stringify(x)), " +
                            "() => lilacWinPng.postMessage('[]')); }", null,
                    )
                }
            }
            web.loadUrl(url)
            withTimeoutOrNull(60_000) { result.await() }
        } finally {
            WebViewCompat.removeWebMessageListener(web, "lilacWinPng")
            web.stopLoading()
            web.destroy()
        }
    }
}
