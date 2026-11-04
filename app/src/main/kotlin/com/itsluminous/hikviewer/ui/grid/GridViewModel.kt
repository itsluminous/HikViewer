package com.itsluminous.hikviewer.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.VerificationCode
import com.itsluminous.hikviewer.data.AccountRepository
import com.itsluminous.hikviewer.data.CameraInventory
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
        /** Hero tile index, null when there are no tiles. */
        val heroIndex: Int?,
        /** True when a verification code must be asked for before streams can start. */
        val needsCode: Boolean,
        /** True when the last code the user typed was wrong. */
        val codeRejected: Boolean = false,
    ) : GridUiState()

    /** The cloud could not be read. */
    data class Error(
        val message: String,
    ) : GridUiState()
}

/**
 * Owns the camera list, the chosen layout, the verification-code gate and the [PlaybackCoordinator].
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

    /** Index of the tile that had focus, restored after fullscreen or a settings round-trip. */
    var focusedIndex: Int = 0
        set(value) {
            field = value
            playback.focusedIndex = value
        }

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

    /** Re-reads settings (layout, visible cameras, hero) and reconfigures the tiles. */
    fun applySettings() {
        val inv = inventory ?: return
        val cameras = VisibleCameras.resolve(inv.cameras, settings.visibleCameraKeys)
        val hero = VisibleCameras.heroIndex(cameras, settings.heroCameraKey)
        val needsCode = inv.needsVerificationCode && !codeAccepted(inv)
        stateFlow.value = GridUiState.Ready(cameras, settings.gridLayout, hero, needsCode)
        playback.setCameras(cameras)
        playback.heroIndex = if (settings.gridLayout == GridLayoutMode.HERO) hero else null
        playback.focusedIndex = focusedIndex.coerceIn(0, (cameras.size - 1).coerceAtLeast(0))
        if (visible && !needsCode) playback.start()
    }

    /** Switches the layout and persists it. */
    fun setLayout(mode: GridLayoutMode) {
        settings.gridLayout = mode
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

    /** Streams and decoders are released. */
    fun onStop() {
        visible = false
        playback.stop()
    }

    override fun onCleared() {
        playback.release()
    }
}
