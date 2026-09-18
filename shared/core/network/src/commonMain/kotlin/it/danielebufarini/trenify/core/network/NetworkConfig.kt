package it.danielebufarini.trenify.core.network

data class NetworkConfig(
    // Chrome Android 154 (Early Stable, 2026-09-09), using Chromium's reduced UA format.
    val userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36",
    val connectTimeoutMillis: Long = 5_000,
    val requestTimeoutMillis: Long = 10_000,
    val socketTimeoutMillis: Long = 10_000,
    val maximumGetRetries: Int = 2,
)
