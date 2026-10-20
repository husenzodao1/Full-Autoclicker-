package com.fullautoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.fullautoclicker.core.DisplayGeometry
import com.fullautoclicker.service.Prefs
import com.fullautoclicker.service.Screen
import kotlin.math.abs

/**
 * Owns every floating window: the bubble, its menu, the target marker and the selection panel.
 *
 * All windows are TYPE_ACCESSIBILITY_OVERLAY, added through the accessibility service, so no
 * "display over other apps" permission is needed. They are laid out in raw display pixels
 * (gravity TOP|LEFT, no inset fitting, cutout allowed), which is the coordinate space that
 * gesture dispatch uses. Main thread only.
 */
@SuppressLint("ClickableViewAccessibility")
class OverlayController(
    private val context: Context,
    private val screen: Screen,
    private val prefs: Prefs,
    private val actions: Actions,
) {
    interface Actions {
        fun onStartPressed()
        fun onStopPressed()
        fun onHidePressed()
        fun onReadyPressed(x: Int, y: Int)
        fun onCancelPressed()
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val location = IntArray(2)

    private val bubbleSize = px(48)
    private val bubble = BubbleView(context)
    private val bubbleParams = windowParams(bubbleSize, bubbleSize, "bubble")
    var isBubbleShowing = false
        private set

    private var menu: LinearLayout? = null
    private var menuStatus: TextView? = null
    private var menuHiddenAt = 0L
    val isMenuShowing: Boolean get() = menu != null

    private val markerSize = px(132)
    private var marker: MarkerView? = null
    private val markerParams = windowParams(markerSize, markerSize, "marker")
    private var panel: LinearLayout? = null
    private var panelCoords: TextView? = null
    private var panelAtTop = true
    val isSelectionShowing: Boolean get() = marker != null

    /** Armed target the bubble and menu must stay away from; null when not armed. */
    private var guardedX = -1
    private var guardedY = -1
    private var statusText = "IDLE"

    init {
        bubble.setOnTouchListener(BubbleTouch())
    }

    // ---------------------------------------------------------------- state from the service

    fun render(look: BubbleView.Look, status: String, armedX: Int, armedY: Int) {
        bubble.look = look
        statusText = status
        menuStatus?.text = status
        guardedX = armedX
        guardedY = armedY
        if (armedX >= 0) keepBubbleOffTarget()
    }

    // ---------------------------------------------------------------- bubble

    fun showBubble() {
        if (isBubbleShowing) return
        val g = screen.geometry()
        bubbleParams.x = prefs.bubbleX.takeIf { it >= 0 } ?: (g.width - bubbleSize - px(8))
        bubbleParams.y = prefs.bubbleY.takeIf { it >= 0 } ?: (g.height / 3)
        clampBubble(g)
        keepBubbleOffTarget(update = false)
        isBubbleShowing = addWindow(bubble, bubbleParams)
    }

    /** HIDE: removes the bubble and menu. The clicker's mode is not changed. */
    fun hideControls() {
        hideMenu()
        if (isBubbleShowing) removeWindow(bubble)
        isBubbleShowing = false
    }

    private fun clampBubble(g: DisplayGeometry) {
        bubbleParams.x = bubbleParams.x.coerceIn(0, (g.width - bubbleSize).coerceAtLeast(0))
        bubbleParams.y = bubbleParams.y.coerceIn(0, (g.height - bubbleSize).coerceAtLeast(0))
    }

    /** Moves the bubble if it sits on the armed target, so a tap can never land on our own window. */
    private fun keepBubbleOffTarget(update: Boolean = true) {
        if (guardedX < 0) return
        val margin = px(12)
        val r = Rect(bubbleParams.x, bubbleParams.y, bubbleParams.x + bubbleSize, bubbleParams.y + bubbleSize)
        r.inset(-margin, -margin)
        if (!r.contains(guardedX, guardedY)) return
        val g = screen.geometry()
        val candidates = listOf(
            guardedX + margin to bubbleParams.y,
            guardedX - margin - bubbleSize to bubbleParams.y,
            bubbleParams.x to guardedY + margin,
            bubbleParams.x to guardedY - margin - bubbleSize,
        )
        val spot = candidates.firstOrNull { (x, y) ->
            x >= 0 && y >= 0 && x + bubbleSize <= g.width && y + bubbleSize <= g.height
        } ?: candidates.first()
        bubbleParams.x = spot.first
        bubbleParams.y = spot.second
        clampBubble(g)
        if (update && isBubbleShowing) updateWindow(bubble, bubbleParams)
        saveBubblePosition()
    }

    private fun saveBubblePosition() {
        prefs.bubbleX = bubbleParams.x
        prefs.bubbleY = bubbleParams.y
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var menuWasOpen = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                    // The menu's outside-touch handler may close it before or after this event.
                    menuWasOpen = isMenuShowing || SystemClock.uptimeMillis() - menuHiddenAt < 300
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        hideMenu()
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        clampBubble(screen.geometry())
                        updateWindow(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        keepBubbleOffTarget()
                        saveBubblePosition()
                    } else {
                        v.performClick()
                        if (menuWasOpen) hideMenu() else showMenu()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        if (isMenuShowing || !isBubbleShowing) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF2202124.toInt(), px(14).toFloat())
            setPadding(px(8), px(6), px(8), px(8))
        }
        val status = label(statusText, 12f, 0xCCFFFFFF.toInt()).apply {
            setPadding(px(6), 0, px(6), px(4))
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton("START", 0xFF2E7D32.toInt()) { hideMenu(); actions.onStartPressed() })
        row.addView(actionButton("STOP", 0xFFC62828.toInt()) { hideMenu(); actions.onStopPressed() })
        row.addView(actionButton("HIDE", 0xFF455A64.toInt()) { hideMenu(); actions.onHidePressed() })
        root.addView(status)
        root.addView(row)
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hideMenu()
                true
            } else {
                false
            }
        }

        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val g = screen.geometry()
        val w = root.measuredWidth
        val h = root.measuredHeight
        val params = windowParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, "menu")
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        params.x = (bubbleParams.x + bubbleSize / 2 - w / 2).coerceIn(0, (g.width - w).coerceAtLeast(0))
        val below = bubbleParams.y + bubbleSize + px(6)
        params.y = if (below + h <= g.height) below else (bubbleParams.y - h - px(6)).coerceAtLeast(0)

        if (addWindow(root, params)) {
            menu = root
            menuStatus = status
        }
    }

    fun hideMenu() {
        val m = menu ?: return
        menu = null
        menuStatus = null
        menuHiddenAt = SystemClock.uptimeMillis()
        removeWindow(m)
    }

    // ---------------------------------------------------------------- target selection

    /** START: shows the marker centered on ([centerX], [centerY]) or mid-screen if invalid. */
    fun showSelection(centerX: Int, centerY: Int) {
        val g = screen.geometry()
        val half = markerSize / 2
        val cx = if (centerX in 0 until g.width && centerY in 0 until g.height) centerX else g.width / 2
        val cy = if (centerX in 0 until g.width && centerY in 0 until g.height) centerY else g.height / 2
        if (marker == null) {
            val view = MarkerView(context)
            view.setOnTouchListener(MarkerTouch())
            markerParams.x = cx - half
            markerParams.y = cy - half
            clampMarker(g)
            if (!addWindow(view, markerParams)) return
            marker = view
        }
        if (panel == null) showPanel()
        onMarkerMoved()
    }

    fun hideSelection() {
        marker?.let { removeWindow(it) }
        marker = null
        panel?.let { removeWindow(it) }
        panel = null
        panelCoords = null
    }

    private fun clampMarker(g: DisplayGeometry) {
        val half = markerSize / 2
        // The center may reach every pixel, including the very screen edges.
        markerParams.x = markerParams.x.coerceIn(-half, g.width - 1 - half)
        markerParams.y = markerParams.y.coerceIn(-half, g.height - 1 - half)
    }

    private fun moveMarkerBy(dx: Int, dy: Int) {
        val m = marker ?: return
        markerParams.x += dx
        markerParams.y += dy
        clampMarker(screen.geometry())
        updateWindow(m, markerParams)
        onMarkerMoved()
    }

    private fun onMarkerMoved() {
        val x = markerParams.x + markerSize / 2
        val y = markerParams.y + markerSize / 2
        panelCoords?.text = "X $x   Y $y"
        val wantTop = y > screen.geometry().height / 2
        if (wantTop != panelAtTop && panel != null) {
            panelAtTop = wantTop
            placePanel()
        }
    }

    private inner class MarkerTouch : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = markerParams.x
                    startY = markerParams.y
                }
                MotionEvent.ACTION_MOVE -> {
                    // Relative drag: the finger can hold any part of the marker, so it never
                    // has to cover the center point being aimed.
                    markerParams.x = startX + (e.rawX - downRawX).toInt()
                    markerParams.y = startY + (e.rawY - downRawY).toInt()
                    clampMarker(screen.geometry())
                    updateWindow(v, markerParams)
                    onMarkerMoved()
                }
                MotionEvent.ACTION_UP -> v.performClick()
            }
            return true
        }
    }

    private fun showPanel() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(0xF2202124.toInt(), px(16).toFloat())
            setPadding(px(12), px(10), px(12), px(10))
        }
        root.addView(label("Drag the marker onto the target", 13f, 0xDDFFFFFF.toInt()))
        val coords = label("", 15f, 0xFFFFFFFF.toInt()).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, px(4), 0, px(6))
        }
        root.addView(coords)
        val nudges = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        nudges.addView(nudgeButton("◀", -1, 0, "Move left 1 pixel"))
        nudges.addView(nudgeButton("▲", 0, -1, "Move up 1 pixel"))
        nudges.addView(nudgeButton("▼", 0, 1, "Move down 1 pixel"))
        nudges.addView(nudgeButton("▶", 1, 0, "Move right 1 pixel"))
        root.addView(nudges)
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("CANCEL", 0xFF455A64.toInt()) { actions.onCancelPressed() })
        buttons.addView(actionButton("READY", 0xFF2E7D32.toInt()) { confirmTarget() })
        root.addView(buttons)

        val params = windowParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, "panel")
        panel = root
        panelCoords = coords
        panelAtTop = markerParams.y + markerSize / 2 > screen.geometry().height / 2
        positionPanel(params)
        if (!addWindow(root, params)) {
            panel = null
            panelCoords = null
        }
    }

    private fun positionPanel(params: WindowManager.LayoutParams) {
        val root = panel ?: return
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val g = screen.geometry()
        params.x = ((g.width - root.measuredWidth) / 2).coerceAtLeast(0)
        params.y = if (panelAtTop) px(48) else (g.height - root.measuredHeight - px(48)).coerceAtLeast(0)
    }

    private fun placePanel() {
        val root = panel ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        positionPanel(params)
        updateWindow(root, params)
    }

    private fun confirmTarget() {
        val m = marker ?: return
        val g = screen.geometry()
        // Prefer where the window really is on screen; fall back to the requested position.
        m.getLocationOnScreen(location)
        val laidOut = m.isLaidOut && m.width > 0
        var x = if (laidOut) location[0] + m.width / 2 else markerParams.x + markerSize / 2
        var y = if (laidOut) location[1] + m.height / 2 else markerParams.y + markerSize / 2
        val requestedX = markerParams.x + markerSize / 2
        val requestedY = markerParams.y + markerSize / 2
        if (x != requestedX || y != requestedY) {
            Log.w(TAG, "Marker window at ($x,$y), requested ($requestedX,$requestedY)")
        }
        x = x.coerceIn(0, (g.width - 1).coerceAtLeast(0))
        y = y.coerceIn(0, (g.height - 1).coerceAtLeast(0))
        actions.onReadyPressed(x, y)
    }

    // ---------------------------------------------------------------- display changes and taps

    /** Rotation or resolution changed: keep every window on screen. */
    fun onDisplayChanged() {
        val g = screen.geometry()
        hideMenu()
        if (isBubbleShowing) {
            clampBubble(g)
            keepBubbleOffTarget(update = false)
            updateWindow(bubble, bubbleParams)
        }
        marker?.let {
            clampMarker(g)
            updateWindow(it, markerParams)
            onMarkerMoved()
            placePanel()
        }
    }

    /**
     * Makes sure none of our windows is under ([x], [y]) before a tap is injected there.
     * Returns true if a window had to be closed or moved; the caller should then give the
     * window system a moment before tapping.
     */
    fun clearPointForTap(x: Int, y: Int): Boolean {
        var moved = false
        menu?.let {
            if (windowContains(it, x, y)) moved = true
            hideMenu() // Pressing Volume Up while the menu is open also closes it.
        }
        if (isBubbleShowing && windowContains(bubble, x, y)) {
            guardedX = x
            guardedY = y
            keepBubbleOffTarget()
            moved = true
        }
        if (marker != null || panel != null) {
            hideSelection()
            moved = true
        }
        return moved
    }

    private fun windowContains(view: View, x: Int, y: Int): Boolean {
        if (!view.isAttachedToWindow) return false
        view.getLocationOnScreen(location)
        return x >= location[0] && x < location[0] + view.width &&
            y >= location[1] && y < location[1] + view.height
    }

    fun removeAll() {
        hideMenu()
        hideSelection()
        if (isBubbleShowing) removeWindow(bubble)
        isBubbleShowing = false
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- helpers

    private fun windowParams(w: Int, h: Int, name: String) = WindowManager.LayoutParams(
        w,
        h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = "FullAutoClicker:$name"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun addWindow(view: View, params: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, params)
        true
    } catch (e: RuntimeException) {
        // BadTokenException when the service is being disconnected, among others.
        Log.e(TAG, "addView failed for ${params.title}", e)
        false
    }

    private fun updateWindow(view: View, params: WindowManager.LayoutParams) {
        if (!view.isAttachedToWindow) return
        try {
            wm.updateViewLayout(view, params)
        } catch (e: RuntimeException) {
            Log.e(TAG, "updateViewLayout failed for ${params.title}", e)
        }
    }

    private fun removeWindow(view: View) {
        try {
            wm.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "removeView failed", e)
        }
    }

    private fun px(dp: Int): Int = (dp * density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun label(text: String, sp: Float, color: Int) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        maxLines = 1
    }

    private fun actionButton(text: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = px(76)
        minHeight = px(46)
        setPadding(px(12), 0, px(12), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(0x55FFFFFF),
            rounded(color, px(10).toFloat()),
            ColorDrawable(0xFFFFFFFF.toInt()),
        )
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(px(3), px(3), px(3), px(3)) }
    }

    private fun nudgeButton(text: String, dx: Int, dy: Int, description: String): TextView {
        val button = actionButton(text, 0xFF37474F.toInt()) {}
        button.minWidth = px(52)
        button.contentDescription = description
        val repeat = object : Runnable {
            override fun run() {
                moveMarkerBy(dx, dy)
                handler.postDelayed(this, 50)
            }
        }
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    moveMarkerBy(dx, dy)
                    handler.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
        return button
    }

    companion object {
        private const val TAG = "AutoClicker"
    }
}
