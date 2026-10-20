package com.fullautoclicker.service

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import com.fullautoclicker.core.DisplayGeometry

/**
 * Reads the default display's real size and rotation. These are logical display pixels: the
 * same space used by overlay windows (getLocationOnScreen) and by gesture dispatch.
 */
class Screen(context: Context) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val point = Point()

    val display: Display? get() = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    fun geometry(): DisplayGeometry {
        val d = display ?: return DisplayGeometry(0, 0, 0)
        @Suppress("DEPRECATION") // Works from any context (a service has no window metrics of its own).
        d.getRealSize(point)
        return DisplayGeometry(point.x, point.y, d.rotation)
    }

    fun registerListener(listener: DisplayManager.DisplayListener, handler: android.os.Handler) {
        displayManager.registerDisplayListener(listener, handler)
    }

    fun unregisterListener(listener: DisplayManager.DisplayListener) {
        displayManager.unregisterDisplayListener(listener)
    }
}
