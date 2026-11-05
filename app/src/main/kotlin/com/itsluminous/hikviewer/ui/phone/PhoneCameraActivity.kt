package com.itsluminous.hikviewer.ui.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.databinding.ActivityPhoneCameraBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.common.LambdaViewModelFactory
import com.itsluminous.hikviewer.ui.common.SwipeDetector
import com.itsluminous.hikviewer.ui.common.SystemBarInsets
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenActivity
import com.itsluminous.hikviewer.ui.playback.PlaybackPhase
import com.itsluminous.hikviewer.ui.playback.PlaybackTimeline
import com.itsluminous.hikviewer.ui.playback.PlaybackUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * The phone's single-camera screen, portrait: a 16:9 live picture on top, the day's time bar and
 * transport controls below. Tapping the bar plays recorded footage from that time (the TV
 * playback engine), Live returns to the stream, swiping the picture switches camera. Locked to
 * portrait on phones so opening a tile never rotates the screen; a TV never shows it.
 */
class PhoneCameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPhoneCameraBinding
    private lateinit var swipes: GestureDetector
    private var playerJob: Job? = null
    private var boundPlayer: TilePlayer? = null
    private var swipeStartedOnPicture = false

    private val viewModel: PhoneCameraViewModel by viewModels {
        val graph = AppGraph.of(this)
        LambdaViewModelFactory {
            PhoneCameraViewModel(
                graph.account,
                graph.settingsStore,
                intent.getIntExtra(FullscreenActivity.EXTRA_INDEX, 0),
                graph.playbackFactory,
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

    // The lock is the point of this screen: a tile tapped on an upright phone must not rotate the
    // device into the landscape fullscreen view. Televisions and the landscape grid never open it.
    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FormFactor.isTelevision(this)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding = ActivityPhoneCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.pad(binding.root)
        binding.video.isFocusable = false
        binding.video.isClickable = false
        val settings = AppGraph.of(this).settingsStore
        binding.video.showName = false // the name has its own line below the picture
        binding.video.showFrameRate = settings.showFrameRate
        swipes = GestureDetector(this, SwipeDetector { direction -> viewModel.step(direction) })
        binding.timeBar.onSeek = viewModel::scrubTo
        binding.dayBack.setOnClickListener { viewModel.changeDay(-1) }
        binding.dayForward.setOnClickListener { viewModel.changeDay(+1) }
        binding.speedButton.setOnClickListener { viewModel.cycleSpeed() }
        binding.liveButton.setOnClickListener { viewModel.goLive() }
        observe()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        viewModel.onStop()
        super.onStop()
    }

    /** Swipes count only on the picture, so a drag along the bar or the buttons never switches camera. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) swipeStartedOnPicture = ev.y < binding.videoFrame.bottom
        if (swipeStartedOnPicture) swipes.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun finish() {
        setResult(Activity.RESULT_OK, Intent().putExtra(FullscreenActivity.EXTRA_INDEX, viewModel.index.value))
        super.finish()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.index.collect { renderCamera() } }
                launch { viewModel.mode.collect { renderMode() } }
                launch { viewModel.activePlayer.collect(::bind) }
                launch { viewModel.recorded.collectLatest { engine -> engine?.state?.collect(::renderRecorded) } }
                launch {
                    while (isActive) {
                        renderClock()
                        viewModel.tick()
                        delay(viewModel.watchdogMillis)
                    }
                }
            }
        }
    }

    private fun renderCamera() {
        binding.cameraName.text = viewModel.camera?.name.orEmpty()
        binding.video.cameraName = viewModel.camera?.name.orEmpty()
    }

    private fun renderMode() {
        val live = viewModel.mode.value == PhoneCameraMode.LIVE
        binding.liveBadge.visibility = if (live) View.VISIBLE else View.GONE
        binding.liveButton.isEnabled = !live
        binding.speedButton.isEnabled = !live
        binding.timeBar.highlighted = !live
        renderClock()
    }

    /** In live mode the playhead is the clock; the recorded state's position takes over otherwise. */
    private fun renderClock() {
        if (viewModel.mode.value != PhoneCameraMode.LIVE) return
        val now = LocalDateTime.now()
        val engineDay =
            viewModel.recorded.value
                ?.state
                ?.value
                ?.day
        val onToday = engineDay == null || engineDay == now.toLocalDate()
        binding.timeBar.positionFraction = if (onToday) PlaybackTimeline.fractionOfDay(now) else 0f
        binding.timeLabel.text = PlaybackTimeline.label(now)
    }

    private fun renderRecorded(state: PlaybackUiState) {
        binding.dateChip.text = state.day.toString()
        binding.speedButton.text = state.speed.label
        binding.timeBar.segments = state.segments
        val live = viewModel.mode.value == PhoneCameraMode.LIVE
        if (!live) {
            binding.timeBar.positionFraction = PlaybackTimeline.fractionOfDay(state.position)
            binding.timeLabel.text = PlaybackTimeline.label(state.position)
        }
        val status =
            when {
                state.phase == PlaybackPhase.LOADING -> getString(R.string.playback_loading)
                state.phase == PlaybackPhase.NO_RECORDINGS -> getString(R.string.playback_no_recordings)
                state.phase == PlaybackPhase.ERROR -> getString(R.string.playback_error)
                live -> null
                state.phase == PlaybackPhase.ENDED -> getString(R.string.playback_ended)
                state.phase == PlaybackPhase.PAUSED -> getString(R.string.playback_paused)
                else -> null
            }
        binding.status.text = status
        binding.status.visibility = if (status == null) View.GONE else View.VISIBLE
    }

    /** Hands the one surface to [player]; the previous owner loses it so its decoder is released. */
    private fun bind(player: TilePlayer?) {
        if (player == boundPlayer) return
        boundPlayer?.attachSurface(null)
        boundPlayer = player
        playerJob?.cancel()
        binding.video.onSurface = player?.let { it::attachSurface }
        if (player == null) return
        (binding.video.surfaceView as? SurfaceView)
            ?.holder
            ?.surface
            ?.takeIf { it.isValid }
            ?.let(player::attachSurface)
        playerJob =
            lifecycleScope.launch {
                launch { player.state.collect(binding.video::bind) }
                launch { player.frameRate.collect { binding.video.frameRate = it } }
            }
    }

    /** Intent plumbing; shares [FullscreenActivity.EXTRA_INDEX] so the grid refocuses the same way. */
    companion object {
        /** Intent to open camera [index] (grid position) live with its timeline. */
        fun intent(
            context: Context,
            index: Int,
        ): Intent = Intent(context, PhoneCameraActivity::class.java).putExtra(FullscreenActivity.EXTRA_INDEX, index)
    }
}
