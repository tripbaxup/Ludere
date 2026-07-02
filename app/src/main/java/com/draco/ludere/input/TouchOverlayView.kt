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
 * - C-cluster: C-Up/Down/Left/Right (mapped to the right analog stick, which
 *   is how N64 cores read the C buttons)
 * - Z: shoulder trigger button (mapped to L2, the standard N64 Z mapping)
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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
    }

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

    // C-button cluster (mapped to right analog stick, per standard N64 core mapping)
    private val cCenterXPct = 0.58f
    private val cCenterYPct = 0.52f
    private val cButtonRadiusPct = 0.075f
    private val cButtonSpacingPct = 0.075f

    // Z button (mapped to L2, the standard N64 "Z" trigger mapping)
    private val zCenterXPct = 0.58f
    private val zCenterYPct = 0.80f
    private val zButtonRadiusPct = 0.08f

    private var leftPointerId: Int = -1
    private var buttonPointers = mutableMapOf<Int, Int>() // pointerId -> keycode

    // C-button direction flags
    private companion object {
        const val C_UP = 1
        const val C_DOWN = 2
        const val C_LEFT = 4
        const val C_RIGHT = 8
    }
    private var cButtonPointers = mutableMapOf<Int, Int>() // pointerId -> direction flag

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

        // Draw right buttons (A/B/X/Y) - 40% more transparent than original (160 -> 96 alpha)
        val rightCx = w * rightCenterXPct
        val rightCy = h * rightCenterYPct
        val btnR = min(w, h) * buttonRadiusPct
        val spacing = min(w, h) * buttonSpacingPct

        paint.color = Color.argb(96, 200, 0, 0)
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

        // Draw C-button cluster (amber, like real N64 C buttons)
        val cCx = w * cCenterXPct
        val cCy = h * cCenterYPct
        val cR = min(w, h) * cButtonRadiusPct
        val cSpacing = min(w, h) * cButtonSpacingPct

        paint.color = Color.argb(140, 210, 170, 0)
        textPaint.textSize = cR * 0.9f
        drawLabeledCircle(canvas, cCx, cCy - cSpacing, cR, "C\u2191") // C-Up
        drawLabeledCircle(canvas, cCx, cCy + cSpacing, cR, "C\u2193") // C-Down
        drawLabeledCircle(canvas, cCx - cSpacing, cCy, cR, "C\u2190") // C-Left
        drawLabeledCircle(canvas, cCx + cSpacing, cCy, cR, "C\u2192") // C-Right

        // Draw Z button
        val zCx = w * zCenterXPct
        val zCy = h * zCenterYPct
        val zR = min(w, h) * zButtonRadiusPct
        paint.color = Color.argb(150, 40, 40, 50)
        textPaint.textSize = zR
        drawLabeledCircle(canvas, zCx, zCy, zR, "Z")
    }

    private fun drawLabeledCircle(canvas: Canvas, cx: Float, cy: Float, r: Float, label: String) {
        canvas.drawCircle(cx, cy, r, paint)
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, textY, textPaint)
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

        // Z button (mapped to L2, the standard N64 Z-trigger mapping)
        val zCx = w * zCenterXPct
        val zCy = h * zCenterYPct
        val zR = min(w, h) * zButtonRadiusPct
        if (hypot(x - zCx, y - zCy) <= zR) return KeyEvent.KEYCODE_BUTTON_L2

        return null
    }

    private fun cButtonDirectionAt(x: Float, y: Float): Int? {
        val w = width.toFloat(); val h = height.toFloat()
        val cCx = w * cCenterXPct
        val cCy = h * cCenterYPct
        val cR = min(w, h) * cButtonRadiusPct
        val cSpacing = min(w, h) * cButtonSpacingPct

        fun hit(cx: Float, cy: Float) = hypot(x - cx, y - cy) <= cR

        if (hit(cCx, cCy - cSpacing)) return C_UP
        if (hit(cCx, cCy + cSpacing)) return C_DOWN
        if (hit(cCx - cSpacing, cCy)) return C_LEFT
        if (hit(cCx + cSpacing, cCy)) return C_RIGHT

        return null
    }

    private fun updateCAnalog() {
        var x = 0f
        var y = 0f

        for (flag in cButtonPointers.values) {
            if (flag and C_LEFT != 0) x -= 1f
            if (flag and C_RIGHT != 0) x += 1f
            // Up = negative Y, Down = positive Y (matches the corrected left-stick convention)
            if (flag and C_UP != 0) y -= 1f
            if (flag and C_DOWN != 0) y += 1f
        }

        x = x.coerceIn(-1f, 1f)
        y = y.coerceIn(-1f, 1f)

        retroView.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
            x,
            y,
            port
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pid = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

                // Buttons (right side, incl. Start/Z) take precedence
                rightButtonAt(x, y)?.let { key ->
                    buttonPointers[pid] = key
                    retroView.sendKeyEvent(KeyEvent.ACTION_DOWN, key, port)
                    return true
                }

                // C-button cluster
                cButtonDirectionAt(x, y)?.let { direction ->
                    cButtonPointers[pid] = direction
                    updateCAnalog()
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

                    // button/C-button moves are ignored (only down/up matter)
                    if (buttonPointers.containsKey(pid)) continue
                    if (cButtonPointers.containsKey(pid)) continue

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
                // C-button release
                if (cButtonPointers.containsKey(pid)) {
                    cButtonPointers.remove(pid)
                    updateCAnalog()
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
        // Do NOT invert Y here. Touching above center already yields a negative
        // dy, and both the physical-controller path (N64InputHandler) and the
        // retro core expect negative-Y = up / positive-Y = down. The previous
        // extra negation flipped this, causing "up" to move the character down.

        // clamp
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

        // Release any pressed C-button pointers
        cButtonPointers.clear()
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, 0f, 0f, port)

        // Clear left pointer tracking and send zeroed axes
        leftPointerId = -1
        if (n64Handler.useAnalogStick) {
            n64Handler.sendVirtualAnalogLeft(0f, 0f, retroView, port)
        } else {
            n64Handler.sendVirtualDpad(0f, 0f, retroView, port)
        }
    }
}