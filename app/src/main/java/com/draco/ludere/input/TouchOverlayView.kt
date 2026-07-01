package com.draco.ludere.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.swordfish.libretrodroid.GLRetroView
import kotlin.math.*

/**
 * Full-screen overlay that implements touch-only virtual controls.
 * - Left area: analog stick (or dpad if n64Handler.useAnalogStick == false)
 * - Right area: action buttons A/B/X/Y, Start
 *
 * This class is intentionally simple and self-contained so mapping/layout
 * is easy to tweak by changing the percentage constants below.
 */
class TouchOverlayView(
    context: Context,
    private val n64Handler: N64InputHandler,
    private val retroView: GLRetroView,
    private val port: Int = 0,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Layout percentages (tweak to taste)
    private val leftAreaWidthPct = 0.40f
    private val leftAreaHeightPct = 0.55f
    private val leftCenterXPct = 0.20f
    private val leftCenterYPct = 0.75f
    private val stickRadiusPct = 0.12f

    private val rightCenterXPct = 0.80f
    private val rightCenterYPct = 0.75f
    // increased by 50% per request
    private val buttonRadiusPct = 0.12f
    private val buttonSpacingPct = 0.09f

    private var leftPointerId: Int = -1
    private var buttonPointers = mutableMapOf<Int, Int>() // pointerId -> keycode

    init {
        // semi-transparent controls
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(110, 0, 0, 0)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw left stick base
        val leftCx = w * leftCenterXPct
        val leftCy = h * leftCenterYPct
        val stickR = min(w, h) * stickRadiusPct
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(leftCx, leftCy, stickR, paint)

        // Draw right buttons
        val rightCx = w * rightCenterXPct
        val rightCy = h * rightCenterYPct
        val btnR = min(w, h) * buttonRadiusPct
        val spacing = min(w, h) * buttonSpacingPct

        paint.color = Color.argb(160, 200, 0, 0)
        // A (bottom-right)
        canvas.drawCircle(rightCx + spacing, rightCy + spacing, btnR, paint)
        // B (bottom-left)
        canvas.drawCircle(rightCx - spacing, rightCy + spacing, btnR, paint)
        // X (top-right)
        canvas.drawCircle(rightCx + spacing, rightCy - spacing, btnR, paint)
        // Y (top-left)
        canvas.drawCircle(rightCx - spacing, rightCy - spacing, btnR, paint)

        // Start small circle in center-top
        paint.color = Color.argb(120, 0, 120, 200)
        canvas.drawCircle(w * 0.5f, h * 0.12f, btnR * 0.8f, paint)
    }

    private fun insideLeftArea(x: Float, y: Float): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        val leftCx = w * leftCenterXPct
        val leftCy = h * leftCenterYPct
        val areaW = w * leftAreaWidthPct
        val areaH = h * leftAreaHeightPct
        return abs(x - leftCx) <= areaW/2 && abs(y - leftCy) <= areaH/2
    }

    private fun rightButtonAt(x: Float, y: Float): Int? {
        val w = width.toFloat(); val h = height.toFloat()
        val rightCx = w * rightCenterXPct
        val rightCy = h * rightCenterYPct
        val btnR = min(w, h) * buttonRadiusPct
        val spacing = min(w, h) * buttonSpacingPct

        fun hit(cx: Float, cy: Float) = hypot(x - cx, y - cy) <= btnR

        // A: bottom-right
        if (hit(rightCx + spacing, rightCy + spacing)) return KeyEvent.KEYCODE_BUTTON_A
        // B: bottom-left
        if (hit(rightCx - spacing, rightCy + spacing)) return KeyEvent.KEYCODE_BUTTON_B
        // X: top-right
        if (hit(rightCx + spacing, rightCy - spacing)) return KeyEvent.KEYCODE_BUTTON_X
        // Y: top-left
        if (hit(rightCx - spacing, rightCy - spacing)) return KeyEvent.KEYCODE_BUTTON_Y
        // Start (center top)
        if (hypot(x - w*0.5f, y - h*0.12f) <= btnR*0.8f) return KeyEvent.KEYCODE_BUTTON_START

        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pid = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

                // Buttons (right side) take precedence
                rightButtonAt(x, y)?.let { key ->
                    buttonPointers[pid] = key
                    retroView.sendKeyEvent(KeyEvent.ACTION_DOWN, key, port)
                    return true
                }

                if (insideLeftArea(x, y)) {
                    // start tracking left pointer
                    leftPointerId = pid
                    handleLeftTouch(x, y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // multiple pointers possible
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)

                    // button moves are ignored (only down/up matter)
                    if (buttonPointers.containsKey(pid)) continue

                    if (pid == leftPointerId) {
                        handleLeftTouch(x, y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // Input was interrupted; clear all state and release any active buttons/axes
                resetState()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(index)
                // button release
                buttonPointers.remove(pid)?.let { key ->
                    retroView.sendKeyEvent(KeyEvent.ACTION_UP, key, port)
                    return true
                }
                if (pid == leftPointerId) {
                    // reset left stick / dpad
                    leftPointerId = -1
                    if (n64Handler.useAnalogStick) {
                        n64Handler.sendVirtualAnalogLeft(0f, 0f, retroView, port)
                    } else {
                        n64Handler.sendVirtualDpad(0f, 0f, retroView, port)
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun handleLeftTouch(x: Float, y: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w * leftCenterXPct
        val cy = h * leftCenterYPct
        val maxR = min(w, h) * stickRadiusPct * 1.6f

        var dx = (x - cx) / maxR
var dy = (y - cy) / maxR

// Leave Y axis in its natural direction.
// Up on the touch stick moves up in-game, down moves down.
dx = dx.coerceIn(-1f, 1f)
dy = dy.coerceIn(-1f, 1f)

        if (n64Handler.useAnalogStick) {
            n64Handler.sendVirtualAnalogLeft(dx, dy, retroView, port)
        } else {
            // quantize to digital -1/0/1 with 0.5 threshold
            val qx = when {
                dx > 0.5f -> 1f
                dx < -0.5f -> -1f
                else -> 0f
            }
            val qy = when {
                dy > 0.5f -> 1f
                dy < -0.5f -> -1f
                else -> 0f
            }
            n64Handler.sendVirtualDpad(qx, qy, retroView, port)
        }
    }

    /**
     * Reset internal touch tracking and release any active virtual controls.
     * Used when switching input modes (analog <-> dpad) so the change takes effect immediately.
     */
    fun resetState() {
        // Release any pressed button pointers
        for ((_, key) in buttonPointers) {
            retroView.sendKeyEvent(KeyEvent.ACTION_UP, key, port)
        }
        buttonPointers.clear()

        // Clear left pointer tracking and send zeroed axes
        leftPointerId = -1
        if (n64Handler.useAnalogStick) {
            n64Handler.sendVirtualAnalogLeft(0f, 0f, retroView, port)
        } else {
            n64Handler.sendVirtualDpad(0f, 0f, retroView, port)
        }
    }
}
