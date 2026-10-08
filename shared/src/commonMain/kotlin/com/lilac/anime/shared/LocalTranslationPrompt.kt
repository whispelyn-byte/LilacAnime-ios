package com.lilac.anime.shared
import com.lilac.anime.shared.ported.*
object LocalTranslationPrompt {
    fun build(modelName: String, template: String, text: String, before: String, after: String, thinking: String): String {
        val model = LocalAiModel(modelName, "", modelName, modelName, 0, null, null, null, "", null)
        val user = template.replace("{source_text}", text).replace("{context}", before).replace("{future_context}", after)
        val prepared = LocalAiAdapterRegistry.resolve(model).prepare(LocalAiPromptRequest(
            "Translate Japanese anime subtitles into natural Korean. Preserve names and line breaks. Return only the translated subtitle, without explanation.",
            user, null, false, true, thinking))
        return prepared.messages.joinToString("\n\n") { it.second }
    }
    fun clean(text: String): String {
        var result = text.replace(Regex("(?is)<think>.*?</think>"), "").replace(Regex("(?is)^.*?</think>"), "")
            .replace(Regex("(?is)<\\|channel>thought.*?<channel\\|>"), "")
        if (result.contains("<|channel>final")) result = result.substringAfterLast("<|channel>final")
        return result.replace(Regex("<\\|(?:im_end|eot_id)\\|>|<turn\\|>|<eos>"), "").trim().trim('"')
    }
}
