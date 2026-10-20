package com.fullautoclicker.service

import android.content.Context
import android.content.SharedPreferences
import com.fullautoclicker.core.SequenceCodec
import com.fullautoclicker.core.SequenceRules
import com.fullautoclicker.core.TapSequence

/**
 * Convenience values only. The armed state is deliberately NOT stored here, so a crash,
 * reboot or service restart can never resume clicking on its own.
 */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("clicker", Context.MODE_PRIVATE)

    /**
     * Last confirmed sequence (taps, drawings and their timings). Used to re-open the markers on
     * the next START and for editing timings in the app; never to arm by itself.
     */
    var sequence: TapSequence?
        get() = SequenceCodec.decode(prefs.getString(KEY_SEQUENCE, null))
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SEQUENCE) else putString(KEY_SEQUENCE, SequenceCodec.encode(value))
            }.apply()
        }

    /** How long the finger stays down for new tap points (1 ms up to 5 s for a long-press). */
    var defaultHoldMs: Long
        get() = SequenceRules.clampHold(prefs.getLong(KEY_DEFAULT_HOLD, SequenceRules.DEFAULT_HOLD_MS))
        set(value) = prefs.edit().putLong(KEY_DEFAULT_HOLD, SequenceRules.clampHold(value)).apply()

    /** Pause given to new steps. */
    var defaultGapMs: Long
        get() = SequenceRules.clampGap(prefs.getLong(KEY_DEFAULT_GAP, SequenceRules.DEFAULT_GAP_MS))
        set(value) = prefs.edit().putLong(KEY_DEFAULT_GAP, SequenceRules.clampGap(value)).apply()

    /** Swipe duration given to new drawings. */
    var defaultPathMs: Long
        get() = SequenceRules.clampPath(prefs.getLong(KEY_DEFAULT_PATH, SequenceRules.DEFAULT_PATH_MS))
        set(value) = prefs.edit().putLong(KEY_DEFAULT_PATH, SequenceRules.clampPath(value)).apply()

    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

    companion object {
        private const val KEY_SEQUENCE = "sequence"
        private const val KEY_DEFAULT_HOLD = "default_hold_ms"
        private const val KEY_DEFAULT_GAP = "default_gap_ms"
        private const val KEY_DEFAULT_PATH = "default_path_ms"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
    }
}
