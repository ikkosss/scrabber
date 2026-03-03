package com.example.bankscraperlogger.export

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonElement
import java.io.File

object SessionDomains {
    data class DomainCount(val host: String, val count: Int)
    data class DomainTime(val host: String, val durationMs: Long)

    fun collect(sessionDir: File): List<DomainCount> {
        val counts = linkedMapOf<String, Int>()
        val gson = Gson()
        fun addUrl(raw: String?) {
            if (raw.isNullOrBlank()) return
            val host = try {
                Uri.parse(raw).host
            } catch (_: Throwable) {
                null
            } ?: return
            counts[host] = (counts[host] ?: 0) + 1
        }

        val pages = File(sessionDir, "pages.jsonl")
        if (pages.exists()) {
            pages.bufferedReader(Charsets.UTF_8).use { br ->
                br.forEachLine { line ->
                    val t = line.trim()
                    if (t.isEmpty()) return@forEachLine
                    val el = try { gson.fromJson(t, JsonElement::class.java) } catch (_: Throwable) { null } ?: return@forEachLine
                    val obj = el.asJsonObjectOrNull() ?: return@forEachLine
                    val rawUrl = obj["url"]?.takeIf { !it.isJsonNull }?.asString
                    addUrl(rawUrl)
                }
            }
        }

        val events = File(sessionDir, "events.jsonl")
        if (events.exists()) {
            events.bufferedReader(Charsets.UTF_8).use { br ->
                br.forEachLine { line ->
                    val t = line.trim()
                    if (t.isEmpty()) return@forEachLine
                    val el = try { gson.fromJson(t, JsonElement::class.java) } catch (_: Throwable) { null } ?: return@forEachLine
                    val obj = el.asJsonObjectOrNull() ?: return@forEachLine
                    val data = obj["data"]?.asJsonObjectOrNull()
                    val rawUrl = data?.get("url")?.takeIf { !it.isJsonNull }?.asString
                    val mainUrl = data?.get("mainPageUrl")?.takeIf { !it.isJsonNull }?.asString
                    addUrl(rawUrl)
                    addUrl(mainUrl)
                }
            }
        }

        return counts.entries
            .map { DomainCount(it.key, it.value) }
            .sortedByDescending { it.count }
    }

    fun dominantHostByTime(sessionDir: File): DomainTime? {
        val events = File(sessionDir, "events.jsonl")
        if (!events.exists()) return null

        val durations = linkedMapOf<String, Long>()
        val gson = Gson()

        var isActive = false
        var lastTs: Long? = null
        var lastHost: String? = null

        fun hostFrom(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return try {
                Uri.parse(raw).host
            } catch (_: Throwable) {
                null
            }
        }

        fun addDelta(nowTs: Long) {
            val prevTs = lastTs
            val host = lastHost
            if (!isActive || prevTs == null || host.isNullOrBlank()) return
            val d = nowTs - prevTs
            if (d <= 0) return
            durations[host] = (durations[host] ?: 0L) + d
        }

        events.bufferedReader(Charsets.UTF_8).use { br ->
            br.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEachLine
                val el = try { gson.fromJson(t, JsonElement::class.java) } catch (_: Throwable) { null } ?: return@forEachLine
                val obj = el.asJsonObjectOrNull() ?: return@forEachLine
                val tsMs = obj["tsMs"]?.takeIf { !it.isJsonNull }?.asLong ?: return@forEachLine
                val type = obj["type"]?.takeIf { !it.isJsonNull }?.asString ?: return@forEachLine
                val data = obj["data"]?.asJsonObjectOrNull()

                when (type) {
                    "session_start" -> {
                        isActive = true
                        lastTs = tsMs
                        lastHost = hostFrom(data?.get("initialUrl")?.takeIf { !it.isJsonNull }?.asString)
                    }
                    "session_pause" -> {
                        addDelta(tsMs)
                        isActive = false
                        lastTs = tsMs
                    }
                    "session_resume" -> {
                        isActive = true
                        lastTs = tsMs
                    }
                    "url_visit" -> {
                        addDelta(tsMs)
                        lastTs = tsMs
                        lastHost = hostFrom(data?.get("url")?.takeIf { !it.isJsonNull }?.asString)
                    }
                    "session_stop" -> {
                        addDelta(tsMs)
                        isActive = false
                        lastTs = tsMs
                    }
                }
            }
        }

        val best = durations.entries.maxByOrNull { it.value } ?: return null
        if (best.value <= 0L) return null
        return DomainTime(host = best.key, durationMs = best.value)
    }

    private fun JsonElement.asJsonObjectOrNull() =
        try {
            if (isJsonObject) asJsonObject else null
        } catch (_: Throwable) {
            null
        }
}

