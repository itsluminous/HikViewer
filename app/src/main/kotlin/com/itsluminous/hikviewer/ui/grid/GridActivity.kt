package com.itsluminous.hikviewer.ui.grid

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
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
import com.itsluminous.hikviewer.ui.common.LambdaViewModelFactory
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenActivity
import com.itsluminous.hikviewer.ui.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The camera grid. Tiles are [CameraTileView]s inside a [CameraGridLayout]; the hidden top bar is
 * driven by [TopBarController] from `dispatchKeyEvent` and hover events so it appears on any input
 * without stealing focus. Back leaves the app (this is the root of the task once logged in).
 */
class GridActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGridBinding
    private lateinit var topBar: TopBarController
    private val tileJobs = mutableListOf<Job>()
    private var pendingFocus: Int? = null

    private val viewModel: GridViewModel by viewModels {
        val graph = AppGraph.of(this)
        LambdaViewModelFactory {
            GridViewModel(graph.account, graph.settingsStore) { vm ->
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
        topBar = TopBarController(binding.topBar, AppGraph.of(this).topBarAutoHideMillis)
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
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) topBar.onUserActivity()
        return super.dispatchGenericMotionEvent(ev)
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
                syncTiles(state.cameras)
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

    private fun syncTiles(cameras: List<Camera>) {
        val grid = binding.grid
        while (grid.childCount > cameras.size) grid.removeViewAt(grid.childCount - 1)
        while (grid.childCount < cameras.size) {
            val tile = CameraTileView(this)
            val index = grid.childCount
            tile.setOnClickListener { if (tile.showsRetry) viewModel.retry(index) else openFullscreen(index) }
            tile.setOnFocusChangeListener { _, focused -> if (focused) viewModel.focusedIndex = index }
            grid.addView(tile)
        }
        cameras.forEachIndexed { i, camera -> (grid.getChildAt(i) as CameraTileView).cameraName = camera.name }
        restoreFocus()
    }

    private fun bindPlayers(players: List<TilePlayer>) {
        tileJobs.forEach { it.cancel() }
        tileJobs.clear()
        players.forEachIndexed { i, player ->
            val tile = binding.grid.getChildAt(i) as? CameraTileView ?: return@forEachIndexed
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
        if (!binding.topBar.hasFocus()) grid.getChildAt(wanted).requestFocus()
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
}
