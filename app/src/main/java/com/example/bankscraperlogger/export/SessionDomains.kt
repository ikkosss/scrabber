package com.example.bankscraperlogger.export

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonElement
import java.io.File

object SessionDomains {
    data class DomainCount(val host: String, val count: Int)

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

    private fun JsonElement.asJsonObjectOrNull() =
        try {
            if (isJsonObject) asJsonObject else null
        } catch (_: Throwable) {
            null
        }
}

