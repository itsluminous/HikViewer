package com.itsluminous.hikviewer.ui.fullscreen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.itsluminous.hikviewer.databinding.ActivityFullscreenBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.ui.common.InactivityTimer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Shows one camera edge to edge. LEFT/RIGHT (or tapping the left/right third of the screen on a
 * phone) switch camera; Back returns the index to the grid so the same tile regains focus. The
 * "switch camera" hint appears on entry and on any key, pointer or touch, and hides after the
 * same idle time as the grid's top bar.
 */
class FullscreenActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFullscreenBinding
    private lateinit var hint: InactivityTimer
    private var stateJob: Job? = null

    private val viewModel: FullscreenViewModel by viewModels {
        val graph = AppGraph.of(this)
        com.itsluminous.hikviewer.ui.common.LambdaViewModelFactory {
            FullscreenViewModel(
                graph.account,
                graph.settingsStore,
                intent.getIntExtra(EXTRA_INDEX, 0),
            ) { vm ->
                graph.playbackFactory.create(
                    opener = { camera, quality, onStatus -> graph.account.openStream(camera, quality, onStatus) },
                    capabilities = graph.capabilities,
                    scope = vm.viewModelScope,
                    quality = graph.settingsStore.fullscreenQuality,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tile.isFocusable = false
        binding.tile.isClickable = false
        hint = InactivityTimer(binding.hint, AppGraph.of(this).topBarAutoHideMillis)
        hint.onUserActivity()
        binding.root.setOnHoverListener { _, _ ->
            hint.onUserActivity()
            false
        }
        binding.leftZone.setOnClickListener { viewModel.step(-1) }
        binding.rightZone.setOnClickListener { viewModel.step(+1) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.index.collect { render() } }
                launch { viewModel.playback.players.collect { players -> bind(players.firstOrNull()) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        viewModel.onStop()
        hint.hideNow()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) hint.onUserActivity()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) hint.onUserActivity()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) hint.onUserActivity()
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> {
            viewModel.step(-1)
            true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            viewModel.step(+1)
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun finish() {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_INDEX, viewModel.index.value))
        super.finish()
    }

    private fun render() {
        binding.tile.cameraName = viewModel.camera?.name.orEmpty()
    }

    private fun bind(player: com.itsluminous.hikviewer.player.TilePlayer?) {
        stateJob?.cancel()
        binding.tile.onSurface = player?.let { it::attachSurface }
        if (player == null) return
        (binding.tile.surfaceView as? android.view.SurfaceView)
            ?.holder
            ?.surface
            ?.takeIf {
                it.isValid
            }?.let(player::attachSurface)
        stateJob = lifecycleScope.launch { player.state.collect(binding.tile::bind) }
    }

    /** Intent plumbing. */
    companion object {
        /** Index of the camera in the grid's visible list; also returned as the result. */
        const val EXTRA_INDEX: String = "index"

        /** Intent to open camera [index] full screen. */
        fun intent(
            context: Context,
            index: Int,
        ): Intent = Intent(context, FullscreenActivity::class.java).putExtra(EXTRA_INDEX, index)
    }
}
