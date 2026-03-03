package com.example.bankscraperlogger.history

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HistoryStore(context: Context) {
    data class Entry(
        val url: String,
        val title: String?,
        val lastVisitedMs: Long,
    )

    private val prefs = context.getSharedPreferences("bsl_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Any()

    fun recordVisit(url: String, title: String?) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val list = load().toMutableList()
            val idx = list.indexOfFirst { it.url == normalizedUrl }
            val updated = Entry(url = normalizedUrl, title = title?.takeIf { it.isNotBlank() }, lastVisitedMs = now)
            if (idx >= 0) {
                list.removeAt(idx)
            }
            list.add(0, updated)
            save(list.take(MAX_ENTRIES))
        }
    }

    fun search(query: String, limit: Int = 8): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        synchronized(lock) {
            return load()
                .asSequence()
                .filter { e ->
                    val t = e.title?.lowercase().orEmpty()
                    t.contains(q) || e.url.lowercase().contains(q)
                }
                .sortedByDescending { it.lastVisitedMs }
                .take(limit)
                .toList()
        }
    }

    fun clearHost(host: String): Int {
        val h = host.trim().lowercase()
        if (h.isBlank()) return 0
        synchronized(lock) {
            val list = load()
            val kept = list.filterNot { e ->
                val entryHost = try { android.net.Uri.parse(e.url).host?.lowercase() } catch (_: Throwable) { null }
                entryHost == h
            }
            val removed = list.size - kept.size
            if (removed > 0) save(kept)
            return removed
        }
    }

    private fun load(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Entry>>() {}.type
            gson.fromJson<List<Entry>>(raw, type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(entries: List<Entry>) {
        prefs.edit().putString(KEY, gson.toJson(entries)).apply()
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 300
    }
}

