package com.itsluminous.hikviewer.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.VerificationCode
import com.itsluminous.hikviewer.data.AccountRepository
import com.itsluminous.hikviewer.data.CameraInventory
import com.itsluminous.hikviewer.data.GridArrangementStore
import com.itsluminous.hikviewer.data.SettingsStore
import com.itsluminous.hikviewer.player.PlaybackCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** The grid screen's state. */
sealed class GridUiState {
    /** Fetching devices and cameras. */
    object Loading : GridUiState()

    /** Cameras known. */
    data class Ready(
        /** Tiles in display order. */
        val cameras: List<Camera>,
        /** Chosen layout (AUTO resolves in the view). */
        val layout: GridLayoutMode,
        /** Hero tile index (always position 0; the 1 + N layouts span it), null when there are no tiles. */
        val heroIndex: Int?,
        /** True when a verification code must be asked for before streams can start. */
        val needsCode: Boolean,
        /** True when the last code the user typed was wrong. */
        val codeRejected: Boolean = false,
        /** Settings → Show camera names. */
        val showNames: Boolean = false,
        /** Settings → Show frame rate. */
        val showFrameRate: Boolean = false,
    ) : GridUiState()

    /** The cloud could not be read. */
    data class Error(
        val message: String,
    ) : GridUiState()
}

/**
 * Owns the camera list, the chosen layout and its tile arrangement, the verification-code gate and
 * the [PlaybackCoordinator].
 * Playback follows the Activity's started state through [onStart]/[onStop] so every decoder and
 * socket is released when the screen is not visible, as the brief requires.
 *
 * The verification code is asked for exactly once: a stored code that still hashes to the
 * recorder's `encryptPwd` is never questioned. A stream that cannot be decrypted with it is a
 * broken stream (shown as a dimmed tile with Retry), not a wrong code, unless the recorder's hash
 * has meanwhile changed, which [onStreamRejectedCode] checks by re-reading the device list.
 */
class GridViewModel(
    private val account: AccountRepository,
    private val settings: SettingsStore,
    private val arrangement: GridArrangementStore,
    /** Created lazily by the Activity's graph; a factory so tests can inject fakes. */
    coordinatorFactory: (GridViewModel) -> PlaybackCoordinator,
) : ViewModel() {
    private val stateFlow = MutableStateFlow<GridUiState>(GridUiState.Loading)
    private var inventory: CameraInventory? = null
    private var visible = false
    private var codeRecheck: Job? = null

    /** Screen state. */
    val state: StateFlow<GridUiState> = stateFlow.asStateFlow()

    /** Tile players and the live budget. */
    val playback: PlaybackCoordinator = coordinatorFactory(this)

    /**
     * Index of the tile that had focus, restored after fullscreen or a settings round-trip. Focus
     * is a view concern only: it is not passed to playback, so moving it never touches a decoder.
     */
    var focusedIndex: Int = 0

    /**
     * True while the screen is a phone held upright. Picks which of the store's two last layouts
     * is read and written (a phone keeps one per orientation; a TV is never upright and always
     * uses the landscape one) and how AUTO resolves, and so how many streams are opened; set by
     * the Activity before [load].
     */
    var portrait: Boolean = false

    /** Loads (or reloads) cameras from the cloud and applies the settings. */
    fun load(refresh: Boolean = false) {
        viewModelScope.launch {
            if (inventory == null || refresh) stateFlow.value = GridUiState.Loading
            try {
                inventory = account.loadInventory(refresh)
                applySettings()
            } catch (e: IOException) {
                stateFlow.value = GridUiState.Error(e.message ?: "Network error")
            } catch (e: IllegalStateException) {
                stateFlow.value = GridUiState.Error(e.message ?: "Not logged in")
            }
        }
    }

    /**
     * Re-reads settings (this orientation's last layout, visible cameras, that layout's
     * arrangement) and reconfigures the tiles.
     */
    fun applySettings() {
        val inv = inventory ?: return
        val layout = arrangement.layout(portrait)
        val cameras = CameraOrder.arranged(inv.cameras, settings, arrangement, portrait)
        val hero = if (cameras.isEmpty()) null else 0
        val needsCode = inv.needsVerificationCode && !codeAccepted(inv)
        stateFlow.value =
            GridUiState.Ready(
                cameras,
                layout,
                hero,
                needsCode,
                showNames = settings.showCameraNames,
                showFrameRate = settings.showFrameRate,
            )
        // Only the tiles the layout has room for get a stream: a 1 + 5 layout of a 16-camera account
        // used to open 16 relay connections and run 16 decrypt pipelines for 6 pictures on screen.
        val shown = cameras.take(GridGeometry.shape(layout, cameras.size, portrait).capacity)
        // Always Automatic: the device-derived ceiling with probing above it. An explicit count used
        // to be a setting (0.1) and let a TV be slowed down; the API stays, the UI no longer offers it.
        playback.setLiveCeiling(null)
        playback.setCameras(shown)
        playback.heroIndex = if (layout.isHero) hero else null
        if (visible && !needsCode) playback.start()
    }

    /** Switches this orientation's layout and persists it; the layout's own arrangement comes back with it. */
    fun setLayout(mode: GridLayoutMode) {
        arrangement.setLayout(portrait, mode)
        applySettings()
    }

    /**
     * Exchanges the tiles at [from] and [to] and persists the result for the current layout (in
     * a 1 + N layout, moving a tile to position 0 makes it the hero). Players follow their
     * cameras, so nothing reconnects. Returns the arrangement as it was, for a Back-cancel.
     */
    fun moveTile(
        from: Int,
        to: Int,
    ): List<String>? {
        val s = stateFlow.value as? GridUiState.Ready ?: return null
        val keys = s.cameras.map { it.key }
        val next = GridArrangement.swapped(keys, from, to)
        if (next == keys) return keys
        arrangement.setOrder(s.layout, next)
        focusedIndex = to
        applySettings()
        return keys
    }

    /** Puts [keys] back as the current layout's arrangement (Back during a move). */
    fun restoreArrangement(keys: List<String>) {
        val s = stateFlow.value as? GridUiState.Ready ?: return
        arrangement.setOrder(s.layout, keys)
        applySettings()
    }

    /**
     * The tile menu's Change: shows the camera with [cameraKey] at grid [position] and persists
     * the current layout's arrangement. A camera already on the grid trades places with this
     * tile's camera; one past the layout's capacity (visible in Settings but not on screen) takes
     * the slot and sends this tile's camera off screen. Position 0 of a 1 + N layout is the hero,
     * so this also picks the hero. Focus stays on the tile.
     */
    fun changeTile(
        position: Int,
        cameraKey: String,
    ) {
        val s = stateFlow.value as? GridUiState.Ready ?: return
        val keys = s.cameras.map { it.key }
        val next = GridArrangement.replaced(keys, position, cameraKey)
        if (next == keys) return
        arrangement.setOrder(s.layout, next)
        focusedIndex = position
        applySettings()
    }

    /**
     * Checks [code] against the recorder's `encryptPwd`; stores it and starts playback when it
     * matches, otherwise flags the rejection for the dialog.
     */
    fun submitVerificationCode(code: String): Boolean {
        val ok = VerificationCode.matches(code.trim(), inventory?.encryptPwd)
        if (ok) {
            settings.verificationCode = code.trim()
            applySettings()
        } else {
            stateFlow.update { s -> if (s is GridUiState.Ready) s.copy(codeRejected = true) else s }
        }
        return ok
    }

    /**
     * A stream's decryptor gave up on the stored code. Re-reads the recorder's `encryptPwd`; only
     * when the stored code no longer matches it is the code dropped and asked for again. One
     * re-check runs at a time, however many tiles report it.
     */
    fun onStreamRejectedCode() {
        if (settings.verificationCode == null || codeRecheck?.isActive == true) return
        codeRecheck =
            viewModelScope.launch {
                val inv =
                    try {
                        account.loadInventory(refresh = true)
                    } catch (_: IOException) {
                        return@launch // cannot tell; keep the code, the tile keeps its Retry
                    } catch (_: IllegalStateException) {
                        return@launch
                    }
                inventory = inv
                if (codeAccepted(inv)) return@launch
                settings.verificationCode = null
                playback.stop()
                applySettings()
            }
    }

    /**
     * A tile is lifted (Move): only the hardware tiles keep decoding live until [onMoveEnded], so
     * the D-pad is answered before the pictures are. Both drop and cancel end a move; so does
     * leaving the screen ([onStop]), since the budget must never stay capped behind the user's back.
     */
    fun onMoveStarted() {
        playback.hardwareOnly = true
    }

    /** The lifted tile was dropped or the move cancelled: the normal live budget is back. */
    fun onMoveEnded() {
        playback.hardwareOnly = false
    }

    /** The Retry overlay of tile [index]. */
    fun retry(index: Int) {
        playback.players.value
            .getOrNull(index)
            ?.let(playback::retry)
    }

    /** True when a stored code exists and still matches the recorder (or the recorder reports no hash). */
    private fun codeAccepted(inv: CameraInventory): Boolean {
        val stored = settings.verificationCode ?: return false
        val hash = inv.encryptPwd ?: return true
        return VerificationCode.matches(stored, hash)
    }

    /** Streams may run. */
    fun onStart() {
        visible = true
        val s = stateFlow.value
        if (s is GridUiState.Ready && !s.needsCode) playback.start()
    }

    /** Streams and decoders are released; `stop()` also drops a move's cap on the budget. */
    fun onStop() {
        visible = false
        playback.stop()
    }

    override fun onCleared() {
        playback.release()
    }
}
