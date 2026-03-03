package com.example.bankscraperlogger.logging

import com.google.gson.JsonElement

data class SessionMeta(
    val sessionId: String,
    val startedAtMs: Long,
    val userAgent: String,
    val initialUrl: String?,
    val externalIp: String? = null,
)

data class EventEnvelope(
    val tsMs: Long,
    val type: String,
    val data: JsonElement?,
)

