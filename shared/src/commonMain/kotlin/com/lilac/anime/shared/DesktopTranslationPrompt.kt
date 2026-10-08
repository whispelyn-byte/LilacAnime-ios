package com.lilac.anime.shared

data class DesktopModelPrompt(val prompt: String, val temperature: Double, val topP: Double, val topK: Int, val repetition: Double)
object DesktopTranslationPrompt {
    fun build(modelName: String, text: String, before: String, terms: String, cast: String): DesktopModelPrompt {
        val name = modelName.lowercase()
        val reference = if (terms.isBlank()) "" else "Reference the following translations:\n" + terms + "\n\n"
        if (name.contains("ja-ko-vn") || name.contains("jako"))
            return DesktopModelPrompt(terms + "\u001e" + text, 0.1, 0.9, 40, 1.05)
        if (name.contains("hy-mt2")) {
            val moe = name.contains("a3b") || name.contains("30b")
            val prompt = if (!moe && before.isNotBlank()) "[Background Information]\n" + before + "\n\n" + reference + "Please translate the following text into Korean, taking the provided background information into consideration.\n\n[Source Text]\n" + text
                else reference + "Translate the following text into Korean. Note that you should only output the translated result without any additional explanation:\n\n" + text
            return DesktopModelPrompt(prompt, 0.7, if (moe) 1.0 else 0.6, if (moe) 0 else 20, if (moe) 1.0 else 1.05)
        }
        if (name.contains("gemma") || name.contains("aya") || name.contains("exaone") || name.contains("qwen3.")) {
            val system = "You translate Japanese or English anime subtitles into natural spoken Korean, keeping each speaker's tone. Family terms follow the speaker: 형/누나 for a boy, 오빠/언니 for a girl. Translate only the requested line; answer with Korean only." +
                if (cast.isBlank()) "" else "\nMain characters (names and gender):\n" + cast
            val user = reference + (if (before.isBlank()) "" else "Previous lines (context only):\n" + before + "\n\n") + "Translate this line:\n" + text
            return DesktopModelPrompt(system + "\u001e" + user, 0.3, 0.95, 64, 1.05)
        }
        return DesktopModelPrompt(reference + "Translate Japanese anime subtitles into Korean. Return only the translated line:\n\n" + text, 0.7, 0.6, 20, 1.05)
    }
}
