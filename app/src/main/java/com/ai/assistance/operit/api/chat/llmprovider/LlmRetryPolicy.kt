package com.ai.assistance.operit.api.chat.llmprovider

internal object LlmRetryPolicy {
    const val MAX_RETRY_ATTEMPTS = 5
    private const val RETRY_BASE_DELAY_MS = 1000L
    private const val JITTER_FACTOR = 0.5
    private val random = java.util.concurrent.ThreadLocalRandom.current()

    fun nextDelayMs(retryAttempt: Int): Long {
        val normalizedAttempt = retryAttempt.coerceAtLeast(1)
        val baseDelay = RETRY_BASE_DELAY_MS * (1L shl (normalizedAttempt - 1))
        val jitter = random.nextDouble(JITTER_FACTOR, 1.0 + JITTER_FACTOR)
        return (baseDelay * jitter).toLong()
    }
}
