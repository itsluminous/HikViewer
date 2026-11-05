package com.itsluminous.hikviewer.ui.grid

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.material.button.MaterialButton
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.databinding.ActivityGridBinding
import com.itsluminous.hikviewer.di.AppGraph
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
 * Tapping a tile opens the camera full screen; on a phone held upright it opens the portrait
 * [PhoneCameraActivity] (live on top, timeline below) so the screen does not rotate.
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
        portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.grid.portrait = portrait
        move = TileMoveController(binding.grid, binding.moveHint, viewModel)
        placeBar()
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
     * TV: the bar overlays the grid, hidden until a key arrives. Phone portrait: it stays at the
     * top and the grid starts under it. Phone landscape: the same actions stand in a column down
     * the left edge, icons only, and the grid starts to its right.
     */
    private fun placeBar() {
        val bar = binding.topBar
        if (television) {
            topBar = InactivityTimer(bar, AppGraph.of(this).topBarAutoHideMillis)
            return
        }
        bar.visibility = View.VISIBLE
        SystemBarInsets.pad(binding.root)
        val gridParams = binding.grid.layoutParams as FrameLayout.LayoutParams
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
            binding.status.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            binding.status.gravity = android.view.Gravity.CENTER_HORIZONTAL
            listOf(binding.layoutButton, binding.settingsButton).forEach { button ->
                button.contentDescription = button.text
                button.text = ""
                button.iconPadding = 0
                button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                button.minWidth = 0
                button.minimumWidth = 0
                button.setPadding(0, button.paddingTop, 0, button.paddingBottom)
                button.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            gridParams.marginStart = width
        }
        binding.grid.layoutParams = gridParams
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
                syncTiles(state.cameras, state.showNames, state.showFrameRate)
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
    ) {
        val grid = binding.grid
        val wanted = cameras.mapTo(HashSet()) { it.key }
        tilesByKey.keys.filter { it !in wanted }.forEach { key -> grid.removeView(tilesByKey.remove(key)) }
        cameras.forEach { camera ->
            val tile = tilesByKey.getOrPut(camera.key) { newTile().also(grid::addView) }
            tile.cameraName = camera.name
            tile.showName = showNames
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
            if (!move.isMoving) showTileMenu(tile)
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
                    updateLiveCount(players)
                }
        }
    }

    private fun updateLiveCount(players: List<TilePlayer>) {
        val live = players.count { it.state.value == TileState.Live }
        binding.status.text = resources.getQuantityString(R.plurals.grid_live_count, live, live)
    }

    /**
     * Focuses the remembered tile when nothing in the grid has focus yet (first render, back from
     * another screen). A render while a tile already holds focus leaves it alone: focus belongs to
     * the view, so it follows a moved tile by itself, and the first frames after launch (16
     * surfaces being created) must not be spent re-requesting focus every state change.
     */
    private fun restoreFocus() {
        val grid = binding.grid
        if (grid.childCount == 0) return
        val pending = pendingFocus
        pendingFocus = null
        if (pending == null && grid.hasFocus()) return
        val wanted = (pending ?: viewModel.focusedIndex).coerceIn(0, grid.childCount - 1)
        if (!binding.topBar.hasFocus()) grid.childAtPosition(wanted)?.requestFocus()
    }

    /** Phones held upright get the portrait live + timeline screen; everything else goes full screen. */
    private fun openFullscreen(index: Int) {
        viewModel.focusedIndex = index
        if (!television && portrait) {
            fullscreen.launch(PhoneCameraActivity.intent(this, index))
        } else {
            fullscreen.launch(FullscreenActivity.intent(this, index))
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

    private fun openPlayback(position: Int) {
        val camera = (viewModel.state.value as? GridUiState.Ready)?.cameras?.getOrNull(position) ?: return
        viewModel.focusedIndex = position
        startActivity(PlaybackActivity.intent(this, camera.key))
    }

    /** Layouts worth offering for the cameras on screen (see [GridLayoutMode.available]); AUTO is always there. */
    private fun showLayoutMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val cameras = (viewModel.state.value as? GridUiState.Ready)?.cameras?.size ?: 0
        GridLayoutMode.available(cameras).forEach { mode ->
            menu.menu.add(0, mode.ordinal, mode.ordinal, mode.labelRes)
        }
        menu.setOnMenuItemClickListener {
            viewModel.setLayout(GridLayoutMode.entries[it.itemId])
            true
        }
        menu.show()
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
}
