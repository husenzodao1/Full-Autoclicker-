package com.fullautoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.LocaleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.fullautoclicker.R
import com.fullautoclicker.core.ClickEngine
import com.fullautoclicker.core.DispatchResult
import com.fullautoclicker.core.DisplayGeometry
import com.fullautoclicker.core.KeyVerdict
import com.fullautoclicker.core.Mode
import com.fullautoclicker.core.PathStep
import com.fullautoclicker.core.SequenceRules
import com.fullautoclicker.core.Step
import com.fullautoclicker.core.StopReason
import com.fullautoclicker.core.TapDispatcher
import com.fullautoclicker.core.TapOutcome
import com.fullautoclicker.core.TapSequence
import com.fullautoclicker.core.TapStep
import com.fullautoclicker.overlay.BubbleView
import com.fullautoclicker.overlay.OverlayController

/**
 * Hardware-key filtering and tap injection.
 *
 * Volume key events are only routed to this service from START until STOP (the key-filter flag is
 * added when a selection starts and removed on STOP), so while IDLE Android handles both volume
 * keys itself. In between, Volume Up is always consumed (no volume change, no volume panel) and
 * taps only while ARMED; Volume Down is always returned to the system.
 */
class ClickerService : AccessibilityService(), OverlayController.Actions {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var screen: Screen
    private lateinit var overlays: OverlayController

    /** Resources in the app's own language; see [refreshLanguage]. */
    private lateinit var strings: Context
    private var stringsLocales: LocaleList? = null

    lateinit var engine: ClickEngine
        private set

    private var connected = false
    private var keyFilterOn = false
    private var screenReceiverRegistered = false
    private var delayedTap: Runnable? = null
    private var lastGeometry: DisplayGeometry? = null
    private var lastHintAt = 0L

    private var cachedGesture: GestureDescription? = null
    private var cachedGestureFor: TapSequence? = null

    val areControlsVisible: Boolean get() = connected && overlays.isBubbleShowing

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        screen = Screen(this)
        // A fresh engine is always IDLE: nothing resumes after a crash, update or restart.
        engine = ClickEngine(TapDispatcher(::dispatchRun), SystemClock::uptimeMillis)
        engine.onChanged = ::onEngineChanged
        stringsLocales = appLocales()
        strings = localizedContext(stringsLocales!!)
        overlays = OverlayController(this, strings, screen, prefs, this)
        connected = true
        // Force the flag off: after a crash the system may rebind with the last dynamic flags.
        setKeyFilter(false, force = true)

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }
        screenReceiverRegistered = true
        lastGeometry = screen.geometry()
        screen.registerListener(displayListener, handler)

        overlays.showBubble()
        render()
        ClickerHub.attach(this)
        Log.i(TAG, "Service connected")
    }

    override fun onInterrupt() {
        if (connected) stopClicking(StopReason.INTERRUPTED)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown(StopReason.SERVICE_TURNED_OFF)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown(StopReason.SERVICE_TURNED_OFF)
        super.onDestroy()
    }

    private fun shutdown(reason: StopReason) {
        if (!connected) return
        connected = false // First, so the engine's change callback no longer touches the UI.
        engine.stop(reason)
        cancelDelayedTap()
        try {
            setKeyFilter(false)
        } catch (e: RuntimeException) {
            // The connection may already be gone; the system drops our filter with it.
        }
        overlays.removeAll()
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (e: IllegalArgumentException) {
                // Not registered.
            }
            screenReceiverRegistered = false
        }
        screen.unregisterListener(displayListener)
        ClickerHub.detach(this)
        Log.i(TAG, "Service shut down: $reason")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No accessibility events are requested; this service only filters keys and taps.
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!connected) return
        onDisplayChanged()
        // The system language may have changed: refresh every text we show.
        render()
        ClickerHub.notifyChanged()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (connected && displayId == Display.DEFAULT_DISPLAY) onDisplayChanged()
        }
    }

    private fun onDisplayChanged() {
        // Refresh-rate and other mode switches also arrive here; only size/rotation matter.
        val geometry = screen.geometry()
        if (geometry == lastGeometry) return
        lastGeometry = geometry
        overlays.onDisplayChanged()
        render()
        ClickerHub.notifyChanged()
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF && connected) stopClicking(StopReason.SCREEN_OFF)
        }
    }

    // ------------------------------------------------------------------ keys

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Volume Down and every other key are never touched.
        if (!connected || event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
        return try {
            val tapsBefore = engine.tapsRequested
            val verdict = engine.onVolumeUpKey(
                down = action == KeyEvent.ACTION_DOWN,
                repeatCount = event.repeatCount,
                downTime = event.downTime,
                eventTime = event.eventTime,
                geometry = screen.geometry(),
            )
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "VolUp action=$action repeat=${event.repeatCount} down=${event.downTime} -> $verdict")
            }
            val newPress = action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
            if (newPress && verdict == KeyVerdict.CONSUME && engine.tapsRequested == tapsBefore) {
                explainSwallowedPress()
            }
            verdict == KeyVerdict.CONSUME
        } catch (t: Throwable) {
            Log.e(TAG, "Key handling failed; stopping", t)
            stopClicking(StopReason.INTERNAL_ERROR)
            false
        }
    }

    /** A press was swallowed without tapping (no target yet, or rotated): say why, at most every 2 s. */
    private fun explainSwallowedPress() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHintAt < HINT_INTERVAL_MS) return
        lastHintAt = now
        val text = if (engine.mode == Mode.SELECTING) R.string.hint_press_ready else R.string.hint_paused
        Toast.makeText(this, strings.getString(text), Toast.LENGTH_SHORT).show()
    }

    private fun setKeyFilter(on: Boolean, force: Boolean = false) {
        if (on == keyFilterOn && !force) return
        val info = serviceInfo ?: return
        info.flags = if (on) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        serviceInfo = info
        keyFilterOn = on
        Log.i(TAG, "Key filter ${if (on) "on" else "off"}")
    }

    // ------------------------------------------------------------------ taps

    /** One Volume Up press: the whole sequence as a single gesture (one stroke per step). */
    private fun dispatchRun(id: Long, sequence: TapSequence): DispatchResult {
        if (overlays.clearPointsForRun(sequence.steps.map { it.start })) {
            // One of our windows was over a touch point; give the window system time to drop it.
            val run = Runnable { delayedTap = null; performDelayedRun(id, sequence) }
            delayedTap = run
            handler.postDelayed(run, OVERLAY_SETTLE_MS)
            return DispatchResult.ACCEPTED
        }
        return if (performRun(id, sequence)) DispatchResult.ACCEPTED else DispatchResult.REJECTED
    }

    private fun performDelayedRun(id: Long, sequence: TapSequence) {
        if (!engine.isInFlight(id)) return
        if (!connected || engine.mode != Mode.ARMED) {
            engine.onTapFinished(id, TapOutcome.CANCELLED)
        } else if (!performRun(id, sequence)) {
            engine.onTapFinished(id, TapOutcome.FAILED)
        }
    }

    private fun performRun(id: Long, sequence: TapSequence): Boolean {
        val gesture = cachedGesture?.takeIf { cachedGestureFor === sequence }
            ?: buildGesture(sequence).also {
                cachedGesture = it
                cachedGestureFor = sequence
            }
        val accepted = dispatchGesture(gesture, TapCallback(id), handler)
        if (!accepted) Log.w(TAG, "dispatchGesture refused run $id")
        return accepted
    }

    /**
     * Each step becomes one stroke with its own start time, so Android plays the steps in order
     * with the exact pauses (a tap is a one-point stroke; a drawing is replayed at constant speed).
     * Throws if Android's limits are exceeded; the engine counts that as a failed run.
     */
    private fun buildGesture(sequence: TapSequence): GestureDescription {
        val builder = GestureDescription.Builder()
        val slots = SequenceRules.timeline(sequence.steps)
        for ((step, slot) in sequence.steps.zip(slots)) {
            val path = Path()
            when (step) {
                is TapStep -> path.moveTo(step.at.x.toFloat(), step.at.y.toFloat())
                is PathStep -> {
                    path.moveTo(step.points[0].x.toFloat(), step.points[0].y.toFloat())
                    for (p in step.points.drop(1)) path.lineTo(p.x.toFloat(), p.y.toFloat())
                }
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, slot.first, slot.second))
        }
        return builder.build()
    }

    private inner class TapCallback(private val id: Long) : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) = finish(TapOutcome.COMPLETED)

        override fun onCancelled(gestureDescription: GestureDescription?) = finish(TapOutcome.CANCELLED)

        private fun finish(outcome: TapOutcome) {
            if (!connected) return
            val tap = engine.onTapFinished(id, outcome)
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                if (tap == null) {
                    Log.d(TAG, "Tap $id $outcome ignored: already written off as lost")
                } else {
                    val now = SystemClock.uptimeMillis()
                    Log.d(TAG, "Tap $id $outcome; key->sent ${tap.dispatchedAt - tap.keyEventTime} ms, key->done ${now - tap.keyEventTime} ms")
                }
            }
        }
    }

    private fun cancelDelayedTap() {
        delayedTap?.let { handler.removeCallbacks(it) }
        delayedTap = null
    }

    // ------------------------------------------------------------------ engine -> UI

    private fun onEngineChanged() {
        if (!connected) return
        setKeyFilter(engine.mode != Mode.IDLE)
        if (engine.mode != Mode.ARMED) cancelDelayedTap()
        if (engine.mode != Mode.SELECTING && overlays.isSelectionShowing) overlays.hideSelection()
        render()
        ClickerHub.notifyChanged()
    }

    private fun render() {
        val sequence = engine.sequence
        val paused = engine.isPaused(screen.geometry())
        val look = when {
            engine.mode == Mode.SELECTING -> BubbleView.Look.SELECTING
            engine.mode == Mode.ARMED && paused -> BubbleView.Look.PAUSED
            engine.mode == Mode.ARMED -> BubbleView.Look.ARMED
            else -> BubbleView.Look.IDLE
        }
        val status = when {
            engine.mode == Mode.SELECTING -> strings.getString(R.string.overlay_status_selecting)
            engine.mode == Mode.ARMED && paused -> strings.getString(R.string.overlay_status_paused)
            engine.mode == Mode.ARMED && sequence != null ->
                strings.getString(R.string.overlay_status_armed, sequence.steps.size)
            else -> strings.getString(R.string.overlay_status_idle)
        }
        val guard = if (engine.mode == Mode.ARMED && sequence != null) sequence.steps.map { it.start } else emptyList()
        overlays.render(look, status, guard)
    }

    // ------------------------------------------------------------------ overlay actions

    override fun onStartPressed() = startSelection()

    override fun onStopPressed() = stopClicking(StopReason.STOPPED_FROM_BUBBLE)

    override fun onHidePressed() {
        overlays.hideControls()
        ClickerHub.notifyChanged()
    }

    override fun onReadyPressed(steps: List<Step>) {
        if (engine.mode != Mode.SELECTING) return
        val geometry = screen.geometry()
        val sequence = TapSequence(SequenceRules.normalize(steps, geometry.width, geometry.height), geometry)
        if (sequence.steps.isEmpty()) return
        overlays.hideSelection()
        prefs.sequence = sequence
        engine.confirmSequence(sequence)
        Log.i(TAG, "Armed with ${sequence.steps.size} steps: ${sequence.steps.map { it.start }}")
    }

    override fun onCancelPressed() = engine.cancelSelection()

    override fun currentSteps(): List<Step> = currentSequence?.steps.orEmpty()

    override fun isChoosingSteps(): Boolean = engine.mode == Mode.SELECTING

    override fun onTimingsEdited(steps: List<Step>) = updateTimings(steps)

    /**
     * Android 13+ applies the per-app language only to activities: this service keeps the system
     * language and gets no callback when the app language changes. So the language is read again
     * when the app or the floating menu opens, and right after it is changed in either of them.
     */
    override fun refreshLanguage() {
        if (!connected) return
        val locales = appLocales()
        if (locales == stringsLocales) return
        stringsLocales = locales
        strings = localizedContext(locales)
        overlays.setLanguage(strings)
        render()
    }

    private fun appLocales(): LocaleList =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(LocaleManager::class.java).applicationLocales
        } else {
            LocaleList.getEmptyLocaleList()
        }

    /** Only the locale is overridden, so the context still follows rotation and other changes. */
    private fun localizedContext(locales: LocaleList): Context =
        if (locales.isEmpty) this else createConfigurationContext(Configuration().apply { setLocales(locales) })

    // ------------------------------------------------------------------ API for the app UI

    fun startSelection() {
        if (!connected) return
        engine.beginSelection()
        val initial = (engine.sequence ?: prefs.sequence)?.steps.orEmpty()
        overlays.showSelection(initial, prefs.defaultHoldMs, prefs.defaultGapMs, prefs.defaultPathMs)
    }

    fun stopClicking(reason: StopReason) {
        if (!connected) return
        cancelDelayedTap()
        engine.stop(reason)
        Log.i(TAG, "Stop: $reason")
    }

    fun showControls() {
        if (!connected) return
        overlays.showBubble()
        render()
        ClickerHub.notifyChanged()
    }

    /** The sequence being used, or the last saved one when nothing is armed. */
    val currentSequence: TapSequence? get() = if (connected) engine.sequence ?: prefs.sequence else prefs.sequence

    /** New timings from the settings screen; positions can only change through START. */
    fun updateTimings(steps: List<Step>) {
        if (!connected) return
        val base = currentSequence ?: return
        if (!SequenceRules.sameShape(base.steps, steps)) return
        val updated = base.copy(steps = steps)
        prefs.sequence = updated
        engine.retime(steps)
        cachedGesture = null
        ClickerHub.notifyChanged()
    }

    fun isPaused(): Boolean = connected && engine.isPaused(screen.geometry())

    companion object {
        const val TAG = "AutoClicker"

        /** Wait after closing one of our windows that covered the target, before tapping. */
        const val OVERLAY_SETTLE_MS = 60L

        /** Minimum time between two "why didn't that tap" hints. */
        const val HINT_INTERVAL_MS = 2000L
    }
}
