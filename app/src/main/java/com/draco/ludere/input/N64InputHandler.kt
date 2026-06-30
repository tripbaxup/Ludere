package com.draco.ludere.input

import android.util.Log
import android.view.MotionEvent
import com.swordfish.libretrodroid.GLRetroView
import kotlin.math.abs

/**
 * N64-specific input handler for proper analog stick and D-Pad support.
 * 
 * The parallel_n64 core requires:
 * 1. Analog stick input via RETRO_DEVICE_ANALOG (not digitized D-Pad)
 * 2. D-Pad as RETRO_DEVICE_JOYPAD directional buttons (not as analog)
 * 
 * This handler ensures both are properly routed to the libretro core.
 */
class N64InputHandler {
    companion object {
        private const val TAG = "N64InputHandler"
        
        // Deadzone: ~10% of max range to avoid drift
        private const val DEADZONE_THRESHOLD = 3276f // 0.1 * 32768
        
        // Analog stick raw range
        private const val ANALOG_MAX = 32767f
        private const val ANALOG_MIN = -32768f
    }
    
    /**
     * State tracking for analog stick (raw X/Y values, not digitized)
     */
    private var analogLeftX = 0f
    private var analogLeftY = 0f
    private var analogRightX = 0f
    private var analogRightY = 0f
    
    /**
     * Track D-Pad state separately to prevent analog from masking it
     */
    private var dpadX = 0f
    private var dpadY = 0f
    
    /**
     * Process motion events specifically for N64.
     * Separates D-Pad and analog stick into their proper libretro device types.
     * 
     * @param event Android MotionEvent from controller or touch input
     * @param retroView GLRetroView instance to send events to
     * @param port Controller port (typically 0 for player 1)
     */
    fun processN64MotionEvent(event: MotionEvent, retroView: GLRetroView, port: Int = 0) {
        // Extract HAT X/Y (D-Pad) from hat axes
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        
        // Extract analog stick input (X/Y axes)
        val rawX = event.getAxisValue(MotionEvent.AXIS_X)
        val rawY = event.getAxisValue(MotionEvent.AXIS_Y)
        
        // Extract right analog (Z/RZ axes for C-buttons or right stick on extended controllers)
        val rightX = event.getAxisValue(MotionEvent.AXIS_Z)
        val rightY = event.getAxisValue(MotionEvent.AXIS_RZ)
        
        // Process D-Pad separately
        if (hatX != dpadX || hatY != dpadY) {
            dpadX = hatX
            dpadY = hatY
            Log.d(TAG, "D-Pad: X=$hatX, Y=$hatY")
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, hatX, hatY, port)
        }
        
        // Process left analog stick with deadzone
        val analogX = applyDeadzone(rawX)
        val analogY = applyDeadzone(rawY)
        
        if (analogX != analogLeftX || analogY != analogLeftY) {
            analogLeftX = analogX
            analogLeftY = analogY
            Log.d(TAG, "Analog Left: X=$analogX, Y=$analogY (raw: X=$rawX, Y=$rawY)")
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, analogX, analogY, port)
        }
        
        // Process right analog stick with deadzone (for C-buttons or extended controllers)
        val rightAnalogX = applyDeadzone(rightX)
        val rightAnalogY = applyDeadzone(rightY)
        
        if (rightAnalogX != analogRightX || rightAnalogY != analogRightY) {
            analogRightX = rightAnalogX
            analogRightY = rightAnalogY
            Log.d(TAG, "Analog Right: X=$rightAnalogX, Y=$rightAnalogY")
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, rightAnalogX, rightAnalogY, port)
        }
    }
    
    /**
     * Apply deadzone to analog input to reduce drift.
     * Maps raw [-32768, 32767] values with deadzone to [-32768, 32767] output.
     * 
     * @param value Raw analog axis value
     * @return Deadzone-adjusted value, or 0 if within deadzone
     */
    private fun applyDeadzone(value: Float): Float {
        if (abs(value) < DEADZONE_THRESHOLD) {
            return 0f
        }
        
        // Scale the value beyond the deadzone back to full range
        val sign = if (value > 0) 1f else -1f
        val absValue = abs(value)
        val scaledValue = (absValue - DEADZONE_THRESHOLD) / (ANALOG_MAX - DEADZONE_THRESHOLD) * ANALOG_MAX
        
        return scaledValue * sign
    }
    
    /**
     * Reset all analog/D-Pad state.
     * Call this when controller disconnect or input is lost.
     */
    fun reset() {
        analogLeftX = 0f
        analogLeftY = 0f
        analogRightX = 0f
        analogRightY = 0f
        dpadX = 0f
        dpadY = 0f
        Log.d(TAG, "N64 input handler reset")
    }
}
