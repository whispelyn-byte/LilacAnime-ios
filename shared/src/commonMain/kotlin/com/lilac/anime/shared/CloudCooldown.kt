package com.lilac.anime.shared

internal expect fun pacificDayRemaining(): Int

object CloudCooldown {
    fun canSwitch(provider: String, status: Int, message: String): Boolean {
        if (provider == "openai" && status == 429 && Regex("insufficient_quota|billing|exceeded your current quota", RegexOption.IGNORE_CASE).containsMatchIn(message)) return false
        return status == 404 || status == 429 || status >= 500 || provider == "qwen" && status == 403 && Regex("quota|free ?tier", RegexOption.IGNORE_CASE).containsMatchIn(message)
    }
    fun seconds(provider: String, status: Int, message: String, untilPacificMidnight: Int): Int {
        // Claude's rate limits are per minute: a used-up model is passed over for five minutes.
        if (status >= 500 || provider == "gemini" && status == 429 && !message.contains("PerDay", true) || provider == "claude" && status == 429) return 300
        return if (provider == "gemini") untilPacificMidnight.coerceIn(1, 86400) else 3600
    }
}
