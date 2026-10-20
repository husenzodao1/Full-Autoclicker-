package com.fullautoclicker.core

/** How a dispatched tap ended. */
enum class TapOutcome { COMPLETED, CANCELLED, FAILED }

/**
 * The clicker's state machine and Volume Up policy.
 *
 * Rules enforced here:
 *  - Only an initial Volume Up key-down (repeatCount == 0) of a new press, while [Mode.ARMED],
 *    requests a tap. Auto-repeat from holding the key and the key-up are swallowed but never tap.
 *  - A press is identified by its down time, so a duplicated delivery of the same press cannot
 *    tap twice.
 *  - At most one tap is in flight and at most one waits behind it; anything beyond that is
 *    dropped and counted, so a burst can never build an unbounded gesture queue.
 *  - [stop] discards the waiting tap immediately and nothing is dispatched until re-armed.
 *  - Nothing here is persisted; a new engine always starts in [Mode.IDLE].
 *
 * Not thread-safe: call everything from the service's main thread.
 */
class ClickEngine(
    private val dispatcher: TapDispatcher,
    private val clock: () -> Long,
) {
    var mode: Mode = Mode.IDLE
        private set

    /** Last confirmed target. Kept after STOP only to pre-position the marker next time. */
    var target: Target? = null
        private set

    /** Why the clicker last returned to IDLE (null after a fresh start or a new READY). */
    var lastStopReason: String? = null
        private set

    /** Invoked after any state, counter or statistics change. */
    var onChanged: (() -> Unit)? = null

    /** Physical key press -> gesture handed to the platform. */
    val dispatchLatency = LatencyStats()

    /** Physical key press -> platform reports the tap gesture finished (includes tap length). */
    val completeLatency = LatencyStats()

    var tapsRequested = 0L
        private set
    var tapsDispatched = 0L
        private set
    var tapsCompleted = 0L
        private set
    var tapsCancelled = 0L
        private set
    var tapsFailed = 0L
        private set
    var tapsDropped = 0L
        private set
    var tapsLost = 0L
        private set
    var repeatsSwallowed = 0L
        private set
    var pressesWhilePaused = 0L
        private set

    /** Most recently dispatched tap, used for end-to-end latency matching. */
    var lastDispatched: TapRecord? = null
        private set

    private var ownedPressDownTime = NO_PRESS
    private var nextTapId = 1L
    private var inFlight: TapRecord? = null
    private var queued: TapRecord? = null

    val isTapInFlight: Boolean get() = inFlight != null

    fun isInFlight(id: Long): Boolean = inFlight?.id == id

    /** True when armed but the display no longer matches the geometry the target was picked in. */
    fun isPaused(current: DisplayGeometry): Boolean {
        val t = target ?: return false
        return mode == Mode.ARMED && t.geometry != current
    }

    /** START: show the marker. Clicking is disabled for the whole selection. */
    fun beginSelection() {
        queued = null
        mode = Mode.SELECTING
        changed()
    }

    /** READY: save the target and arm. Ignored unless a selection is in progress. */
    fun confirmTarget(newTarget: Target): Boolean {
        if (mode != Mode.SELECTING) return false
        target = newTarget
        lastStopReason = null
        // A new session never waits on a tap from the previous one; its late callback is ignored.
        inFlight = null
        queued = null
        mode = Mode.ARMED
        changed()
        return true
    }

    /** Leaves selection without arming. */
    fun cancelSelection() {
        if (mode != Mode.SELECTING) return
        stop("Selection cancelled")
    }

    /** STOP: no new taps from now on; a queued tap is discarded. */
    fun stop(reason: String) {
        val wasActive = mode != Mode.IDLE || queued != null
        queued = null
        mode = Mode.IDLE
        lastStopReason = reason
        if (wasActive) changed()
    }

    /**
     * Decides what to do with one Volume Up key event.
     *
     * @param down true for ACTION_DOWN (including auto-repeat), false for ACTION_UP
     * @param downTime KeyEvent.getDownTime(): identical for every event of one physical press
     * @param eventTime KeyEvent.getEventTime(): when this event happened (uptime ms)
     */
    fun onVolumeUpKey(
        down: Boolean,
        repeatCount: Int,
        downTime: Long,
        eventTime: Long,
        geometry: DisplayGeometry,
    ): KeyVerdict {
        if (!down) {
            // The key-up of a press we consumed must be consumed too, whatever happened since.
            if (ownedPressDownTime != NO_PRESS && downTime == ownedPressDownTime) {
                ownedPressDownTime = NO_PRESS
                return KeyVerdict.CONSUME
            }
            return KeyVerdict.PASS
        }

        if (downTime == ownedPressDownTime) {
            // Long-press auto-repeat (or a redelivery) of a press that already tapped once.
            repeatsSwallowed++
            return KeyVerdict.CONSUME
        }
        if (repeatCount > 0) {
            // Repeat of a press that began while we were not consuming: leave it to Android.
            return KeyVerdict.PASS
        }

        val t = target
        if (mode != Mode.ARMED || t == null) return KeyVerdict.PASS
        if (t.geometry != geometry) {
            // Rotated or resized since READY: the saved point no longer means the same spot.
            pressesWhilePaused++
            changed()
            return KeyVerdict.PASS
        }

        ownedPressDownTime = downTime
        requestTap(eventTime)
        return KeyVerdict.CONSUME
    }

    /**
     * Called by the dispatcher when a tap it accepted has finished, one way or another.
     * Returns the finished tap, or null if the callback is stale (the tap was written off).
     */
    fun onTapFinished(id: Long, outcome: TapOutcome): TapRecord? {
        val tap = inFlight
        if (tap == null || tap.id != id) return null
        inFlight = null
        when (outcome) {
            TapOutcome.COMPLETED -> {
                tapsCompleted++
                completeLatency.record(clock() - tap.keyEventTime)
            }
            TapOutcome.CANCELLED -> tapsCancelled++
            TapOutcome.FAILED -> tapsFailed++
        }
        val next = queued
        queued = null
        if (next != null && mode == Mode.ARMED && target != null) dispatch(next)
        changed()
        return tap
    }

    fun resetStats() {
        dispatchLatency.reset()
        completeLatency.reset()
        tapsRequested = 0
        tapsDispatched = 0
        tapsCompleted = 0
        tapsCancelled = 0
        tapsFailed = 0
        tapsDropped = 0
        tapsLost = 0
        repeatsSwallowed = 0
        pressesWhilePaused = 0
        changed()
    }

    private fun requestTap(keyEventTime: Long) {
        tapsRequested++
        val tap = TapRecord(nextTapId++, keyEventTime)
        val current = inFlight
        if (current != null && clock() - current.dispatchedAt > STUCK_TAP_MS) {
            // The platform never reported back; don't let one lost callback block every later tap.
            inFlight = null
            tapsLost++
        }
        when {
            inFlight == null -> dispatch(tap)
            queued == null -> queued = tap
            else -> tapsDropped++
        }
        changed()
    }

    private fun dispatch(tap: TapRecord) {
        val t = target ?: return
        tap.dispatchedAt = clock()
        inFlight = tap
        val result = try {
            dispatcher.dispatchTap(tap.id, t.x, t.y)
        } catch (e: RuntimeException) {
            DispatchResult.REJECTED
        }
        if (result == DispatchResult.REJECTED) {
            inFlight = null
            tapsFailed++
            return
        }
        tapsDispatched++
        lastDispatched = tap
        dispatchLatency.record(tap.dispatchedAt - tap.keyEventTime)
    }

    private fun changed() {
        onChanged?.invoke()
    }

    companion object {
        private const val NO_PRESS = Long.MIN_VALUE

        /** A tap still unreported after this long is treated as lost. */
        const val STUCK_TAP_MS = 1500L
    }
}
