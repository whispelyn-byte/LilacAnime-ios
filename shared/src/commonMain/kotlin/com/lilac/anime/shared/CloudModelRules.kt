package com.lilac.anime.shared

object CloudModelRules {
    private fun version(name: String) = Regex("\\d+(?:\\.\\d+)?").find(name)?.value?.toDoubleOrNull() ?: 0.0
    fun sorted(provider: String, names: List<String>): List<String> = names.distinct().filter { name ->
        when (provider) {
            "gemini" -> name.startsWith("gemini-") && !Regex("image|tts|audio|live|embedding|robotics|computer-use|native|exp", RegexOption.IGNORE_CASE).containsMatchIn(name)
            "openai" -> Regex("^(gpt-\\d|o\\d)").containsMatchIn(name) && !Regex("audio|realtime|image|tts|transcribe|search|embedding|moderation|codex|instruct|oss|-\\d{4}-\\d{2}-\\d{2}$", RegexOption.IGNORE_CASE).containsMatchIn(name)
            "qwen" -> name.startsWith("qw", true) && !Regex("vl|audio|omni|tts|asr|coder|math|embedding|image|-mt-|ocr|realtime|deep-research", RegexOption.IGNORE_CASE).containsMatchIn(name)
            else -> true
        }
    }.sortedWith(compareByDescending<String> { version(it) }.thenBy { it.length }.thenBy { it })
    fun default(provider: String, names: List<String>) = when (provider) {
        "gemini" -> names.firstOrNull { it.contains("flash") && !it.contains("lite") && !it.contains("preview") } ?: names.firstOrNull { it.contains("flash") } ?: "gemini-2.5-flash"
        "openai" -> names.firstOrNull { it.contains("mini") } ?: "gpt-4.1-mini"
        "qwen" -> names.firstOrNull { it == "qwen-plus" } ?: names.firstOrNull { it.contains("plus") } ?: "qwen-plus"
        else -> ""
    }
    fun chain(provider: String, selected: String, names: List<String>): List<String> {
        fun rank(name: String): Int = when (provider) {
            "gemini" -> if (!name.contains("flash")) -1 else (if (name.contains("lite")) 2 else 0) + (if (name.contains("preview")) 1 else 0)
            "openai" -> if (name.contains("mini")) 0 else if (name.contains("nano")) 1 else -1
            "qwen" -> if (name.contains("plus")) 0 else if (name.contains("flash")) 2 else if (name.contains("turbo")) 4 else -1
            else -> -1
        }
        return (listOf(selected) + names.filter { rank(it) >= 0 }.sortedBy(::rank)).filter(String::isNotBlank).distinct()
    }
}
