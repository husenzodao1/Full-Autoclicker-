package com.fullautoclicker.core

/** What the clicker is doing. Never persisted: every process start begins in [IDLE]. */
enum class Mode { IDLE, SELECTING, ARMED }

/** Size and rotation of the display, in the same pixel space that gesture dispatch uses. */
data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int)

/** A screen point in display pixels. */
data class Point(val x: Int, val y: Int)

/** One action of a sequence. [delayAfterMs] is the pause between this step's end and the next step's start. */
sealed interface Step {
    val delayAfterMs: Long

    /** Where the finger first touches the screen; injected touches land on the window under it. */
    val start: Point
}

/** A tap at one point (Х), with the finger held down for [holdMs] (up to seconds: a long-press). */
data class TapStep(val at: Point, val holdMs: Long, override val delayAfterMs: Long) : Step {
    override val start: Point get() = at
}

/** A drawn path (Б), replayed as one swipe at constant speed over [durationMs]. */
data class PathStep(val points: List<Point>, val durationMs: Long, override val delayAfterMs: Long) : Step {
    override val start: Point get() = points.first()
}

/** The armed sequence: everything one Volume Up press performs, plus the display it was made on. */
data class TapSequence(val steps: List<Step>, val geometry: DisplayGeometry)

/** Why the clicker last returned to IDLE. Shown to the user, so it is mapped to text in the UI. */
enum class StopReason {
    STOPPED_FROM_BUBBLE,
    STOPPED_FROM_APP,
    SELECTION_CANCELLED,
    SCREEN_OFF,
    SERVICE_TURNED_OFF,
    INTERRUPTED,
    INTERNAL_ERROR,
}

/** Whether a key event is swallowed by the clicker or handed back to Android. */
enum class KeyVerdict { PASS, CONSUME }

/** Result of handing one run of the sequence to the platform. */
enum class DispatchResult {
    /** Accepted; [ClickEngine.onTapFinished] will be called for this run id later. */
    ACCEPTED,

    /** Refused synchronously (service not connected, invalid gesture...). No callback follows. */
    REJECTED,
}

/** Performs the actual touch injection of a whole sequence. Implemented by the accessibility service. */
fun interface TapDispatcher {
    fun dispatch(id: Long, sequence: TapSequence): DispatchResult
}

/** One requested run of the sequence, tracked from the key press that caused it. */
class TapRecord(
    val id: Long,
    /** Uptime (ms) at which the physical key press happened, from KeyEvent.getEventTime(). */
    val keyEventTime: Long,
) {
    /** Uptime (ms) at which the gesture was handed to the platform; -1 while queued. */
    var dispatchedAt: Long = -1L
        internal set

    /** How long the dispatched gesture takes from first touch to last release. */
    var durationMs: Long = 0L
        internal set
}
