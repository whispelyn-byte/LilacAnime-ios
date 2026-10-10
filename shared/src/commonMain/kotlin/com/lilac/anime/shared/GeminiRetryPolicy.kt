package com.lilac.anime.shared

import kotlinx.serialization.json.*

/** Same quota and RetryInfo decisions as desktop generate(), without making a request. */
object GeminiRetryPolicy {
    fun waitSeconds(body: String, attempt: Int): Double? {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.obj("error") ?: root
        val details = error?.list("details").orEmpty().filterIsInstance<JsonObject>()
        if (details.flatMap { it.list("violations") }.filterIsInstance<JsonObject>().any { it.text("quotaId").contains("PerDay", true) }) return null
        val retry = details.firstNotNullOfOrNull { it.text("retryDelay").removeSuffix("s").toDoubleOrNull()?.takeIf { n -> n > 0 } }
        val message = error?.text("message").orEmpty()
        val wait = retry ?: Regex("retry in ([\\d.]+)\\s*s", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.toDoubleOrNull() ?: (2.5 * (1 shl attempt))
        return wait.takeIf { it.isFinite() && it <= 120 }
    }
}
