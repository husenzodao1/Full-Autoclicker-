package com.fullautoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import com.fullautoclicker.R
import com.fullautoclicker.core.DisplayGeometry
import com.fullautoclicker.core.PathStep
import com.fullautoclicker.core.Point
import com.fullautoclicker.core.SequenceRules
import com.fullautoclicker.core.Step
import com.fullautoclicker.core.TapStep
import com.fullautoclicker.service.Prefs
import com.fullautoclicker.service.Screen
import kotlin.math.abs

/**
 * Owns every floating window: the bubble, its menu, and while choosing steps the numbered tap
 * markers, the layer that shows recorded drawings, the drawing surface and the selection panel.
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
        fun onReadyPressed(steps: List<Step>)
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

    // ---- step selection
    private sealed class Draft {
        abstract var gapMs: Long

        class Tap(
            val view: MarkerView,
            val params: WindowManager.LayoutParams,
            val holdMs: Long,
            override var gapMs: Long,
        ) : Draft()

        class Path(val points: List<Point>, val durationMs: Long, override var gapMs: Long) : Draft()
    }

    private val markerSize = px(132)
    private val drafts = ArrayList<Draft>()
    private var activeTap: Draft.Tap? = null
    private var selecting = false
    private var defaultHoldMs = SequenceRules.DEFAULT_HOLD_MS
    private var defaultGapMs = SequenceRules.DEFAULT_GAP_MS
    private var defaultPathMs = SequenceRules.DEFAULT_PATH_MS
    private var pathsView: PathsView? = null
    private var drawView: DrawView? = null
    private var panel: LinearLayout? = null
    private var panelStatus: TextView? = null
    private val panelAddButtons = ArrayList<TextView>()
    private var panelReady: TextView? = null
    private var panelUndo: TextView? = null
    private var panelAtTop = true
    val isSelectionShowing: Boolean get() = selecting

    /** Where armed runs first touch the screen; the bubble and menu must stay off these points. */
    private var guarded: List<Point> = emptyList()
    private var statusText = context.getString(R.string.overlay_status_idle)

    init {
        bubble.setOnTouchListener(BubbleTouch())
    }

    // ---------------------------------------------------------------- state from the service

    fun render(look: BubbleView.Look, status: String, guardPoints: List<Point>) {
        bubble.look = look
        statusText = status
        menuStatus?.text = status
        guarded = guardPoints
        if (guarded.isNotEmpty()) keepBubbleOffTargets()
    }

    // ---------------------------------------------------------------- bubble

    fun showBubble() {
        if (isBubbleShowing) return
        val g = screen.geometry()
        bubbleParams.x = prefs.bubbleX.takeIf { it >= 0 } ?: (g.width - bubbleSize - px(8))
        bubbleParams.y = prefs.bubbleY.takeIf { it >= 0 } ?: (g.height / 3)
        clampBubble(g)
        keepBubbleOffTargets(update = false)
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

    private fun bubbleCovers(x: Int, y: Int, points: List<Point>): Boolean {
        val margin = px(12)
        val r = Rect(x - margin, y - margin, x + bubbleSize + margin, y + bubbleSize + margin)
        return points.any { r.contains(it.x, it.y) }
    }

    /** Moves the bubble off every armed touch point, so a run can never land on our own window. */
    private fun keepBubbleOffTargets(update: Boolean = true) {
        if (guarded.isEmpty() || !bubbleCovers(bubbleParams.x, bubbleParams.y, guarded)) return
        val g = screen.geometry()
        val step = bubbleSize + px(16)
        val candidates = ArrayList<Pair<Int, Int>>()
        for (k in 1..4) {
            candidates += bubbleParams.x - k * step to bubbleParams.y
            candidates += bubbleParams.x + k * step to bubbleParams.y
            candidates += bubbleParams.x to bubbleParams.y - k * step
            candidates += bubbleParams.x to bubbleParams.y + k * step
        }
        var y = 0
        while (y + bubbleSize <= g.height) {
            candidates += g.width - bubbleSize to y
            candidates += 0 to y
            y += step
        }
        val spot = candidates.firstOrNull { (x, cy) ->
            x >= 0 && cy >= 0 && x + bubbleSize <= g.width && cy + bubbleSize <= g.height && !bubbleCovers(x, cy, guarded)
        } ?: return
        bubbleParams.x = spot.first
        bubbleParams.y = spot.second
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
                        bubbleParams.x = startX + (e.rawX - downRawX).toInt()
                        bubbleParams.y = startY + (e.rawY - downRawY).toInt()
                        clampBubble(screen.geometry())
                        updateWindow(bubble, bubbleParams)
                        keepBubbleOffTargets()
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
        row.addView(actionButton(str(R.string.overlay_start), 0xFF2E7D32.toInt()) { hideMenu(); actions.onStartPressed() })
        row.addView(actionButton(str(R.string.overlay_stop), 0xFFC62828.toInt()) { hideMenu(); actions.onStopPressed() })
        row.addView(actionButton(str(R.string.overlay_hide), 0xFF455A64.toInt()) { hideMenu(); actions.onHidePressed() })
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

    // ---------------------------------------------------------------- step selection

    /**
     * START: shows the steps of [initial] (or one tap marker mid-screen when empty) for editing.
     * New taps are held for [holdMs], new steps get [gapMs] as their pause and drawings get
     * [pathMs] as their duration.
     */
    fun showSelection(initial: List<Step>, holdMs: Long, gapMs: Long, pathMs: Long) {
        if (selecting) return
        selecting = true
        defaultHoldMs = holdMs
        defaultGapMs = gapMs
        defaultPathMs = pathMs
        val g = screen.geometry()

        val paths = PathsView(context)
        if (addWindow(paths, fullScreenParams("paths", touchable = false))) pathsView = paths

        for (step in initial.take(SequenceRules.MAX_STEPS)) {
            when (step) {
                is TapStep -> addTapDraft(step.at, step.holdMs, step.delayAfterMs)
                is PathStep -> drafts += Draft.Path(step.points, step.durationMs, step.delayAfterMs)
            }
        }
        if (drafts.isEmpty()) addTapDraft(Point(g.width / 2, g.height / 2), defaultHoldMs, defaultGapMs)
        activeTap = drafts.lastOrNull { it is Draft.Tap } as Draft.Tap?
        refreshDrafts()
        showPanel()
    }

    fun hideSelection() {
        if (!selecting) return
        selecting = false
        removeDrawView()
        for (d in drafts) if (d is Draft.Tap) removeWindow(d.view)
        drafts.clear()
        activeTap = null
        pathsView?.let { removeWindow(it) }
        pathsView = null
        removePanel()
    }

    private fun addTapDraft(at: Point, holdMs: Long, gapMs: Long) {
        val view = MarkerView(context)
        val params = windowParams(markerSize, markerSize, "marker")
        params.x = at.x - markerSize / 2
        params.y = at.y - markerSize / 2
        clampMarker(params, screen.geometry())
        if (!addWindow(view, params)) return
        val draft = Draft.Tap(view, params, holdMs, gapMs)
        view.setOnTouchListener(MarkerTouch(draft))
        drafts += draft
        activeTap = draft
    }

    private fun onAddPoint() {
        if (drafts.size >= SequenceRules.MAX_STEPS) return
        val g = screen.geometry()
        val offset = (drafts.size % 5) * px(24)
        addTapDraft(Point(g.width / 2 + offset, g.height / 2 + offset), defaultHoldMs, defaultGapMs)
        // A window added later sits above the panel; bring the panel back on top.
        reAddPanel()
        refreshDrafts()
    }

    private fun onAddDrawing() {
        if (drafts.size >= SequenceRules.MAX_STEPS || drawView != null) return
        removePanel()
        val view = DrawView(context, str(R.string.draw_hint)) { points -> onDrawingFinished(points) }
        if (addWindow(view, fullScreenParams("draw", touchable = true))) drawView = view else showPanel()
    }

    private fun onDrawingFinished(points: List<Point>) {
        removeDrawView()
        if (SequenceRules.pathLength(points) >= SequenceRules.MIN_PATH_LENGTH_PX) {
            drafts += Draft.Path(SequenceRules.simplify(points), defaultPathMs, defaultGapMs)
        }
        refreshDrafts()
        showPanel()
    }

    private fun removeDrawView() {
        drawView?.let { removeWindow(it) }
        drawView = null
    }

    private fun onUndo() {
        val last = drafts.removeLastOrNull() ?: return
        if (last is Draft.Tap) {
            removeWindow(last.view)
            if (activeTap === last) activeTap = drafts.lastOrNull { it is Draft.Tap } as Draft.Tap?
        }
        refreshDrafts()
    }

    /** Renumbers markers, redraws recorded paths and updates the panel. */
    private fun refreshDrafts() {
        drafts.forEachIndexed { i, d -> if (d is Draft.Tap) d.view.number = i + 1 }
        pathsView?.paths = drafts.mapIndexedNotNull { i, d -> if (d is Draft.Path) (i + 1) to d.points else null }
        updatePanel()
    }

    private fun clampMarker(params: WindowManager.LayoutParams, g: DisplayGeometry) {
        val half = markerSize / 2
        // The center may reach every pixel, including the very screen edges.
        params.x = params.x.coerceIn(-half, g.width - 1 - half)
        params.y = params.y.coerceIn(-half, g.height - 1 - half)
    }

    private fun moveActiveBy(dx: Int, dy: Int) {
        val d = activeTap ?: return
        d.params.x += dx
        d.params.y += dy
        clampMarker(d.params, screen.geometry())
        updateWindow(d.view, d.params)
        updatePanel()
    }

    private inner class MarkerTouch(private val draft: Draft.Tap) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = draft.params.x
                    startY = draft.params.y
                    activeTap = draft
                    updatePanel()
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    // Relative drag: the finger can hold any part of the marker, so it never
                    // has to cover the center point being aimed. The up event carries the
                    // final finger position too.
                    draft.params.x = startX + (e.rawX - downRawX).toInt()
                    draft.params.y = startY + (e.rawY - downRawY).toInt()
                    clampMarker(draft.params, screen.geometry())
                    updateWindow(v, draft.params)
                    updatePanel()
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            return true
        }
    }

    private fun showPanel() {
        if (panel != null || !selecting) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(0xF2202124.toInt(), px(16).toFloat())
            setPadding(px(12), px(10), px(12), px(10))
        }
        root.addView(label(str(R.string.overlay_drag_hint), 13f, 0xDDFFFFFF.toInt()))
        val status = label("", 15f, 0xFFFFFFFF.toInt()).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, px(4), 0, px(6))
        }
        root.addView(status)
        val nudges = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        nudges.addView(nudgeButton("◀", -1, 0, str(R.string.nudge_left)))
        nudges.addView(nudgeButton("▲", 0, -1, str(R.string.nudge_up)))
        nudges.addView(nudgeButton("▼", 0, 1, str(R.string.nudge_down)))
        nudges.addView(nudgeButton("▶", 1, 0, str(R.string.nudge_right)))
        root.addView(nudges)
        val add = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val addPoint = actionButton(str(R.string.overlay_add_point), 0xFF1565C0.toInt()) { onAddPoint() }
        val addDrawing = actionButton(str(R.string.overlay_add_drawing), 0xFF6A1B9A.toInt()) { onAddDrawing() }
        val undo = actionButton("↶", 0xFF37474F.toInt()) { onUndo() }.apply {
            minWidth = px(52)
            contentDescription = str(R.string.overlay_undo)
        }
        add.addView(addPoint)
        add.addView(addDrawing)
        add.addView(undo)
        root.addView(add)
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton(str(R.string.overlay_cancel), 0xFF455A64.toInt()) { actions.onCancelPressed() })
        val ready = actionButton(str(R.string.overlay_ready), 0xFF2E7D32.toInt()) { confirmSteps() }
        buttons.addView(ready)
        root.addView(buttons)

        panel = root
        panelStatus = status
        panelAddButtons.clear()
        panelAddButtons += addPoint
        panelAddButtons += addDrawing
        panelUndo = undo
        panelReady = ready
        updatePanel()
        val params = windowParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, "panel")
        positionPanel(params)
        if (!addWindow(root, params)) removePanelRefs()
    }

    private fun reAddPanel() {
        removePanel()
        showPanel()
    }

    private fun removePanel() {
        panel?.let { removeWindow(it) }
        removePanelRefs()
    }

    private fun removePanelRefs() {
        panel = null
        panelStatus = null
        panelAddButtons.clear()
        panelUndo = null
        panelReady = null
    }

    private fun updatePanel() {
        val active = activeTap
        val steps = context.getString(R.string.overlay_steps, drafts.size, SequenceRules.MAX_STEPS)
        panelStatus?.text = if (active != null) {
            val index = drafts.indexOf(active) + 1
            steps + "  " + context.getString(
                R.string.overlay_active,
                index,
                active.params.x + markerSize / 2,
                active.params.y + markerSize / 2,
            )
        } else {
            steps
        }
        val canAdd = drafts.size < SequenceRules.MAX_STEPS
        for (b in panelAddButtons) setEnabledLook(b, canAdd)
        panelUndo?.let { setEnabledLook(it, drafts.isNotEmpty()) }
        panelReady?.let { setEnabledLook(it, drafts.isNotEmpty()) }

        val activeY = active?.let { it.params.y + markerSize / 2 } ?: 0
        val wantTop = activeY > screen.geometry().height / 2
        if (wantTop != panelAtTop) {
            panelAtTop = wantTop
            placePanel()
        }
    }

    private fun setEnabledLook(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
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

    private fun confirmSteps() {
        if (drafts.isEmpty()) return
        val steps = drafts.map { d ->
            when (d) {
                is Draft.Tap -> TapStep(markerCenter(d), d.holdMs, d.gapMs)
                is Draft.Path -> PathStep(d.points, d.durationMs, d.gapMs)
            }
        }
        actions.onReadyPressed(steps)
    }

    /** Where the marker window really is on screen; falls back to the requested position. */
    private fun markerCenter(d: Draft.Tap): Point {
        d.view.getLocationOnScreen(location)
        val laidOut = d.view.isLaidOut && d.view.width > 0
        val x = if (laidOut) location[0] + d.view.width / 2 else d.params.x + markerSize / 2
        val y = if (laidOut) location[1] + d.view.height / 2 else d.params.y + markerSize / 2
        val requestedX = d.params.x + markerSize / 2
        val requestedY = d.params.y + markerSize / 2
        if (x != requestedX || y != requestedY) Log.w(TAG, "Marker window at ($x,$y), requested ($requestedX,$requestedY)")
        return Point(x, y)
    }

    // ---------------------------------------------------------------- display changes and runs

    /** Rotation or resolution changed: keep every window on screen. */
    fun onDisplayChanged() {
        val g = screen.geometry()
        hideMenu()
        if (isBubbleShowing) {
            clampBubble(g)
            keepBubbleOffTargets(update = false)
            updateWindow(bubble, bubbleParams)
        }
        for (d in drafts) {
            if (d is Draft.Tap) {
                clampMarker(d.params, g)
                updateWindow(d.view, d.params)
            }
        }
        if (selecting) {
            placePanel()
            updatePanel()
        }
    }

    /**
     * Makes sure none of our windows is under any of [points] before a run is injected.
     * Returns true if a window had to be closed or moved; the caller should then give the
     * window system a moment before tapping.
     */
    fun clearPointsForRun(points: List<Point>): Boolean {
        var moved = false
        menu?.let { m ->
            if (points.any { windowContains(m, it.x, it.y) }) moved = true
            hideMenu() // Pressing Volume Up while the menu is open also closes it.
        }
        if (isBubbleShowing && points.any { windowContains(bubble, it.x, it.y) }) {
            guarded = points
            keepBubbleOffTargets()
            moved = true
        }
        if (selecting) {
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

    private fun fullScreenParams(name: String, touchable: Boolean): WindowManager.LayoutParams {
        val g = screen.geometry()
        return windowParams(g.width, g.height, name).apply {
            if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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

    private fun str(id: Int): String = context.getString(id)

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
                moveActiveBy(dx, dy)
                handler.postDelayed(this, 50)
            }
        }
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    moveActiveBy(dx, dy)
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
