package com.fullautoclicker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A touch target inside the app for checking the clicker: aim the marker here, press Volume Up,
 * and the pad reports when and where the injected touch actually arrived.
 */
class TestPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Receives (screen x, screen y, uptime ms at delivery) for every touch-down on the pad. */
    var onTouchDown: ((Int, Int, Long) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1744.toInt() }
    private var lastLocalX = -1f
    private var lastLocalY = -1f

    init {
        isClickable = true
        contentDescription = "Test pad"
    }

    fun setColors(lineColor: Int, textColor: Int) {
        border.color = lineColor
        text.color = textColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = border.strokeWidth
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, 12f * density, 12f * density, border)
        canvas.drawText("TEST PAD", width / 2f, height / 2f - 4f * density, text)
        canvas.drawText("aim here, press Volume Up", width / 2f, height / 2f + 16f * density, text)
        if (lastLocalX >= 0) canvas.drawCircle(lastLocalX, lastLocalY, 4f * density, mark)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            lastLocalX = event.x
            lastLocalY = event.y
            onTouchDown?.invoke(event.rawX.toInt(), event.rawY.toInt(), now)
            invalidate()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
