package com.n8nAndroidServer.core.system

data class SystemRequest(
    val category: String, // "bluetooth", "wifi", etc.
    val action: String,   // "connect", "toggle"
    val params: Map<String, String>? = null
)

data class SystemResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, String>? = null
)
