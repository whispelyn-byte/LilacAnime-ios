package com.lilac.anime.shared

object CloudModelRules {
    const val CLAUDE_DEFAULT = "claude-opus-5-5"
    /** The family a Claude model belongs to (opus, sonnet, haiku, fable): a used-up model hands over to its own kind. */
    fun claudeFamily(model: String) = Regex("^claude-(?:\\d+(?:-\\d+)*-)?([a-z]+)").find(model)?.groupValues?.get(1).orEmpty()
    private fun version(name: String) = Regex("\\d+(?:\\.\\d+)?").find(name)?.value?.toDoubleOrNull() ?: 0.0
    fun sorted(provider: String, names: List<String>): List<String> = names.distinct().filter { name ->
        when (provider) {
            "gemini" -> Regex("^gemini-\\d").containsMatchIn(name) && !Regex("image|tts|audio|live|embedding|robotics|computer-use|native|exp", RegexOption.IGNORE_CASE).containsMatchIn(name)
            "openai" -> Regex("^(gpt-\\d|o\\d)").containsMatchIn(name) && !Regex("audio|realtime|image|tts|transcribe|search|embedding|moderation|codex|instruct|oss|-\\d{4}-\\d{2}-\\d{2}$", RegexOption.IGNORE_CASE).containsMatchIn(name)
            "qwen" -> name.startsWith("qw", true) && !Regex("vl|audio|omni|tts|asr|coder|math|embedding|image|-mt-|ocr|realtime|deep-research", RegexOption.IGNORE_CASE).containsMatchIn(name)
            else -> true
        }
    }.let { if (provider == "qwen") it.sorted() else if (provider == "claude") it else it.sortedWith(compareByDescending<String> { version(it) }.thenBy { it.length }.thenBy { it }) }
    fun default(provider: String, names: List<String>) = when (provider) {
        "gemini" -> names.firstOrNull { it.contains("flash") && !it.contains("lite") && !it.contains("preview") } ?: names.firstOrNull { it.contains("flash") } ?: names.firstOrNull() ?: "gemini-flash-latest"
        "openai" -> names.firstOrNull { it.endsWith("mini") } ?: names.firstOrNull() ?: "gpt-4.1-mini"
        "qwen" -> names.firstOrNull { it == "qwen-plus" } ?: names.firstOrNull { it.contains("plus") } ?: names.firstOrNull() ?: "qwen-plus"
        // Every model the key lists (newest first, as the API gives them); the latest Opus when it is there.
        "claude" -> if (CLAUDE_DEFAULT in names) CLAUDE_DEFAULT else names.firstOrNull() ?: CLAUDE_DEFAULT
        else -> ""
    }
    fun chain(provider: String, selected: String, names: List<String>): List<String> {
        fun rank(name: String): Int = when (provider) {
            "gemini" -> if (!name.contains("flash") || Regex("transcribe|customtools|image|tts|live|audio", RegexOption.IGNORE_CASE).containsMatchIn(name)) -1 else (if (name.contains("lite")) 2 else 0) + (if (name.contains("preview")) 1 else 0)
            "openai" -> if (name.contains("mini")) 0 else if (name.contains("nano")) 1 else -1
            "qwen" -> {
                val kind = if (name.contains("plus")) 0 else if (name.contains("flash")) 2 else if (name.contains("turbo")) 4 else -1
                if (kind < 0) -1 else kind + if (Regex("\\d{4}-?\\d{2}-?\\d{2}|\\d{4}$").containsMatchIn(name)) 1 else 0
            }
            "claude" -> if (claudeFamily(name) == claudeFamily(selected)) 0 else -1
            else -> -1
        }
        return (listOf(selected) + names.filter { rank(it) >= 0 }.sortedBy(::rank)).filter(String::isNotBlank).distinct()
    }
}
