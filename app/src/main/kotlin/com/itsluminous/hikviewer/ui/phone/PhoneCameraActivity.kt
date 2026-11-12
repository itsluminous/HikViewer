package com.itsluminous.hikviewer.ui.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.itsluminous.hikviewer.databinding.ActivityPhoneCameraBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.ui.common.FormFactor
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
 * alone, as large as the screen allows (`layout-land`), with the same transport (the shared
 * `phone_camera_transport` include) laid over its bottom edge. Swiping the picture switches camera
 * in both. A rotation recreates the Activity with the other layout while the
 * [PhoneCameraViewModel] keeps the camera, the mode and the recorded engine, so footage resumes
 * at the same position and speed. The tile menu's Playback opens the same screen in its recorded
 * mode ([EXTRA_PLAYBACK]), so footage too follows the phone instead of forcing it sideways. A TV
 * never shows this screen.
 *
 * Sideways, the controls are a transient visual on the grid's [InactivityTimer] policy: shown for
 * the idle time when the screen opens (so the user learns the bar exists), hidden after it, and a
 * single tap on the picture (`GestureDetector`'s confirmed tap, which a fling never is) shows them
 * again or, while they are up, puts them away. Any touch that lands on the controls restarts the
 * timer and is theirs alone: a drag along the bar scrubs into the footage and never switches
 * camera. Upright, the controls have their own half of the screen and never hide.
 *
 * A phone whose auto-rotate is off ([AutoRotateSetting]) cannot reach the sideways layout by being
 * turned, so, like a video player, the screen then offers the turn as a button: upright, a
 * fullscreen icon in the picture's corner forces `SENSOR_LANDSCAPE` (either way up, as a turned
 * phone would be); sideways, an exit icon in the controls overlay forces `PORTRAIT`. The system
 * recreates the Activity for the new orientation exactly as a physical turn does, and the forced
 * orientation rides along in the saved state so the new instance keeps it (a fresh instance would
 * fall back to the sensor). While forced sideways, one Back goes upright first and the next leaves
 * the screen. With auto-rotate on, the buttons are gone, nothing is forced (`UNSPECIFIED`), and a
 * lock still in force is released the moment the switch is flipped, so the sensor takes over. A TV
 * neither shows the buttons nor locks anything.
 */
class PhoneCameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPhoneCameraBinding
    private lateinit var gestures: GestureDetector
    private var playerJob: Job? = null
    private var boundPlayer: TilePlayer? = null
    private var gestureStartedOnPicture = false
    private var gestureStartedOnControls = false
    private var isTelevision = false
    private val autoRotate by lazy { AutoRotateSetting(contentResolver) }

    /**
     * The orientation this screen forced with its fullscreen buttons (`SENSOR_LANDSCAPE` or
     * `PORTRAIT`), or `UNSPECIFIED` while the phone decides; kept across the recreate in the saved state.
     */
    private var forcedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** Enabled only while forced sideways: Back then goes upright instead of leaving. */
    private val exitFullscreenOnBack =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = exitFullscreen()
        }

    /** Landscape only: the controls overlay (bar, buttons, help) hides after the grid's idle time. */
    private var landscapeControls: InactivityTimer? = null

    /** True when this instance was inflated with the portrait layout (picture on top, controls below). */
    val isUpright: Boolean get() = binding.cameraName != null

    /** True while the landscape controls overlay is on screen; always true upright, where it never hides. */
    val controlsShown: Boolean get() = landscapeControls?.isVisible ?: true

    private val viewModel: PhoneCameraViewModel by viewModels {
        val graph = AppGraph.of(this)
        LambdaViewModelFactory {
            PhoneCameraViewModel(
                graph.account,
                graph.settingsStore,
                graph.arrangementStore,
                intent.getIntExtra(FullscreenActivity.EXTRA_INDEX, 0),
                graph.playbackFactory,
                portraitGrid = intent.getBooleanExtra(EXTRA_PORTRAIT_GRID, false),
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
        binding.video.showName = if (isUpright) false else settings.showCameraNames
        binding.video.showFrameRate = settings.showFrameRate
        val onTap: (() -> Unit)? = if (isUpright) null else ::toggleControls
        gestures = GestureDetector(this, SwipeDetector(onTap) { direction -> viewModel.step(direction) })
        binding.transport.timeBar.onSeek = viewModel::scrubTo
        binding.transport.dayBack.setOnClickListener { viewModel.changeDay(-1) }
        binding.transport.dayForward.setOnClickListener { viewModel.changeDay(+1) }
        binding.transport.speedButton.setOnClickListener { viewModel.cycleSpeed() }
        binding.transport.liveButton.setOnClickListener { viewModel.goLive() }
        if (!isUpright) {
            // The window hands its first focus to the overlay's first button (there is nothing else
            // focusable), and a view that holds focus cannot be hidden; the root takes it instead.
            binding.root.isFocusable = true
            binding.root.isFocusableInTouchMode = true
            landscapeControls =
                InactivityTimer(
                    binding.controls,
                    AppGraph.of(this).topBarAutoHideMillis,
                    releaseFocus = { binding.root.requestFocus() },
                ).also { it.onUserActivity() }
        }
        isTelevision = FormFactor.isTelevision(this)
        forcedOrientation =
            savedInstanceState?.getInt(STATE_FORCED_ORIENTATION, forcedOrientation) ?: forcedOrientation
        binding.fullscreenButton?.setOnClickListener { enterFullscreen() }
        binding.transport.fullscreenExitButton?.setOnClickListener { exitFullscreen() }
        onBackPressedDispatcher.addCallback(this, exitFullscreenOnBack)
        observe()
    }

    /** A tap on the picture sideways: the controls come up for the idle time, or go away if they were up. */
    private fun toggleControls() {
        val timer = landscapeControls ?: return
        if (timer.isVisible) timer.hideNow() else timer.onUserActivity()
    }

    /** The upright fullscreen button: sideways, either way up, until exited or auto-rotate is turned on. */
    private fun enterFullscreen() = force(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

    /** The sideways exit button, or Back while forced sideways: upright again. */
    private fun exitFullscreen() = force(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

    private fun force(orientation: Int) {
        forcedOrientation = orientation
        refreshOrientationControls()
    }

    /**
     * Puts the fullscreen buttons, the orientation lock and the Back behaviour in line with the
     * auto-rotate switch: all three exist only on a phone whose switch is off. Called when the
     * screen resumes and whenever the switch changes while it is up.
     */
    private fun refreshOrientationControls() {
        if (isTelevision) return
        val offersToggle = !autoRotate.isOn
        if (!offersToggle) forcedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (requestedOrientation != forcedOrientation) requestedOrientation = forcedOrientation
        binding.fullscreenButton?.isVisible = offersToggle
        binding.transport.fullscreenExitButton?.isVisible = offersToggle
        exitFullscreenOnBack.isEnabled =
            offersToggle &&
            !isUpright &&
            forcedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_FORCED_ORIENTATION, forcedOrientation)
    }

    override fun onResume() {
        super.onResume()
        refreshOrientationControls()
        if (!isTelevision) autoRotate.watch(::refreshOrientationControls)
    }

    override fun onPause() {
        autoRotate.stop()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        viewModel.onStop()
        landscapeControls?.hideNow()
        super.onStop()
    }

    /**
     * Swipes and taps count only on the picture, so a drag along the bar or a press on a button
     * never switches camera or toggles the overlay. A touch on the shown overlay (sideways) is the
     * user working the controls: every event of it restarts their timer, so a slow scrub along the
     * bar never has the bar vanish under the finger, and the idle time starts when it lifts.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureStartedOnControls = controlsShown && hits(binding.controls, ev)
            gestureStartedOnPicture = !gestureStartedOnControls && ev.y < binding.videoFrame.bottom
        }
        if (gestureStartedOnControls) landscapeControls?.onUserActivity()
        if (gestureStartedOnPicture) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** True when [ev] (window coordinates, as the Activity receives them) is inside [view]. */
    private fun hits(
        view: View,
        ev: MotionEvent,
    ): Boolean {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val x = ev.x - location[0]
        val y = ev.y - location[1]
        return x >= 0 && y >= 0 && x < view.width && y < view.height
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
        binding.transport.liveButton.isEnabled = !live
        binding.transport.speedButton.isEnabled = !live
        binding.transport.timeBar.highlighted = !live
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
        binding.transport.timeBar.positionFraction = if (onToday) PlaybackTimeline.fractionOfDay(now) else 0f
        binding.transport.timeLabel.text = PlaybackTimeline.label(now)
    }

    private fun renderRecorded(state: PlaybackUiState) {
        binding.transport.dateChip.text = state.day.toString()
        binding.transport.speedButton.text = state.speed.label
        binding.transport.timeBar.segments = state.segments
        val live = viewModel.mode.value == PhoneCameraMode.LIVE
        if (!live) {
            binding.transport.timeBar.positionFraction = PlaybackTimeline.fractionOfDay(state.position)
            binding.transport.timeLabel.text = PlaybackTimeline.label(state.position)
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
         * Boolean: the grid that sent [FullscreenActivity.EXTRA_INDEX] was upright, so the index
         * counts in its portrait layout's arrangement (a phone keeps a layout per orientation).
         */
        const val EXTRA_PORTRAIT_GRID: String = "portraitGrid"

        /** Saved state: the orientation the fullscreen buttons forced, so a recreate keeps the lock. */
        private const val STATE_FORCED_ORIENTATION = "forcedOrientation"

        /**
         * Intent to open camera [index] (a position in the grid, which was upright when
         * [portraitGrid]): live with its timeline, or, with [playback], on its recorded footage.
         * Both follow the phone's orientation; the TV's
         * [com.itsluminous.hikviewer.ui.playback.PlaybackActivity] is never used on a phone.
         */
        fun intent(
            context: Context,
            index: Int,
            portraitGrid: Boolean = false,
            playback: Boolean = false,
        ): Intent = Intent(context, PhoneCameraActivity::class.java)
            .putExtra(FullscreenActivity.EXTRA_INDEX, index)
            .putExtra(EXTRA_PORTRAIT_GRID, portraitGrid)
            .putExtra(EXTRA_PLAYBACK, playback)
    }
}
