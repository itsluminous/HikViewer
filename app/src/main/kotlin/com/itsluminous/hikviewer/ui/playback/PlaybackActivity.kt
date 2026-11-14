package com.itsluminous.hikviewer.ui.playback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.databinding.ActivityPlaybackBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.ui.common.InactivityTimer
import com.itsluminous.hikviewer.ui.common.LambdaViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Recorded footage of one camera, full screen, with a 24 h time bar in recorder time: it opens on
 * the recorder's today ([com.itsluminous.hikviewer.data.RecorderClocks]) and, when the recorder's
 * clock differs from this device's, a second line gives the device's local time ([RecorderTimeLabel]).
 *
 * The D-pad never moves view focus here: the controls hide after the shared idle time and a
 * hidden view cannot hold focus, so the ViewModel keeps a logical [PlaybackControl] (UP/DOWN
 * cycle it, drawn as an accent frame) and the Activity routes LEFT/RIGHT/OK to it. A pointer or
 * finger works directly: tap or drag along the bar to seek, click the chips.
 */
class PlaybackActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlaybackBinding
    private lateinit var controls: InactivityTimer

    /** Screen X of the last touch or mouse press; null once a key was used, so OK means "back a day". */
    private var lastPointerX: Float? = null

    private val viewModel: PlaybackViewModel by viewModels {
        val graph = AppGraph.of(this)
        val key = intent.getStringExtra(EXTRA_CAMERA_KEY).orEmpty()
        val camera =
            checkNotNull(
                graph.account.inventory.value
                    ?.camera(key),
            ) { "unknown camera $key" }
        LambdaViewModelFactory {
            PlaybackViewModel(camera, graph.account, graph.playbackFactory, graph.recorderClocks)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.video.isFocusable = false
        binding.video.isClickable = false
        binding.video.cameraName = viewModel.camera.name
        binding.video.showName = AppGraph.of(this).settingsStore.showCameraNames
        binding.video.showFrameRate = AppGraph.of(this).settingsStore.showFrameRate
        controls = InactivityTimer(binding.controls, AppGraph.of(this).topBarAutoHideMillis)
        controls.onUserActivity()
        binding.video.attachPlayer(viewModel.player, lifecycleScope)
        binding.timeBar.onSeek = { fraction ->
            controls.onUserActivity()
            viewModel.seekToFraction(fraction)
        }
        // The chip reads "◀ date ▶": a tap or click on its right half goes forward, anywhere else back.
        binding.dateChip.setOnClickListener { chip ->
            val forward = lastPointerX?.let { x -> x > chipMiddleX(chip) } == true
            viewModel.changeDay(if (forward) +1 else -1)
        }
        binding.speedButton.setOnClickListener { viewModel.cycleSpeed() }
        binding.root.setOnHoverListener { _, _ ->
            controls.onUserActivity()
            false
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    while (isActive) {
                        delay(viewModel.watchdogMillis)
                        viewModel.tick()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        viewModel.onStop()
        controls.hideNow()
        super.onStop()
    }

    private fun render(state: PlaybackUiState) {
        binding.dateChip.text = getString(R.string.playback_date_chip, state.day.toString())
        binding.dateChip.isActivated = state.control == PlaybackControl.DATE
        binding.speedButton.text = state.speed.label
        binding.speedButton.isActivated = state.control == PlaybackControl.SPEED
        binding.timeBar.segments = state.segments
        binding.timeBar.positionFraction = PlaybackTimeline.fractionOfDay(state.position)
        binding.timeBar.highlighted = state.control == PlaybackControl.BAR
        RecorderTimeLabel.bind(binding.timeLabel, binding.localTimeLabel, state.position, viewModel.recorderClock)
        val status = state.phase.statusRes?.let(::getString)
        binding.status.text = status
        binding.status.visibility = if (status == null) View.GONE else View.VISIBLE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            controls.onUserActivity()
            lastPointerX = null
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            controls.onUserActivity()
            lastPointerX = ev.rawX
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun chipMiddleX(chip: View): Float {
        val origin = IntArray(2).also(chip::getLocationOnScreen)
        return origin[0] + chip.width / 2f
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) controls.onUserActivity()
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val control = viewModel.state.value.control
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                viewModel.moveControl(+1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                viewModel.moveControl(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                horizontal(control, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1, event.repeatCount)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.repeatCount == 0) select(control)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        // Whatever control has focus now: the hold started on the bar must end with the key, or
        // a LEFT held while DOWN moves to the date chip would resume at its old step on the bar.
        val seekKey = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (seekKey) {
            viewModel.endSeekHold()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun horizontal(
        control: PlaybackControl,
        direction: Int,
        repeatCount: Int,
    ) {
        when (control) {
            PlaybackControl.BAR -> viewModel.seekBy(direction)
            PlaybackControl.DATE -> if (repeatCount == 0) viewModel.changeDay(direction)
            PlaybackControl.SPEED -> if (repeatCount == 0) viewModel.cycleSpeed(forward = direction > 0)
        }
    }

    private fun select(control: PlaybackControl) {
        when (control) {
            PlaybackControl.BAR, PlaybackControl.DATE -> viewModel.togglePause()
            PlaybackControl.SPEED -> viewModel.cycleSpeed()
        }
    }

    /** Intent plumbing. */
    companion object {
        /** [com.itsluminous.hikviewer.core.devices.Camera.key] of the camera to play back. */
        const val EXTRA_CAMERA_KEY: String = "cameraKey"

        /** Intent to play back the camera with [cameraKey]. */
        fun intent(
            context: Context,
            cameraKey: String,
        ): Intent = Intent(context, PlaybackActivity::class.java).putExtra(EXTRA_CAMERA_KEY, cameraKey)
    }
}
