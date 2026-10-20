package com.fullautoclicker.overlay

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.LocaleManager
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
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
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
    strings: Context,
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

        /** Steps of the armed (or last saved) sequence, for the settings panel. */
        fun currentSteps(): List<Step>
        fun isChoosingSteps(): Boolean
        fun onTimingsEdited(steps: List<Step>)

        /** Re-reads the app language (it can also be changed in the phone's settings). */
        fun refreshLanguage()
    }

    /**
     * Strings come from here, not from [context]: Android applies the per-app language to
     * activities only, so the service's own resources stay in the system language.
     */
    private var strings: Context = strings

    private val wm = context.getSystemService(WindowManager::class.java)
    private val ui = Ui(context)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val location = IntArray(2)

    private val bubbleSize = px(46)
    private val bubble = BubbleView(context)
    private val bubbleParams = windowParams(bubbleSize, bubbleSize, "bubble")
    var isBubbleShowing = false
        private set

    private var menu: LinearLayout? = null
    private var menuStatus: TextView? = null
    private var menuHiddenAt = 0L
    val isMenuShowing: Boolean get() = menu != null

    private var settings: View? = null

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
    private val panelAddButtons = ArrayList<View>()
    private var panelReady: View? = null
    private var panelUndo: View? = null
    val isSelectionShowing: Boolean get() = selecting

    /** Where armed runs first touch the screen; the bubble and menu must stay off these points. */
    private var guarded: List<Point> = emptyList()
    private var statusText = str(R.string.overlay_status_idle)

    init {
        bubble.setOnTouchListener(BubbleTouch())
    }

    // ---------------------------------------------------------------- state from the service

    fun render(look: BubbleView.Look, status: String, guardPoints: List<Point>) {
        bubble.look = look
        bubble.contentDescription = str(
            when (look) {
                BubbleView.Look.IDLE -> R.string.bubble_desc_idle
                BubbleView.Look.SELECTING -> R.string.bubble_desc_selecting
                BubbleView.Look.ARMED -> R.string.bubble_desc_armed
                BubbleView.Look.PAUSED -> R.string.bubble_desc_paused
            },
        )
        statusText = status
        menuStatus?.text = status
        guarded = guardPoints
        if (guarded.isNotEmpty()) keepBubbleOffTargets()
    }

    /** New app language: windows built from now on use it; open ones are refreshed. */
    fun setLanguage(strings: Context) {
        this.strings = strings
        hideMenu()
        if (settings != null) {
            hideSettings()
            showSettings()
        }
        if (panel != null) reAddPanel()
        refreshDrafts()
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
        hideSettings()
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
        actions.refreshLanguage()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ui.glass(24f)
            setPadding(px(10), px(10), px(10), px(8))
        }
        val status = ui.text(statusText, 11f, Ui.MUTED, ui.display).apply {
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(px(6), 0, px(6), px(6))
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ui.iconButton(Icon.PLAY, Ui.MINT, str(R.string.overlay_start), str(R.string.overlay_start)) {
            hideMenu(); actions.onStartPressed()
        })
        row.addView(ui.iconButton(Icon.STOP, Ui.CORAL, str(R.string.overlay_stop), str(R.string.overlay_stop)) {
            hideMenu(); actions.onStopPressed()
        })
        row.addView(ui.iconButton(Icon.SETTINGS, Ui.TEXT, str(R.string.overlay_settings), str(R.string.overlay_settings)) {
            hideMenu(); showSettings()
        })
        row.addView(ui.iconButton(Icon.HIDE, Ui.MUTED, str(R.string.overlay_hide), str(R.string.overlay_hide)) {
            hideMenu(); actions.onHidePressed()
        })
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
        // Exact size: a WRAP_CONTENT window may first be measured narrower and clip the labels.
        val params = windowParams(w, h, "menu")
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val margin = px(8)
        params.x = (bubbleParams.x + bubbleSize / 2 - w / 2).coerceIn(margin, (g.width - w - margin).coerceAtLeast(margin))
        val below = bubbleParams.y + bubbleSize + px(8)
        params.y = if (below + h <= g.height) below else (bubbleParams.y - h - px(8)).coerceAtLeast(0)

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
        hideSettings()
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
        drafts.forEachIndexed { i, d ->
            if (d is Draft.Tap) {
                d.view.number = i + 1
                d.view.contentDescription = strings.getString(R.string.marker_desc_numbered, i + 1)
            }
        }
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
            background = ui.glass(24f)
            setPadding(px(16), px(14), px(16), px(12))
        }
        val status = ui.text("", 14f, Ui.TEXT, ui.display).apply {
            fontFeatureSettings = "tnum"
            maxLines = 1
        }
        root.addView(status)
        root.addView(ui.text(str(R.string.overlay_drag_hint), 12f, Ui.MUTED).apply {
            setPadding(0, px(4), 0, px(8))
        })
        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val addPoint = ui.iconButton(Icon.ADD_POINT, Ui.SKY, str(R.string.overlay_add_point), str(R.string.overlay_add_point)) { onAddPoint() }
        val addDrawing = ui.iconButton(Icon.DRAW, Ui.VIOLET, str(R.string.overlay_add_drawing), str(R.string.overlay_add_drawing)) { onAddDrawing() }
        val undo = ui.iconButton(Icon.UNDO, Ui.TEXT, str(R.string.overlay_undo_short), str(R.string.overlay_undo)) { onUndo() }
        tools.addView(addPoint)
        tools.addView(addDrawing)
        tools.addView(undo)
        // Space, not View: a plain View measured "at most" would take the whole screen width.
        tools.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        for ((icon, delta, description) in listOf(
            Triple(Icon.LEFT, -1 to 0, R.string.nudge_left),
            Triple(Icon.UP, 0 to -1, R.string.nudge_up),
            Triple(Icon.DOWN, 0 to 1, R.string.nudge_down),
            Triple(Icon.RIGHT, 1 to 0, R.string.nudge_right),
        )) {
            tools.addView(nudgeButton(icon, delta.first, delta.second, str(description)))
        }
        root.addView(tools)
        // Landscape has width to spare but little height: keep the buttons on the tools row there.
        val wide = screen.geometry().let { it.width > it.height }
        val buttons = if (wide) {
            tools.addView(Space(context), LinearLayout.LayoutParams(px(12), 1))
            tools
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, px(10), 0, 0)
            }.also { root.addView(it) }
        }
        buttons.addView(ui.button(str(R.string.overlay_cancel), Ui.TEXT, filled = false) { actions.onCancelPressed() })
        buttons.addView(Space(context), LinearLayout.LayoutParams(px(8), 1))
        val ready = ui.button(str(R.string.overlay_ready), Ui.MINT, filled = true) { confirmSteps() }
        buttons.addView(ready)

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
        val steps = strings.getString(R.string.overlay_steps, drafts.size, SequenceRules.MAX_STEPS)
        panelStatus?.text = if (active != null) {
            val index = drafts.indexOf(active) + 1
            steps + "   ·   " + strings.getString(
                R.string.overlay_active,
                index,
                active.params.x + markerSize / 2,
                active.params.y + markerSize / 2,
            )
        } else {
            steps
        }
        val canAdd = drafts.size < SequenceRules.MAX_STEPS
        for (b in panelAddButtons) ui.setEnabled(b, canAdd)
        panelUndo?.let { ui.setEnabled(it, drafts.isNotEmpty()) }
        panelReady?.let { ui.setEnabled(it, drafts.isNotEmpty()) }

        placePanel()
    }

    private fun positionPanel(params: WindowManager.LayoutParams) {
        val root = panel ?: return
        val g = screen.geometry()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(g.width - px(16), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        params.width = root.measuredWidth
        params.height = root.measuredHeight
        val (x, y) = panelSpot(params.width, params.height, g)
        params.x = x
        params.y = y
    }

    /**
     * Of six spots along the top and bottom edges, the one that covers the fewest steps.
     * On a tie, the edge away from the active marker and then the middle win.
     */
    private fun panelSpot(w: Int, h: Int, g: DisplayGeometry): Pair<Int, Int> {
        val top = px(40)
        val bottom = (g.height - h - px(40)).coerceAtLeast(0)
        val activeY = activeTap?.let { it.params.y + markerSize / 2 } ?: 0
        val ys = if (activeY > g.height / 2) listOf(top, bottom) else listOf(bottom, top)
        val xs = listOf((g.width - w) / 2, px(8), g.width - w - px(8)).map { it.coerceAtLeast(0) }
        val points = drafts.flatMap { d ->
            when (d) {
                is Draft.Tap -> listOf(Point(d.params.x + markerSize / 2, d.params.y + markerSize / 2))
                is Draft.Path -> listOf(d.points.first(), d.points.last())
            }
        }
        val pad = px(24)
        return ys.flatMap { y -> xs.map { x -> x to y } }.minBy { (x, y) ->
            points.count { it.x in x - pad..x + w + pad && it.y in y - pad..y + h + pad }
        }
    }

    /** Moves the panel only when its spot or size really changed (this runs on every drag step). */
    private fun placePanel() {
        val root = panel ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val before = listOf(params.x, params.y, params.width, params.height)
        positionPanel(params)
        if (listOf(params.x, params.y, params.width, params.height) != before) updateWindow(root, params)
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
        hideSettings()
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
        if (selecting && panel != null) reAddPanel() // Its layout depends on the orientation.
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
        settings?.let { panel ->
            if (points.any { windowContains(panel, it.x, it.y) }) moved = true
            hideSettings()
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
        hideSettings()
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

    private fun str(id: Int): String = strings.getString(id)

    private fun nudgeButton(icon: Icon, dx: Int, dy: Int, description: String): View {
        val button = IconView(context, icon, Ui.SOFT).apply {
            background = ui.pill(0x14FFFFFF, 12f)
            contentDescription = description
            isClickable = true
        }
        button.layoutParams = LinearLayout.LayoutParams(px(32), px(32)).apply { setMargins(px(1), 0, px(1), 0) }
        repeatOnHold(button) { moveActiveBy(dx, dy) }
        return button
    }

    /** Runs [action] once on touch, then repeats while held (faster after a while). */
    private fun repeatOnHold(view: View, action: (fast: Boolean) -> Unit) {
        var count = 0
        val repeat = object : Runnable {
            override fun run() {
                count++
                action(count > 12)
                handler.postDelayed(this, 70)
            }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    count = 0
                    action(false)
                    handler.postDelayed(repeat, 380)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    // ---------------------------------------------------------------- settings panel

    /** Every setting of the app, reachable from the floating circle. Changes apply immediately. */
    fun showSettings() {
        if (settings != null) return
        val g = screen.geometry()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(18))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ui.text(str(R.string.settings_title), 22f, Ui.TEXT, ui.displayHeavy), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(IconView(context, Icon.CLOSE, Ui.SOFT).apply {
            background = ui.pill(0x14FFFFFF, 18f)
            contentDescription = str(R.string.settings_close)
            isClickable = true
            setOnClickListener { hideSettings() }
        }, LinearLayout.LayoutParams(px(36), px(36)))
        content.addView(header)

        content.addView(ui.caption(str(R.string.settings_new_steps)))
        content.addView(stepper(str(R.string.settings_press), prefs.defaultHoldMs, SequenceRules::clampHold) { prefs.defaultHoldMs = it })
        content.addView(stepper(str(R.string.settings_pause), prefs.defaultGapMs, SequenceRules::clampGap) { prefs.defaultGapMs = it })
        content.addView(stepper(str(R.string.settings_drawing), prefs.defaultPathMs, SequenceRules::clampPath) { prefs.defaultPathMs = it })

        val steps = actions.currentSteps()
        content.addView(ui.caption(strings.getString(R.string.settings_steps, steps.size)))
        when {
            actions.isChoosingSteps() -> content.addView(note(str(R.string.steps_locked)))
            steps.isEmpty() -> content.addView(note(str(R.string.steps_empty)))
            else -> addStepEditors(content, steps)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) addLanguageChips(content)

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        val root = FrameLayout(context).apply {
            background = ui.glass(28f)
            clipToOutline = true
            addView(scroll)
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    hideSettings()
                    true
                } else {
                    false
                }
            }
        }
        val width = minOf(px(380), g.width - px(24))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = minOf(root.measuredHeight, (g.height * 0.86f).toInt())
        val params = windowParams(width, height, "settings").apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            x = (g.width - width) / 2
            y = (g.height - height) / 2
        }
        if (addWindow(root, params)) settings = root
    }

    fun hideSettings() {
        val v = settings ?: return
        settings = null
        removeWindow(v)
    }

    private fun note(text: String) = ui.text(text, 13f, Ui.MUTED).apply { setPadding(0, 0, 0, px(4)) }

    private fun addStepEditors(content: LinearLayout, initial: List<Step>) {
        val working = initial.toMutableList()
        val total = ui.text("", 13f, Ui.SOFT, ui.display).apply {
            fontFeatureSettings = "tnum"
            setPadding(0, px(10), 0, 0)
        }
        fun refreshTotal() {
            total.text = if (SequenceRules.isTooLong(working)) {
                str(R.string.steps_too_long)
            } else {
                strings.getString(R.string.settings_total, SequenceRules.totalDurationMs(working))
            }
        }
        fun commit(index: Int, step: Step) {
            working[index] = step
            actions.onTimingsEdited(working.toList())
            refreshTotal()
        }
        working.forEachIndexed { i, step ->
            content.addView(ui.text(
                when (step) {
                    is TapStep -> strings.getString(R.string.step_tap, i + 1, step.at.x, step.at.y)
                    is PathStep -> strings.getString(R.string.step_path, i + 1, step.points.size)
                },
                13f, Ui.TEXT, ui.bodyMedium,
            ).apply { setPadding(0, px(10), 0, px(2)) })
            when (step) {
                is TapStep -> content.addView(stepper(str(R.string.settings_press), step.holdMs, SequenceRules::clampHold) {
                    commit(i, (working[i] as TapStep).copy(holdMs = it))
                })
                is PathStep -> content.addView(stepper(str(R.string.settings_drawing), step.durationMs, SequenceRules::clampPath) {
                    commit(i, (working[i] as PathStep).copy(durationMs = it))
                })
            }
            if (i < working.size - 1) {
                content.addView(stepper(str(R.string.settings_pause), step.delayAfterMs, SequenceRules::clampGap) {
                    commit(i, when (val s = working[i]) {
                        is TapStep -> s.copy(delayAfterMs = it)
                        is PathStep -> s.copy(delayAfterMs = it)
                    })
                })
            }
        }
        refreshTotal()
        content.addView(total)
    }

    /** Label, then  −  value  +  ; holding a button repeats and speeds up. */
    private fun stepper(label: String, initial: Long, clamp: (Long) -> Long, onChange: (Long) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(3), 0, px(3))
        }
        row.addView(ui.text(label, 14f, Ui.SOFT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        var value = clamp(initial)
        val shown = ui.text("", 16f, Ui.TEXT, ui.display).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            minWidth = px(84)
        }
        fun show() {
            shown.text = strings.getString(R.string.value_ms, value)
        }
        fun change(up: Boolean, fast: Boolean) {
            val base = if (up) value else value - 1
            var step = when {
                base < 100 -> 1L
                base < 1000 -> 10L
                else -> 100L
            }
            if (fast) step *= 10
            val next = clamp(if (up) value + step else value - step)
            if (next != value) {
                value = next
                show()
                onChange(value)
            }
        }
        show()
        val minus = IconView(context, Icon.MINUS, Ui.TEXT).apply {
            background = ui.pill(0x14FFFFFF, 18f)
            contentDescription = strings.getString(R.string.settings_less, label)
        }
        val plus = IconView(context, Icon.PLUS, Ui.TEXT).apply {
            background = ui.pill(0x14FFFFFF, 18f)
            contentDescription = strings.getString(R.string.settings_more, label)
        }
        repeatOnHold(minus) { fast -> change(up = false, fast = fast) }
        repeatOnHold(plus) { fast -> change(up = true, fast = fast) }
        row.addView(minus, LinearLayout.LayoutParams(px(36), px(36)))
        row.addView(shown)
        row.addView(plus, LinearLayout.LayoutParams(px(36), px(36)))
        return row
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun addLanguageChips(content: LinearLayout) {
        content.addView(ui.caption(str(R.string.language_label)))
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val current = localeManager.applicationLocales.get(0)?.language ?: ""
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val choices = listOf("" to str(R.string.settings_lang_auto), "en" to "EN", "ru" to "RU", "tg" to "TJ")
        for ((tag, name) in choices) {
            val selected = tag == current
            val chip = ui.text(name, 13f, if (selected) Ui.INK else Ui.TEXT, ui.display).apply {
                gravity = Gravity.CENTER
                minWidth = px(56)
                minHeight = px(36)
                setPadding(px(12), 0, px(12), 0)
                background = ui.pill(if (selected) Ui.MINT else 0x14FFFFFF, 18f)
                isClickable = true
                setOnClickListener {
                    if (!selected) {
                        localeManager.applicationLocales = LocaleList.forLanguageTags(tag)
                        actions.refreshLanguage() // Rebuilds this panel in the new language.
                    }
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, px(8), 0)
            })
        }
        content.addView(row)
    }

    companion object {
        private const val TAG = "AutoClicker"
    }
}
