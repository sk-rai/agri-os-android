package com.agrios.app.core.network

/**
 * API configuration constants.
 * For emulator: use 10.0.2.2 to reach host machine's localhost.
 * For real device on same WiFi: use the machine's local IP.
 */
object ApiConfig {
    // Change this to your backend IP when testing on real device
    const val BASE_URL = "http://10.0.2.2:8000/api/v1/"

    // Timeouts
    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 60L
}
