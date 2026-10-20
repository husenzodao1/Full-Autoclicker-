package com.fullautoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.fullautoclicker.core.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The small floating circle. Its color and glyph show the current mode; the owner sets its description. */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context) : View(context) {
    enum class Look { IDLE, SELECTING, ARMED, PAUSED }

    var look: Look = Look.IDLE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 3f * density
        val accent = when (look) {
            Look.IDLE -> 0xFFCBD5E1.toInt()
            Look.SELECTING -> Ui.AMBER
            Look.ARMED -> Ui.MINT
            Look.PAUSED -> Ui.SLATE
        }
        // Soft outer halo, glass disc, thin colored ring: calm when idle, clear when armed.
        halo.color = accent and 0x00FFFFFF or 0x33000000
        halo.strokeWidth = 3f * density
        canvas.drawCircle(cx, cy, r + 1.5f * density, halo)
        canvas.drawCircle(cx, cy, r, glass)
        ring.color = accent and 0x00FFFFFF or (if (look == Look.IDLE) 0x66000000 else 0xE6000000.toInt())
        canvas.drawCircle(cx, cy, r - density, ring)
        glyph.color = accent
        val g = r * 0.34f
        when (look) {
            Look.IDLE -> {
                glyph.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, g * 0.45f, glyph)
            }
            Look.SELECTING -> {
                glyph.style = Paint.Style.STROKE
                canvas.drawLine(cx - g, cy, cx + g, cy, glyph)
                canvas.drawLine(cx, cy - g, cx, cy + g, glyph)
            }
            Look.ARMED -> {
                glyph.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, g * 0.75f, glyph)
            }
            Look.PAUSED -> {
                glyph.style = Paint.Style.STROKE
                canvas.drawLine(cx - g * 0.4f, cy - g, cx - g * 0.4f, cy + g, glyph)
                canvas.drawLine(cx + g * 0.4f, cy - g, cx + g * 0.4f, cy + g, glyph)
            }
        }
    }
}

/**
 * A tap-step marker: a ring with a crosshair and its step number. The exact tap point is the
 * center of this view. Lines stop short of the center so the targeted pixel itself stays visible.
 */
@SuppressLint("ViewConstructor")
class MarkerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A34D399 }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99000000.toInt()
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.CORAL }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SKY }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.INK
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
        typeface = Ui(context).displayHeavy
    }

    /** Position of this tap in the sequence (1-based); 0 hides the badge. */
    var number = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val ring = 22f * density
        val gap = 5f * density
        val arm = min(cx, cy) - 6f * density

        canvas.drawCircle(cx, cy, ring, shade)
        for (p in arrayOf(dark, light)) {
            canvas.drawCircle(cx, cy, ring, p)
            canvas.drawLine(cx - arm, cy, cx - gap, cy, p)
            canvas.drawLine(cx + gap, cy, cx + arm, cy, p)
            canvas.drawLine(cx, cy - arm, cx, cy - gap, p)
            canvas.drawLine(cx, cy + gap, cx, cy + arm, p)
        }
        canvas.drawCircle(cx, cy, 2.5f * density, light.apply { style = Paint.Style.FILL })
        light.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, 1.5f * density, dot)
        if (number > 0) drawBadge(canvas, cx + ring, cy - ring, number, badge, badgeText, density)
    }
}

private fun drawBadge(canvas: Canvas, x: Float, y: Float, n: Int, fill: Paint, text: Paint, density: Float) {
    canvas.drawCircle(x, y, 11f * density, fill)
    canvas.drawText(n.toString(), x, y + text.textSize * 0.35f, text)
}

/**
 * Full-screen, non-touchable layer that shows the recorded drawings while steps are chosen:
 * each path with a dot and its step number at the start and an arrow at the end.
 */
@SuppressLint("ViewConstructor")
class PathsView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val location = IntArray(2)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Ui.VIOLET
    }
    private val shadow = Paint(line).apply {
        strokeWidth = 7f * density
        color = 0x99000000.toInt()
    }
    private val start = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.VIOLET }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.INK
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
        typeface = Ui(context).displayHeavy
    }

    /** (step number, points in screen pixels). */
    var paths: List<Pair<Int, List<Point>>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        val ox = location[0].toFloat()
        val oy = location[1].toFloat()
        for ((n, pts) in paths) {
            if (pts.size < 2) continue
            val path = Path()
            path.moveTo(pts[0].x - ox, pts[0].y - oy)
            for (p in pts.drop(1)) path.lineTo(p.x - ox, p.y - oy)
            canvas.drawPath(path, shadow)
            canvas.drawPath(path, line)
            val a = pts[pts.size - 2]
            val b = pts.last()
            val angle = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            val len = 16f * density
            for (side in doubleArrayOf(-0.5, 0.5)) {
                val t = angle + Math.PI + side
                canvas.drawLine(b.x - ox, b.y - oy, (b.x - ox + len * cos(t)).toFloat(), (b.y - oy + len * sin(t)).toFloat(), line)
            }
            drawBadge(canvas, pts[0].x - ox, pts[0].y - oy, n, start, text, density)
        }
    }
}

/**
 * Full-screen surface for recording one drawing with one finger. Reports the finger trace in
 * screen pixels when the finger lifts; the caller decides whether it is long enough to keep.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DrawView(
    context: Context,
    private val hint: String,
    private val onFinished: (List<Point>) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val location = IntArray(2)
    private val points = ArrayList<Point>()
    private val trace = Path()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Ui.VIOLET
    }
    private val veil = Paint().apply { color = 0x26000000 }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
        typeface = Ui(context).display
    }

    init {
        contentDescription = hint
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
        val top = 64f * density
        val half = hintText.measureText(hint) / 2 + 22f * density
        val r = 21f * density
        canvas.drawRoundRect(width / 2f - half, top - 28f * density, width / 2f + half, top + 14f * density, r, r, hintBg)
        canvas.drawText(hint, width / 2f, top, hintText)
        canvas.drawPath(trace, line)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        getLocationOnScreen(location)
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        val lx = x - location[0].toFloat()
        val ly = y - location[1].toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                trace.reset()
                trace.moveTo(lx, ly)
                points += Point(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                // Include the batched samples between frames, so fast strokes keep their shape.
                for (h in 0 until event.historySize) {
                    val hx = (event.getHistoricalX(h) + location[0]).toInt()
                    val hy = (event.getHistoricalY(h) + location[1]).toInt()
                    points += Point(hx, hy)
                    trace.lineTo(hx - location[0].toFloat(), hy - location[1].toFloat())
                }
                points += Point(x, y)
                trace.lineTo(lx, ly)
            }
            MotionEvent.ACTION_UP -> {
                points += Point(x, y)
                performClick()
                onFinished(points.toList())
            }
            MotionEvent.ACTION_CANCEL -> onFinished(emptyList())
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
