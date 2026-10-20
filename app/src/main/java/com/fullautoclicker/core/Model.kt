package com.fullautoclicker.core

/** What the clicker is doing. Never persisted: every process start begins in [IDLE]. */
enum class Mode { IDLE, SELECTING, ARMED }

/** Size and rotation of the display, in the same pixel space that gesture dispatch uses. */
data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int)

/** A saved tap target and the display geometry it was picked in. */
data class Target(val x: Int, val y: Int, val geometry: DisplayGeometry)

/** Whether a key event is swallowed by the clicker or handed back to Android. */
enum class KeyVerdict { PASS, CONSUME }

/** Result of handing one tap to the platform. */
enum class DispatchResult {
    /** Accepted; [ClickEngine.onTapFinished] will be called for this tap id later. */
    ACCEPTED,

    /** Refused synchronously (service not connected, invalid gesture...). No callback follows. */
    REJECTED,
}

/** Performs the actual touch injection. Implemented by the accessibility service. */
fun interface TapDispatcher {
    fun dispatchTap(id: Long, x: Int, y: Int): DispatchResult
}

/** One requested tap, tracked from the key press that caused it. */
class TapRecord(
    val id: Long,
    /** Uptime (ms) at which the physical key press happened, from KeyEvent.getEventTime(). */
    val keyEventTime: Long,
) {
    /** Uptime (ms) at which the gesture was handed to the platform; -1 while queued. */
    var dispatchedAt: Long = -1L
        internal set
}
