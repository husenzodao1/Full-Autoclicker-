package com.fullautoclicker.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.fullautoclicker.R
import com.fullautoclicker.core.ClickEngine
import com.fullautoclicker.core.LatencyStats
import com.fullautoclicker.core.Mode
import com.fullautoclicker.service.ClickerHub
import com.fullautoclicker.service.ClickerService
import com.fullautoclicker.service.Prefs
import java.util.Locale

/** Setup, status, STOP, and a test pad that measures what the injected taps really do. */
class MainActivity : Activity() {

    private lateinit var serviceStatus: TextView
    private lateinit var modeStatus: TextView
    private lateinit var latencyText: TextView
    private lateinit var padText: TextView
    private lateinit var enableButton: Button
    private lateinit var showButton: Button
    private lateinit var stopButton: Button
    private lateinit var tapLength: RadioGroup
    private lateinit var pad: TestPadView

    private val padLatency = LatencyStats()
    private var padInjected = 0
    private var padFinger = 0
    private var lastMatchedTapId = -1L
    private var lastTouch: Pair<Int, Int>? = null

    private val hubListener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceStatus = findViewById(R.id.service_status)
        modeStatus = findViewById(R.id.mode_status)
        latencyText = findViewById(R.id.latency_text)
        padText = findViewById(R.id.pad_text)
        enableButton = findViewById(R.id.btn_enable)
        showButton = findViewById(R.id.btn_show)
        stopButton = findViewById(R.id.btn_stop)
        tapLength = findViewById(R.id.tap_length)
        pad = findViewById(R.id.test_pad)

        applyInsets(findViewById(R.id.root))

        enableButton.setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btn_app_info).setOnClickListener { openAppInfo() }
        showButton.setOnClickListener { ClickerHub.service?.showControls() }
        stopButton.setOnClickListener { ClickerHub.service?.stopClicking("Stopped from app") }
        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            padLatency.reset()
            padInjected = 0
            padFinger = 0
            lastTouch = null
            ClickerHub.service?.engine?.resetStats()
            refresh()
        }

        val durationIds = mapOf(10L to R.id.tap_10, 20L to R.id.tap_20, 40L to R.id.tap_40)
        tapLength.check(durationIds[Prefs(this).tapDurationMs] ?: R.id.tap_20)
        tapLength.setOnCheckedChangeListener { _, id ->
            val ms = durationIds.entries.firstOrNull { it.value == id }?.key ?: return@setOnCheckedChangeListener
            val service = ClickerHub.service
            if (service != null) service.setTapDuration(ms) else Prefs(this).tapDurationMs = ms
        }

        pad.setColors(getColor(R.color.accent), getColor(R.color.subtext))
        pad.onTouchDown = ::onPadTouched
    }

    override fun onResume() {
        super.onResume()
        ClickerHub.addListener(hubListener)
        refresh()
    }

    override fun onPause() {
        ClickerHub.removeListener(hubListener)
        super.onPause()
    }

    private fun refresh() {
        val service = ClickerHub.service
        val enabledInSettings = isEnabledInSettings()
        serviceStatus.text = when {
            service != null -> getString(R.string.status_service_on)
            enabledInSettings -> getString(R.string.status_service_stuck)
            else -> getString(R.string.status_service_off)
        }
        serviceStatus.setTextColor(getColor(if (service != null) R.color.accent else R.color.danger))
        enableButton.text = getString(if (service != null) R.string.btn_settings else R.string.btn_enable)

        showButton.isEnabled = service != null && !service.areControlsVisible
        stopButton.isEnabled = service != null && service.engine.mode != Mode.IDLE
        stopButton.alpha = if (stopButton.isEnabled) 1f else 0.4f

        if (service == null) {
            modeStatus.text = getString(R.string.mode_unavailable)
            latencyText.text = ""
        } else {
            val engine = service.engine
            val target = engine.target
            modeStatus.text = when {
                engine.mode == Mode.ARMED && service.isPaused() ->
                    getString(R.string.mode_paused, target?.x ?: 0, target?.y ?: 0)
                engine.mode == Mode.ARMED -> getString(R.string.mode_armed, target?.x ?: 0, target?.y ?: 0)
                engine.mode == Mode.SELECTING -> getString(R.string.mode_selecting)
                else -> getString(R.string.mode_idle) +
                    (engine.lastStopReason?.let { "\n" + getString(R.string.mode_last_stop, it) } ?: "")
            }
            latencyText.text = formatEngineStats(engine, service.tapDurationMs)
        }
        padText.text = formatPadStats(service?.engine)
    }

    private fun onPadTouched(x: Int, y: Int, now: Long) {
        val tap = ClickerHub.service?.engine?.lastDispatched
        // Attribute by dispatch time: on a busy system the key itself may be older than a second.
        if (tap != null && tap.id != lastMatchedTapId && now - tap.dispatchedAt in 0..PAD_MATCH_MS) {
            lastMatchedTapId = tap.id
            padInjected++
            padLatency.record(now - tap.keyEventTime)
        } else {
            padFinger++
        }
        lastTouch = x to y
        if (Log.isLoggable(ClickerService.TAG, Log.DEBUG)) {
            Log.d(ClickerService.TAG, "Pad touch at $x,$y injected=$padInjected finger=$padFinger key->touch ${padLatency.last} ms")
        }
        refresh()
    }

    private fun formatEngineStats(engine: ClickEngine, tapMs: Long): String {
        val d = engine.dispatchLatency
        val c = engine.completeLatency
        return buildString {
            append("Key → tap sent:     ").append(stats(d)).append('\n')
            append("Key → tap finished: ").append(stats(c)).append("  (includes ").append(tapMs).append(" ms hold)\n")
            append("Taps: ").append(engine.tapsDispatched).append(" sent · ")
            append(engine.tapsCompleted).append(" done · ")
            append(engine.tapsCancelled).append(" cancelled · ")
            append(engine.tapsFailed).append(" failed · ")
            append(engine.tapsDropped).append(" dropped · ")
            append(engine.tapsLost).append(" lost\n")
            append("Held-key repeats ignored: ").append(engine.repeatsSwallowed)
            append(" · presses while paused: ").append(engine.pressesWhilePaused)
        }
    }

    private fun formatPadStats(engine: ClickEngine?): String = buildString {
        append("Injected taps received: ").append(padInjected)
        append(" · finger touches: ").append(padFinger).append('\n')
        append("Key → touch delivered: ").append(stats(padLatency))
        val touch = lastTouch
        if (touch != null) {
            append("\nLast touch at ").append(touch.first).append(", ").append(touch.second)
            val target = engine?.target
            if (target != null) {
                append(" · target ").append(target.x).append(", ").append(target.y)
                append(" · offset ").append(touch.first - target.x).append(", ").append(touch.second - target.y)
            }
        }
    }

    private fun stats(s: LatencyStats): String =
        if (s.size == 0) {
            "no samples yet"
        } else {
            String.format(
                Locale.US,
                "last %d ms · avg %.1f · p95 %d · max %d (n=%d)",
                s.last, s.average(), s.percentile(0.95), s.max(), s.total,
            )
        }

    private fun isEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, ClickerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openAccessibilitySettings() {
        val component = ComponentName(this, ClickerService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            // Asks Settings to scroll to and highlight this service where supported.
            putExtra(":settings:fragment_args_key", component)
            putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", component) })
        }
        launch(intent)
    }

    private fun openAppInfo() {
        launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        /** An injected tap reaches the pad within this long of being dispatched, even on a busy system. */
        const val PAD_MATCH_MS = 5000L
    }

    private fun applyInsets(root: View) {
        val base = root.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    base + insets.systemWindowInsetLeft,
                    base + insets.systemWindowInsetTop,
                    base + insets.systemWindowInsetRight,
                    base + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }
}
