package com.fullautoclicker.touchtest

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Game-like touch target: landscape, immersive full screen, a SurfaceView drawn from its own
 * render thread (the same arrangement Unity and most shooters use). Every touch-down is logged as
 *   TouchTest: DOWN x=.. y=.. fire=true|false n=.. src=0x.. device=.. tool=..
 * and the big circle on the right ("FIRE") counts hits, so a tap's arrival is visible on screen.
 */
class GameActivity : Activity() {

    private lateinit var view: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = GameView(this)
        setContentView(view)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private class GameView(activity: Activity) : SurfaceView(activity), SurfaceHolder.Callback {
        @Volatile private var running = false
        private var thread: Thread? = null

        @Volatile private var hits = 0
        @Volatile private var touches = 0
        @Volatile private var lastX = -1f
        @Volatile private var lastY = -1f
        @Volatile private var flashUntil = 0L
        @Volatile private var version = 0

        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f }
        private val fire = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }

        init {
            holder.addCallback(this)
        }

        private fun fireCenterX() = width * 0.82f
        private fun fireCenterY() = height * 0.62f
        private fun fireRadius() = height * 0.16f

        private var moves = 0
        private var lastUpTime = -1L

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) moves += event.historySize + 1
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                Log.i(
                    "TouchTest",
                    "UP x=${event.rawX.toInt()} y=${event.rawY.toInt()} moves=$moves held=${event.eventTime - event.downTime}ms",
                )
                lastUpTime = event.eventTime
            }
            version++
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                moves = 0
                val gap = if (lastUpTime >= 0) "${event.downTime - lastUpTime}ms" else "-"
                val dx = event.x - fireCenterX()
                val dy = event.y - fireCenterY()
                val inFire = dx * dx + dy * dy <= fireRadius() * fireRadius()
                touches++
                if (inFire) {
                    hits++
                    flashUntil = SystemClock.uptimeMillis() + 150
                }
                lastX = event.x
                lastY = event.y
                Log.i(
                    "TouchTest",
                    "DOWN x=${event.rawX.toInt()} y=${event.rawY.toInt()} fire=$inFire n=$touches gapSinceLastUp=$gap " +
                        "src=0x${Integer.toHexString(event.source)} device=${event.deviceId} tool=${event.getToolType(0)}",
                )
            }
            return true
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            running = true
            thread = Thread {
                // Redraw only when something changed (or a flash ends): a continuous loop is too
                // heavy for a software-emulated test device and would starve the system.
                var drawnVersion = -1
                while (running) {
                    val flashing = SystemClock.uptimeMillis() < flashUntil + 50
                    if (drawnVersion != version || flashing) {
                        drawnVersion = version
                        val canvas: Canvas? = holder.lockCanvas()
                        if (canvas != null) {
                            try {
                                render(canvas)
                            } finally {
                                holder.unlockCanvasAndPost(canvas)
                            }
                        }
                    }
                    Thread.sleep(33)
                }
            }.also { it.start() }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            running = false
            thread?.join()
        }

        private fun render(canvas: Canvas) {
            canvas.drawColor(0xFF1B2A1B.toInt())
            canvas.drawText("TOUCH TEST GAME  (landscape, SurfaceView)", 40f, 70f, text)
            canvas.drawText("FIRE hits: $hits    touches: $touches", 40f, 130f, text)
            if (lastX >= 0) canvas.drawText("last touch: ${lastX.toInt()}, ${lastY.toInt()}", 40f, 190f, text)
            fire.color = if (SystemClock.uptimeMillis() < flashUntil) 0xFFFFC107.toInt() else 0xFFD32F2F.toInt()
            canvas.drawCircle(fireCenterX(), fireCenterY(), fireRadius(), fire)
            canvas.drawText("FIRE", fireCenterX() - 50f, fireCenterY() + 16f, text)
            if (lastX >= 0) canvas.drawCircle(lastX, lastY, 10f, dot)
        }
    }
}
