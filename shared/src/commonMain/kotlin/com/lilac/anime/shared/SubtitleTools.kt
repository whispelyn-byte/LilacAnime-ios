package com.lilac.anime.shared
import com.lilac.anime.shared.ported.SubtitleDocument
import com.fleeksoft.ksoup.Ksoup

object SubtitleTools {
    fun driveConfirmation(html: String, baseUrl: String): String {
        val document = Ksoup.parse(html, baseUrl)
        val form = document.selectFirst("form[action]") ?: return ""
        val action = form.absUrl("action")
        val url = runCatching { io.ktor.http.Url(action) }.getOrNull() ?: return ""
        if (url.protocol != io.ktor.http.URLProtocol.HTTPS || url.host !in listOf("drive.google.com", "drive.usercontent.google.com")) return ""
        return io.ktor.http.URLBuilder(action).apply {
            form.select("input[name]").forEach { parameters.append(it.attr("name"), it.attr("value")) }
        }.buildString()
    }
    fun format(content: String, suggested: String): String {
        val supported = listOf("ass", "ssa", "srt", "vtt", "smi", "sami", "sbv", "sub", "mpl", "mpl2", "ttml", "xml")
        if (suggested.lowercase() in supported && SubtitleDocument.parse(content, suggested.lowercase()).isNotEmpty()) return suggested.lowercase()
        return SubtitleDocument.parse(content, "").firstOrNull()?.kind ?: ""
    }
    fun lines(content: String, extension: String): List<String> =
        SubtitleDocument.parse(content, extension.lowercase()).map { SubtitleDocument.modelText(it.text, it.kind) }
    fun replace(content: String, extension: String, lines: List<String>): String {
        val cues = SubtitleDocument.parse(content, extension.lowercase())
        require(cues.size == lines.size) { "번역 자막 줄 수가 일치하지 않습니다." }
        return SubtitleDocument.render(content, extension.lowercase(), cues, lines.map { it as String? }.toTypedArray())
    }
    fun cues(content: String, extension: String): List<SubtitleCue> =
        SubtitleDocument.parse(content, extension.lowercase()).map {
            SubtitleCue(it.startMs / 1000.0, it.endMs / 1000.0,
                Ksoup.parse(it.text.replace("\\N", "\n").replace(Regex("\\{[^}]*\\}"), "")).text())
        }
    fun toVtt(content: String, extension: String): String = "WEBVTT\n\n" + cues(content, extension).mapIndexed { i, cue ->
        (i + 1).toString() + "\n" + clock(cue.startSeconds) + " --> " + clock(cue.endSeconds) + "\n" + cue.text
    }.joinToString("\n\n")
    private fun clock(seconds: Double): String {
        val ms = (seconds.coerceAtLeast(0.0) * 1000).toLong()
        return (ms / 3600000).toString().padStart(2, '0') + ":" +
            (ms / 60000 % 60).toString().padStart(2, '0') + ":" +
            (ms / 1000 % 60).toString().padStart(2, '0') + "." + (ms % 1000).toString().padStart(3, '0')
    }
}
