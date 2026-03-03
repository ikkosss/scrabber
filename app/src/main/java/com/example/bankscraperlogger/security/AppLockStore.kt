package com.example.bankscraperlogger.security

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

class AppLockStore(context: Context) {
    private val prefs = context.getSharedPreferences("bsl_lock", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false) && !getPinHash().isNullOrBlank()

    fun setOrChangePin(pin: String) {
        require(pin.length in 4..12) { "PIN must be 4..12 digits" }
        val salt = UUID.randomUUID().toString()
        val hash = sha256("$salt:$pin")
        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, hash)
            .apply()
        markUnlockedNow()
    }

    fun disable() {
        prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_SALT)
            .remove(KEY_HASH)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        return sha256("$salt:$pin") == expected
    }

    fun markBackgroundNow(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_BACKGROUND_MS, nowMs).apply()
    }

    fun markUnlockedNow(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_UNLOCK_MS, nowMs).apply()
    }

    fun shouldLock(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isEnabled()) return false
        val lastBg = prefs.getLong(KEY_LAST_BACKGROUND_MS, 0L)
        if (lastBg == 0L) return false
        val timeoutMs = prefs.getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
        return (nowMs - lastBg) >= timeoutMs
    }

    fun setTimeoutMs(timeoutMs: Long) {
        prefs.edit().putLong(KEY_TIMEOUT_MS, timeoutMs).apply()
    }

    fun simulateForward(hours: Int, nowMs: Long = System.currentTimeMillis()) {
        val delta = hours * 60L * 60L * 1000L
        prefs.edit().putLong(KEY_LAST_BACKGROUND_MS, nowMs - delta).apply()
    }

    private fun getPinHash(): String? = prefs.getString(KEY_HASH, null)

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
        private const val KEY_TIMEOUT_MS = "timeout_ms"
        private const val KEY_LAST_BACKGROUND_MS = "last_bg_ms"
        private const val KEY_LAST_UNLOCK_MS = "last_unlock_ms"

        const val DEFAULT_TIMEOUT_MS: Long = 60L * 60L * 1000L // 1h
    }
}

