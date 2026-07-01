package com.draco.ludere.gamepad

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.draco.ludere.R
import com.draco.ludere.input.N64InputHandler
import com.swordfish.libretrodroid.GLRetroView
import io.github.controlwear.virtual.joystick.android.JoystickView
import io.reactivex.disposables.CompositeDisposable
import kotlin.math.cos
import kotlin.math.sin

class GamePad(
    context: Context,
    private val sharedN64Handler: N64InputHandler? = null,
) {
    val pad = JoystickView(context)

    companion object {
        @Suppress("DEPRECATION")
        fun shouldShowGamePads(activity: Activity): Boolean {
            if (!activity.resources.getBoolean(R.bool.config_gamepad))
                return false

            val hasTouchScreen = activity.packageManager?.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            if (hasTouchScreen == null || hasTouchScreen == false)
                return false

            val currentDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                activity.display!!.displayId
            else {
                val wm = activity.getSystemService(AppCompatActivity.WINDOW_SERVICE) as WindowManager
                wm.defaultDisplay.displayId
            }

            val dm = activity.getSystemService(Service.DISPLAY_SERVICE) as DisplayManager
            if (dm.getDisplay(currentDisplayId).flags and Display.FLAG_PRESENTATION == Display.FLAG_PRESENTATION)
                return false

            for (id in InputDevice.getDeviceIds()) {
                InputDevice.getDevice(id).apply {
                    if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD)
                        return false
                }
            }

            return true
        }
    }

    fun subscribe(compositeDisposable: CompositeDisposable, retroView: GLRetroView) {
        pad.setOnMoveListener { angle, strength ->
            val deadZone = 10
            if (strength < deadZone) {
                if (sharedN64Handler != null && !sharedN64Handler.useAnalogStick) {
                    retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f)
                } else if (sharedN64Handler == null) {
                    retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f)
                } else {
                    retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, 0f, 0f)
                }
                return@setOnMoveListener
            }

            val rad = Math.toRadians(angle.toDouble())
            val x = (cos(rad) * (strength / 100.0)).toFloat()
            val y = (sin(rad) * (strength / 100.0)).toFloat()

            if (sharedN64Handler != null && !sharedN64Handler.useAnalogStick) {
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y)
            } else if (sharedN64Handler == null) {
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y)
            } else {
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y)
            }
        }
    }
}
