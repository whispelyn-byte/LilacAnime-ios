package com.lilac.anime.shared

import kotlin.test.*
import kotlinx.serialization.json.*

// Desktop 0.5.29–0.5.32: 번역 프롬프트 and 효과음·화자 이름 빼고 번역, against the desktop source (desktop-oracle.cjs).
class DesktopPromptRulesTest {
    @Test fun userRulesReachLocalModelsThatFollowInstructionsOnly() {
        val cases = desktopOracle.list("localRules").filterIsInstance<JsonObject>()
        assertTrue(cases.size >= 12)
        for (test in cases) {
            val prompt = DesktopTranslationPrompt.request(test.text("kind"), test.text("source"), test.text("before"), "", "", test.text("extra"), test.text("custom"))
            assertEquals(test.text("prompt"), prompt.prompt, test.text("model") + " " + test.text("extra") + "/" + test.text("custom"))
        }
    }
    @Test fun userRulesReplaceOrFollowTheApiTranslationRules() {
        for (test in desktopOracle.list("cloudRules").filterIsInstance<JsonObject>()) {
            val wrapped = test.text("wrapped") == "true"
            assertEquals(test.text("expected"), DesktopCloudPrompt.build("", wrapped, test.text("extra"), test.text("custom")))
        }
        assertEquals(DesktopCloudPrompt.build("", false), DesktopCloudPrompt.build("", false, " ", ""))
        assertTrue(DesktopCloudPrompt.build("Anime: 작품", true, "EXTRA", "").contains("Anime: 작품\n\nFORMAT"))
        assertTrue(DesktopCloudPrompt.rules.startsWith("- Read the lines as a scene"))
    }
    @Test fun captionLabelsSpeakersAndVocalSoundsMatchDesktop() {
        val cases = desktopOracle.list("dropCaptions").filterIsInstance<JsonObject>()
        assertTrue(cases.size >= 30)
        for (test in cases) assertEquals(test.text("expected"), DesktopCaptions.drop(test.text("source")), test.text("source"))
    }
    @Test fun rulesArePartOfTheCloudCacheIdentity() {
        val plain = TranslationConfig("gemini", "key", "", "international", "")
        assertEquals("", plain.rulesExtra); assertEquals("", plain.rulesCustom)
        assertNotEquals(plain, plain.copy(rulesExtra = "의역"))
    }
}
