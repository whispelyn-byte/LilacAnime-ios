package com.lilac.anime.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranslatedSubtitleStyleTest {
    private val ass = """
        [Script Info]
        ScriptType: v4.00+
        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,SourceFancyFont,70,&H0000FFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,8,10,10,10,1
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\pos(50,50)\fnSourceFancyFont}こんにちは
        Dialogue: 0,0:00:02.00,0:00:04.00,Default,,0,0,0,,またね
    """.trimIndent()

    @Test fun translatedAssUsesPlainCuesAndPreservesOverlap() {
        val output = SubtitleTools.translatedVtt(ass, "ass", listOf("안녕\n하세요", "다음에 봐요"))
        assertTrue(output.startsWith("WEBVTT\n"))
        assertFalse(output.contains("SourceFancyFont"))
        assertFalse(output.contains("\\pos"))
        val cues = SubtitleTools.cues(output, "vtt")
        assertEquals(2, cues.size)
        assertEquals(1.0, cues[0].startSeconds)
        assertEquals(3.0, cues[0].endSeconds)
        assertEquals(2.0, cues[1].startSeconds)
        assertTrue(output.contains("안녕\n하세요"))
    }
    @Test fun translatedMarkupIsEscapedAndMissingLinesRejected() {
        val output = SubtitleTools.translatedVtt(ass, "ass", listOf("<b>문자 & 설명</b>", "끝"))
        assertTrue(output.contains("&lt;b&gt;문자 &amp; 설명&lt;/b&gt;"))
        assertFailsWith<IllegalArgumentException> { SubtitleTools.translatedVtt(ass, "ass", listOf("누락")) }
    }
    @Test fun escapedLineBreaksBecomeRealLines() {
        val srt = "1\n00:00:01,000 --> 00:00:03,000\n一行目\n二行目\n\n2\n00:00:04,000 --> 00:00:05,000\nまた\n"
        val output = SubtitleTools.translatedVtt(srt, "srt", listOf("첫 줄\\n둘째 줄", "다시\\N한 번\\h!"))
        assertFalse(output.contains("\\n")); assertFalse(output.contains("\\N")); assertFalse(output.contains("\\h"))
        assertTrue(output.contains("첫 줄\n둘째 줄"))
        assertTrue(output.contains("다시\n한 번 !"))
    }
    @Test fun modelsGetRealLineBreaksAsDesktop() {
        val srt = "1\n00:00:01,000 --> 00:00:03,000\n一行目\n<i>二行目</i>\n"
        assertEquals(listOf("一行目\n二行目"), SubtitleTools.lines(srt, "srt"))
        val twoLines = ass.replace("またね", "{\\an8}また\\Nね\\hね")
        assertEquals(listOf("こんにちは", "また\nね ね"), SubtitleTools.lines(twoLines, "ass"))
    }
    @Test fun sameTimingLinesBecomeOneCue() {
        val twoSpeakers = ass + "\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,さようなら"
        val output = SubtitleTools.translatedVtt(twoSpeakers, "ass", listOf("안녕", "다음에 봐요", "잘 가"))
        val cues = SubtitleTools.cues(output, "vtt")
        assertEquals(2, cues.size)
        assertTrue(output.contains("00:00:01.000 --> 00:00:03.000\n안녕\n잘 가\n\n"))
        assertEquals(2.0, cues[1].startSeconds)
    }
    @Test fun discardedBilingualCueDoesNotLeaveAnEmptyVttCue() {
        val output = SubtitleTools.translatedVtt(ass, "ass", listOf("", "남은 자막\n\n둘째 줄"))
        val cues = SubtitleTools.cues(output, "vtt")
        assertEquals(1, cues.size)
        assertEquals(2.0, cues.single().startSeconds)
        assertTrue(output.contains("남은 자막\n둘째 줄"))
    }
}
