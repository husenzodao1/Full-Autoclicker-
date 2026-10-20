package com.fullautoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.fullautoclicker.core.ClickEngine
import com.fullautoclicker.core.DispatchResult
import com.fullautoclicker.core.DisplayGeometry
import com.fullautoclicker.core.KeyVerdict
import com.fullautoclicker.core.Mode
import com.fullautoclicker.core.Target
import com.fullautoclicker.core.TapDispatcher
import com.fullautoclicker.core.TapOutcome
import com.fullautoclicker.overlay.BubbleView
import com.fullautoclicker.overlay.OverlayController

/**
 * Hardware-key filtering and tap injection.
 *
 * Volume key events are only routed to this service while ARMED (the key-filter flag is added on
 * READY and removed on STOP), so in every other state Android handles both volume keys itself.
 * While ARMED, only Volume Up is consumed; Volume Down is always returned to the system.
 */
class ClickerService : AccessibilityService(), OverlayController.Actions {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var screen: Screen
    private lateinit var overlays: OverlayController

    lateinit var engine: ClickEngine
        private set

    private var connected = false
    private var keyFilterOn = false
    private var screenReceiverRegistered = false
    private var delayedTap: Runnable? = null
    private var lastGeometry: DisplayGeometry? = null

    private var cachedGesture: GestureDescription? = null
    private var cachedGestureKey = ""

    /** Tap length used for every injected tap. */
    var tapDurationMs: Long = Prefs.DEFAULT_TAP_MS
        private set

    val areControlsVisible: Boolean get() = connected && overlays.isBubbleShowing

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        screen = Screen(this)
        tapDurationMs = prefs.tapDurationMs
        // A fresh engine is always IDLE: nothing resumes after a crash, update or restart.
        engine = ClickEngine(TapDispatcher(::dispatchTap), SystemClock::uptimeMillis)
        engine.onChanged = ::onEngineChanged
        overlays = OverlayController(this, screen, prefs, this)
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
        if (connected) stopClicking("Interrupted by the system")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown("Accessibility service turned off")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown("Service destroyed")
        super.onDestroy()
    }

    private fun shutdown(reason: String) {
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
        if (connected) onDisplayChanged()
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
            if (intent.action == Intent.ACTION_SCREEN_OFF && connected) stopClicking("Screen turned off")
        }
    }

    // ------------------------------------------------------------------ keys

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Volume Down and every other key are never touched.
        if (!connected || event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
        return try {
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
            verdict == KeyVerdict.CONSUME
        } catch (t: Throwable) {
            Log.e(TAG, "Key handling failed; stopping", t)
            stopClicking("Internal error")
            false
        }
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

    private fun dispatchTap(id: Long, x: Int, y: Int): DispatchResult {
        if (overlays.clearPointForTap(x, y)) {
            // One of our windows was over the target; give the window system time to drop it.
            val run = Runnable { delayedTap = null; performDelayedTap(id, x, y) }
            delayedTap = run
            handler.postDelayed(run, OVERLAY_SETTLE_MS)
            return DispatchResult.ACCEPTED
        }
        return if (performTap(id, x, y)) DispatchResult.ACCEPTED else DispatchResult.REJECTED
    }

    private fun performDelayedTap(id: Long, x: Int, y: Int) {
        if (!engine.isInFlight(id)) return
        if (!connected || engine.mode != Mode.ARMED) {
            engine.onTapFinished(id, TapOutcome.CANCELLED)
        } else if (!performTap(id, x, y)) {
            engine.onTapFinished(id, TapOutcome.FAILED)
        }
    }

    private fun performTap(id: Long, x: Int, y: Int): Boolean {
        val key = "$x,$y,$tapDurationMs"
        val gesture = cachedGesture?.takeIf { cachedGestureKey == key } ?: run {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, tapDurationMs))
                .build()
                .also {
                    cachedGesture = it
                    cachedGestureKey = key
                }
        }
        val accepted = dispatchGesture(gesture, TapCallback(id), handler)
        if (!accepted) Log.w(TAG, "dispatchGesture refused tap $id")
        return accepted
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
        setKeyFilter(engine.mode == Mode.ARMED)
        if (engine.mode != Mode.ARMED) cancelDelayedTap()
        if (engine.mode != Mode.SELECTING && overlays.isSelectionShowing) overlays.hideSelection()
        render()
        ClickerHub.notifyChanged()
    }

    private fun render() {
        val target = engine.target
        val paused = engine.isPaused(screen.geometry())
        val look = when {
            engine.mode == Mode.SELECTING -> BubbleView.Look.SELECTING
            engine.mode == Mode.ARMED && paused -> BubbleView.Look.PAUSED
            engine.mode == Mode.ARMED -> BubbleView.Look.ARMED
            else -> BubbleView.Look.IDLE
        }
        val status = when {
            engine.mode == Mode.SELECTING -> "SELECTING target"
            engine.mode == Mode.ARMED && paused -> "PAUSED: rotate back"
            engine.mode == Mode.ARMED && target != null -> "ARMED  ${target.x}, ${target.y}"
            else -> "IDLE"
        }
        val armed = engine.mode == Mode.ARMED && target != null
        overlays.render(look, status, if (armed) target!!.x else -1, if (armed) target!!.y else -1)
    }

    // ------------------------------------------------------------------ overlay actions

    override fun onStartPressed() = startSelection()

    override fun onStopPressed() = stopClicking("Stopped")

    override fun onHidePressed() {
        overlays.hideControls()
        ClickerHub.notifyChanged()
    }

    override fun onReadyPressed(x: Int, y: Int) {
        if (engine.mode != Mode.SELECTING) return
        overlays.hideSelection()
        prefs.lastTargetX = x
        prefs.lastTargetY = y
        engine.confirmTarget(Target(x, y, screen.geometry()))
        Log.i(TAG, "Armed at $x,$y")
    }

    override fun onCancelPressed() = engine.cancelSelection()

    // ------------------------------------------------------------------ API for the app UI

    fun startSelection() {
        if (!connected) return
        engine.beginSelection()
        val t = engine.target
        overlays.showSelection(t?.x ?: prefs.lastTargetX, t?.y ?: prefs.lastTargetY)
    }

    fun stopClicking(reason: String) {
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

    fun setTapDuration(ms: Long) {
        tapDurationMs = ms
        prefs.tapDurationMs = ms
        cachedGesture = null
        ClickerHub.notifyChanged()
    }

    fun isPaused(): Boolean = connected && engine.isPaused(screen.geometry())

    companion object {
        const val TAG = "AutoClicker"

        /** Wait after closing one of our windows that covered the target, before tapping. */
        const val OVERLAY_SETTLE_MS = 60L
    }
}
