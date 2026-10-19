package com.lilac.anime.shared
import com.lilac.anime.shared.compat.*
import com.lilac.anime.shared.ported.CloudTranslationText
import com.lilac.anime.shared.ported.TranslationFormatException
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

// Fixed, so no other address takes the user's key. The models that take the server-side fallback (fallbacks: "default").
private const val ANTHROPIC_API = "https://api.anthropic.com"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_FALLBACK_BETA = "server-side-fallback-2026-07-01"
private val CLAUDE_FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
// rulesExtra / rulesCustom: 번역 프롬프트 (the user's rules after the app's, or in their place). DeepL takes no instruction.
data class TranslationConfig(val provider: String, val key: String, val model: String = "", val region: String = "international", val terminology: String = "",
                             val rulesExtra: String = "", val rulesCustom: String = "") {
    constructor(provider: String, key: String, model: String, region: String, terminology: String) : this(provider, key, model, region, terminology, "", "")
}
class CloudTranslator(private val client: HttpClient = HttpClient {
    expectSuccess = true
    install(HttpTimeout) { requestTimeoutMillis = 180_000; connectTimeoutMillis = 20_000 }
}) {
    private val cache = mutableMapOf<String, List<String>>()
    private val modelLists = mutableMapOf<String, List<String>>()
    private val spent = mutableMapOf<String, kotlin.time.TimeMark>()
    private val workingShapes = mutableMapOf<String, Int>()
    suspend fun models(config: TranslationConfig): List<String> {
        require(config.key.isNotBlank()) { "API Key를 설정하세요." }
        val auth = if (config.provider == "deepl") "DeepL-Auth-Key " + config.key else "Bearer " + config.key
        val endpoint = when (config.provider) {
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models"
            "openai" -> "https://api.openai.com/v1/models"
            "qwen" -> "https://" + (if (config.region == "china") "dashscope.aliyuncs.com" else "dashscope-intl.aliyuncs.com") + "/compatible-mode/v1/models"
            "deepl" -> "https://" + (if (config.key.endsWith(":fx", true)) "api-free.deepl.com" else "api.deepl.com") + "/v2/usage"
            "claude" -> "$ANTHROPIC_API/v1/models"
            else -> error("지원하지 않는 번역 공급자입니다.")
        }
        val names = mutableListOf<String>(); val tokens = mutableSetOf<String>(); var token = ""
        do {
            val root = JSONObject(client.get(endpoint) {
                timeout { requestTimeoutMillis = 20_000 }
                if (config.provider == "gemini") {
                    header("x-goog-api-key", config.key); parameter("pageSize", "1000")
                    if (token.isNotEmpty()) parameter("pageToken", token)
                } else if (config.provider == "claude") {
                    anthropicHeaders(config.key).forEach { (name, value) -> header(name, value) }
                    parameter("limit", "100"); if (token.isNotEmpty()) parameter("after_id", token)
                } else header("Authorization", auth)
            }.bodyAsText())
            if (config.provider == "deepl") return listOf("DeepL")
            val rows = root.optJSONArray(if (config.provider == "gemini") "models" else "data") ?: error("모델 목록이 없습니다.")
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                if (config.provider == "gemini" && row.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") != true) continue
                row.optString(if (config.provider == "gemini") "name" else "id").removePrefix("models/").takeIf(String::isNotBlank)?.let(names::add)
            }
            token = when (config.provider) {
                "gemini" -> root.optString("nextPageToken")
                // The Models API pages newest first: has_more, then the next page after last_id.
                "claude" -> if (root.optBoolean("has_more")) root.optString("last_id") else ""
                else -> ""
            }
        } while (token.isNotEmpty() && tokens.add(token))
        return CloudModelRules.sorted(config.provider, names).also {
            require(it.isNotEmpty()) { "이 키로 쓸 수 있는 번역 모델이 없습니다. API 키와 지역을 확인하세요." }
        }
    }
    private suspend fun chain(config: TranslationConfig): List<String> {
        val key = config.provider + "|" + config.region + "|" + config.key.hashCode()
        val available = modelLists[key] ?: try { models(config).also { modelLists[key] = it } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val preferred = config.model.removePrefix("models/").ifBlank { CloudModelRules.default(config.provider, available) }
        val chain = CloudModelRules.chain(config.provider, preferred, available)
        return chain.filter { spent[config.provider + ":" + config.region + ":" + config.key.hashCode() + ":" + it]?.hasPassedNow() != false }.ifEmpty { listOf(preferred) }
    }
    suspend fun translate(lines: List<String>, config: TranslationConfig): List<String> = translate(lines, config, 0)
    private suspend fun translate(lines: List<String>, config: TranslationConfig, recoveryDepth: Int): List<String> {
        if (lines.isEmpty()) return emptyList()
        require(config.key.isNotBlank()) { "API Key를 설정하세요." }
        val cacheKey = config.provider + "|" + config.model + "|" + config.region + "|" + config.key.hashCode() + "|" + config.terminology + "|" + config.rulesExtra + "|" + config.rulesCustom + "|" + lines.joinToString("\u0000")
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
                    "claude" -> claude(lines, config.copy(model = model))
                    else -> error("지원하지 않는 번역 공급자입니다.")
                }
                if (result.size != lines.size) throw TranslationFormatException("번역 응답의 줄 수가 다릅니다.")
                break
            } catch (e: CancellationException) { throw e }
            catch (e: TranslationFormatException) {
                // Do not discard good batches or switch every provider for one malformed response.
                // Bounded splitting retains positions; never guess which original a short array belongs to.
                result = if (lines.size > 1 && recoveryDepth < 2) {
                    lines.chunked((lines.size + 1) / 2).flatMap { translate(it, config, recoveryDepth + 1) }
                } else List(lines.size) { "" }
                break
            }
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
        if (recoveryDepth == 0 && output.all { it.isBlank() }) {
            throw TranslationFormatException("번역 API가 사용할 수 있는 자막을 반환하지 않았습니다.")
        }
        if (cache.size > 512) cache.clear()
        if (output.all { it.isNotBlank() }) cache[cacheKey] = output
        return output
    }
    private suspend fun post(url: String, body: JSONObject, authorization: String? = null, apiKey: String? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            try {
                return JSONObject(client.post(url) {
                    contentType(ContentType.Application.Json)
                    authorization?.let { header("Authorization", it) }
                    apiKey?.let { header("x-goog-api-key", it) }
                    headers.forEach { (name, value) -> header(name, value) }
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
    private fun instruction(config: TranslationConfig) = DesktopCloudPrompt.build(config.terminology, config.provider != "gemini", config.rulesExtra, config.rulesCustom)
    private fun itemSchema() = JSONObject().put("type", "object").put("properties", JSONObject()
        .put("i", JSONObject().put("type", "integer")).put("t", JSONObject().put("type", "string")))
        .put("required", JSONArray().put("i").put("t")).put("additionalProperties", false)
    private fun schema() = JSONObject().put("type", "object").put("properties", JSONObject().put("lines",
        JSONObject().put("type", "array").put("items", itemSchema()))).put("required", JSONArray().put("lines")).put("additionalProperties", false)
    private suspend fun openai(lines: List<String>, config: TranslationConfig): List<String> {
        val model = config.model.ifBlank { "gpt-4.1-mini" }
        val shapeKey = "openai:" + model
        for (shape in (workingShapes[shapeKey] ?: 0)..2) {
            try {
                val body = JSONObject().put("model", model).put("store", false).put("instructions", instruction(config))
                    .put("input", CloudTranslationText.jsonInput(lines, true))
                if (shape < 2 && Regex("^(o\\d|gpt-5)").containsMatchIn(model)) body.put("reasoning", JSONObject().put("effort", "low"))
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
                if (text.isEmpty()) continue
                workingShapes[shapeKey] = shape
                return CloudTranslationText.parseDesktop(text, lines)
            } catch (error: ClientRequestException) {
                if (error.response.status != HttpStatusCode.BadRequest || shape == 2) throw error
            }
        }
        error("OpenAI 번역에 실패했습니다.")
    }
    private suspend fun gemini(lines: List<String>, config: TranslationConfig): List<String> {
        val model = config.model.ifBlank { "gemini-flash-latest" }
        val shapeKey = "gemini:" + model
        val thinking = if (model.startsWith("gemini-2.5")) JSONObject().put("thinkingBudget", if (model.contains("pro")) 128 else 0)
            else JSONObject().put("thinkingLevel", "low")
        val schema = JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().put("i", JSONObject().put("type", "INTEGER")).put("t", JSONObject().put("type", "STRING")))
            .put("required", JSONArray().put("i").put("t")))
        var failure: Exception = IllegalStateException("Gemini 번역에 실패했습니다.")
        for (shape in (workingShapes[shapeKey] ?: 0)..2) {
            val generation = JSONObject().put("responseMimeType", "application/json")
            if (shape < 2) generation.put("responseSchema", schema).put("temperature", 0.3)
            if (shape == 0) generation.put("thinkingConfig", thinking)
            for (attempt in 0..3) {
                currentCoroutineContext().ensureActive()
                try {
                    val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction(config)))))
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", CloudTranslationText.jsonInput(lines, false))))))
                        .put("generationConfig", generation)
                    val root = JSONObject(client.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
                        contentType(ContentType.Application.Json); header("x-goog-api-key", config.key); setBody(body.toString())
                    }.bodyAsText())
                    val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val text = if (parts == null) "" else (0 until parts.length()).mapNotNull {
                        parts.optJSONObject(it)?.takeIf { part -> !part.optBoolean("thought") }?.optString("text")
                    }.joinToString("")
                    if (text.isNotEmpty()) {
                        workingShapes[shapeKey] = shape
                        return CloudTranslationText.parseDesktop(text, lines)
                    }
                    failure = IllegalStateException("Gemini가 빈 응답을 보냈습니다.")
                    break
                } catch (e: CancellationException) { throw e }
                catch (e: ResponseException) {
                    failure = e
                    val status = e.response.status.value
                    val message = e.response.bodyAsText()
                    if (status in listOf(401, 403, 404) || message.contains("api key", true)) throw e
                    if (status == 429) {
                        val wait = GeminiRetryPolicy.waitSeconds(message, attempt) ?: throw e
                        if (attempt == 3) throw e
                        delay(((wait + 1) * 1000).toLong()); continue
                    }
                    if (status >= 500) { if (attempt > 0) throw e; delay(2500); continue }
                    break // A simpler shape can be accepted after a 400.
                } catch (e: HttpRequestTimeoutException) {
                    failure = e
                    if (attempt > 0) throw e
                    delay(2500)
                }
            }
        }
        throw failure
    }
    private suspend fun deepl(lines: List<String>, config: TranslationConfig): List<String> {
        val host = if (config.key.endsWith(":fx", true)) "api-free.deepl.com" else "api.deepl.com"
        val body = JSONObject().put("text", JSONArray().apply { lines.forEach { put(it) } }).put("target_lang", "KO").put("preserve_formatting", true)
        config.terminology.lineSequence().firstOrNull { it.startsWith("Anime:") }?.let { body.put("context", "Anime subtitles:" + it.removePrefix("Anime:")) }
        val root = post("https://$host/v2/translate", body, "DeepL-Auth-Key " + config.key)
        val values = root.optJSONArray("translations") ?: error("DeepL 결과가 없습니다.")
        return (0 until values.length()).map { values.optJSONObject(it)?.optString("text").orEmpty().trim() }
    }
    private fun anthropicHeaders(key: String, beta: String? = null) =
        mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION) + (beta?.let { mapOf("anthropic-beta" to it) } ?: emptyMap())
    // Claude (the Anthropic Messages API), as desktop anthropic-api.cjs asks it: the instructions as a cached system prompt
    // (every batch of an episode sends the same one), the lines as JSON. Structured JSON at low effort first (subtitles
    // need little thinking, and the newest models cannot turn it off), with the server-side fallback for a declined batch
    // on the models that take it; then without effort (models before it); then plain text. A key out of credit is a 400
    // like a request a model does not take, so it is not asked again in another shape.
    private suspend fun claude(lines: List<String>, config: TranslationConfig): List<String> {
        val model = config.model.ifBlank { CloudModelRules.CLAUDE_DEFAULT }
        val shapeKey = "claude:" + model
        for (shape in (workingShapes[shapeKey] ?: 0)..2) {
            try {
                val fallback = shape == 0 && model in CLAUDE_FALLBACK_MODELS
                val body = JSONObject().put("model", model).put("max_tokens", 16000)
                    .put("system", JSONArray().put(JSONObject().put("type", "text").put("text", instruction(config)).put("cache_control", JSONObject().put("type", "ephemeral"))))
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", CloudTranslationText.jsonInput(lines, true))))
                if (shape < 2) {
                    val output = JSONObject()
                    if (shape == 0) output.put("effort", "low")
                    body.put("output_config", output.put("format", JSONObject().put("type", "json_schema").put("schema", schema())))
                }
                if (fallback) body.put("fallbacks", "default")
                val root = post("$ANTHROPIC_API/v1/messages", body, headers = anthropicHeaders(config.key, if (fallback) CLAUDE_FALLBACK_BETA else null))
                when (root.optString("stop_reason")) {
                    "refusal" -> {
                        val category = root.optJSONObject("stop_details")?.optString("category").orEmpty()
                        error("Claude가 이 부분의 번역을 거절했습니다" + (if (category.isNotEmpty()) " ($category)" else "") + ".")
                    }
                    "max_tokens" -> error("Claude의 답이 길이 제한에서 끊겼습니다.")
                }
                val content = root.optJSONArray("content")
                val text = buildString {
                    if (content != null) for (i in 0 until content.length()) {
                        val block = content.optJSONObject(i) ?: continue
                        if (block.optString("type") == "text") append(block.optString("text"))
                    }
                }
                if (text.isEmpty()) continue
                workingShapes[shapeKey] = shape
                return CloudTranslationText.parseDesktop(text, lines)
            } catch (error: ClientRequestException) {
                if (error.response.status != HttpStatusCode.BadRequest || shape == 2 ||
                    Regex("credit balance|billing", RegexOption.IGNORE_CASE).containsMatchIn(error.response.bodyAsText())) throw error
            }
        }
        error("Claude 번역에 실패했습니다.")
    }
    private suspend fun qwen(lines: List<String>, config: TranslationConfig): List<String> {
        val host = if (config.region == "china") "dashscope.aliyuncs.com" else "dashscope-intl.aliyuncs.com"
        val model = config.model.ifBlank { "qwen-plus" }
        val shapeKey = "qwen:" + model
        for (shape in (workingShapes[shapeKey] ?: 0)..2) try {
        val body = JSONObject().put("model", model).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", instruction(config)))
            .put(JSONObject().put("role", "user").put("content", CloudTranslationText.jsonInput(lines, true))))
            .put("temperature", 0.3)
        if (shape < 2) body.put("response_format", JSONObject().put("type", "json_object"))
        if (shape == 0) body.put("enable_thinking", false)
        val root = post("https://$host/compatible-mode/v1/chat/completions", body, "Bearer " + config.key)
        val text = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (text.isEmpty()) continue
        workingShapes[shapeKey] = shape
        return CloudTranslationText.parseDesktop(text, lines)
        } catch (error: ClientRequestException) { if (error.response.status != HttpStatusCode.BadRequest || shape == 2) throw error }
        error("Qwen 번역에 실패했습니다.")
    }
    fun clearCache() = cache.clear()
    fun close() = client.close()
}
