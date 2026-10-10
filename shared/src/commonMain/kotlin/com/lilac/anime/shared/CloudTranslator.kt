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
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

data class TranslationConfig(val provider: String, val key: String, val model: String = "", val region: String = "international", val terminology: String = "")
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
            else -> error("지원하지 않는 번역 공급자입니다.")
        }
        val names = mutableListOf<String>(); val tokens = mutableSetOf<String>(); var token = ""
        do {
            val root = JSONObject(client.get(endpoint) {
                timeout { requestTimeoutMillis = 20_000 }
                if (config.provider == "gemini") {
                    header("x-goog-api-key", config.key); parameter("pageSize", "1000")
                    if (token.isNotEmpty()) parameter("pageToken", token)
                } else header("Authorization", auth)
            }.bodyAsText())
            if (config.provider == "deepl") return listOf("DeepL")
            val rows = root.optJSONArray(if (config.provider == "gemini") "models" else "data") ?: error("모델 목록이 없습니다.")
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                if (config.provider == "gemini" && row.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") != true) continue
                row.optString(if (config.provider == "gemini") "name" else "id").removePrefix("models/").takeIf(String::isNotBlank)?.let(names::add)
            }
            token = if (config.provider == "gemini") root.optString("nextPageToken") else ""
        } while (token.isNotEmpty() && tokens.add(token))
        return CloudModelRules.sorted(config.provider, names)
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
        if (cache.size > 512) cache.clear()
        if (output.all { it.isNotBlank() }) cache[cacheKey] = output
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
    private fun instruction(config: TranslationConfig) = DesktopCloudPrompt.build(config.terminology, config.provider != "gemini")
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
