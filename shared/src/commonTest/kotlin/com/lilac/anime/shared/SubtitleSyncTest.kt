package com.lilac.anime.shared

import kotlin.math.abs
import kotlin.test.*
import kotlinx.serialization.json.*

// Desktop 0.5.29–0.5.36 자막 싱크 맞추기: subtitle-sync.cjs's own functions against the desktop source (desktop-oracle.cjs),
// then the alignment that stands in for ffsubsync, on lines whose right answer is known.
class SubtitleSyncTest {
    private fun JsonElement.number() = jsonPrimitive.double
    private fun cues(array: JsonArray) = array.map { SyncCue(it.jsonArray[0].number(), it.jsonArray[1].number()) }
    private fun segments(value: JsonElement?) = (value as? JsonArray)?.map { SyncSegment(it.jsonObject["from"]!!.number(), it.jsonObject["shift"]!!.number()) }

    @Test fun retimingMatchesDesktopForOneShiftScaleAndSegments() {
        for (test in desktopOracle.list("syncRetime").filterIsInstance<JsonObject>()) {
            val segments = segments(test["segments"])
            val offset = test["offset"]!!.number(); val scale = test["scale"]!!.number()
            val actual = if (test.text("kind") == "vtt") SubtitleSync.retimeVtt(test.text("text"), offset, scale, segments) else SubtitleSync.retimeAss(test.text("text"), offset, scale, segments)
            assertEquals(test.text("expected"), actual)
        }
        // An SRT keeps its comma; a format without timing lines is retimed as a VTT.
        assertEquals("1\n00:00:01,500 --> 00:00:02,000\n가\n", SubtitleSync.retime("1\n00:00:04,500 --> 00:00:05,000\n가\n", "srt", -3.0, 1.0, emptyList()))
        assertEquals("vtt", SubtitleSync.retimedExtension("smi")); assertEquals("ass", SubtitleSync.retimedExtension("ASS"))
    }
    @Test fun speechLinesMatchDesktopDialogueAndAssStyles() {
        for (test in desktopOracle.list("syncDialogue").filterIsInstance<JsonObject>())
            assertEquals(cues(test["expected"]!!.jsonArray), SubtitleSync.dialogueCues(test.text("text"), "vtt"))
        for (test in desktopOracle.list("syncSpeech").filterIsInstance<JsonObject>())
            assertEquals(cues(test["expected"]!!.jsonArray), SubtitleSync.assSpeech(test.text("text")))
    }
    @Test fun fitScoreAndKeptSegmentsMatchDesktop() {
        for (test in desktopOracle.list("syncFit").filterIsInstance<JsonObject>()) {
            val speech = SubtitleSync.merged(cues(test["speech"]!!.jsonArray))
            assertEquals(cues(test["merged"]!!.jsonArray), speech)
            val score = SubtitleSync.fitScore(cues(test["cues"]!!.jsonArray), speech, SubtitleSync.mover(test["offset"]!!.number(), test["scale"]!!.number(), null))
            assertEquals(test["expected"]!!.number(), score, 1e-9)
        }
        val kept = desktopOracle["syncKept"]!!.jsonObject
        val lines = cues(kept["cues"]!!.jsonArray); val speech = cues(kept["speech"]!!.jsonArray)
        for (test in kept["cases"]!!.jsonArray.map { it.jsonObject }) {
            val chapters = (test["chapters"] as? JsonArray)?.map { SyncCue(it.jsonObject["startTime"]!!.number(), it.jsonObject["endTime"]!!.number()) }
            val actual = SubtitleSync.keptSegments(segments(test["segments"])!!, lines, chapters) { SubtitleSync.fitScore(lines, speech, SubtitleSync.mover(0.0, 1.0, it)) }
            assertEquals(segments(test["expected"]), actual)
        }
        assertEquals(-1.0, SubtitleSync.shiftAt(listOf(SyncSegment(0.0, -1.0), SyncSegment(90.0, 89.25)), 89.9))
        assertEquals(89.25, SubtitleSync.shiftAt(listOf(SyncSegment(0.0, -1.0), SyncSegment(90.0, 89.25)), 90.0))
    }

    /** A fansub's ASS as files come (BOM, CRLF, styles, comments, layered signs, karaoke, a short opening style). */
    private fun fansubAss(): String {
        val head = "﻿[Script Info]\r\nScriptType: v4.00+\r\nPlayResX: 1920\r\n\r\n[V4+ Styles]\r\nFormat: Name, Fontname, Fontsize\r\n" +
            "Style: Default,Arial,60\r\nStyle: Signs,Arial,40\r\n\r\n[Events]\r\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n"
        val rows = (0 until 120).flatMap { i ->
            val t = 30.0 + i * 9.5
            listOfNotNull(
                "Dialogue: 0,${SubtitleSync.assStamp(t)},${SubtitleSync.assStamp(t + 2.2)},Default,Sota,0,0,0,,{\\i1}대사 $i, 쉼표도\\N둘째 줄",
                if (i % 10 == 0) "Dialogue: 1,${SubtitleSync.assStamp(t)},${SubtitleSync.assStamp(t + 3)},Signs,,0,0,0,,{\\pos(960,80)}간판 $i" else null,
                if (i % 15 == 0) "Comment: 0,${SubtitleSync.assStamp(t)},${SubtitleSync.assStamp(t + 1)},Default,,0,0,0,,메모" else null,
                if (i < 9) "Dialogue: 0,${SubtitleSync.assStamp(t + 1)},${SubtitleSync.assStamp(t + 3)},OP,,0,0,0,karaoke,{\\k20}la {\\k30}la" else null)
        }
        return head + rows.joinToString("\r\n") + "\r\n"
    }
    @Test fun aFansubAssGivesItsSpokenLinesAndRetimes() {
        val ass = fansubAss()
        val spoken = SubtitleSync.spokenCues(ass, "ass")
        assertEquals(120, spoken.size)
        assertEquals(30.0, spoken.first().start); assertEquals(32.2, spoken.first().end, 1e-9)
        assertTrue(SubtitleSync.allCues(ass, "ass").size >= 120)
        val moved = SubtitleSync.retime(ass, "ass", -1.5, 1.0, emptyList())
        assertTrue(moved.contains("Dialogue: 0,0:00:28.50,0:00:30.70,Default,Sota,0,0,0,,{\\i1}대사 0, 쉼표도\\N둘째 줄"), moved.take(600))
        // Odd files: no events, a format line only, rows with missing fields.
        assertEquals(emptyList(), SubtitleSync.assSpeech("[Script Info]\nTitle: x\n"))
        assertEquals(emptyList(), SubtitleSync.assSpeech("[Events]\nFormat: Layer, Start, End, Style, Text\n"))
        assertEquals(1, SubtitleSync.assSpeech("[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:03.00,0:00:04.00,Default,말\nDialogue: 0,0:00:05.00,0:00:06.00\n").size)
    }

    /** Speech-like lines at uneven times over [length] seconds (an even rhythm would fit any shift of its step). */
    private fun lines(length: Double, seed: Int = 11): List<SyncCue> {
        var value = seed.toLong(); fun random() = ((value * 16807) % 2147483647).also { value = it } / 2147483647.0
        val out = mutableListOf<SyncCue>(); var t = 1.0
        while (t < length) { out += SyncCue(t, t + 1 + random() * 2); t += 2 + random() * 6 }
        return out
    }
    @Test fun aLateSubtitleIsMovedBackByTheSiteTrack() {
        val truth = lines(1400.0)
        val late = truth.map { SyncCue(it.start + 3.2, it.end + 3.2) }
        val result = SubtitleSync.sync(late, late, truth, byTrack = true, chapters = emptyList(), duration = 1420.0)
        assertFalse(result.unchanged); assertFalse(result.uncertain)
        assertEquals("track", result.method)
        assertTrue(abs(result.offset + 3.2) <= 0.02, "offset ${result.offset}")
        assertEquals(1.0, result.scale); assertNull(result.segments)
        assertTrue(result.chosen!! > 0.95 && result.none < 0.8, "${result.none} → ${result.chosen}")
    }
    @Test fun aSubtitleAlreadyRightIsLeftAsItIs() {
        val truth = lines(1400.0)
        val result = SubtitleSync.sync(truth, truth, truth, byTrack = true, chapters = emptyList(), duration = 1420.0)
        assertTrue(result.unchanged); assertEquals(0.0, result.offset)
    }
    @Test fun anotherEpisodesSubtitleIsLeftAsItIs() {
        val result = SubtitleSync.sync(lines(1400.0, seed = 5), lines(1400.0, seed = 5), lines(1400.0, seed = 99), byTrack = true, chapters = emptyList(), duration = 1420.0)
        assertTrue(result.unchanged || result.uncertain, "${result.none} → ${result.chosen}")
        assertEquals(0.0, result.offset)
    }
    @Test fun anOpeningTheSubtitleHasNotIsFoundAsSegments() {
        // An 89 s opening in the video after 2 minutes (Toradora! 1's Netflix-made subtitle), and 1 s early before it.
        val subtitle = lines(1300.0)
        val video = subtitle.map { if (it.start < 120) SyncCue(it.start - 1, it.end - 1) else SyncCue(it.start + 89, it.end + 89) }
        val result = SubtitleSync.sync(subtitle, subtitle, video, byTrack = true, chapters = emptyList(), duration = 1420.0)
        val segments = assertNotNull(result.segments, "${result.tried}")
        assertEquals(2, segments.size)
        assertTrue(abs(segments[0].shift + 1) <= 0.05 && abs(segments[1].shift - 89) <= 0.05, "$segments")
        assertTrue(abs(segments[1].from - 120) <= 70, "$segments")
        assertEquals(segments[1].shift, result.offset, "most lines take the second shift")
    }
    @Test fun speechIsFoundInTheDialogueBandLevels() {
        val frames = 6000 // 60 s at 10 ms
        val talk = listOf(5.0 to 7.5, 12.0 to 13.2, 30.0 to 34.0, 50.0 to 51.0)
        val levels = FloatArray(frames) { i ->
            val t = i / 100.0
            val noise = ((i * 7919) % 13) * 0.4f
            if (talk.any { t >= it.first && t < it.second }) -22f + noise else -58f + noise
        }
        val speech = SubtitleSync.speechFromLevels(levels, 0.01)
        assertEquals(talk.size, speech.size, "$speech")
        for ((found, expected) in speech.zip(talk)) assertTrue(abs(found.start - expected.first) <= 0.02 && abs(found.end - expected.second) <= 0.02, "$found")
        assertEquals(-100f, SubtitleSync.level(0.0), 0.01f)
    }
    @Test fun aLateSubtitleIsMovedBackByTheAudio() {
        val truth = lines(1400.0)
        // The audio's speech is found a little wider than the lines and with a few misses.
        val speech = truth.filterIndexed { i, _ -> i % 9 != 4 }.map { SyncCue(it.start - 0.15, it.end + 0.2) }
        val late = truth.map { SyncCue(it.start + 2.6, it.end + 2.6) }
        val result = SubtitleSync.sync(late, late, speech, byTrack = false, chapters = emptyList(), duration = 1420.0)
        assertEquals("audio", result.method); assertFalse(result.unchanged)
        assertTrue(abs(result.offset + 2.6) <= 0.1, "offset ${result.offset}")
    }
}
