package com.lilac.anime.shared.ported

import com.lilac.anime.shared.*
import com.lilac.anime.shared.compat.*
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import kotlin.math.min


internal class TranslationFormatException(message: String) : IllegalStateException(message)

internal object CloudTranslationText {
    fun jsonInput(lines: List<String>, wrapped: Boolean): String {
        val items = JSONArray().apply { lines.forEachIndexed { index, text -> put(JSONObject().put("i", index).put("t", text)) } }
        return if (wrapped) JSONObject().put("lines", items).toString() else items.toString()
    }
    fun parseDesktop(text: String, original: List<String>): List<String> = try {
        parseDesktopResponse(text, original)
    } catch (error: Exception) {
        throw TranslationFormatException(error.message ?: "번역 응답 형식을 읽을 수 없습니다.")
    }
    private fun parseDesktopResponse(text: String, original: List<String>): List<String> {
        val clean = text.replace(Regex("^```(?:json)?\\s*|\\s*```$"), "").trim()
        if (!clean.startsWith("{") && !clean.startsWith("[")) return parseMarked(text, original)
        val values = if (clean.startsWith("{")) JSONObject(clean).optJSONArray("lines") ?: error("번역 결과에 lines가 없습니다.") else JSONArray(clean)
        val result = MutableList(original.size) { "" }
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val id = item.optInt("i", -1)
            if (id in result.indices && result[id].isEmpty()) result[id] = item.optString("t").trim()
        }
        return result
    }
    fun markedInput(lines: List<String>): String = lines.mapIndexed { index, line -> "<LILAC_${index + 1}> $line" }.joinToString("\n")

    fun parseMarked(text: String, original: List<String>): List<String> {
        if (original.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()

        // The desktop port asks OpenAI/Qwen for structured JSON when the model
        // supports it. Accept that response here too, while retaining the marker
        // format as the universal fallback for Gemini/older models.
        parseJsonLines(normalized, original)?.let { return it }
        val matches = Regex("<LILAC_(\\d+)>").findAll(normalized).toList()
        if (matches.isEmpty()) {
            if (original.size == 1 && normalized.isNotBlank()) return listOf(stripFences(normalized))
            error("API 응답에서 자막 구분 표식을 찾지 못했습니다.")
        }
        val result = MutableList(original.size) { "" }
        matches.forEachIndexed { matchIndex, match ->
            val lineIndex = match.groupValues[1].toIntOrNull()?.minus(1) ?: return@forEachIndexed
            if (lineIndex !in result.indices) return@forEachIndexed
            val start = match.range.last + 1
            val end = matches.getOrNull(matchIndex + 1)?.range?.first ?: normalized.length
            result[lineIndex] = stripFences(normalized.substring(start, end).trim().removePrefix(":" ).trim())
        }
        // Keep valid IDs and let the scheduler retry only missing lines.
        return result
    }

    private fun parseJsonLines(text: String, original: List<String>): List<String>? = runCatching {
        val clean = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val values = when {
            clean.startsWith("{") -> {
                val obj = JSONObject(clean)
                obj.optJSONArray("lines") ?: return@runCatching null
            }
            clean.startsWith("[") -> JSONArray(clean)
            else -> return@runCatching null
        }
        val result = MutableList(original.size) { "" }
        for (i in 0 until values.length()) {
            val item = values.optJSONObject(i) ?: continue
            val index = item.optInt("i", i + 1) - 1
            val translated = item.optString("t").trim()
            if (index in result.indices && translated.isNotBlank()) result[index] = translated
        }
        if (result.all { it.isNotBlank() }) result else null
    }.getOrNull()

    fun stripFences(text: String): String = text
        .removePrefix("```text").removePrefix("```txt").removePrefix("```")
        .removeSuffix("```").trim()
}
