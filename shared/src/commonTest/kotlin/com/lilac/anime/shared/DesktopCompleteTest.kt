package com.lilac.anime.shared
import kotlin.test.*
class DesktopCompleteTest {
    @Test fun cloudDefaultsUseStableFlashAndOnlySmallFallbackModels() {
        val models = CloudModelRules.sorted("gemini", listOf("gemini-3-flash-preview", "gemini-2.5-flash", "gemini-3-image", "gemini-2.5-pro"))
        assertEquals("gemini-2.5-flash", CloudModelRules.default("gemini", models))
        assertFalse(CloudModelRules.chain("gemini", "gemini-2.5-flash", models).contains("gemini-2.5-pro"))
        assertEquals(listOf("gpt-4.1-mini", "gpt-5-mini", "gpt-4.1-nano"), CloudModelRules.chain("openai", "gpt-4.1-mini", listOf("gpt-5", "gpt-5-mini", "gpt-4.1-nano")))
    }

    private val boy = AnimeCharacter("Sota Hori", "堀 創太", "Sota", "Hori", "Male")
    private val girl = AnimeCharacter("Kyoko Hori", "堀 京子", "Kyoko", "Hori", "Female")
    @Test fun fullCastNameAndSingleCharacterBoundaries() {
        assertEquals("시이나 마히루", AnimeGlossary.romaji("Shiina Mahiru"))
        assertEquals("핫토리", AnimeGlossary.romaji("Hattori"))
        assertEquals("", AnimeGlossary.romaji("Lelouch"))
        val cast = listOf(AnimeCharacter("Amane Fujimiya", "藤宮周", "Amane", "Fujimiya", "Male"))
        assertTrue(AnimeGlossary.hints("周くん", cast, "").contains("周 = 아마네"))
        assertFalse(AnimeGlossary.hints("一週間", cast, "").contains("周 ="))
    }
    @Test fun siblingUsesTaggedSpeakerAndDoesNotGuessAmbiguousSurname() {
        assertEquals("お姉ちゃん=누나", AnimeGlossary.speakerTerms("(創太) お姉ちゃん", listOf(boy, girl)))
        assertEquals("お兄ちゃん=오빠", AnimeGlossary.speakerTerms("(京子) お兄ちゃん", listOf(boy, girl)))
        assertEquals("", AnimeGlossary.speakerTerms("(堀) お兄ちゃん", listOf(boy, girl)))
        assertEquals("", AnimeGlossary.speakerTerms("お兄ちゃん", listOf(boy, girl)))
    }
    @Test fun seasonDisambiguationIsPreserved() {
        assertEquals("진격의 거인 2기", DesktopTitleRules.seasonal("진격의 거인 (애니메이션)", "Attack on Titan 2nd Season"))
        assertEquals("진격의 거인 2기", DesktopTitleRules.seasonal("진격의 거인 2기", "Attack on Titan Season 2"))
    }
    @Test fun smallHyMtHasContextAndMoeDoesNotTranslateContext() {
        assertTrue(DesktopTranslationPrompt.build("Hy-MT2-1.8B", "line", "before", "", "").prompt.contains("[Background Information]"))
        assertFalse(DesktopTranslationPrompt.build("Hy-MT2-30B-A3B", "line", "before", "", "").prompt.contains("before"))
        assertEquals(0, DesktopTranslationPrompt.build("Hy-MT2-30B-A3B", "line", "", "", "").topK)
    }
}
