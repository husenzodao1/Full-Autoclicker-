package com.fullautoclicker.ui

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.fullautoclicker.R
import com.fullautoclicker.core.ClickEngine
import com.fullautoclicker.core.LatencyStats
import com.fullautoclicker.core.Mode
import com.fullautoclicker.core.PathStep
import com.fullautoclicker.core.SequenceRules
import com.fullautoclicker.core.Step
import com.fullautoclicker.core.StopReason
import com.fullautoclicker.core.TapStep
import com.fullautoclicker.service.ClickerHub
import com.fullautoclicker.service.ClickerService
import com.fullautoclicker.service.Prefs

/** Setup, status, STOP, and a test pad that measures what the injected taps really do. */
class MainActivity : Activity() {

    private lateinit var serviceStatus: TextView
    private lateinit var modeStatus: TextView
    private lateinit var latencyText: TextView
    private lateinit var padText: TextView
    private lateinit var enableButton: Button
    private lateinit var showButton: Button
    private lateinit var stopButton: Button
    private lateinit var pad: TestPadView
    private lateinit var stepsList: LinearLayout
    private lateinit var stepsTotal: TextView

    /** Steps the rows were built for; rows are rebuilt only when the steps themselves change. */
    private var shownSteps: List<Step>? = null
    private var shownLocked = false

    private val padLatency = LatencyStats()
    private var padInjected = 0
    private var padFinger = 0
    private var lastMatchedTapId = -1L
    private var lastTouch: Pair<Int, Int>? = null

    private val hubListener: () -> Unit = { refresh() }

    private val stepTitleFace by lazy { Typeface.create(resources.getFont(R.font.exo2), 600, false) }

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
        pad = findViewById(R.id.test_pad)
        stepsList = findViewById(R.id.steps_list)
        stepsTotal = findViewById(R.id.steps_total)

        applyInsets(findViewById(R.id.root))

        enableButton.setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btn_app_info).setOnClickListener { openAppInfo() }
        showButton.setOnClickListener { ClickerHub.service?.showControls() }
        stopButton.setOnClickListener { ClickerHub.service?.stopClicking(StopReason.STOPPED_FROM_APP) }
        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            padLatency.reset()
            padInjected = 0
            padFinger = 0
            lastTouch = null
            ClickerHub.service?.engine?.resetStats()
            refresh()
        }


        pad.setColors(getColor(R.color.accent), getColor(R.color.subtext))
        pad.onTouchDown = ::onPadTouched

        val prefs = Prefs(this)
        bindNumber(findViewById(R.id.default_hold), prefs.defaultHoldMs, SequenceRules::clampHold) { prefs.defaultHoldMs = it }
        bindNumber(findViewById(R.id.default_gap), prefs.defaultGapMs, SequenceRules::clampGap) { prefs.defaultGapMs = it }
        bindNumber(findViewById(R.id.default_path), prefs.defaultPathMs, SequenceRules::clampPath) { prefs.defaultPathMs = it }

        val languageButton = findViewById<Button>(R.id.btn_language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setUpLanguageButton(languageButton)
        } else {
            languageButton.visibility = View.GONE
        }
    }

    /** Per-app language (Android 13+), so Tajik works even where the phone has no Tajik system language. */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun setUpLanguageButton(button: Button) {
        val localeManager = getSystemService(LocaleManager::class.java)
        val tags = arrayOf("", "en", "ru", "tg")
        val names = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_en),
            getString(R.string.language_ru),
            getString(R.string.language_tg),
        )
        val current = localeManager.applicationLocales.get(0)?.language ?: ""
        val selected = tags.indexOf(current).coerceAtLeast(0)
        button.text = getString(R.string.btn_language, names[selected])
        button.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.language_label)
                .setSingleChoiceItems(names, selected) { dialog, which ->
                    dialog.dismiss()
                    if (which != selected) {
                        // The system recreates this activity; the service picks it up via onConfigurationChanged.
                        localeManager.applicationLocales = LocaleList.forLanguageTags(tags[which])
                    }
                }
                .show()
        }
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
            val steps = engine.sequence?.steps.orEmpty()
            modeStatus.text = when {
                engine.mode == Mode.ARMED && service.isPaused() -> getString(R.string.mode_paused)
                engine.mode == Mode.ARMED -> getString(
                    R.string.mode_armed,
                    steps.size,
                    SequenceRules.totalDurationMs(steps),
                )
                engine.mode == Mode.SELECTING -> getString(R.string.mode_selecting)
                else -> getString(R.string.mode_idle) +
                    (engine.lastStopReason?.let { "\n" + getString(R.string.mode_last_stop, stopReasonText(it)) } ?: "")
            }
            latencyText.text = formatEngineStats(engine)
        }
        padText.text = formatPadStats(service?.engine)
        refreshSteps()
    }

    // ---------------------------------------------------------------- steps and their timings

    private fun refreshSteps() {
        val service = ClickerHub.service
        val prefs = Prefs(this)
        val steps = (service?.currentSequence ?: prefs.sequence)?.steps.orEmpty()
        val locked = service?.engine?.mode == Mode.SELECTING
        val shown = shownSteps
        if (shown == null || !SequenceRules.sameShape(shown, steps) || locked != shownLocked) {
            buildStepRows(steps, locked)
        }
        shownSteps = steps
        shownLocked = locked
        stepsTotal.text = when {
            steps.isEmpty() -> getString(R.string.steps_empty)
            locked -> getString(R.string.steps_locked)
            SequenceRules.isTooLong(steps) -> getString(R.string.steps_too_long)
            else -> getString(R.string.steps_total, steps.size, SequenceRules.totalDurationMs(steps))
        }
    }

    private fun buildStepRows(steps: List<Step>, locked: Boolean) {
        stepsList.removeAllViews()
        steps.forEachIndexed { i, step ->
            stepsList.addView(TextView(this).apply {
                text = when (step) {
                    is TapStep -> getString(R.string.step_tap, i + 1, step.at.x, step.at.y)
                    is PathStep -> getString(R.string.step_path, i + 1, step.points.size)
                }
                setTextColor(getColor(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = stepTitleFace
                setPadding(0, dp(12), 0, 0)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (step is TapStep) {
                row.addView(smallLabel(getString(R.string.step_hold)))
                row.addView(numberField(step.holdMs, locked, SequenceRules::clampHold) { applyTiming(i, holdMs = it) })
                row.addView(smallLabel(getString(R.string.unit_ms)))
            }
            if (step is PathStep) {
                row.addView(smallLabel(getString(R.string.step_duration)))
                row.addView(numberField(step.durationMs, locked, SequenceRules::clampPath) { applyTiming(i, durationMs = it) })
                row.addView(smallLabel(getString(R.string.unit_ms)))
            }
            if (i < steps.size - 1) {
                row.addView(smallLabel(getString(R.string.step_gap)))
                row.addView(numberField(step.delayAfterMs, locked, SequenceRules::clampGap) { applyTiming(i, gapMs = it) })
                row.addView(smallLabel(getString(R.string.unit_ms)))
            }
            if (row.childCount > 0) stepsList.addView(row)
        }
    }

    private fun applyTiming(index: Int, gapMs: Long? = null, durationMs: Long? = null, holdMs: Long? = null) {
        val service = ClickerHub.service
        val prefs = Prefs(this)
        val sequence = service?.currentSequence ?: prefs.sequence ?: return
        val steps = sequence.steps.toMutableList()
        steps[index] = when (val s = steps.getOrNull(index) ?: return) {
            is TapStep -> s.copy(holdMs = holdMs ?: s.holdMs, delayAfterMs = gapMs ?: s.delayAfterMs)
            is PathStep -> s.copy(durationMs = durationMs ?: s.durationMs, delayAfterMs = gapMs ?: s.delayAfterMs)
        }
        if (service != null) service.updateTimings(steps) else prefs.sequence = sequence.copy(steps = steps)
        shownSteps = steps
        refreshSteps()
    }

    private fun smallLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.subtext))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun numberField(value: Long, locked: Boolean, clamp: (Long) -> Long, onValue: (Long) -> Unit) =
        EditText(this).apply {
            isEnabled = !locked
            bindNumber(this, value, clamp, onValue)
        }

    /**
     * Number field in ms: every edit is applied (clamped to the allowed range) right away, and the
     * text is corrected to the value really used when the field loses focus.
     */
    private fun bindNumber(field: EditText, value: Long, clamp: (Long) -> Long, onValue: (Long) -> Unit) {
        field.setText(value.toString())
        field.inputType = InputType.TYPE_CLASS_NUMBER
        field.filters = arrayOf(InputFilter.LengthFilter(5))
        field.imeOptions = EditorInfo.IME_ACTION_DONE
        field.minEms = 3
        field.setSelectAllOnFocus(true)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.toLongOrNull()?.let { onValue(clamp(it)) }
            }
        })
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val used = clamp(field.text.toString().toLongOrNull() ?: value)
                if (field.text.toString() != used.toString()) field.setText(used.toString())
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun onPadTouched(x: Int, y: Int, now: Long) {
        val tap = ClickerHub.service?.engine?.lastDispatched
        // Attribute by dispatch time (a run can touch several times); latency from its first touch.
        if (tap != null && now - tap.dispatchedAt in 0..(tap.durationMs + PAD_MATCH_MS)) {
            padInjected++
            if (tap.id != lastMatchedTapId) {
                lastMatchedTapId = tap.id
                padLatency.record(now - tap.keyEventTime)
            }
        } else {
            padFinger++
        }
        lastTouch = x to y
        if (Log.isLoggable(ClickerService.TAG, Log.DEBUG)) {
            Log.d(ClickerService.TAG, "Pad touch at $x,$y injected=$padInjected finger=$padFinger key->touch ${padLatency.last} ms")
        }
        refresh()
    }

    private fun formatEngineStats(engine: ClickEngine): String = listOf(
        getString(R.string.stats_key_sent, stats(engine.dispatchLatency)),
        getString(R.string.stats_key_done, stats(engine.completeLatency)),
        getString(
            R.string.stats_taps,
            engine.tapsDispatched, engine.tapsCompleted, engine.tapsCancelled,
            engine.tapsFailed, engine.tapsDropped, engine.tapsLost,
        ),
        getString(R.string.stats_swallowed, engine.repeatsSwallowed, engine.pressesWhilePaused + engine.pressesWithoutTarget),
    ).joinToString("\n")

    private fun formatPadStats(engine: ClickEngine?): String = buildString {
        append(getString(R.string.pad_counts, padInjected, padFinger)).append('\n')
        append(getString(R.string.pad_latency, stats(padLatency)))
        val touch = lastTouch
        if (touch != null) {
            append('\n').append(getString(R.string.pad_last_touch, touch.first, touch.second))
            val nearest = engine?.sequence?.steps?.withIndex()?.minByOrNull { (_, step) ->
                val dx = (step.start.x - touch.first).toLong()
                val dy = (step.start.y - touch.second).toLong()
                dx * dx + dy * dy
            }
            if (nearest != null) {
                val p = nearest.value.start
                append(getString(R.string.pad_offset, nearest.index + 1, p.x, p.y, touch.first - p.x, touch.second - p.y))
            }
        }
    }

    private fun stats(s: LatencyStats): String =
        if (s.size == 0) {
            getString(R.string.stats_none)
        } else {
            getString(R.string.stats_line, s.last, s.average(), s.percentile(0.95), s.max(), s.total)
        }

    private fun stopReasonText(reason: StopReason): String = getString(
        when (reason) {
            StopReason.STOPPED_FROM_BUBBLE -> R.string.stop_from_bubble
            StopReason.STOPPED_FROM_APP -> R.string.stop_from_app
            StopReason.SELECTION_CANCELLED -> R.string.stop_selection_cancelled
            StopReason.SCREEN_OFF -> R.string.stop_screen_off
            StopReason.SERVICE_TURNED_OFF -> R.string.stop_service_off
            StopReason.INTERRUPTED -> R.string.stop_interrupted
            StopReason.INTERNAL_ERROR -> R.string.stop_internal_error
        },
    )

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
