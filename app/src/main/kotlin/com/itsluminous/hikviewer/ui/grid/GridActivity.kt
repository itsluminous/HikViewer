package com.itsluminous.hikviewer.ui.grid

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.databinding.ActivityGridBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.player.TileState
import com.itsluminous.hikviewer.ui.common.InactivityTimer
import com.itsluminous.hikviewer.ui.common.LambdaViewModelFactory
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenActivity
import com.itsluminous.hikviewer.ui.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The camera grid. Tiles are [CameraTileView]s inside a [CameraGridLayout], one per camera and
 * keyed by the camera; rearranging only changes the layout's position order, so no view is
 * reparented (which would destroy its surface) and no stream is rebound. The hidden top bar is driven by an [InactivityTimer] from `dispatchKeyEvent` and hover
 * events so it appears on any input without stealing focus. Back leaves the app (this is the root
 * of the task once logged in).
 *
 * A long press on a tile (held OK, long click, long touch) opens a small menu of [TileAction]s.
 * Move lifts the tile; D-pad keys swap it with the neighbour in that direction, a mouse or finger
 * drag swaps it with the tile under the pointer, OK or release drops it, Back puts the original
 * arrangement back. Every swap is persisted for the current layout by the view model.
 */
class GridActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGridBinding
    private lateinit var topBar: InactivityTimer
    private val tileJobs = mutableListOf<Job>()
    private val tilesByKey = LinkedHashMap<String, CameraTileView>()
    private var pendingFocus: Int? = null

    /** The lifted tile while a move is in progress; its index is read from the grid, since swaps move it. */
    private var moving: CameraTileView? = null

    /** Arrangement when the move began, restored on Back. */
    private var moveOrigin: List<String>? = null

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
        topBar = InactivityTimer(binding.topBar, AppGraph.of(this).topBarAutoHideMillis)
        binding.grid.portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.grid.onTopEdgeUp = {
            topBar.onUserActivity()
            binding.layoutButton
        }
        binding.layoutButton.setOnClickListener { showLayoutMenu(it) }
        binding.settingsButton.setOnClickListener {
            settings.launch(Intent(this, SettingsActivity::class.java))
        }
        binding.root.setOnHoverListener { _, _ ->
            topBar.onUserActivity()
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
        topBar.hideNow()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) topBar.onUserActivity()
        if (moving != null && handleMoveKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) topBar.onUserActivity()
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (moving != null && handleMoveTouch(ev)) return true
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
                moving != null -> endMove()
                tile.showsRetry -> viewModel.retry(index)
                else -> openFullscreen(index)
            }
        }
        tile.setOnLongClickListener {
            if (moving == null) showTileMenu(tile)
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
            tile.onSurface = player::attachSurface
            // A surface that already exists (tile re-bound to a new player) is handed over at once;
            // the holder's surface is null before creation and invalid after destruction.
            tile.surfaceView
                .holder()
                ?.surface
                ?.takeIf { it.isValid }
                ?.let(player::attachSurface)
            tileJobs +=
                lifecycleScope.launch {
                    player.state.collect { state ->
                        tile.bind(state)
                        if (state is TileState.Error && state.wrongVerificationCode) viewModel.onStreamRejectedCode()
                        updateLiveCount(players)
                    }
                }
            tileJobs += lifecycleScope.launch { player.frameRate.collect { tile.frameRate = it } }
        }
    }

    private fun View.holder() = (this as? android.view.SurfaceView)?.holder

    private fun updateLiveCount(players: List<TilePlayer>) {
        val live = players.count { it.state.value == TileState.Live }
        binding.status.text = resources.getQuantityString(R.plurals.grid_live_count, live, live)
    }

    private fun restoreFocus() {
        val grid = binding.grid
        if (grid.childCount == 0) return
        val wanted = (pendingFocus ?: viewModel.focusedIndex).coerceIn(0, grid.childCount - 1)
        pendingFocus = null
        if (!binding.topBar.hasFocus()) grid.childAtPosition(wanted)?.requestFocus()
    }

    private fun openFullscreen(index: Int) {
        viewModel.focusedIndex = index
        fullscreen.launch(FullscreenActivity.intent(this, index))
    }

    private fun showLayoutMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        GridLayoutMode.entries.forEachIndexed { i, mode -> menu.menu.add(0, i, i, mode.labelRes) }
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
        menu.setOnDismissListener { (moving ?: tile).requestFocus() }
        menu.show()
    }

    /** Runs [action] for the tile at grid [position]. */
    fun onTileAction(
        position: Int,
        action: TileAction,
    ) {
        when (action) {
            TileAction.MOVE -> beginMove(position)
        }
    }

    // --- Move mode -----------------------------------------------------------------------------

    /** True while a tile is lifted. */
    val isMoving: Boolean get() = moving != null

    private fun beginMove(position: Int) {
        val tile = binding.grid.childAtPosition(position) as? CameraTileView ?: return
        moving = tile
        moveOrigin = (viewModel.state.value as? GridUiState.Ready)?.cameras?.map { it.key }
        tile.lifted = true
        tile.requestFocus()
        binding.moveHint.visibility = View.VISIBLE
    }

    private fun endMove() {
        moving?.lifted = false
        moving = null
        moveOrigin = null
        binding.moveHint.visibility = View.GONE
    }

    private fun cancelMove() {
        val origin = moveOrigin
        val lifted = moving
        endMove()
        if (origin != null && lifted != null) {
            // The lifted camera's index in the original arrangement is where focus must land.
            val key =
                (viewModel.state.value as? GridUiState.Ready)
                    ?.cameras
                    ?.getOrNull(
                        binding.grid.positionOf(lifted),
                    )?.key
            viewModel.focusedIndex = origin.indexOf(key).coerceAtLeast(0)
            viewModel.restoreArrangement(origin)
        }
    }

    /**
     * Swaps the lifted tile with the one at [target]. The view model persists the new order and
     * moves focus to [target]; the re-render then moves the lifted view there and focuses it.
     */
    private fun swapLifted(target: Int) {
        val from = binding.grid.positionOf(moving ?: return)
        if (target < 0 || target == from || target >= binding.grid.childCount) return
        viewModel.moveTile(from, target)
    }

    private fun handleMoveKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            // Swallow the UP of every key we handled on DOWN (OK's UP would otherwise click the tile).
            return event.keyCode in MOVE_KEYS
        }
        val from = binding.grid.positionOf(moving ?: return false)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> cancelMove()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> endMove()
            KeyEvent.KEYCODE_DPAD_LEFT -> swapLifted(binding.grid.neighbour(from, -1, 0))
            KeyEvent.KEYCODE_DPAD_RIGHT -> swapLifted(binding.grid.neighbour(from, +1, 0))
            KeyEvent.KEYCODE_DPAD_UP -> swapLifted(binding.grid.neighbour(from, 0, -1))
            KeyEvent.KEYCODE_DPAD_DOWN -> swapLifted(binding.grid.neighbour(from, 0, +1))
            else -> return false
        }
        return true
    }

    /** Drag with a finger or a pressed mouse button: swap with whatever tile is under the pointer. */
    private fun handleMoveTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val target = binding.grid.tileUnder(ev.rawX, ev.rawY)
                if (target >= 0) swapLifted(target)
            }
            MotionEvent.ACTION_UP -> endMove()
            MotionEvent.ACTION_CANCEL -> cancelMove()
            else -> return false
        }
        return true
    }

    private fun CameraGridLayout.tileUnder(
        rawX: Float,
        rawY: Float,
    ): Int {
        val origin = IntArray(2).also(::getLocationOnScreen)
        val x = (rawX - origin[0]).toInt()
        val y = (rawY - origin[1]).toInt()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x in c.left until c.right && y in c.top until c.bottom) return positionOf(c)
        }
        return -1
    }

    private companion object {
        val MOVE_KEYS =
            setOf(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
            )
    }
}
