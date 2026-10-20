package com.fullautoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.fullautoclicker.R

/** Visual language of the floating UI: dark glass surfaces, one accent per meaning, two fonts. */
class Ui(private val context: Context) {
    val density = context.resources.displayMetrics.density

    private val onest: Typeface = context.resources.getFont(R.font.onest)
    private val exo: Typeface = context.resources.getFont(R.font.exo2)

    /** Body text (Onest). */
    val body: Typeface = Typeface.create(onest, 400, false)
    val bodyMedium: Typeface = Typeface.create(onest, 500, false)

    /** Titles, labels on buttons and numbers (Exo 2). */
    val display: Typeface = Typeface.create(exo, 600, false)
    val displayHeavy: Typeface = Typeface.create(exo, 800, false)

    fun px(dp: Float): Int = (dp * density + 0.5f).toInt()

    fun px(dp: Int): Int = px(dp.toFloat())

    /** The frosted dark card every floating panel sits on. */
    fun glass(radiusDp: Float = 22f) = GradientDrawable().apply {
        setColor(SURFACE)
        cornerRadius = radiusDp * density
        setStroke(px(1f), HAIRLINE)
    }

    fun pill(color: Int, radiusDp: Float = 14f): RippleDrawable {
        val shape = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
        }
        return RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, ColorDrawable(0xFFFFFFFF.toInt()))
    }

    fun text(value: String, sp: Float, color: Int = TEXT, face: Typeface = body) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = face
        includeFontPadding = false
    }

    /** Small spaced-out caption above a group ("NEW STEPS", "LANGUAGE"...). */
    fun caption(value: String) = text(value.uppercase(), 10.5f, MUTED, display).apply {
        letterSpacing = 0.12f
        setPadding(0, px(14), 0, px(6))
    }

    /** Round icon button with an optional tiny label underneath. */
    fun iconButton(icon: Icon, tint: Int, label: String?, description: String, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(6), px(4), px(6), px(4))
            background = pill(0x00000000, 16f)
            isClickable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }
        val disc = IconView(context, icon, tint).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tint and 0x00FFFFFF or 0x24000000)
            }
        }
        box.addView(disc, LinearLayout.LayoutParams(px(40), px(40)))
        if (label != null) {
            box.addView(text(label, 10f, SOFT, bodyMedium).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, px(4), 0, 0)
            })
        }
        return box
    }

    /** Wide text button; [filled] for the one main action of a panel. */
    fun button(label: String, color: Int, filled: Boolean, onClick: () -> Unit) = text(label, 14f, if (filled) INK else color, display).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.04f
        minWidth = px(96)
        minHeight = px(44)
        setPadding(px(18), 0, px(18), 0)
        background = if (filled) pill(color) else pill(0x14FFFFFF)
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    companion object {
        const val SURFACE = 0xF2111317.toInt()
        const val HAIRLINE = 0x1FFFFFFF
        const val RIPPLE = 0x33FFFFFF
        const val TEXT = 0xFFF4F5F7.toInt()
        const val SOFT = 0xCCE5E7EB.toInt()
        const val MUTED = 0xFF8B93A1.toInt()
        const val INK = 0xFF0B0D10.toInt()
        const val MINT = 0xFF34D399.toInt()
        const val AMBER = 0xFFFBBF24.toInt()
        const val CORAL = 0xFFFB7185.toInt()
        const val SKY = 0xFF60A5FA.toInt()
        const val VIOLET = 0xFFA78BFA.toInt()
        const val SLATE = 0xFF64748B.toInt()
    }
}

enum class Icon { PLAY, STOP, SETTINGS, HIDE, ADD_POINT, DRAW, UNDO, CLOSE, MINUS, PLUS, LEFT, UP, DOWN, RIGHT, CHECK }

/** Line icons drawn on a 24-unit grid, so they stay crisp at any size. */
@SuppressLint("ViewConstructor")
class IconView(context: Context, private val icon: Icon, color: Int) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 40f // icon drawn in the middle 24 of 40 units
        canvas.save()
        canvas.translate((width - 40 * s) / 2 + 8 * s, (height - 40 * s) / 2 + 8 * s)
        canvas.scale(s, s)
        stroke.strokeWidth = 2f
        path.reset()
        when (icon) {
            Icon.PLAY -> {
                path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close()
                canvas.drawPath(path, fill)
            }
            Icon.STOP -> canvas.drawRoundRect(RectF(6.5f, 6.5f, 17.5f, 17.5f), 2.5f, 2.5f, fill)
            Icon.SETTINGS -> {
                for ((y, x) in listOf(6f to 15f, 12f to 8f, 18f to 13f)) {
                    canvas.drawLine(4f, y, 20f, y, stroke)
                    canvas.drawCircle(x, y, 2.6f, fill)
                }
            }
            Icon.HIDE -> {
                canvas.drawLine(5f, 9f, 12f, 16f, stroke)
                canvas.drawLine(12f, 16f, 19f, 9f, stroke)
            }
            Icon.ADD_POINT -> {
                canvas.drawCircle(12f, 12f, 6.5f, stroke)
                canvas.drawCircle(12f, 12f, 1.6f, fill)
                canvas.drawLine(12f, 2.5f, 12f, 5f, stroke)
                canvas.drawLine(12f, 19f, 12f, 21.5f, stroke)
                canvas.drawLine(2.5f, 12f, 5f, 12f, stroke)
                canvas.drawLine(19f, 12f, 21.5f, 12f, stroke)
            }
            Icon.DRAW -> {
                path.moveTo(3.5f, 17f)
                path.cubicTo(7f, 8f, 10f, 20f, 13.5f, 12f)
                path.cubicTo(16f, 6.5f, 18.5f, 9f, 20.5f, 6f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(3.5f, 17f, 1.8f, fill)
            }
            Icon.UNDO -> {
                path.moveTo(9f, 14f); path.lineTo(4.5f, 9.5f); path.lineTo(9f, 5f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(5f, 9.5f); path.lineTo(14f, 9.5f)
                path.cubicTo(18f, 9.5f, 20f, 12f, 20f, 14.5f)
                path.cubicTo(20f, 17f, 18f, 19f, 14f, 19f); path.lineTo(10f, 19f)
                canvas.drawPath(path, stroke)
            }
            Icon.CLOSE -> {
                canvas.drawLine(6.5f, 6.5f, 17.5f, 17.5f, stroke)
                canvas.drawLine(17.5f, 6.5f, 6.5f, 17.5f, stroke)
            }
            Icon.MINUS -> canvas.drawLine(6f, 12f, 18f, 12f, stroke)
            Icon.PLUS -> {
                canvas.drawLine(6f, 12f, 18f, 12f, stroke)
                canvas.drawLine(12f, 6f, 12f, 18f, stroke)
            }
            Icon.LEFT -> chevron(canvas, 14f, 6f, 8f, 12f, 14f, 18f)
            Icon.RIGHT -> chevron(canvas, 10f, 6f, 16f, 12f, 10f, 18f)
            Icon.UP -> chevron(canvas, 6f, 14f, 12f, 8f, 18f, 14f)
            Icon.DOWN -> chevron(canvas, 6f, 10f, 12f, 16f, 18f, 10f)
            Icon.CHECK -> chevron(canvas, 5.5f, 12.5f, 10f, 17f, 18.5f, 7.5f)
        }
        canvas.restore()
    }

    private fun chevron(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3)
        canvas.drawPath(path, stroke)
    }
}
