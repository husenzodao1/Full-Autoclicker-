package com.fullautoclicker.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Convenience values only. The armed state is deliberately NOT stored here, so a crash,
 * reboot or service restart can never resume clicking on its own.
 */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("clicker", Context.MODE_PRIVATE)

    /** Last confirmed target, used only to pre-position the marker on the next START. */
    var lastTargetX: Int
        get() = prefs.getInt(KEY_TARGET_X, -1)
        set(value) = prefs.edit().putInt(KEY_TARGET_X, value).apply()

    var lastTargetY: Int
        get() = prefs.getInt(KEY_TARGET_Y, -1)
        set(value) = prefs.edit().putInt(KEY_TARGET_Y, value).apply()

    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

    /** How long the simulated finger stays down. */
    var tapDurationMs: Long
        get() = prefs.getLong(KEY_TAP_MS, DEFAULT_TAP_MS).takeIf { it in TAP_DURATIONS } ?: DEFAULT_TAP_MS
        set(value) = prefs.edit().putLong(KEY_TAP_MS, value).apply()

    companion object {
        private const val KEY_TARGET_X = "target_x"
        private const val KEY_TARGET_Y = "target_y"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_TAP_MS = "tap_ms"

        val TAP_DURATIONS = longArrayOf(10L, 20L, 40L)
        const val DEFAULT_TAP_MS = 20L
    }
}
