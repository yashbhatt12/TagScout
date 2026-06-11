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
        const val DEFAULT_ANTENNA_STRENGTH = 5                          // ← NEW
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
        return prefs.getInt(KEY_ANTENNA_STRENGTH, DEFAULT_ANTENNA_STRENGTH)
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