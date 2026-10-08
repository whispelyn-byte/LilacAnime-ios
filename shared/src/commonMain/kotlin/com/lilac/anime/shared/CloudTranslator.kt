package com.lilac.anime.shared
import com.lilac.anime.shared.compat.*
import com.lilac.anime.shared.ported.CloudTranslationText
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

data class TranslationConfig(val provider: String, val key: String, val model: String = "", val region: String = "international", val terminology: String = "")
class CloudTranslator(private val client: HttpClient = HttpClient {
    expectSuccess = true
    install(HttpTimeout) { requestTimeoutMillis = 150_000; connectTimeoutMillis = 20_000 }
}) {
    private val cache = mutableMapOf<String, List<String>>()
    private val modelLists = mutableMapOf<String, List<String>>()
    private val spent = mutableMapOf<String, kotlin.time.TimeMark>()
    suspend fun models(config: TranslationConfig): List<String> {
        require(config.key.isNotBlank()) { "API Key를 설정하세요." }
        val auth = if (config.provider == "deepl") "DeepL-Auth-Key " + config.key else "Bearer " + config.key
        val endpoint = when (config.provider) {
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models"
            "openai" -> "https://api.openai.com/v1/models"
            "qwen" -> "https://" + (if (config.region == "china") "dashscope.aliyuncs.com" else "dashscope-intl.aliyuncs.com") + "/compatible-mode/v1/models"
            "deepl" -> "https://" + (if (config.key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com") + "/v2/usage"
            else -> error("지원하지 않는 번역 공급자입니다.")
        }
        val root = JSONObject(client.get(endpoint) {
            if (config.provider == "gemini") header("x-goog-api-key", config.key) else header("Authorization", auth)
        }.bodyAsText())
        if (config.provider == "deepl") return listOf("DeepL")
        val rows = root.optJSONArray(if (config.provider == "gemini") "models" else "data") ?: error("모델 목록이 없습니다.")
        val names = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            if (config.provider == "gemini" && row.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") != true) null
            else row.optString(if (config.provider == "gemini") "name" else "id").removePrefix("models/").takeIf(String::isNotBlank)
        }
        return CloudModelRules.sorted(config.provider, names)
    }
    private suspend fun chain(config: TranslationConfig): List<String> {
        val key = config.provider + "|" + config.region + "|" + config.key.hashCode()
        val available = modelLists[key] ?: try { models(config).also { modelLists[key] = it } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val preferred = config.model.removePrefix("models/").ifBlank { CloudModelRules.default(config.provider, available) }
        val chain = CloudModelRules.chain(config.provider, preferred, available)
        return chain.filter { spent[config.provider + ":" + config.region + ":" + config.key.hashCode() + ":" + it]?.hasPassedNow() != false }.ifEmpty { error("번역 모델 사용량이 소진되었습니다. 다른 API나 로컬 AI로 전환하세요.") }
    }
    suspend fun translate(lines: List<String>, config: TranslationConfig): List<String> {
        if (lines.isEmpty()) return emptyList()
        require(config.key.isNotBlank()) { "API Key를 설정하세요." }
        val cacheKey = config.provider + "|" + config.model + "|" + config.region + "|" + config.key.hashCode() + "|" + config.terminology + "|" + lines.joinToString("\u0000")
        cache[cacheKey]?.let { return it }
        var result: List<String>? = null
        var failure: Exception? = null
        val chain = if (config.provider == "deepl") listOf("") else chain(config)
        for (model in chain) {
            try {
                result = when (config.provider) {
                    "deepl" -> deepl(lines, config)
                    "gemini" -> gemini(lines, config.copy(model = model))
                    "openai" -> openai(lines, config.copy(model = model))
                    "qwen" -> qwen(lines, config.copy(model = model))
                    else -> error("지원하지 않는 번역 공급자입니다.")
                }
                break
            } catch (e: CancellationException) { throw e }
            catch (e: ResponseException) {
                failure = e
                val status = e.response.status.value
                val message = e.response.bodyAsText()
                if (!CloudCooldown.canSwitch(config.provider, status, message)) throw e
                spent[config.provider + ":" + config.region + ":" + config.key.hashCode() + ":" + model] = TimeSource.Monotonic.markNow() + CloudCooldown.seconds(config.provider, status, message, pacificDayRemaining()).seconds
            } catch (e: HttpRequestTimeoutException) {
                failure = e
                spent[config.provider + ":" + config.region + ":" + config.key.hashCode() + ":" + model] = TimeSource.Monotonic.markNow() + 300.seconds
            }
        }
        val output = result ?: throw (failure ?: IllegalStateException("사용 가능한 번역 모델이 없습니다."))
        check(output.size == lines.size && output.all { it.isNotBlank() }) { "번역 줄 수가 일치하지 않습니다." }
        if (cache.size > 512) cache.clear()
        cache[cacheKey] = output
        return output
    }
    private suspend fun post(url: String, body: JSONObject, authorization: String? = null, apiKey: String? = null): JSONObject {
        for (attempt in 0..3) {
            try {
                return JSONObject(client.post(url) {
                    contentType(ContentType.Application.Json)
                    authorization?.let { header("Authorization", it) }
                    apiKey?.let { header("x-goog-api-key", it) }
                    setBody(body.toString())
                }.bodyAsText())
            } catch (e: ResponseException) {
                val status = e.response.status.value
                val message = e.response.bodyAsText()
                val retry = status >= 500 || status == 429 && !Regex("quota|billing|insufficient|exceeded your current", RegexOption.IGNORE_CASE).containsMatchIn(message)
                if (!retry || attempt == 3) throw e
                delay(((e.response.headers["Retry-After"]?.toDoubleOrNull() ?: (2.5 * (1 shl attempt))) * 1000).toLong().coerceIn(500, 60000))
            } catch (e: HttpRequestTimeoutException) {
                if (attempt == 3) throw e
                delay(2500L * (1 shl attempt))
            }
        }
        error("번역 API 요청 실패")
    }
    private fun instruction(config: TranslationConfig) = "Translate Japanese or English anime subtitles into natural Korean. Preserve meaning, names and tone. Return exactly one translated item per input, with its original 1-based index. Return JSON {\"lines\":[{\"i\":1,\"t\":\"translation\"}]}. Do not add explanations." + if (config.terminology.isBlank()) "" else "\nUse these spellings consistently:\n" + config.terminology
    private fun itemSchema() = JSONObject().put("type", "object").put("properties", JSONObject()
        .put("i", JSONObject().put("type", "integer")).put("t", JSONObject().put("type", "string")))
        .put("required", JSONArray().put("i").put("t")).put("additionalProperties", false)
    private fun schema() = JSONObject().put("type", "object").put("properties", JSONObject().put("lines",
        JSONObject().put("type", "array").put("items", itemSchema()))).put("required", JSONArray().put("lines")).put("additionalProperties", false)
    private suspend fun openai(lines: List<String>, config: TranslationConfig): List<String> {
        val model = config.model.ifBlank { "gpt-4.1-mini" }
        for (shape in 0..2) {
            try {
                val body = JSONObject().put("model", model).put("store", false).put("instructions", instruction(config))
                    .put("input", CloudTranslationText.markedInput(lines))
                if (shape == 0) body.put("text", JSONObject().put("format", JSONObject().put("type", "json_schema").put("name", "subtitles").put("strict", true).put("schema", schema())))
                if (shape == 1) body.put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
                val root = post("https://api.openai.com/v1/responses", body, "Bearer " + config.key)
                val text = root.optString("output_text").ifBlank {
                    val output = root.optJSONArray("output") ?: error("OpenAI 응답에 출력이 없습니다.")
                    buildString {
                        for (i in 0 until output.length()) {
                            val parts = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                            for (j in 0 until parts.length()) {
                                val part = parts.optJSONObject(j) ?: continue
                                if (part.optString("type") == "refusal") error("번역 요청이 거절되었습니다.")
                                if (part.optString("type") == "output_text") append(part.optString("text"))
                            }
                        }
                    }
                }
                return CloudTranslationText.parseMarked(text, lines)
            } catch (error: ClientRequestException) {
                if (error.response.status != HttpStatusCode.BadRequest || shape == 2) throw error
            }
        }
        error("OpenAI 번역에 실패했습니다.")
    }
    private suspend fun gemini(lines: List<String>, config: TranslationConfig): List<String> {
        val models = if (config.model.isNotBlank()) listOf(config.model.removePrefix("models/")) else {
            val root = JSONObject(client.get("https://generativelanguage.googleapis.com/v1beta/models") { header("x-goog-api-key", config.key) }.bodyAsText())
            val array = root.optJSONArray("models") ?: error("Gemini 모델 목록이 없습니다.")
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.takeIf {
                it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true
            }?.optString("name")?.removePrefix("models/") }.sortedBy { if (it.contains("flash")) 0 else 1 }
        }
        for (model in models) {
            try {
                val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction(config)))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", CloudTranslationText.markedInput(lines))))))
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3))
                val root = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", body, apiKey = config.key)
                val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: error("Gemini 번역 응답이 없습니다.")
                val text = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.takeIf { part -> !part.optBoolean("thought") }?.optString("text") }.joinToString("")
                return CloudTranslationText.parseMarked(text, lines)
            } catch (error: ClientRequestException) {
                if (error.response.status.value !in listOf(400, 404) || model == models.last()) throw error
            }
        }
        error("사용 가능한 Gemini 모델이 없습니다.")
    }
    private suspend fun deepl(lines: List<String>, config: TranslationConfig): List<String> {
        val host = if (config.key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val root = post("https://$host/v2/translate", JSONObject().put("text", JSONArray().apply { lines.forEach { put(it) } })
            .put("target_lang", "KO").put("preserve_formatting", true), "DeepL-Auth-Key " + config.key)
        val values = root.optJSONArray("translations") ?: error("DeepL 결과가 없습니다.")
        return (0 until values.length()).map { values.optJSONObject(it)?.optString("text").orEmpty() }
    }
    private suspend fun qwen(lines: List<String>, config: TranslationConfig): List<String> {
        val host = if (config.region == "china") "dashscope.aliyuncs.com" else "dashscope-intl.aliyuncs.com"
        val model = config.model.ifBlank { "qwen-plus" }
        val body = JSONObject().put("model", model).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", instruction(config)))
            .put(JSONObject().put("role", "user").put("content", CloudTranslationText.markedInput(lines))))
            .put("temperature", 0.3)
        if (model.contains("qwen3", true)) body.put("enable_thinking", false)
        val root = post("https://$host/compatible-mode/v1/chat/completions", body, "Bearer " + config.key)
        return CloudTranslationText.parseMarked(root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty(), lines)
    }
    fun clearCache() = cache.clear()
    fun close() = client.close()
}
