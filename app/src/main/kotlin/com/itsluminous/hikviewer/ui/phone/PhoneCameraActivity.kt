package com.itsluminous.hikviewer.ui.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.itsluminous.hikviewer.databinding.ActivityPhoneCameraBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.ui.common.InactivityTimer
import com.itsluminous.hikviewer.ui.common.LambdaViewModelFactory
import com.itsluminous.hikviewer.ui.common.SwipeDetector
import com.itsluminous.hikviewer.ui.common.SystemBarInsets
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenActivity
import com.itsluminous.hikviewer.ui.playback.PlaybackTimeline
import com.itsluminous.hikviewer.ui.playback.PlaybackUiState
import com.itsluminous.hikviewer.ui.playback.aboutTheDay
import com.itsluminous.hikviewer.ui.playback.statusRes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * The phone's single-camera screen. It follows the phone: upright, a 16:9 live picture on top with
 * the day's time bar and transport controls below (tapping the bar plays recorded footage from
 * that time with the TV playback engine, Live returns to the stream); turned sideways, the picture
 * alone, as large as the screen allows (`layout-land`). Swiping the picture switches camera in
 * both. A rotation recreates the Activity with the other layout while the [PhoneCameraViewModel]
 * keeps the camera, the mode and the recorded engine, so footage resumes at the same position;
 * the controls that have no view in landscape are simply absent from the binding there. The tile
 * menu's Playback opens the same screen in its recorded mode ([EXTRA_PLAYBACK]), so footage too
 * follows the phone instead of forcing it sideways. A TV never shows this screen.
 */
class PhoneCameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPhoneCameraBinding
    private lateinit var swipes: GestureDetector
    private var playerJob: Job? = null
    private var boundPlayer: TilePlayer? = null
    private var swipeStartedOnPicture = false

    /** Landscape only: the help line hides after the grid's idle time; any touch brings it back. */
    private var landscapeHelp: InactivityTimer? = null

    /** True when this instance was inflated with the portrait layout (the one with the time bar). */
    val hasTimeline: Boolean get() = binding.timeBar != null

    private val viewModel: PhoneCameraViewModel by viewModels {
        val graph = AppGraph.of(this)
        LambdaViewModelFactory {
            PhoneCameraViewModel(
                graph.account,
                graph.settingsStore,
                graph.arrangementStore,
                intent.getIntExtra(FullscreenActivity.EXTRA_INDEX, 0),
                graph.playbackFactory,
                startInPlayback = intent.getBooleanExtra(EXTRA_PLAYBACK, false),
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
        binding = ActivityPhoneCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.pad(binding.root)
        binding.video.isFocusable = false
        binding.video.isClickable = false
        val settings = AppGraph.of(this).settingsStore
        // Upright, the name has its own line below the picture; sideways it is an overlay, as on the TV.
        binding.video.showName = if (hasTimeline) false else settings.showCameraNames
        binding.video.showFrameRate = settings.showFrameRate
        swipes = GestureDetector(this, SwipeDetector { direction -> viewModel.step(direction) })
        binding.timeBar?.onSeek = viewModel::scrubTo
        binding.dayBack?.setOnClickListener { viewModel.changeDay(-1) }
        binding.dayForward?.setOnClickListener { viewModel.changeDay(+1) }
        binding.speedButton?.setOnClickListener { viewModel.cycleSpeed() }
        binding.liveButton?.setOnClickListener { viewModel.goLive() }
        if (!hasTimeline) {
            landscapeHelp =
                InactivityTimer(binding.help, AppGraph.of(this).topBarAutoHideMillis).also { it.onUserActivity() }
        }
        observe()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        viewModel.onStop()
        landscapeHelp?.hideNow()
        super.onStop()
    }

    /** Swipes count only on the picture, so a drag along the bar or the buttons never switches camera. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swipeStartedOnPicture = ev.y < binding.videoFrame.bottom
            landscapeHelp?.onUserActivity()
        }
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
        binding.cameraName?.text = viewModel.camera?.name.orEmpty()
        binding.video.cameraName = viewModel.camera?.name.orEmpty()
    }

    /**
     * Live or footage: the Live button and the bar's highlight say which; the picture carries no
     * text badge, its dot alone tells whether pictures arrive (the grid's [com.itsluminous.hikviewer.player.TileLiveness]).
     */
    private fun renderMode() {
        val live = viewModel.mode.value == PhoneCameraMode.LIVE
        binding.liveButton?.isEnabled = !live
        binding.speedButton?.isEnabled = !live
        binding.timeBar?.highlighted = !live
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
        binding.timeBar?.positionFraction = if (onToday) PlaybackTimeline.fractionOfDay(now) else 0f
        binding.timeLabel.text = PlaybackTimeline.label(now)
    }

    private fun renderRecorded(state: PlaybackUiState) {
        binding.dateChip?.text = state.day.toString()
        binding.speedButton?.text = state.speed.label
        binding.timeBar?.segments = state.segments
        val live = viewModel.mode.value == PhoneCameraMode.LIVE
        if (!live) {
            binding.timeBar?.positionFraction = PlaybackTimeline.fractionOfDay(state.position)
            binding.timeLabel.text = PlaybackTimeline.label(state.position)
        }
        // While live, only what concerns the day's listing is worth a line; the transport is the clock.
        val status =
            state.phase.statusRes
                ?.takeIf { !live || state.phase.aboutTheDay }
                ?.let(::getString)
        binding.status.text = status
        binding.status.visibility = if (status == null) View.GONE else View.VISIBLE
    }

    /** Hands the one surface to [player]; the previous owner loses it so its decoder is released. */
    private fun bind(player: TilePlayer?) {
        if (player == boundPlayer) return
        boundPlayer?.attachSurface(null)
        boundPlayer = player
        playerJob?.cancel()
        playerJob = null
        if (player == null) {
            binding.video.detachPlayer()
            return
        }
        playerJob = binding.video.attachPlayer(player, lifecycleScope)
    }

    /** Intent plumbing; shares [FullscreenActivity.EXTRA_INDEX] so the grid refocuses the same way. */
    companion object {
        /** Boolean: open on the camera's recorded footage (the tile menu's Playback) instead of live. */
        const val EXTRA_PLAYBACK: String = "playback"

        /**
         * Intent to open camera [index] (grid position): live with its timeline, or, with [playback],
         * on its recorded footage. Both follow the phone's orientation; the TV's
         * [com.itsluminous.hikviewer.ui.playback.PlaybackActivity] is never used on a phone.
         */
        fun intent(
            context: Context,
            index: Int,
            playback: Boolean = false,
        ): Intent = Intent(context, PhoneCameraActivity::class.java)
            .putExtra(FullscreenActivity.EXTRA_INDEX, index)
            .putExtra(EXTRA_PLAYBACK, playback)
    }
}
