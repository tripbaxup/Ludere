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
 *
 * Layout strategy: every element is anchored to a screen CORNER using an
 * offset scaled by min(width, height) ("u"). This keeps controls in the
 * actual reachable thumb zones consistently across aspect ratios, instead
 * of using raw percentages of full height (which pushed clusters toward
 * the middle of the screen on tall/portrait aspect ratios).
 *
 * - Bottom-left: analog stick (or dpad if n64Handler.useAnalogStick == false)
 * - Bottom-right: A/B/X/Y face buttons
 * - Directly above the face buttons: C-Up/Down/Left/Right (mapped to the
 *   right analog stick, which is how N64 cores read the C buttons). In
 *   portrait, where the emulator letterboxes the game vertically, this
 *   cluster is shrunk/clamped so it never draws on top of the game image.
 * - Top-right: Z shoulder button (mapped to L2, the standard N64 Z mapping)
 * - Top-center: Start
 */
class TouchOverlayView(
    context: Context,
    private val n64Handler: N64InputHandler,
    private val retroView: GLRetroView,
    private val port: Int = 0,
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(90, 255, 255, 255)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(40, 255, 255, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Left stick (improved for racing games)
// Moved farther inward so your thumb has full travel when steering left.
// Slightly larger stick and increased drag range for finer analog control.
private val leftInsetPct = 0.33f          // was 0.26f
private val leftBottomInsetPct = 0.26f    // was 0.30f
private val stickRadiusPct = 0.15f        // was 0.13f
private val stickDragRangePct = 0.40f     // was 0.34f

    // Main face buttons (A/B/X/Y)
    private val rightInsetPct = 0.30f
    private val rightBottomInsetPct = 0.30f
    private val buttonRadiusPct = 0.085f
    private val buttonSpacingPct = 0.115f

    // C-button cluster (ideal / landscape sizing -- see cGeometry())
    private val cRadiusPct = 0.058f
    private val cSpacingPct = 0.10f
    private val cAboveGapPct = 0.03f       // gap between the C-cluster and whatever is below/above it
    private val cMinScalePortrait = 0.55f  // never shrink the C-cluster below 55% of its ideal size

    // Z (now top-right shoulder button)
    private val zInsetPct = 0.15f
    private val zRadiusPct = 0.09f

    // Start (top-center)
    private val startTopInsetPct = 0.14f
    private val startRadiusPct = 0.075f

    private var leftPointerId: Int = -1
    private var stickVisualDx = 0f // current knob offset, normalized -1..1, for drawing only
    private var stickVisualDy = 0f
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
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // --- Geometry helpers (single source of truth shared by draw + hit-test) ---

    private fun unit() = min(width, height).toFloat()

    private fun isPortrait() = height > width

    private fun leftCenter(): Pair<Float, Float> {
        val u = unit()
        return Pair(u * leftInsetPct, height - u * leftBottomInsetPct)
    }

    private fun rightCenter(): Pair<Float, Float> {
        val u = unit()
        return Pair(width - u * rightInsetPct, height - u * rightBottomInsetPct)
    }

    private data class CGeometry(val cx: Float, val cy: Float, val radius: Float, val spacing: Float)

    /**
     * Computes the center, radius, and spacing of the C-button diamond. The
     * cluster always sits directly above the main A/B/X/Y cluster, centered
     * on the same X. In portrait, the emulator letterboxes a standard N64
     * 4:3 image to the view's full width; if the gap between the game's
     * bottom edge and the main buttons isn't tall enough for the cluster at
     * its normal size, it's shrunk (down to cMinScalePortrait) to fit --
     * obscuring the game view is worse than a smaller cluster. If you use a
     * core/output with a different aspect ratio, adjust the 4f/3f below.
     */
    private fun cGeometry(): CGeometry {
        val u = unit()
        val (rightCx, rightCy) = rightCenter()
        val btnR = u * buttonRadiusPct
        val sp = u * buttonSpacingPct
        val mainClusterTopY = rightCy - sp - btnR
        val gap = u * cAboveGapPct

        var cR = u * cRadiusPct
        var cSp = u * cSpacingPct

        if (isPortrait()) {
            val gameHeight = width * (3f / 4f)
            val gameBottomY = (height - gameHeight) / 2f + gameHeight

            val available = mainClusterTopY - gameBottomY - 2f * gap
            val ideal = 2f * (cSp + cR)
            if (available < ideal) {
                val scale = (available / ideal).coerceIn(cMinScalePortrait, 1f)
                cR *= scale
                cSp *= scale
            }
        }

        val cy = mainClusterTopY - gap - (cSp + cR)
        return CGeometry(rightCx, cy, cR, cSp)
    }

    private fun zCenter(): Pair<Float, Float> {
        val u = unit()
        return Pair(width - u * zInsetPct, u * zInsetPct)
    }

    private fun startCenter(): Pair<Float, Float> {
        val u = unit()
        return Pair(width * 0.5f, u * startTopInsetPct)
    }

    private fun drawLabeledCircle(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, label: String) {
        fillPaint.color = color
        canvas.drawCircle(cx, cy, r, fillPaint)
        strokePaint.strokeWidth = r * 0.06f
        canvas.drawCircle(cx, cy, r, strokePaint)
        textPaint.textSize = r * 0.85f
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, textY, textPaint)
    }

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val u = unit()
        val thickness = r * 0.65f
        val length = r * 2.0f
        
        fillPaint.color = Color.argb(90, 255, 255, 255)
        strokePaint.strokeWidth = u * 0.004f

        // Horizontal bar
        canvas.drawRect(cx - length/2, cy - thickness/2, cx + length/2, cy + thickness/2, fillPaint)
        canvas.drawRect(cx - length/2, cy - thickness/2, cx + length/2, cy + thickness/2, strokePaint)
        
        // Vertical bar
        canvas.drawRect(cx - thickness/2, cy - length/2, cx + thickness/2, cy + length/2, fillPaint)
        canvas.drawRect(cx - thickness/2, cy - length/2, cx + thickness/2, cy + length/2, strokePaint)

        // Direction indicators
        textPaint.textSize = thickness * 0.6f
        val textOffset = length * 0.35f
        canvas.drawText("\u2191", cx, cy - textOffset + (textPaint.textSize * 0.3f), textPaint)
        canvas.drawText("\u2193", cx, cy + textOffset + (textPaint.textSize * 0.3f), textPaint)
        canvas.drawText("\u2190", cx - textOffset, cy + (textPaint.textSize * 0.3f), textPaint)
        canvas.drawText("\u2192", cx + textOffset, cy + (textPaint.textSize * 0.3f), textPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val u = unit()

        // --- Left stick / DPAD ---
        val (leftCx, leftCy) = leftCenter()
        val dragR = u * stickDragRangePct
        
        if (n64Handler.useAnalogStick) {
            val knobR = u * stickRadiusPct
            canvas.drawCircle(leftCx, leftCy, dragR, guidePaint) // faint boundary = real drag range

            // The knob moves within the ring to reflect the current touch
            // offset (clamped so it never visually leaves the ring), instead of
            // always sitting dead-center regardless of drag direction.
            val knobTravel = dragR - knobR
            val knobCx = leftCx + stickVisualDx * knobTravel
            val knobCy = leftCy + stickVisualDy * knobTravel
            fillPaint.color = Color.argb(90, 255, 255, 255)
            canvas.drawCircle(knobCx, knobCy, knobR, fillPaint)
            strokePaint.strokeWidth = u * 0.004f
            canvas.drawCircle(knobCx, knobCy, knobR, strokePaint)
        } else {
            drawDpad(canvas, leftCx, leftCy, dragR * 0.8f)
            
            // Draw a small indicator for the current DPAD press
            if (stickVisualDx != 0f || stickVisualDy != 0f) {
                val indicatorR = u * 0.03f
                val indicatorDist = dragR * 0.7f
                fillPaint.color = Color.argb(180, 255, 255, 255)
                canvas.drawCircle(leftCx + stickVisualDx * indicatorDist, leftCy + stickVisualDy * indicatorDist, indicatorR, fillPaint)
            }
        }

        // --- Main buttons (A/B/X/Y), 40% more transparent than the original design ---
        val (rightCx, rightCy) = rightCenter()
        val btnR = u * buttonRadiusPct
        val sp = u * buttonSpacingPct
        val red = Color.argb(96, 200, 0, 0)
        drawLabeledCircle(canvas, rightCx + sp, rightCy + sp, btnR, red, "A")
        drawLabeledCircle(canvas, rightCx - sp, rightCy + sp, btnR, red, "B")
        drawLabeledCircle(canvas, rightCx + sp, rightCy - sp, btnR, red, "X")
        drawLabeledCircle(canvas, rightCx - sp, rightCy - sp, btnR, red, "Y")

        // --- C-button cluster (directly above A/B/X/Y) ---
        val cGeo = cGeometry()
        val amber = Color.argb(150, 210, 170, 0)
        drawLabeledCircle(canvas, cGeo.cx, cGeo.cy - cGeo.spacing, cGeo.radius, amber, "\u2191")
        drawLabeledCircle(canvas, cGeo.cx, cGeo.cy + cGeo.spacing, cGeo.radius, amber, "\u2193")
        drawLabeledCircle(canvas, cGeo.cx - cGeo.spacing, cGeo.cy, cGeo.radius, amber, "\u2190")
        drawLabeledCircle(canvas, cGeo.cx + cGeo.spacing, cGeo.cy, cGeo.radius, amber, "\u2192")

        // --- Z (top-right shoulder) ---
        val (zCx, zCy) = zCenter()
        drawLabeledCircle(canvas, zCx, zCy, u * zRadiusPct, Color.argb(150, 40, 40, 50), "Z")

        // --- Start (top-center) ---
        val (startCx, startCy) = startCenter()
        drawLabeledCircle(canvas, startCx, startCy, u * startRadiusPct, Color.argb(130, 0, 110, 190), "+")
    }

    private fun insideLeftArea(x: Float, y: Float): Boolean {
        val (leftCx, leftCy) = leftCenter()
        val dragR = unit() * stickDragRangePct
        return hypot(x - leftCx, y - leftCy) <= dragR
    }

    private fun rightButtonAt(x: Float, y: Float): Int? {
        val u = unit()
        val (rightCx, rightCy) = rightCenter()
        val btnR = u * buttonRadiusPct
        val sp = u * buttonSpacingPct

        fun hit(cx: Float, cy: Float) = hypot(x - cx, y - cy) <= btnR

        if (hit(rightCx + sp, rightCy + sp)) return KeyEvent.KEYCODE_BUTTON_A
        if (hit(rightCx - sp, rightCy + sp)) return KeyEvent.KEYCODE_BUTTON_B
        if (hit(rightCx + sp, rightCy - sp)) return KeyEvent.KEYCODE_BUTTON_X
        if (hit(rightCx - sp, rightCy - sp)) return KeyEvent.KEYCODE_BUTTON_Y

        val (startCx, startCy) = startCenter()
        if (hypot(x - startCx, y - startCy) <= u * startRadiusPct) return KeyEvent.KEYCODE_BUTTON_START

        val (zCx, zCy) = zCenter()
        if (hypot(x - zCx, y - zCy) <= u * zRadiusPct) return KeyEvent.KEYCODE_BUTTON_L2

        return null
    }

    private fun cButtonDirectionAt(x: Float, y: Float): Int? {
        val cGeo = cGeometry()

        fun hit(cx: Float, cy: Float) = hypot(x - cx, y - cy) <= cGeo.radius

        if (hit(cGeo.cx, cGeo.cy - cGeo.spacing)) return C_UP
        if (hit(cGeo.cx, cGeo.cy + cGeo.spacing)) return C_DOWN
        if (hit(cGeo.cx - cGeo.spacing, cGeo.cy)) return C_LEFT
        if (hit(cGeo.cx + cGeo.spacing, cGeo.cy)) return C_RIGHT

        return null
    }

    private fun updateCAnalog() {
        var x = 0f
        var y = 0f

        for (flag in cButtonPointers.values) {
            if (flag and C_LEFT != 0) x -= 1f
            if (flag and C_RIGHT != 0) x += 1f
            if (flag and C_UP != 0) y -= 1f
            if (flag and C_DOWN != 0) y += 1f
        }

        x = x.coerceIn(-1f, 1f)
        y = y.coerceIn(-1f, 1f)

        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, x, y, port)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pid = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

                rightButtonAt(x, y)?.let { key ->
                    buttonPointers[pid] = key
                    retroView.sendKeyEvent(KeyEvent.ACTION_DOWN, key, port)
                    return true
                }

                cButtonDirectionAt(x, y)?.let { direction ->
                    cButtonPointers[pid] = direction
                    updateCAnalog()
                    return true
                }

                if (insideLeftArea(x, y)) {
                    leftPointerId = pid
                    handleLeftTouch(x, y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)

                    if (buttonPointers.containsKey(pid)) continue
                    if (cButtonPointers.containsKey(pid)) continue

                    if (pid == leftPointerId) {
                        handleLeftTouch(x, y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resetState()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(index)
                buttonPointers.remove(pid)?.let { key ->
                    retroView.sendKeyEvent(KeyEvent.ACTION_UP, key, port)
                    return true
                }
                if (cButtonPointers.containsKey(pid)) {
                    cButtonPointers.remove(pid)
                    updateCAnalog()
                    return true
                }
                if (pid == leftPointerId) {
                    leftPointerId = -1
                    stickVisualDx = 0f
                    stickVisualDy = 0f
                    invalidate()
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
        val (cx, cy) = leftCenter()
        val maxR = unit() * stickDragRangePct

        // Use polar coordinates for smoother clamping and more natural feel
        val rawDx = x - cx
        val rawDy = y - cy
        val dist = sqrt(rawDx * rawDx + rawDy * rawDy)
        
        val dx: Float
        val dy: Float
        
        if (dist > maxR) {
            dx = rawDx / dist
            dy = rawDy / dist
        } else {
            dx = rawDx / maxR
            dy = rawDy / maxR
        }

        // Update the on-screen knob position so it visually tracks the
        // thumb, then invalidate to trigger a redraw with the new offset.
        stickVisualDx = dx
        stickVisualDy = dy
        invalidate()

        if (n64Handler.useAnalogStick) {
            n64Handler.sendVirtualAnalogLeft(dx, dy, retroView, port)
        } else {
            // D-PAD quantization with a small diagonal tolerance
            val qx = when {
                dx > 0.3f -> 1f
                dx < -0.3f -> -1f
                else -> 0f
            }
            val qy = when {
                dy > 0.3f -> 1f
                dy < -0.3f -> -1f
                else -> 0f
            }
            
            // For visual feedback in D-PAD mode, we might want to snap the indicator
            stickVisualDx = qx
            stickVisualDy = qy
            
            n64Handler.sendVirtualDpad(qx, qy, retroView, port)
        }
    }

    fun resetState() {
        for ((_, key) in buttonPointers) {
            retroView.sendKeyEvent(KeyEvent.ACTION_UP, key, port)
        }
        buttonPointers.clear()

        cButtonPointers.clear()
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, 0f, 0f, port)

        leftPointerId = -1
        stickVisualDx = 0f
        stickVisualDy = 0f
        if (n64Handler.useAnalogStick) {
            n64Handler.sendVirtualAnalogLeft(0f, 0f, retroView, port)
        } else {
            n64Handler.sendVirtualDpad(0f, 0f, retroView, port)
        }
        invalidate()
    }
}