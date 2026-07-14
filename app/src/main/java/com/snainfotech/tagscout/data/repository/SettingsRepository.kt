package com.snainfotech.tagscout.data.repository

import android.content.Context
import android.content.SharedPreferences

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "tagscout_settings",  // Storage file name
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_BUZZER_LEVEL = "buzzer_level"
        private const val KEY_SLEEP_TIMEOUT = "sleep_timeout"
        private const val KEY_ANTENNA_STRENGTH = "antenna_strength"   // ← NEW

        const val DEFAULT_BUZZER_LEVEL = "MEDIUM"
        const val DEFAULT_SLEEP_TIMEOUT = "5 Minutes"
        const val DEFAULT_ANTENNA_STRENGTH = 30                         // ← NEW (SDK valid range is 5-30 dBm; 30 is the SDK's own factory default)           // ← NEW
    }

    // ============================================
    // BUZZER LEVEL
    // ============================================
    fun getBuzzerLevel(): String {
        return prefs.getString(KEY_BUZZER_LEVEL, DEFAULT_BUZZER_LEVEL) ?: DEFAULT_BUZZER_LEVEL
    }

    fun setBuzzerLevel(level: String) {
        prefs.edit().putString(KEY_BUZZER_LEVEL, level).apply()
    }

    // ============================================
    // SLEEP TIMEOUT
    // ============================================
    fun getSleepTimeout(): String {
        return prefs.getString(KEY_SLEEP_TIMEOUT, DEFAULT_SLEEP_TIMEOUT) ?: DEFAULT_SLEEP_TIMEOUT
    }

    fun setSleepTimeout(timeout: String) {
        prefs.edit().putString(KEY_SLEEP_TIMEOUT, timeout).apply()
    }

    // ============================================
    // ANTENNA STRENGTH
    // ============================================
    fun getAntennaStrength(): Int {
        // Clamp defensively — a value saved before this range was corrected
        // (old UI allowed 1-10; the SDK's real valid range is 5-30) could
        // otherwise still be sent to real hardware as an invalid value.
        val saved = prefs.getInt(KEY_ANTENNA_STRENGTH, DEFAULT_ANTENNA_STRENGTH)
        return saved.coerceIn(5, 30)
    }

    fun setAntennaStrength(strength: Int) {
        prefs.edit().putInt(KEY_ANTENNA_STRENGTH, strength).apply()
    }
    // ============================================
    // RESET TO DEFAULTS (called when device is reset)
    // ============================================
    fun resetToDefaults() {
        prefs.edit()
            .putString(KEY_BUZZER_LEVEL, DEFAULT_BUZZER_LEVEL)
            .putString(KEY_SLEEP_TIMEOUT, DEFAULT_SLEEP_TIMEOUT)
            .putInt(KEY_ANTENNA_STRENGTH, DEFAULT_ANTENNA_STRENGTH)
            .apply()
    }
}