package com.lilac.anime.shared

import com.lilac.anime.shared.ported.SubtitleDocument
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/** A subtitle line's (or a stretch of speech's) start and end, in seconds. */
data class SyncCue(val start: Double, val end: Double)
/** From [from] (the subtitle's own time) on, a line moves by [shift] seconds. */
data class SyncSegment(val from: Double, val shift: Double)
data class SyncCandidate(val kind: String, val offset: Double, val scale: Double, val segments: List<SyncSegment>?, val score: Double)
/**
 * What a sync found: [unchanged] when nothing fits clearly better than the subtitle as it is, [uncertain] when nothing
 * fits at all (another release or another episode's subtitle: left as it is), else how to move it.
 */
data class SyncResult(val offset: Double, val scale: Double, val segments: List<SyncSegment>?, val method: String,
                      val unchanged: Boolean, val uncertain: Boolean, val none: Double, val chosen: Double?, val tried: List<SyncCandidate>)

/**
 * subtitle-sync.cjs (desktop 0.5.29–0.5.36, 자막 싱크 맞추기) without ffsubsync: the desktop runs ffsubsync in its own
 * Python, which iOS cannot. Its alignment is done here the way ffsubsync does it — the subtitle's lines and the reference
 * speech as two signals at 100 per second (±1: speech or not), the shift where they agree most found by FFT
 * cross-correlation — and every candidate is then scored and chosen exactly as the desktop does (fitScore, keptSegments,
 * the floors and margins it found on real episodes). The reference is the site's own dialogue track when there is one
 * (Re:Anime / Miruro, timed to this video), else the speech found in the episode's audio.
 */
object SubtitleSync {
    private const val RATE = 100.0
    // Thresholds from 8 episodes against their site tracks: a right shift raised the score by 0.06 to 0.17 (to 0.89 and
    // over), a subtitle already right by under 0.003.
    private const val TRACK_MARGIN = 0.03; private const val TRACK_FLOOR = 0.6
    private const val AUDIO_MARGIN = 0.03; private const val AUDIO_FLOOR = 0.3
    // ffsubsync's framerate ratios (fix_framerate): film, PAL and their inverses.
    private val frameRates = listOf(24.0 / 23.976, 25.0 / 23.976, 25.0 / 24.0).flatMap { listOf(it, 1 / it) }

    // MARK: Times

    fun seconds(stamp: String): Double = stamp.trim().replace(',', '.').split(':').fold(0.0) { sum, part -> sum * 60 + (part.toDoubleOrNull() ?: 0.0) }
    private fun assSeconds(stamp: String) = seconds(stamp.replace(Regex(":(\\d+)$"), ".$1"))
    fun vttStamp(value: Double, separator: Char = '.'): String {
        val ms = max(0L, (value * 1000).roundToLong())
        return two(ms / 3600000) + ":" + two(ms / 60000 % 60) + ":" + two(ms / 1000 % 60) + separator + (ms % 1000).toString().padStart(3, '0')
    }
    fun assStamp(value: Double): String {
        val cs = max(0L, (value * 100).roundToLong())
        return (cs / 360000).toString() + ":" + two(cs / 6000 % 60) + ":" + two(cs / 100 % 60) + "." + two(cs % 100)
    }
    private fun two(value: Long) = value.toString().padStart(2, '0')

    /** The shift of the segment [time] is in (the first segment's before it). */
    fun shiftAt(segments: List<SyncSegment>, time: Double): Double {
        var shift = segments[0].shift
        for (segment in segments) { if (segment.from <= time + 1e-6) shift = segment.shift else break }
        return shift
    }
    /** time → time * scale + offset (ffsubsync's order), or by segments: a line by the shift of the segment its start is in. */
    fun mover(offset: Double, scale: Double, segments: List<SyncSegment>?): (Double, Double) -> Double {
        if (segments != null && segments.size > 1) return { time, start -> time + shiftAt(segments, start) }
        return { time, _ -> time * scale + offset }
    }

    // MARK: Retiming (only the times change: styles, tags and text stay as they are)

    private val stampPattern = Regex("((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})")
    /** VTT and SRT timing lines (an SRT keeps its comma). */
    fun retimeVtt(text: String, offset: Double, scale: Double = 1.0, segments: List<SyncSegment>? = null): String {
        val move = mover(offset, scale, segments)
        return text.split('\n').joinToString("\n") { line ->
            if (!line.contains("-->")) line
            else {
                val start = seconds(stampPattern.find(line)?.value ?: "0")
                stampPattern.replace(line) { vttStamp(move(seconds(it.value), start), if (it.value.contains(',')) ',' else '.') }
            }
        }
    }
    private val assEvent = Regex("^((?:Dialogue|Comment):\\s*[^,\\r\\n]*,)(\\d+:\\d{2}:\\d{2}[.:]\\d{1,3}),(\\d+:\\d{2}:\\d{2}[.:]\\d{1,3})", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    fun retimeAss(text: String, offset: Double, scale: Double = 1.0, segments: List<SyncSegment>? = null): String {
        val move = mover(offset, scale, segments)
        return assEvent.replace(text) {
            val (head, start, end) = it.destructured
            val from = assSeconds(start)
            head + assStamp(move(from, from)) + "," + assStamp(move(assSeconds(end), from))
        }
    }
    /** A retimed copy of a subtitle file's text, in its own format (a format with no timing lines of its own: as a VTT). */
    // Swift calls these: a Kotlin exception that reaches Swift undeclared ends the app (SIGABRT, 0.4.20 crash report:
    // assSpeech from spokenCues), so each is declared and Swift's sync fails quietly instead.
    @Throws(Throwable::class)
    fun retime(content: String, extension: String, offset: Double, scale: Double, segments: List<SyncSegment>): String {
        val parts = segments.takeIf { it.size > 1 }
        return when (extension.lowercase()) {
            "ass", "ssa" -> retimeAss(content, offset, scale, parts)
            "vtt", "srt" -> retimeVtt(content, offset, scale, parts)
            else -> retimeVtt(SubtitleTools.toVtt(content, extension), offset, scale, parts)
        }
    }
    /** The extension a retimed copy is written with ([retime]: a format without timing lines becomes a VTT). */
    fun retimedExtension(extension: String): String = extension.lowercase().takeIf { it in listOf("ass", "ssa", "vtt", "srt") } ?: "vtt"

    // MARK: Which lines are speech

    /**
     * desktop dialogueVtt: the lines matched are dialogue only. Sound captions and speaker-only lines（拍手）, song lyrics (♪)
     * and signs at the top ({\an8}) are on screen while nobody talks; they are left out (all lines are kept when that would
     * leave fewer than 20, or under a third of them).
     */
    fun dialogueCues(content: String, extension: String): List<SyncCue> {
        val cues = SubtitleDocument.parse(content, extension.lowercase()).filter { it.endMs > it.startMs }
        val dialogue = cues.filter { cue ->
            val plain = cue.text.replace(Regex("<[^>]*>"), "").replace(Regex("\\{\\\\[^}]*\\}"), "").replace(Regex("\\\\[Nn]"), "\n")
            !Regex("\\{\\\\an[789]\\}").containsMatchIn(cue.text) && !Regex("[♪♫]").containsMatchIn(plain) && DesktopCaptions.drop(plain).isNotEmpty()
        }
        val use = if (dialogue.size >= 20 && dialogue.size >= cues.size / 3.0) dialogue else cues
        return use.map { SyncCue(it.startMs / 1000.0, it.endMs / 1000.0) }.sortedBy { it.start }
    }
    /** Every line of the file (the desktop's second ask runs on the subtitle as it is). */
    @Throws(Throwable::class)
    fun allCues(content: String, extension: String): List<SyncCue> =
        SubtitleDocument.parse(content, extension.lowercase()).filter { it.endMs > it.startMs }.map { SyncCue(it.startMs / 1000.0, it.endMs / 1000.0) }.sortedBy { it.start }

    // Style names that say a line is not speech: opening and ending songs (a fansub's "OP Romaji", "ED English"), signs,
    // songs, karaoke, titles, notes. Names differ from one fansub to the next, so a style is also judged by its lines.
    private val notSpeechStyle = Regex("(^|[^a-z])(op|ed)([^a-z]|$)|opening|ending|sign|song|karaoke|kara([^a-z]|$)|lyric|title|insert|logo|eyecatch|preview|note|kfx", RegexOption.IGNORE_CASE)
    // Tags of lines placed, drawn or sung on the picture: signs and karaoke, not speech.
    private val placed = Regex("\\\\(pos|move|an[789]|p[1-9]|i?clip|org|k[fo]?\\d|K\\d)")
    private class AssRow(val style: String, val start: Double, val end: Double, val text: String, val words: String)
    private fun assRows(text: String): List<AssRow> {
        var fields: List<String>? = null; var events = false
        val rows = mutableListOf<AssRow>()
        for (line in text.removePrefix("﻿").split(Regex("\\r?\\n"))) {
            if (Regex("^\\[events]", RegexOption.IGNORE_CASE).containsMatchIn(line)) { events = true; continue }
            if (line.startsWith("[")) { events = false; continue }
            if (!events) continue
            if (Regex("^format:", RegexOption.IGNORE_CASE).containsMatchIn(line)) { fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }; continue }
            val names = fields ?: continue
            if (!Regex("^dialogue:", RegexOption.IGNORE_CASE).containsMatchIn(line)) continue
            val parts = line.substringAfter(':').split(',')
            val row = names.mapIndexed { i, field -> field to if (i == names.size - 1) parts.drop(i).joinToString(",") else parts.getOrElse(i) { "" }.trim() }.toMap()
            val start = assSeconds(row["start"].orEmpty()); val end = assSeconds(row["end"].orEmpty())
            val body = row["text"].orEmpty()
            val words = body.replace(Regex("\\{[^}]*\\}"), "").replace(Regex("\\\\[Nn]"), " ").replace("\\h", " ").trim()
            if (end > start) rows += AssRow(row["style"].orEmpty(), start, end, body, words)
        }
        return rows
    }
    /** The styles that hold the speech: not named as signs or songs, few lines placed on the picture, spread over the episode. */
    private fun speechStyles(rows: List<AssRow>): Set<String> {
        class Style(var lines: Int = 0, var placed: Int = 0, var first: Double = Double.POSITIVE_INFINITY, var last: Double = Double.NEGATIVE_INFINITY)
        val styles = LinkedHashMap<String, Style>()
        for (row in rows) {
            val style = styles.getOrPut(row.style) { Style() }
            style.lines++; if (placed.containsMatchIn(row.text)) style.placed++
            style.first = min(style.first, row.start); style.last = max(style.last, row.start)
        }
        val span = rows.maxOf { it.start } - rows.minOf { it.start }
        val speech = styles.filter { (name, s) -> !notSpeechStyle.containsMatchIn(name) && s.lines >= 5 && s.placed.toDouble() / s.lines < 0.3 && s.last - s.first >= span / 3 }.keys
        if (speech.isNotEmpty()) return speech
        return setOfNotNull(styles.entries.maxByOrNull { it.value.lines - it.value.placed }?.key)
    }
    /** desktop assSpeech: an ASS file's spoken lines, each once (typesetting repeats a line in layers). */
    fun assSpeech(text: String): List<SyncCue> {
        val rows = assRows(text)
        if (rows.isEmpty()) return emptyList()
        val styles = speechStyles(rows); val seen = mutableSetOf<String>()
        return rows.filter { row ->
            styles.contains(row.style) && !placed.containsMatchIn(row.text) && row.words.isNotEmpty() && seen.add(row.start.toString() + "|" + row.end + "|" + row.words)
        }.map { SyncCue(it.start, it.end) }.sortedBy { it.start }
    }
    /** desktop spokenVtt: the spoken lines of a subtitle, from its ASS styles when it is one (20 lines at least), else by content. */
    @Throws(Throwable::class)
    fun spokenCues(content: String, extension: String): List<SyncCue> {
        if (extension.lowercase() in listOf("ass", "ssa")) assSpeech(content).takeIf { it.size >= 20 }?.let { return it }
        return dialogueCues(content, extension)
    }

    // MARK: Scoring

    /** Speech intervals, sorted and merged. */
    fun merged(intervals: List<SyncCue>): List<SyncCue> {
        val out = mutableListOf<SyncCue>()
        for (item in intervals.filter { it.end > it.start }.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && item.start <= last.end) out[out.size - 1] = SyncCue(last.start, max(last.end, item.end)) else out += item
        }
        return out
    }
    /**
     * How well a subtitle fits the reference speech: the share of its lines' time that falls on speech, with its lines
     * moved as a candidate says. The same measure for every candidate, so they can be compared.
     */
    fun fitScore(cues: List<SyncCue>, speech: List<SyncCue>, move: (Double, Double) -> Double = { time, _ -> time }): Double {
        var total = 0.0; var hit = 0.0
        for (cue in cues) {
            val from = move(cue.start, cue.start); val to = move(cue.end, cue.start)
            if (to <= from) continue
            total += to - from
            var low = 0; var high = speech.size
            while (low < high) { val mid = (low + high) ushr 1; if (speech[mid].end <= from) low = mid + 1 else high = mid }
            var i = low
            while (i < speech.size && speech[i].start < to) { hit += max(0.0, min(to, speech[i].end) - max(from, speech[i].start)); i++ }
        }
        return if (total > 0) hit / total else 0.0
    }
    /**
     * desktop keptSegments: segments a candidate may keep — each with 8 lines and 45 s of the subtitle at least, never
     * moving a line before the end of the segment before it, and, against the audio, each new segment starting at an
     * opening or ending (chapters) within 20 s. While a segment breaks a rule, the one whose going leaves the best fit is
     * let go, and neighbours within 0.3 s become one. null when nothing is left to split.
     */
    fun keptSegments(segments: List<SyncSegment>, cues: List<SyncCue>, chapters: List<SyncCue>?, score: (List<SyncSegment>) -> Double): List<SyncSegment>? {
        val edges = chapters?.flatMap { listOf(it.start, it.end) }?.filter { it.isFinite() }
        fun join(parts: List<SyncSegment>): List<SyncSegment> {
            val out = mutableListOf<SyncSegment>()
            for (segment in parts) { val last = out.lastOrNull(); if (last == null || abs(last.shift - segment.shift) > 0.3) out += segment }
            return out
        }
        fun linesOf(parts: List<SyncSegment>, i: Int) = cues.filter { it.start >= (if (i > 0) parts[i].from else Double.NEGATIVE_INFINITY) && (i + 1 >= parts.size || it.start < parts[i + 1].from) }
        fun broken(parts: List<SyncSegment>): Boolean = parts.indices.any { i ->
            val lines = linesOf(parts, i)
            if (lines.size < 8 || lines.last().start - lines.first().start < 45) return@any true
            if (i == 0) return@any false
            val before = linesOf(parts, i - 1)
            val endBefore = before.maxOf { it.end } + parts[i - 1].shift; val startHere = lines.first().start + parts[i].shift
            if (startHere < endBefore - 1) return@any true
            edges != null && !edges.any { abs(it - startHere) <= 20 || abs(it - endBefore) <= 20 }
        }
        var parts = join(segments)
        var guard = 0
        while (parts.size > 1 && broken(parts) && guard < 30) {
            val options = parts.indices.map { i -> join(if (i > 0) parts.filterIndexed { k, _ -> k != i } else listOf(parts[1].copy(from = parts[0].from)) + parts.drop(2)) }
            parts = options.maxByOrNull { score(it) } ?: break
            guard++
        }
        return if (parts.size > 1 && !broken(parts)) parts else null
    }

    // MARK: Alignment (ffsubsync's FFTAligner)

    /** Speech (or a line on screen) as +1, the rest as -1, at 100 per second (ffsubsync: 2 · bit - 1). */
    private fun signal(cues: List<SyncCue>, length: Int, scale: Double = 1.0): DoubleArray {
        val out = DoubleArray(length) { -1.0 }
        for (cue in cues) {
            val a = max(0, (cue.start * scale * RATE).roundToInt()); val b = min(length, (cue.end * scale * RATE).roundToInt())
            for (i in a until b) out[i] = 1.0
        }
        return out
    }
    private fun fft(re: DoubleArray, im: DoubleArray, invert: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var length = 2
        while (length <= n) {
            val angle = 2 * PI / length * (if (invert) 1 else -1)
            val wr = cos(angle); val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until length / 2) {
                    val a = i + k; val b = a + length / 2
                    val vr = re[b] * cr - im[b] * ci; val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi; re[a] += vr; im[a] += vi
                    val next = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = next
                }
                i += length
            }
            length = length shl 1
        }
        if (invert) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
    /** The lag (in samples, the subtitle moved later) within ±[maxLag] where Σ reference[t + lag] · subtitle[t] is largest. */
    private fun bestLag(reference: DoubleArray, subtitle: DoubleArray, maxLag: Int): Pair<Int, Double> {
        var size = 1
        while (size < reference.size + subtitle.size) size = size shl 1
        val ar = DoubleArray(size); val ai = DoubleArray(size); val br = DoubleArray(size); val bi = DoubleArray(size)
        reference.copyInto(ar); subtitle.copyInto(br)
        fft(ar, ai, false); fft(br, bi, false)
        // reference · conj(subtitle): the cross-correlation, lag k at k (k ≥ 0) and size + k (k < 0).
        for (i in 0 until size) { val r = ar[i] * br[i] + ai[i] * bi[i]; val m = ai[i] * br[i] - ar[i] * bi[i]; ar[i] = r; ai[i] = m }
        fft(ar, ai, true)
        var best = 0; var score = Double.NEGATIVE_INFINITY
        val reach = min(maxLag, size / 2 - 1)
        fun at(lag: Int) = ar[if (lag >= 0) lag else size + lag]
        for (lag in -reach..reach) {
            val value = at(lag)
            // The smaller shift wins a tie between apart peaks, as an unmoved subtitle is the likelier one.
            if (value > score + 0.5 || abs(value - score) <= 0.5 && abs(lag) < abs(best)) { score = max(score, value); best = lag }
        }
        // Lines shorter than the speech they sit in fit about as well over a stretch of shifts: its middle. Within it the
        // fit moves by tens (the signals' ends, lines without speech); leaving it costs every line's edge at once, so the
        // stretch is what stays within a twentieth of the drop just beside the best.
        val edge = score - min(at(max(-reach, best - 5)), at(min(reach, best + 5)))
        val tolerance = max(2.0, 0.05 * edge)
        var low = best; var high = best
        while (low > -reach && at(low - 1) >= score - tolerance) low--
        while (high < reach && at(high + 1) >= score - tolerance) high++
        return (low + high) / 2 to score
    }
    /** One shift (and, with [frameRates], a scale) within ±[reach] s: null when the best is at the edge of where it looked. */
    private fun align(reference: DoubleArray, cues: List<SyncCue>, reach: Double, frameRate: Boolean): Pair<Double, Double>? {
        var found: Triple<Double, Double, Double>? = null
        for (scale in if (frameRate) listOf(1.0) + frameRates else listOf(1.0)) {
            val (lag, score) = bestLag(reference, signal(cues, reference.size, scale), (reach * RATE).roundToInt())
            if (found == null || score > found.third) found = Triple(lag / RATE, scale, score)
        }
        val result = found ?: return null
        return if (abs(result.first) >= reach - 1) null else result.first to result.second
    }
    /**
     * Segments (ffsubsync's --split-penalty): the subtitle in blocks of 12 lines and a minute at least, each block's own
     * best shift within ±[reach] s, neighbours with the same shift joined. keptSegments decides what is kept.
     */
    private fun splitAlign(reference: DoubleArray, cues: List<SyncCue>, reach: Double): List<SyncSegment> {
        val blocks = mutableListOf<MutableList<SyncCue>>()
        for (cue in cues) {
            val block = blocks.lastOrNull()
            if (block == null || block.size >= 12 && cue.start - block.first().start >= 60) blocks += mutableListOf(cue) else block += cue
        }
        if (blocks.size > 1 && (blocks.last().size < 12 || blocks.last().last().start - blocks.last().first().start < 60)) { val last = blocks.removeAt(blocks.size - 1); blocks.last() += last }
        if (blocks.size < 2) return emptyList()
        val lag = (reach * RATE).roundToInt()
        val segments = mutableListOf<SyncSegment>()
        for (block in blocks) {
            // The block's own lines (its stretch of the timeline only) against the reference around it.
            val from = max(0, (block.first().start * RATE).roundToInt() - 100); val until = min(reference.size, (block.maxOf { it.end } * RATE).roundToInt() + 100)
            if (until <= from) continue
            val window = max(0, from - lag); val windowEnd = min(reference.size, until + lag)
            val local = bestLag(reference.copyOfRange(window, windowEnd), signalFor(block, window, until), lag).first / RATE
            val shift = (local * 100).roundToInt() / 100.0
            if (segments.isEmpty() || abs(shift - segments.last().shift) > 0.2) segments += SyncSegment(block.first().start, shift)
        }
        return segments
    }
    /** A block's lines as a signal over [window, until) of the timeline: ±1 inside the block's stretch, 0 elsewhere. */
    private fun signalFor(block: List<SyncCue>, window: Int, until: Int): DoubleArray {
        val from = max(window, (block.first().start * RATE).roundToInt() - 100)
        val out = DoubleArray(until - window)
        for (i in (from - window) until out.size) out[i] = -1.0
        for (cue in block) {
            val a = max(from, (cue.start * RATE).roundToInt()) - window; val b = min(until, (cue.end * RATE).roundToInt()) - window
            for (i in max(0, a) until b) out[i] = 1.0
        }
        return out
    }

    // MARK: The sync

    /**
     * timing: the subtitle's spoken lines (a translation's from the file it was made from: its Japanese lines tell
     * dialogue from captions better; the times are the same); all: every line of the file; reference: the site track's
     * spoken lines or the speech in the audio; chapters: the episode's opening and ending (AniSkip), where the audio may
     * split. Candidates as the desktop asks ffsubsync for them: one shift within 60 s, 5 minutes and the whole episode,
     * the file as it is within 5 minutes, segments within 150 s and 5 minutes, and against a site track a changed frame rate.
     */
    @Throws(Throwable::class)
    fun sync(timing: List<SyncCue>, all: List<SyncCue>, reference: List<SyncCue>, byTrack: Boolean, chapters: List<SyncCue>, duration: Double): SyncResult {
        val speech = merged(reference)
        val cues = timing.sortedBy { it.start }
        val method = if (byTrack) "track" else "audio"
        val length = ceil(listOf(duration, speech.maxOfOrNull { it.end } ?: 0.0, cues.maxOfOrNull { it.end } ?: 0.0).maxOrNull()!!) + 30
        val samples = (length * RATE).roundToInt()
        val signal = signal(speech, samples)
        val candidates = mutableListOf(SyncCandidate("none", 0.0, 1.0, null, fitScore(cues, speech)))
        fun add(kind: String, offset: Double, scale: Double, segments: List<SyncSegment>?) {
            candidates += SyncCandidate(kind, offset, scale, segments, fitScore(cues, speech, mover(offset, scale, segments)))
        }
        if (cues.isNotEmpty() && speech.isNotEmpty()) {
            for (reach in listOf(60.0, 300.0, length).filter { it <= length }.distinct()) align(signal, cues, reach, false)?.let { add("shift", it.first, 1.0, null) }
            align(signal, all.ifEmpty { cues }, min(300.0, length), false)?.let { add("shift", it.first, 1.0, null) }
            for (reach in listOf(150.0, 300.0).filter { it <= length }) {
                val global = align(signal, cues, reach, false) ?: continue
                val segments = splitAlign(signal, cues, reach)
                val kept = if (segments.size > 1) keptSegments(segments, cues, if (byTrack) null else chapters) { fitScore(cues, speech, mover(0.0, 1.0, it)) } else null
                if (kept != null && kept.size > 1) add("segments", global.first, 1.0, kept)
            }
            if (byTrack) align(signal, cues, min(150.0, length), true)?.let { add("frame-rate", it.first, it.second, null) }
        }
        val none = candidates[0]
        val ranked = candidates.drop(1).sortedByDescending { it.score }
        // Segments only when they fit clearly better than the best single shift (which is the more precise).
        val top = ranked.firstOrNull()
        val single = ranked.firstOrNull { it.segments == null }
        val chosen = if (top?.segments != null && single != null && top.score - single.score < 0.02) single else top
        val floor = if (byTrack) TRACK_FLOOR else AUDIO_FLOOR; val margin = if (byTrack) TRACK_MARGIN else AUDIO_MARGIN
        fun round(value: Double) = (value * 1000).roundToInt() / 1000.0
        fun result(offset: Double, scale: Double, segments: List<SyncSegment>?, unchanged: Boolean, uncertain: Boolean = false) =
            SyncResult(offset, scale, segments, method, unchanged, uncertain, round(none.score), chosen?.let { round(it.score) }, candidates)
        if (chosen == null || chosen.score < floor) return if (none.score >= floor) result(0.0, 1.0, null, true) else result(0.0, 1.0, null, false, uncertain = true)
        if (chosen.score < none.score + margin) return result(0.0, 1.0, null, true)
        val segments = chosen.segments?.map { SyncSegment((it.from * 100).roundToInt() / 100.0, it.shift) }
        // The shift most lines take (the one said).
        val offset = if (segments == null) chosen.offset else cues.groupingBy { shiftAt(segments, it.start) }.eachCount().maxByOrNull { it.value }?.key ?: chosen.offset
        return result(offset, if (segments != null) 1.0 else chosen.scale, segments, false)
    }

    // MARK: Speech in the audio

    /**
     * The speech in an episode's audio from its dialogue band's level every [frameSeconds] (NativeAudio's LilacSpeechLevels:
     * the centre of the mix, where dialogue sits, 300–3400 Hz, in dB): louder than the quiet around it by 8 dB and more,
     * gaps under 0.25 s joined and blips under 0.12 s left out. It stands in for WebRTC's voice detector, which the
     * desktop runs in ffsubsync's Python.
     */
    @Throws(Throwable::class)
    fun speechFromLevels(levels: FloatArray, frameSeconds: Double): List<SyncCue> {
        val count = levels.size
        if (count == 0) return emptyList()
        val window = max(1, (15 / frameSeconds).roundToInt())
        // The quiet: the 20th percentile of the levels around each frame (15 s on either side), sampled every second.
        val step = max(1, (1 / frameSeconds).roundToInt())
        val floors = DoubleArray((count + step - 1) / step) { k ->
            val center = k * step
            val slice = levels.copyOfRange(max(0, center - window), min(count, center + window + 1)).sorted()
            slice[(slice.size * 0.2).toInt().coerceAtMost(slice.size - 1)].toDouble()
        }
        val loud = levels.sorted().let { it[(it.size * 0.9).toInt().coerceAtMost(it.size - 1)].toDouble() }
        val active = BooleanArray(count) { i ->
            val floor = floors[i / step]
            levels[i] > max(floor + 8, loud - 30) && levels[i] > -70
        }
        val out = mutableListOf<SyncCue>()
        var start = -1; var lastEnd = -1
        val join = (0.25 / frameSeconds).roundToInt(); val shortest = (0.12 / frameSeconds).roundToInt()
        for (i in 0..count) {
            val on = i < count && active[i]
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                if (out.isNotEmpty() && start - lastEnd <= join) { val last = out.removeAt(out.size - 1); out += SyncCue(last.start, i * frameSeconds) }
                else out += SyncCue(start * frameSeconds, i * frameSeconds)
                lastEnd = i; start = -1
            }
        }
        return out.filter { (it.end - it.start) / frameSeconds >= shortest }
    }
    /** A level in dB for a mean square (the native side writes these; also used by the tests). */
    fun level(meanSquare: Double): Float = (10 * log10(meanSquare + 1e-10)).toFloat()
}
