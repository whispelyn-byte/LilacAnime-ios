package com.lilac.anime.shared
import kotlin.test.*

class CloudCooldownTest {
    @Test fun geminiDailyQuotaWaitsForPacificReset() { assertEquals(12345, CloudCooldown.seconds("gemini", 429, "quotaId:GenerateRequestsPerDayPerProjectPerModel", 12345)) }
    @Test fun geminiMinuteLimitAndBusyHandOffForFiveMinutes() {
        assertEquals(300, CloudCooldown.seconds("gemini", 429, "PerMinute", 12345))
        assertEquals(300, CloudCooldown.seconds("openai", 503, "high demand", 12345))
    }
    @Test fun qwenQuota403CanSwitchButInvalidCredentialsCannot() {
        assertTrue(CloudCooldown.canSwitch("qwen", 403, "free tier quota exceeded"))
        assertFalse(CloudCooldown.canSwitch("qwen", 403, "invalid key"))
        assertFalse(CloudCooldown.canSwitch("openai", 429, "insufficient_quota billing"))
        assertTrue(CloudCooldown.canSwitch("openai", 503, "billing service unavailable"))
        assertTrue(CloudCooldown.canSwitch("openai", 404, "billing model not found"))
        assertEquals(3600, CloudCooldown.seconds("qwen", 403, "quota", 12345))
    }
}
