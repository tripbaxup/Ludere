package com.draco.ludere.views

import android.app.Service
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.draco.ludere.databinding.ActivityGameBinding
import com.draco.ludere.viewmodels.GameActivityViewModel

class GameActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGameBinding
    private val viewModel: GameActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Lock the window's preferred refresh rate to 60Hz. On phones with
        // 90Hz/120Hz screens, letting the display run at its native high
        // refresh rate can make the emulator's frame pacing drift slightly
        // faster than intended, which feels like a mild fast-forward.
        // Pinning to 60Hz keeps N64 emulation running at its native speed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val params = window.attributes
            params.preferredRefreshRate = 60f
            window.attributes = params
        }

        /* Use immersive mode when we change the window insets */
        window.decorView.setOnApplyWindowInsetsListener { view, windowInsets ->
            view.post { viewModel.immersive(window) }
            return@setOnApplyWindowInsetsListener windowInsets
        }

        registerInputListener()
        viewModel.setConfigOrientation(this)
        viewModel.updateGamePadVisibility(this, binding.leftContainer, binding.rightContainer)
        viewModel.prepareMenu(this)
        viewModel.setupRetroView(this, binding.retroviewContainer)
        viewModel.setupGamePads(binding.leftContainer, binding.rightContainer)
    }

    /**
     * Listen for new controller additions and removals
     */
    private fun registerInputListener() {
        val inputManager = getSystemService(Service.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                viewModel.updateGamePadVisibility(this@GameActivity, binding.leftContainer, binding.rightContainer)
            }
            override fun onInputDeviceRemoved(deviceId: Int) {
                viewModel.updateGamePadVisibility(this@GameActivity, binding.leftContainer, binding.rightContainer)
            }
            override fun onInputDeviceChanged(deviceId: Int) {
                viewModel.updateGamePadVisibility(this@GameActivity, binding.leftContainer, binding.rightContainer)
            }
        }, null)
    }

    override fun onBackPressed() = viewModel.showMenu()

    override fun onDestroy() {
        viewModel.dismissMenu()
        viewModel.dispose()
        viewModel.detachRetroView(this)
        super.onDestroy()
    }

    override fun onPause() {
        viewModel.preserveState()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return viewModel.processKeyEvent(keyCode, event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return viewModel.processKeyEvent(keyCode, event) ?: super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return viewModel.processMotionEvent(event) ?: super.onGenericMotionEvent(event)
    }
}