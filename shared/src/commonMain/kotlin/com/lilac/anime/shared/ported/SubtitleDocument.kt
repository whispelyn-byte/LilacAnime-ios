package com.lilac.anime.shared.ported
import com.lilac.anime.shared.compat.*
object SubtitleDocument {
    data class Cue(val startMs: Long, val endMs: Long, val text: String, val raw: String, val kind: String, val index: Int)
    private fun parseByContent(c: String): List<Cue> {
        val normalized = c.trimStart()
        return when {
            normalized.startsWith("WEBVTT", true) -> parseVtt(c)
            Regex("(?im)^Dialogue:").containsMatchIn(c) -> parseAss(c)
            Regex("(?is)<SAMI|<SYNC\\s+Start\\s*=").containsMatchIn(c) -> parseSmi(c)
            Regex("(?is)<tt[ >]|<ttml|xmlns=.*ttml").containsMatchIn(c) -> parseTtml(c)
            Regex("(?m)^\\s*\\{\\d+\\}\\{\\d+\\}").containsMatchIn(c) -> parseSub(c)
            Regex("(?m)^\\s*\\[?\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\]?\\s*[-=]+>").containsMatchIn(c) -> parseSrt(c)
            else -> emptyList()
        }
    }

    fun parse(content: String, ext: String): List<Cue> = when (ext) {
        "ass", "ssa" -> parseAss(content)
        "srt" -> parseSrt(content)
        "vtt" -> parseVtt(content)
        "smi", "sami" -> parseSmi(content)
        "sbv" -> parseSbv(content)
        "sub" -> parseSub(content)
        "mpl", "mpl2" -> parseMpl2(content)
        "ttml", "xml" -> parseTtml(content)
        else -> parseByContent(content)
    }

    private fun normalizeModelText(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\\N", "\n")
        .trim()

    fun modelText(text: String, kind: String): String {
        var value = text.replace("\r\n", "\n").replace('\r', '\n')
        if (kind == "srt") value = value.replace("\n", "\\N")
        value = value.replace(Regex("(?s)\\{[^}]*\\}"), "")
        value = value.replace(Regex("(?is)<[^>]+>"), "")
        return value.trim()
    }

    private fun restoreTags(original: String, translated: String, kind: String): String {
        var text = translated.replace("\r\n", "\n").replace('\r', '\n').replace("\\N", "\n")
        if (kind == "sub" || kind == "mpl2") return text.replace("\n", "|")
        val regex = if (kind == "ass") Regex("""\{[^}]*\}""") else Regex("(?is)<[^>]+>")
        val source = original.replace("\\N", "\n")
        val plainLength = regex.replace(source, "").length
        val insertions = linkedMapOf<Int, MutableList<String>>()
        var previous = 0
        var sourceOffset = 0
        for (match in regex.findAll(source)) {
            sourceOffset += match.range.first - previous
            val position = if (plainLength == 0) 0 else ((sourceOffset.toDouble() / plainLength) * text.length).toInt().coerceIn(0, text.length)
            insertions.getOrPut(position) { mutableListOf() }.add(match.value)
            previous = match.range.last + 1
        }
        fun escape(value: String): String = when (kind) {
            "ass" -> value.replace("\n", "\\N")
            "ttml" -> value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            else -> value
        }
        return buildString {
            var offset = 0
            for ((position, tags) in insertions.entries.sortedBy { it.key }) {
                append(escape(text.substring(offset, position)))
                append(tags.joinToString(""))
                offset = position
            }
            append(escape(text.substring(offset)))
        }
    }

    private fun parseAss(c: String): List<Cue> {
        val lines = c.lines()
        val firstJapaneseDialogueLine = 0
        var cueIndex = 0
        return lines.mapIndexedNotNull { idx, line ->
            if (idx < firstJapaneseDialogueLine || !line.startsWith("Dialogue:", true)) return@mapIndexedNotNull null
            val body = line.substringAfter(':').trimStart()
            val parts = body.split(',', limit = 10)
            if (parts.size < 10) return@mapIndexedNotNull null
            val start = assTime(parts[1]) ?: return@mapIndexedNotNull null
            val end = assTime(parts[2]) ?: return@mapIndexedNotNull null
            Cue(start, end, parts[9].trim(), line, "ass", cueIndex++)
        }
    }

    private fun parseSrt(c: String): List<Cue> {
        val blocks = c.replace("\r\n", "\n").split(Regex("\n{2,}"))
        var idx = 0
        return blocks.mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val timeLine = lines.firstOrNull { it.contains(" --> ") } ?: return@mapNotNull null
            val m = Regex("(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3})").find(timeLine) ?: return@mapNotNull null
            val start = srtTime(m.groupValues[1]) ?: return@mapNotNull null
            val end = srtTime(m.groupValues[2]) ?: return@mapNotNull null
            val text = lines.drop(lines.indexOf(timeLine) + 1).joinToString("\n").trim()
            Cue(start, end, text, block, "srt", idx++)
        }
    }

    private fun parseVtt(c: String): List<Cue> {
        val blocks = c.replace("\r\n", "\n").split(Regex("\n{2,}"))
        var idx = 0
        return blocks.mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val timeLine = lines.firstOrNull { it.contains(" --> ") } ?: return@mapNotNull null
            val m = Regex("(\\d{2}:\\d{2}(?::\\d{2})?[.,]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}(?::\\d{2})?[.,]\\d{3})").find(timeLine) ?: return@mapNotNull null
            val start = webVttTime(m.groupValues[1]) ?: return@mapNotNull null
            val end = webVttTime(m.groupValues[2]) ?: return@mapNotNull null
            val text = lines.drop(lines.indexOf(timeLine) + 1).joinToString("\n").trim()
            Cue(start, end, text, block, "vtt", idx++)
        }
    }

    private fun parseSmi(c: String): List<Cue> {
        val regex = Regex("(?is)<SYNC\\s+Start\\s*=\\s*(\\d+)\\s*>(.*?)(?=<SYNC\\s+Start|</BODY>|</SAMI>)")
        val matches = regex.findAll(c).toList()
        return matches.mapIndexed { i, m ->
            val start = m.groupValues[1].toLongOrNull() ?: 0L
            val next = matches.getOrNull(i + 1)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: start + 4000L
            val text = m.groupValues[2].trim()
            Cue(start, next, text, m.value, "smi", i)
        }
    }

    private fun parseSbv(c: String): List<Cue> {
        val blocks = c.replace("\r\n", "\n").split(Regex("\n{2,}"))
        var idx = 0
        return blocks.mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val times = lines.firstOrNull()?.split(',') ?: return@mapNotNull null
            if (times.size != 2) return@mapNotNull null
            val start = webVttTime(times[0].trim()) ?: return@mapNotNull null
            val end = webVttTime(times[1].trim()) ?: return@mapNotNull null
            Cue(start, end, lines.drop(1).joinToString("\n"), block, "sbv", idx++)
        }
    }

    private fun parseSub(c: String): List<Cue> {
        val regex = Regex("^\\s*\\{(\\d+)\\}\\{(\\d+)\\}(.*)$")
        val raw = c.lineSequence().mapIndexedNotNull { index, line ->
            regex.find(line)?.let { Triple(index, line, it) }
        }.toList()
        val header = raw.firstOrNull()?.takeIf { (_, _, m) ->
            m.groupValues[1] == m.groupValues[2] && m.groupValues[1] in listOf("0", "1") &&
                m.groupValues[3].trim().toDoubleOrNull()?.let { it > 0 && it <= 240 } == true
        }
        val fps = header?.third?.groupValues?.get(3)?.trim()?.toDoubleOrNull() ?: 25.0
        return raw.filter { it !== header }.mapIndexedNotNull { cueIndex, (_, line, match) ->
            val start = match.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val end = match.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
            if (end <= start) return@mapIndexedNotNull null
            Cue((start * 1000 / fps).toLong(), (end * 1000 / fps).toLong(),
                match.groupValues[3].replace("|", "\\N").trim(), line, "sub", cueIndex)
        }
    }

    private fun parseMpl2(c: String): List<Cue> = c.lineSequence().mapIndexedNotNull { idx, line ->
        val m = Regex("^\\s*\\[(\\d+)\\]\\s*\\[(\\d+)\\]\\s*(.*)$").find(line) ?: return@mapIndexedNotNull null
        val start = m.groupValues[1].toLongOrNull()?.times(100L) ?: return@mapIndexedNotNull null
        val end = m.groupValues[2].toLongOrNull()?.times(100L) ?: return@mapIndexedNotNull null
        Cue(start, end, m.groupValues[3].replace("|", "\\n").trim(), line, "mpl2", idx)
    }.toList()

    private fun parseTtml(c: String): List<Cue> {
        val regex = Regex("(?is)<p\\b([^>]*)>(.*?)</p>")
        return regex.findAll(c).mapIndexedNotNull { idx, m ->
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val begin = Regex("(?i)\\bbegin\\s*=\\s*[\\\"']([^\\\"']+)").find(attrs)?.groupValues?.get(1)
            val end = Regex("(?i)\\bend\\s*=\\s*[\\\"']([^\\\"']+)").find(attrs)?.groupValues?.get(1)
            val dur = Regex("(?i)\\bdur\\s*=\\s*[\\\"']([^\\\"']+)").find(attrs)?.groupValues?.get(1)
            val start = ttmlTime(begin) ?: return@mapIndexedNotNull null
            val finish = ttmlTime(end) ?: dur?.let { start + (ttmlDuration(it) ?: return@mapIndexedNotNull null) } ?: return@mapIndexedNotNull null
            val text = body.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            Cue(start, finish, text, m.value, "ttml", idx)
        }.toList()
    }

    private fun ttmlTime(s: String?): Long? {
        val v = s?.trim() ?: return null
        if (v.matches(Regex("\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?"))) return webVttTime(v)
        if (v.matches(Regex("\\d+(?:\\.\\d+)?ms"))) return v.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (v.matches(Regex("\\d+(?:\\.\\d+)?s"))) return v.removeSuffix("s").toDoubleOrNull()?.times(1000)?.toLong()
        return null
    }

    private fun ttmlDuration(s: String): Long? = ttmlTime(s)

    fun render(original: String, ext: String, cues: List<Cue>, translated: Array<String?>): String = when (ext) {
        "ass", "ssa" -> {
            original.replace("\r\n", "\n").replace('\r', '\n').lines().map { line ->
                if (!line.startsWith("Dialogue:", true)) return@map line
                val parts = line.substringAfter(':').trimStart().split(',', limit = 10)
                if (parts.size < 10) return@map line
                val start = assTime(parts[1]) ?: return@map line
                val end = assTime(parts[2]) ?: return@map line
                val rawText = parts[9].trim()
                val cue = cues.firstOrNull { it.kind == "ass" && it.startMs == start && it.endMs == end && it.text.trim() == rawText }
                    ?: cues.firstOrNull { it.kind == "ass" && it.startMs == start && it.endMs == end }
                    ?: return@map line
                val t = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
                val mutable = parts.toMutableList()
                mutable[9] = t
                "Dialogue: " + mutable.joinToString(",")
            }.joinToString("\n")
        }
        "srt" -> renderSrtPreservingTiming(original, cues, translated)
        "vtt" -> {
            val blocks = original.replace("\r\n", "\n").split(Regex("\n{2,}"))
            var i = 0
            blocks.joinToString("\n\n") { block ->
                val cue = cues.getOrNull(i) ?: return@joinToString block
                if (!block.contains(" --> ")) return@joinToString block
                val lines = block.lines().toMutableList(); val ti = lines.indexOfFirst { it.contains(" --> ") }
                if (ti >= 0) { lines.subList(ti + 1, lines.size).clear(); lines += restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind); i++ }
                lines.joinToString("\n")
            }
        }
        "sbv" -> {
            val blocks = original.replace("\r\n", "\n").split(Regex("\n{2,}"))
            var i = 0
            blocks.joinToString("\n\n") { block ->
                val cue = cues.getOrNull(i) ?: return@joinToString block
                if (!block.contains(",")) return@joinToString block
                val lines = block.lines().toMutableList()
                if (lines.size >= 2) { lines.subList(1, lines.size).clear(); lines += restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind); i++ }
                lines.joinToString("\n")
            }
        }
        "smi", "sami" -> {
            var i = 0
            Regex("(?is)<SYNC\\s+Start\\s*=\\s*(\\d+)\\s*>(.*?)(?=<SYNC\\s+Start|</BODY>|</SAMI>)").replace(original) { m ->
                val cue = cues.getOrNull(i++) ?: return@replace m.value
                val text = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
                val tag = Regex("(?is)<SYNC\\s+Start\\s*=\\s*\\d+\\s*>").find(m.value)?.value ?: ""
                "$tag$text"
            }
        }
        "sub", "mpl", "mpl2" -> {
            val remaining = cues.toMutableList()
            original.lineSequence().map { line ->
                val index = remaining.indexOfFirst { it.raw == line }
                if (index < 0) line else {
                    val cue = remaining.removeAt(index)
                    val text = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
                    val regex = if (ext == "sub") Regex("""^(\s*\{\d+\}\{\d+\}\s*).*$""")
                                else Regex("""^(\s*\[\d+\]\s*\[\d+\]\s*).*$""")
                    regex.replace(line) { it.groupValues[1] + text }
                }
            }.joinToString("\n")
        }
        "ttml", "xml" -> {
            var i = 0
            Regex("(?is)<p\\b([^>]*)>(.*?)</p>").replace(original) { m ->
                val cue = cues.getOrNull(i++) ?: return@replace m.value
                val text = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
                "<p${m.groupValues[1]}>$text</p>"
            }
        }
        else -> original
    }


    private fun renderSrtPreservingTiming(original: String, cues: List<Cue>, translated: Array<String?>): String {
        val normalized = original.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n{2,}"))
        var cueIndex = 0
        return blocks.joinToString("\n\n") { block ->
            val lines = block.split('\n').toMutableList()
            val timingIndex = lines.indexOfFirst { it.contains(" --> ") }
            if (timingIndex < 0) return@joinToString block
            val cue = cues.getOrNull(cueIndex) ?: return@joinToString block
            val originalTiming = lines[timingIndex]
            val expectedTiming = formatSrtTiming(cue.startMs, cue.endMs, originalTiming)
            if (originalTiming != expectedTiming) {
                Log.w("SubtitleProfile", "SRT_TIMING_SOURCE_MISMATCH cue=${cue.index} source=[$originalTiming] parsed=[$expectedTiming]")
            }
            val value = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
            lines.subList(timingIndex + 1, lines.size).clear()
            lines += value.replace("\\N", "\n")
            cueIndex++
            lines[timingIndex] = originalTiming
            lines.joinToString("\n")
        }
    }

    private fun formatSrtTiming(startMs: Long, endMs: Long, originalTiming: String): String {
        val separator = if (originalTiming.contains(" --> ")) " --> " else "-->"
        fun format(ms: Long): String {
            val total = ms.coerceAtLeast(0L)
            val h = total / 3_600_000L
            val m = (total % 3_600_000L) / 60_000L
            val s = (total % 60_000L) / 1_000L
            val msPart = total % 1_000L
            return "%02d:%02d:%02d,%03d".format( h, m, s, msPart)
        }
        return format(startMs) + separator + format(endMs)
    }

    private fun assTime(s: String): Long? = s.trim().split(':').let { p -> if (p.size != 3) null else ((p[0].toLongOrNull() ?: return null) * 3600000L + (p[1].toLongOrNull() ?: return null) * 60000L + ((p[2].substringBefore('.').toLongOrNull() ?: return null) * 1000L) + (p[2].substringAfter('.', "0").padEnd(2, '0').take(2).toLongOrNull() ?: 0L) * 10L) }
    private fun srtTime(s: String): Long? = webVttTime(s.replace(',', '.'))
    private fun webVttTime(s: String): Long? { val p = s.replace(',', '.').split(':'); if (p.size !in 2..3) return null; val sec = p.last().toDoubleOrNull() ?: return null; val min = p[p.size-2].toLongOrNull() ?: return null; val h = if (p.size == 3) p[0].toLongOrNull() ?: return null else 0L; return h*3600000L + min*60000L + (sec*1000).toLong() }
}
