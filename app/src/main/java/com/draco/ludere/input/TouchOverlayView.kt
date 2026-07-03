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
 * - Bottom-right: A/B face buttons (X and Y have been removed, and A/B
 *   are swapped from their original screen positions)
 * - Center, in the space X/Y used to occupy: Z shoulder button (mapped to
 *   L2, the standard N64 Z mapping)
 * - Directly above Z: C-Up/Down/Left/Right (mapped to the right analog
 *   stick, which is how N64 cores read the C buttons).
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

    private val leftInsetPct = 0.36f
    private val leftBottomInsetPct = 0.36f
    private val leftBottomInsetPortraitPct = 0.56f
    private val stickRadiusPct = 0.13f
    private val stickDragRangePct = 0.30f

    private val stickDeadzonePct = 0.08f

    private val rightInsetPct = 0.30f
    private val rightBottomInsetPct = 0.30f
    private val buttonRadiusPct = 0.085f
    private val buttonSpacingPct = 0.115f

    private val cRadiusPct = 0.058f
    private val cSpacingPct = 0.10f

    private val zRadiusPct = 0.09f

    private val startTopInsetPct = 0.14f
    private val startRadiusPct = 0.075f

    private var leftPointerId: Int = -1
    private var stickVisualDx = 0f
    private var stickVisualDy = 0f
    private var buttonPointers = mutableMapOf<Int, Int>()

    private companion object {
        const val C_UP = 1
        const val C_DOWN = 2
        const val C_LEFT = 4
        const val C_RIGHT = 8
    }

    private var cButtonPointers = mutableMapOf<Int, Int>()

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun unit() = min(width, height).toFloat()
    private fun isPortrait() = height > width

    private fun leftCenter(): Pair<Float, Float> {
        val u = unit()
        val bottomInset = if (isPortrait()) leftBottomInsetPortraitPct else leftBottomInsetPct
        return Pair(u * leftInsetPct, height - u * bottomInset)
    }

    private fun rightCenter(): Pair<Float, Float> {
        val u = unit()
        return Pair(width - u * rightInsetPct, height - u * rightBottomInsetPct)
    }

    private data class CGeometry(val cx: Float, val cy: Float, val radius: Float, val spacing: Float)

    private val cAboveZGapPct = 0.03f

    private fun zCenter(): Pair<Float, Float> {
        val u = unit()
        val (rightCx, rightCy) = rightCenter()
        val sp = u * buttonSpacingPct
        return Pair(rightCx, rightCy - sp)
    }

    private fun cGeometry(): CGeometry {
        val u = unit()
        val (zCx, zCy) = zCenter()
        val zR = u * zRadiusPct
        val gap = u * cAboveZGapPct
        val cR = u * cRadiusPct
        val cSp = u * cSpacingPct

        val cy = zCy - zR - gap - (cSp + cR)
        return CGeometry(zCx, cy, cR, cSp)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val u = unit()

        val (leftCx, leftCy) = leftCenter()
        val dragR = u * stickDragRangePct

        if (n64Handler.useAnalogStick) {
            val knobR = u * stickRadiusPct
            canvas.drawCircle(leftCx, leftCy, dragR, guidePaint)

            val knobTravel = dragR - knobR
            val knobCx = leftCx + stickVisualDx * knobTravel
            val knobCy = leftCy + stickVisualDy * knobTravel
            fillPaint.color = Color.argb(90, 255, 255, 255)
            canvas.drawCircle(knobCx, knobCy, knobR, fillPaint)
            strokePaint.strokeWidth = u * 0.004f
            canvas.drawCircle(knobCx, knobCy, knobR, strokePaint)
        }

        val (rightCx, rightCy) = rightCenter()
        val btnR = u * buttonRadiusPct
        val sp = u * buttonSpacingPct
        val green = Color.argb(96, 0, 170, 0)
        val purple = Color.argb(96, 140, 0, 200)

        // ✅ ONLY CHANGE: swapped labels
        drawLabeledCircle(canvas, rightCx + sp, rightCy + sp, btnR, green, "B")
        drawLabeledCircle(canvas, rightCx - sp, rightCy + sp, btnR, purple, "A")

        val cGeo = cGeometry()
        val amber = Color.argb(150, 210, 170, 0)
        drawLabeledCircle(canvas, cGeo.cx, cGeo.cy - cGeo.spacing, cGeo.radius, amber, "\u2191")
        drawLabeledCircle(canvas, cGeo.cx, cGeo.cy + cGeo.spacing, cGeo.radius, amber, "\u2193")
        drawLabeledCircle(canvas, cGeo.cx - cGeo.spacing, cGeo.cy, cGeo.radius, amber, "\u2190")
        drawLabeledCircle(canvas, cGeo.cx + cGeo.spacing, cGeo.cy, cGeo.radius, amber, "\u2192")

        val (zCx, zCy) = zCenter()
        drawLabeledCircle(canvas, zCx, zCy, u * zRadiusPct, Color.argb(150, 40, 40, 50), "Z")

        val (startCx, startCy) = startCenter()
        drawLabeledCircle(canvas, startCx, startCy, u * startRadiusPct, Color.argb(130, 0, 110, 190), "+")
    }
}
