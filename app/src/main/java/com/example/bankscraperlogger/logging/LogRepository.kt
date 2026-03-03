package com.example.bankscraperlogger.logging

import android.content.Context
import android.webkit.WebResourceRequest
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class LogRepository(private val context: Context) {
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    private val lock = Any()

    private var collecting: Boolean = false
    private var meta: SessionMeta? = null
    private var sessionDir: File? = null

    private val activityEventsSinceLastSample = AtomicLong(0)
    private val activityPagesSinceLastSample = AtomicLong(0)
    private val activityBytesSinceLastSample = AtomicLong(0)

    fun isCollecting(): Boolean = collecting

    fun getActiveSessionDir(): File? = sessionDir

    fun getMeta(): SessionMeta? = meta

    data class ActivitySample(
        val events: Long,
        val pages: Long,
        val bytes: Long,
    )

    fun drainActivitySample(): ActivitySample {
        return ActivitySample(
            events = activityEventsSinceLastSample.getAndSet(0),
            pages = activityPagesSinceLastSample.getAndSet(0),
            bytes = activityBytesSinceLastSample.getAndSet(0),
        )
    }

    fun startNewSession(userAgent: String, initialUrl: String?) {
        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(File(context.filesDir, "bsl_sessions"), sessionId)
        dir.mkdirs()

        val startedAtMs = System.currentTimeMillis()
        val newMeta = SessionMeta(
            sessionId = sessionId,
            startedAtMs = startedAtMs,
            userAgent = userAgent,
            initialUrl = initialUrl,
        )

        synchronized(lock) {
            sessionDir = dir
            meta = newMeta
            collecting = true
            writeJson(File(dir, "meta.json"), gson.toJsonTree(newMeta))
            appendEvent(
                type = "session_start",
                data = jsonObjectOf(
                    "sessionId" to sessionId,
                    "startedAtMs" to startedAtMs,
                    "initialUrl" to initialUrl,
                ),
            )
        }
    }

    fun stopSession() {
        synchronized(lock) {
            if (!collecting) return
            collecting = false
            appendEvent(
                type = "session_stop",
                data = jsonObjectOf("stoppedAtMs" to System.currentTimeMillis()),
            )
        }
    }

    fun setExternalIp(ip: String) {
        val current = meta ?: return
        val dir = sessionDir ?: return
        val updated = current.copy(externalIp = ip)
        synchronized(lock) {
            meta = updated
            writeJson(File(dir, "meta.json"), gson.toJsonTree(updated))
            appendEvent(type = "external_ip", data = jsonObjectOf("ip" to ip))
        }
    }

    fun logVisitedUrl(url: String, source: String) {
        if (!collecting) return
        appendEvent(
            type = "url_visit",
            data = jsonObjectOf(
                "url" to url,
                "source" to source,
            ),
        )
    }

    fun logRequest(request: WebResourceRequest, mainPageUrl: String?) {
        if (!collecting) return

        val headers = JsonObject()
        try {
            request.requestHeaders.forEach { (k, v) -> headers.addProperty(k, v) }
        } catch (_: Throwable) {
            // Some WebView implementations may throw; keep logging best-effort.
        }

        val hasGesture = try {
            request.hasGesture()
        } catch (_: Throwable) {
            false
        }

        appendEvent(
            type = "network_request",
            data = jsonObjectOf(
                "url" to request.url.toString(),
                "method" to request.method,
                "isForMainFrame" to request.isForMainFrame,
                "hasGesture" to hasGesture,
                "isRedirect" to request.isRedirect,
                "mainPageUrl" to mainPageUrl,
                "headers" to headers,
            ),
        )
    }

    fun logPageHtml(url: String, title: String?, html: String, cookies: String?) {
        if (!collecting) return
        val dir = sessionDir ?: return
        val snapshot = jsonObjectOf(
            "tsMs" to System.currentTimeMillis(),
            "url" to url,
            "title" to title,
            "cookies" to cookies,
            "html" to html,
        )
        synchronized(lock) {
            appendJsonl(File(dir, "pages.jsonl"), snapshot)
            activityPagesSinceLastSample.incrementAndGet()
        }
    }

    private fun appendEvent(type: String, data: JsonElement?) {
        val dir = sessionDir ?: return
        val envelope = EventEnvelope(
            tsMs = System.currentTimeMillis(),
            type = type,
            data = data,
        )
        synchronized(lock) {
            appendJsonl(File(dir, "events.jsonl"), gson.toJsonTree(envelope))
            activityEventsSinceLastSample.incrementAndGet()
        }
    }

    private fun appendJsonl(file: File, element: JsonElement) {
        file.parentFile?.mkdirs()
        val line = gson.toJson(element) + "\n"
        file.appendText(line)
        activityBytesSinceLastSample.addAndGet(line.toByteArray(Charsets.UTF_8).size.toLong())
    }

    private fun writeJson(file: File, element: JsonElement) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(element))
    }

    private fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonElement {
        val obj = JsonObject()
        for ((k, v) in pairs) {
            when (v) {
                null -> obj.add(k, com.google.gson.JsonNull.INSTANCE)
                is String -> obj.addProperty(k, v)
                is Boolean -> obj.addProperty(k, v)
                is Number -> obj.addProperty(k, v)
                is JsonElement -> obj.add(k, v)
                else -> obj.add(k, gson.toJsonTree(v))
            }
        }
        return obj
    }
}

