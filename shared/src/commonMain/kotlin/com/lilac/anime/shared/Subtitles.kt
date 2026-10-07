package com.lilac.anime.shared

data class SubtitleCue(val startSeconds: Double, val endSeconds: Double, val text: String)

object Subtitles {
    /** Basic SRT and WebVTT text cues; ASS styling is intentionally not interpreted. */
    fun parse(content: String): List<SubtitleCue> =
        content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n[ \\t]*\n")).mapNotNull { block ->
                val lines = block.lines()
                val timeIndex = lines.indexOfFirst { "-->" in it }
                if (timeIndex < 0) return@mapNotNull null
                val times = lines[timeIndex].split("-->", limit = 2)
                val start = timestamp(times[0].trim()) ?: return@mapNotNull null
                val end = timestamp(times[1].trim().substringBefore(' ')) ?: return@mapNotNull null
                val text = lines.drop(timeIndex + 1).joinToString("\n").replace(Regex("<[^>]*>"), "").trim()
                if (end <= start || text.isEmpty()) null else SubtitleCue(start, end, text)
            }.sortedBy { it.startSeconds }

    private fun timestamp(value: String): Double? {
        val parts = value.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toIntOrNull() ?: return null else 0
        if (!seconds.isFinite() || hours < 0 || minutes !in 0..59 || seconds < 0 || seconds >= 60) return null
        return hours * 3600.0 + minutes * 60.0 + seconds
    }

    fun textAt(cues: List<SubtitleCue>, seconds: Double): String =
        cues.filter { seconds >= it.startSeconds && seconds < it.endSeconds }.joinToString("\n") { it.text }
}
