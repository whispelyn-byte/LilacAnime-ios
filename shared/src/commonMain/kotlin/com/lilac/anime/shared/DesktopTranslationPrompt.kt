package com.lilac.anime.shared

data class DesktopModelPrompt(val prompt: String, val temperature: Double, val topP: Double, val topK: Int, val repetition: Double)
object DesktopTranslationPrompt {
    fun kind(modelName: String, template: String): String = when {
        Regex("ja-ko-vn|jako", RegexOption.IGNORE_CASE).containsMatchIn(modelName) || Regex("일한 번역가|고유명사 및 용어 규칙").containsMatchIn(template) -> "jako"
        Regex("gemma-?4", RegexOption.IGNORE_CASE).containsMatchIn(modelName) || template.contains("<|turn>") -> "gemma"
        Regex("exaone|qwen3\\.[5-9]|aya[-_ ]?(?:expanse|\\d)", RegexOption.IGNORE_CASE).containsMatchIn(modelName) -> "general"
        modelName.contains("hy-mt2", true) -> if (Regex("a3b|30b", RegexOption.IGNORE_CASE).containsMatchIn(modelName)) "hy-mt2-moe" else "hy-mt2"
        modelName.contains("hy-mt", true) -> "hy-mt"
        else -> "chat"
    }
    fun build(modelName: String, text: String, before: String, terms: String, cast: String): DesktopModelPrompt = request(kind(modelName, ""), text, before, terms, cast)
    // U+001E separates the system and user messages for the native chat adapter.
    fun request(kind: String, text: String, before: String, terms: String, cast: String): DesktopModelPrompt {
        val entries = terms.lines().filter { it.contains('=') }.map { it.substringBefore('=') to it.substringAfter('=') }
        fun references(separator: String) = entries.joinToString("\n") { it.first + separator + it.second }
        val instruction = "Translate the following segment into Korean, without additional explanation."
        if (kind == "jako") return DesktopModelPrompt((if (entries.isEmpty()) "" else entries.joinToString(",") { it.first + "=" + it.second } + "\u001e") + text, 0.1, 0.9, 40, 1.05)
        if (kind == "hy-mt2" || kind == "hy-mt2-moe") {
            val moe = kind == "hy-mt2-moe"
            val reference = if (entries.isEmpty()) "" else "Reference the following translations:\n" + references(" translates to ") + "\n\n"
            val prompt = if (!moe && before.isNotBlank()) "[Background Information]\n" + before + "\n\n" + reference + "Please translate the following text into Korean, taking the provided background information into consideration.\n\n[Source Text]\n" + text
                else reference + "Translate the following text into Korean. Note that you should only output the translated result without any additional explanation:\n\n" + text
            return DesktopModelPrompt(prompt, 0.7, if (moe) 1.0 else 0.6, if (moe) 0 else 20, if (moe) 1.0 else 1.05)
        }
        if (kind == "gemma" || kind == "general") {
            val system = listOfNotNull(
                "You translate Japanese anime subtitles into natural spoken Korean, keeping each speaker's tone (반말 or 존댓말 as the scene calls for).",
                "Words for family follow the speaker: お兄ちゃん / 兄さん → 형 from a boy, 오빠 from a girl; お姉ちゃん / 姉さん → 누나 from a boy, 언니 from a girl.",
                "A speaker's name or a sound in brackets at the start stays in brackets, translated: （創太） → (소타), （ため息） → (한숨). Keep the line breaks.",
                cast.takeIf { it.isNotEmpty() }?.let { "Main characters (Japanese name = Korean spelling, gender):\n" + it },
                "Answer with the Korean line only: no notes, quotes or romanization."
            ).joinToString("\n")
            val user = listOfNotNull(
                if (entries.isEmpty()) null else "Names and terms (use these Korean spellings):\n" + references(" = "),
                before.takeIf { it.isNotEmpty() }?.let { "Previous lines (context only, do not translate them):\n" + it },
                "Translate this line:\n" + text
            ).joinToString("\n\n")
            return DesktopModelPrompt(system + "\u001e" + user, 0.3, 0.95, 64, 1.0)
        }
        val reference = if (entries.isEmpty()) "" else if (kind == "hy-mt") "参考下面的翻译：\n" + references(" 翻译成 ") + "\n\n"
            else "This is a line from a Japanese anime. Use these Korean translations:\n" + references(" = ") + "\n\n"
        return DesktopModelPrompt(reference + instruction + "\n\n" + text, 0.7, if (kind == "hy-mt") 0.6 else 0.8, 20, 1.05)
    }
    fun clean(output: String, original: String): String {
        var value = output.replace(Regex("<\\|[a-z_]+\\|>", RegexOption.IGNORE_CASE), "").replace(Regex("\\r\\n?"), "\n").trim()
            .replace(Regex("^<target>|</target>$"), "").trim()
            .replace(Regex("^```(?:text|plaintext|korean|ko)?\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("\\s*```$"), "").trim()
        Regex("<target>([\\s\\S]*?)</target>", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { value = it }
        return value.split('\n').map { it.trimEnd() }.filter { it.isNotEmpty() }.takeLast(original.split('\n').count { it.isNotEmpty() }.coerceAtLeast(1)).joinToString("\n")
    }
    fun needsRetry(text: String): Boolean = text.isEmpty() || Regex("[぀-ゟ゠-ヺヽ-ヿ]").containsMatchIn(text)
    fun maxTokens(source: String): Int = (48 + source.length * 3).coerceAtMost(512)
}
