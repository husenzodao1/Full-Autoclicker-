package com.fullautoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/** The small floating circle. Its color and glyph show the current mode. */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context) : View(context) {
    enum class Look { IDLE, SELECTING, ARMED, PAUSED }

    var look: Look = Look.IDLE
        set(value) {
            if (field == value) return
            field = value
            contentDescription = "Auto clicker: ${value.name.lowercase()}"
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x99FFFFFF.toInt()
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    init {
        contentDescription = "Auto clicker: idle"
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - density
        fill.color = when (look) {
            Look.IDLE -> 0xE6424242.toInt()
            Look.SELECTING -> 0xF0F9A825.toInt()
            Look.ARMED -> 0xF02E7D32.toInt()
            Look.PAUSED -> 0xF0546E7A.toInt()
        }
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, rim)
        val g = r * 0.38f
        when (look) {
            Look.IDLE -> {
                glyph.style = Paint.Style.STROKE
                canvas.drawCircle(cx, cy, g, glyph)
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
 * The target marker: a ring with a crosshair. The exact tap point is the center of this view.
 * Lines stop short of the center so the targeted pixel itself stays visible.
 */
@SuppressLint("ViewConstructor")
class MarkerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FF1744 }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCC000000.toInt()
        strokeWidth = 3.5f * density
    }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.5f * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1744.toInt() }

    init {
        contentDescription = "Tap target marker"
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
    }
}
