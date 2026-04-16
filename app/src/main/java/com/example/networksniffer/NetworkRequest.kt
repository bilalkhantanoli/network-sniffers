package com.example.networksniffer

/**
 * Canonical network event model shared across services and UI.
 */
data class NetworkRequest(
    val timestamp: Long = System.currentTimeMillis(),
    val method: String = "",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val requestBody: String = "",
    val responseCode: Int = 0,
    val responseBody: String = "",
    val responseHeaders: Map<String, String> = emptyMap(),
    val timeTaken: Long = 0,
    val protocol: String = "HTTP/1.1"
)
