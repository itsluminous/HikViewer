package com.itsluminous.hikviewer.ui.grid

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isEmpty
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.material.button.MaterialButton
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.data.NamePosition
import com.itsluminous.hikviewer.databinding.ActivityGridBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.player.TileLiveness
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.player.TileState
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.common.InactivityTimer
import com.itsluminous.hikviewer.ui.common.LambdaViewModelFactory
import com.itsluminous.hikviewer.ui.common.SystemBarInsets
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenActivity
import com.itsluminous.hikviewer.ui.phone.PhoneCameraActivity
import com.itsluminous.hikviewer.ui.playback.PlaybackActivity
import com.itsluminous.hikviewer.ui.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The camera grid. Tiles are [CameraTileView]s inside a [CameraGridLayout], one per camera and
 * keyed by the camera; rearranging only changes the layout's position order, so no view is
 * reparented (which would destroy its surface) and no stream is rebound. On a TV the hidden top
 * bar is driven by an [InactivityTimer] from `dispatchKeyEvent` and hover events so it appears on
 * any input without stealing focus; a phone has no D-pad to summon it, so there the bar is always
 * on screen: along the top in portrait, down the left edge in landscape (see [FormFactor]). Back
 * leaves the app (this is the root of the task once logged in).
 *
 * Tapping a tile opens the camera full screen on a TV; on a phone it opens [PhoneCameraActivity],
 * which shows live + timeline upright and the picture alone sideways, following the phone.
 *
 * A long press on a tile (held OK, long click, long touch) opens a small menu of [TileAction]s:
 * Move hands the tile to a [TileMoveController], Change lists the cameras in a
 * [CameraPickerDialog], Playback opens the recording screen.
 */
class GridActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGridBinding

    /** Auto-hide policy of the bar; null on phones, where the bar is persistent. */
    private var topBar: InactivityTimer? = null
    private var television = true
    private var portrait = false

    /** Phone landscape: the status is stacked in short lines so the side bar stays icon-wide. */
    private var compactStatus = false
    private val tileJobs = mutableListOf<Job>()
    private val tilesByKey = LinkedHashMap<String, CameraTileView>()
    private var pendingFocus: Int? = null
    private var picker: AlertDialog? = null

    /** Lift-and-swap of a tile (long press → Move); see [TileMoveController]. */
    private lateinit var move: TileMoveController

    private val viewModel: GridViewModel by viewModels {
        val graph = AppGraph.of(this)
        LambdaViewModelFactory {
            GridViewModel(graph.account, graph.settingsStore, graph.arrangementStore) { vm ->
                graph.playbackFactory.create(
                    opener = { camera, quality, onStatus -> graph.account.openStream(camera, quality, onStatus) },
                    capabilities = graph.capabilities,
                    scope = vm.viewModelScope,
                )
            }
        }
    }

    private val fullscreen =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingFocus = result.data?.getIntExtra(FullscreenActivity.EXTRA_INDEX, -1)?.takeIf { it >= 0 }
            restoreFocus()
        }

    private val settings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (!AppGraph.of(this).account.isLoggedIn) {
                finish()
                return@registerForActivityResult
            }
            viewModel.applySettings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGridBinding.inflate(layoutInflater)
        setContentView(binding.root)
        television = FormFactor.isTelevision(this)
        // A TV is never "upright": it has one layout, the landscape one, whatever it reports.
        portrait = !television && resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.grid.portrait = portrait
        viewModel.portrait = portrait
        move = TileMoveController(binding.grid, binding.moveHint, viewModel)
        placeBar()
        // The focus border follows the top bar's idle policy (see CameraGridLayout).
        binding.grid.focusAutoHideMillis = AppGraph.of(this).topBarAutoHideMillis
        binding.grid.onTopEdgeUp = {
            topBar?.onUserActivity()
            binding.layoutButton
        }
        binding.layoutButton.setOnClickListener { showLayoutMenu(it) }
        binding.settingsButton.setOnClickListener {
            settings.launch(Intent(this, SettingsActivity::class.java))
        }
        binding.root.setOnHoverListener { _, _ ->
            topBar?.onUserActivity()
            false
        }
        observe()
        viewModel.load()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        viewModel.onStop()
        topBar?.hideNow()
        super.onStop()
    }

    /** Dialogs are windows of this activity; on a phone rotation it is recreated and the new one shows its own. */
    override fun onDestroy() {
        picker?.dismiss()
        picker = null
        VerificationCodeDialog.dismiss()
        super.onDestroy()
    }

    /**
     * TV: the bar overlays the grid, hidden until a key arrives, its buttons labelled. Phone: the
     * bar stays on screen and its buttons are icons only (the label survives as the content
     * description), since a 360 dp screen has no room for "Layout" and "Settings" next to the
     * status. Portrait: along the top, the grid starts under it. Landscape: the same actions stand
     * in a column down the left edge and the grid starts to its right.
     */
    private fun placeBar() {
        val bar = binding.topBar
        if (television) {
            topBar = InactivityTimer(bar, AppGraph.of(this).topBarAutoHideMillis, releaseFocus = ::leaveIdleBar)
            return
        }
        bar.visibility = View.VISIBLE
        SystemBarInsets.pad(binding.root)
        val gridParams = binding.grid.layoutParams as FrameLayout.LayoutParams
        val buttons = listOf(binding.layoutButton, binding.settingsButton)
        buttons.forEach(::iconOnly)
        if (portrait) {
            gridParams.topMargin = resources.getDimensionPixelSize(R.dimen.top_bar_height)
        } else {
            val width = resources.getDimensionPixelSize(R.dimen.side_bar_width)
            bar.orientation = LinearLayout.VERTICAL
            bar.layoutParams =
                FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = android.view.Gravity.START
                }
            bar.setPadding(0, resources.getDimensionPixelSize(R.dimen.side_bar_padding), 0, 0)
            binding.title.visibility = View.GONE
            // Stacked status (LiveStatusText.compact): the bar must stay as wide as its icons.
            compactStatus = true
            binding.status.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            binding.status.gravity = android.view.Gravity.CENTER_HORIZONTAL
            binding.status.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIDE_BAR_STATUS_SP)
            buttons.forEach { button ->
                button.setPadding(0, button.paddingTop, 0, button.paddingBottom)
                button.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            gridParams.marginStart = width
        }
        binding.grid.layoutParams = gridParams
    }

    /**
     * The top bar went idle with focus on one of its buttons: someone who picked a layout seldom
     * walks back down to the cameras, so the bar would stay over the grid for good. Focus goes to
     * the first tile instead, quietly (the border stays hidden: the user is idle), and the bar
     * hides. Not while the layout menu or a dialog is open: that is a window of its own, the keys
     * go there and never reach this activity's timer, so idle here does not mean idle there.
     */
    private fun leaveIdleBar(): Boolean = hasWindowFocus() && binding.grid.focusQuietly(0)

    /** Drops a bar button's text, keeping it as the content description, and shrinks it to its icon. */
    private fun iconOnly(button: MaterialButton) {
        button.contentDescription = button.text
        button.text = ""
        button.iconPadding = 0
        button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        button.minWidth = 0
        button.minimumWidth = 0
        val pad = resources.getDimensionPixelSize(R.dimen.icon_button_padding)
        button.setPadding(pad, button.paddingTop, pad, button.paddingBottom)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // How long the key waited for the main thread: the number a TV logcat is read for when
        // the remote feels slow. One line per press and release, nothing per frame.
        val waited = SystemClock.uptimeMillis() - event.eventTime
        val action = if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"
        Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} $action reached the grid $waited ms after the press")
        if (event.action == KeyEvent.ACTION_DOWN) topBar?.onUserActivity()
        if (move.isMoving && move.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) topBar?.onUserActivity()
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (move.isMoving && move.onTouch(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.playback.players.collect(::bindPlayers) }
            }
        }
    }

    private fun render(state: GridUiState) {
        binding.message.visibility =
            if (state is GridUiState.Ready && state.cameras.isNotEmpty()) View.GONE else View.VISIBLE
        when (state) {
            GridUiState.Loading -> binding.message.setText(R.string.grid_loading)
            is GridUiState.Error -> binding.message.text = state.message
            is GridUiState.Ready -> {
                if (state.cameras.isEmpty()) binding.message.setText(R.string.grid_empty)
                binding.grid.mode = state.layout
                syncTiles(state.cameras, state.showNames, state.showFrameRate, state.namePosition)
                if (state.needsCode) {
                    VerificationCodeDialog.show(
                        this,
                        state.codeRejected,
                        viewModel::submitVerificationCode,
                    )
                }
            }
        }
    }

    /** One tile per camera; the grid shows them in [cameras] order. Existing tiles are never recreated or reparented. */
    private fun syncTiles(
        cameras: List<Camera>,
        showNames: Boolean,
        showFrameRate: Boolean,
        namePosition: NamePosition,
    ) {
        val grid = binding.grid
        val wanted = cameras.mapTo(HashSet()) { it.key }
        tilesByKey.keys.filter { it !in wanted }.forEach { key -> grid.removeView(tilesByKey.remove(key)) }
        cameras.forEach { camera ->
            val tile = tilesByKey.getOrPut(camera.key) { newTile().also(grid::addView) }
            tile.cameraName = camera.name
            tile.showName = showNames
            tile.namePosition = namePosition
            tile.showFrameRate = showFrameRate
        }
        grid.order = cameras.map { grid.indexOfChild(tilesByKey.getValue(it.key)) }
        restoreFocus()
    }

    private fun newTile(): CameraTileView {
        val tile = CameraTileView(this)
        tile.setOnClickListener {
            val index = binding.grid.positionOf(tile)
            when {
                move.isMoving -> move.end()
                tile.showsRetry -> viewModel.retry(index)
                else -> openFullscreen(index)
            }
        }
        tile.setOnLongClickListener {
            // A tile whose decoder failed answers the long press with the codec's words (the one
            // thing a D-pad user can do to read them); every other tile gets the menu.
            if (!move.isMoving && !tile.showDecoderDetail()) showTileMenu(tile)
            true
        }
        tile.setOnFocusChangeListener { _, focused ->
            if (focused) viewModel.focusedIndex = binding.grid.positionOf(tile)
        }
        return tile
    }

    private fun bindPlayers(players: List<TilePlayer>) {
        tileJobs.forEach { it.cancel() }
        tileJobs.clear()
        players.forEach { player ->
            val tile = tilesByKey[player.camera.key] ?: return@forEach
            tileJobs +=
                tile.attachPlayer(player, lifecycleScope) { state ->
                    if (state is TileState.Error && state.wrongVerificationCode) viewModel.onStreamRejectedCode()
                }
            // Liveness moves on the player's tick, not only with its state (a stalled tile keeps
            // its state and loses its dot), so the count follows the same flow as the dot.
            tileJobs += lifecycleScope.launch { player.liveness.collect { updateLiveCount(players) } }
        }
    }

    private fun updateLiveCount(players: List<TilePlayer>) {
        val counts = TileLiveness.count(players.map { it.liveness.value })
        binding.status.text =
            if (compactStatus) LiveStatusText.compact(resources, counts) else LiveStatusText.format(resources, counts)
    }

    /**
     * Focuses the remembered tile when nothing in the grid has focus yet (first render, back from
     * another screen). A render while a tile already holds focus leaves it alone: focus belongs to
     * the view, so it follows a moved tile by itself, and the first frames after launch (16
     * surfaces being created) must not be spent re-requesting focus every state change.
     */
    private fun restoreFocus() {
        val grid = binding.grid
        if (grid.isEmpty()) return
        val pending = pendingFocus
        pendingFocus = null
        if (pending == null && grid.hasFocus()) return
        val wanted = (pending ?: viewModel.focusedIndex).coerceIn(0, grid.childCount - 1)
        if (!binding.topBar.hasFocus()) grid.childAtPosition(wanted)?.requestFocus()
    }

    /**
     * A phone gets the single-camera screen that follows the phone's orientation (live + timeline
     * upright, the picture alone sideways); a TV goes to the fullscreen screen with the D-pad hint.
     */
    private fun openFullscreen(index: Int) {
        viewModel.focusedIndex = index
        if (television) {
            fullscreen.launch(FullscreenActivity.intent(this, index))
        } else {
            fullscreen.launch(PhoneCameraActivity.intent(this, index, portraitGrid = portrait))
        }
    }

    /** Change: list the cameras on the grid; the pick replaces (or swaps with) this tile's camera. */
    private fun showCameraPicker(position: Int) {
        val state = viewModel.state.value as? GridUiState.Ready ?: return
        val current = state.cameras.getOrNull(position) ?: return
        viewModel.focusedIndex = position
        picker =
            CameraPickerDialog.show(
                this,
                state.cameras,
                current.key,
                onPick = { key -> viewModel.changeTile(position, key) },
                // The dialog is its own window; once it is gone the tile (now showing the pick) takes
                // focus back. Not after onDestroy, when the grid is detached.
                onDismiss = {
                    picker = null
                    if (!isDestroyed) binding.grid.childAtPosition(position)?.requestFocus()
                },
            )
    }

    /**
     * Playback: the TV gets the D-pad playback screen, always sideways; a phone gets its camera
     * screen in recorded mode, which follows the phone (video over the bar upright, the picture
     * alone sideways) and hands the tile index back like the live screen does.
     */
    private fun openPlayback(position: Int) {
        val camera = (viewModel.state.value as? GridUiState.Ready)?.cameras?.getOrNull(position) ?: return
        viewModel.focusedIndex = position
        if (television) {
            startActivity(PlaybackActivity.intent(this, camera.key))
        } else {
            fullscreen.launch(PhoneCameraActivity.intent(this, position, portraitGrid = portrait, playback = true))
        }
    }

    /**
     * Layouts worth offering for the cameras on screen (see [GridLayoutMode.available]); AUTO is
     * always there, and a phone held upright gets the portrait shapes (1 x N, 2 x N) and the even
     * 1 + N layouts that fill its two columns instead of the TV's grids. The entry in force (this
     * orientation's stored layout, AUTO when AUTO is stored) is checked and the D-pad starts on
     * it; see [LayoutPickerDialog] for why this is a list dialog and not a `PopupMenu`.
     */
    private fun showLayoutMenu(anchor: View) {
        val cameras = (viewModel.state.value as? GridUiState.Ready)?.cameras?.size ?: 0
        val current = AppGraph.of(this).arrangementStore.layout(portrait)
        picker =
            LayoutPickerDialog.show(
                this,
                GridLayoutMode.available(cameras, portraitPhone = portrait),
                current,
                onPick = { mode -> viewModel.setLayout(mode) },
                // The dialog is its own window; once it is gone the bar's button takes focus back,
                // as it did after the popup. Not after onDestroy, when the view is detached.
                onDismiss = {
                    picker = null
                    if (!isDestroyed) {
                        topBar?.onUserActivity()
                        anchor.requestFocus()
                    }
                },
            )
    }

    private fun showTileMenu(tile: CameraTileView) {
        val menu = PopupMenu(this, tile)
        TileAction.entries.forEachIndexed { i, action -> menu.menu.add(0, i, i, action.labelRes) }
        menu.setOnMenuItemClickListener {
            onTileAction(binding.grid.positionOf(tile), TileAction.entries[it.itemId])
            true
        }
        // The popup is its own window: while it is up the tile cannot hold focus, so once it is
        // gone the lifted tile (or the one the menu was opened on) takes focus back.
        menu.setOnDismissListener { (move.lifted ?: tile).requestFocus() }
        menu.show()
    }

    /** Runs [action] for the tile at grid [position]. */
    fun onTileAction(
        position: Int,
        action: TileAction,
    ) {
        when (action) {
            TileAction.MOVE -> move.begin(position)
            TileAction.CHANGE -> showCameraPicker(position)
            TileAction.PLAYBACK -> openPlayback(position)
        }
    }

    /** True while a tile is lifted. */
    val isMoving: Boolean get() = move.isMoving

    private companion object {
        const val TAG = "HikViewer"

        /** The stacked status in the side bar: small enough for "1 fps" to fit the bar's width in one line. */
        const val SIDE_BAR_STATUS_SP = 12f
    }
}
