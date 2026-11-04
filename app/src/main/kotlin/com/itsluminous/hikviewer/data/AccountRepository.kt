package com.itsluminous.hikviewer.data

import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.Device
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Devices and their channels, as last fetched from the cloud. */
data class CameraInventory(
    /** Devices in cloud order. */
    val devices: List<Device>,
    /** Every channel of every device, device order then channel order. */
    val cameras: List<Camera>,
) {
    /** Lookup by [Camera.key]. */
    fun camera(key: String): Camera? = cameras.firstOrNull { it.key == key }

    /** The device a camera belongs to. */
    fun deviceOf(camera: Camera): Device? = devices.firstOrNull { it.serial == camera.deviceSerial }

    /** True when any device encrypts its streams, so a verification code is needed. */
    val needsVerificationCode: Boolean get() = devices.any { it.isEncrypted }

    /** `encryptPwd` of the first encrypted device: what a typed code is checked against. */
    val encryptPwd: String? get() = devices.firstOrNull { it.isEncrypted }?.encryptPwd
}

/**
 * The one place that knows whether somebody is logged in.
 *
 * Owns the [CloudAccount] bound to the stored session, persists token refreshes through
 * [SessionStore], caches the camera inventory for the process lifetime (the grid, fullscreen and
 * settings screens all need it and the cloud takes ~1 s per call), and wipes both stores on logout.
 */
class AccountRepository(
    private val cloud: CloudServices,
    private val sessionStore: SessionStore,
    private val settingsStore: SettingsStore,
) {
    private val mutex = Mutex()
    private var account: CloudAccount? = sessionStore.session()?.let(::bind)
    private val inventoryState = MutableStateFlow<CameraInventory?>(null)

    /** The cached inventory; null until [loadInventory] succeeded once. */
    val inventory: StateFlow<CameraInventory?> = inventoryState.asStateFlow()

    /** True when a session is stored. It may still be expired; the first call finds out. */
    val isLoggedIn: Boolean get() = account != null

    /** Logs in and stores the session. Throws [com.itsluminous.hikviewer.core.auth.LoginError]. */
    suspend fun login(
        username: String,
        password: String,
        region: Region,
    ) {
        val session = cloud.login(username, password, region)
        sessionStore.save(session)
        account = bind(session)
        inventoryState.value = null
    }

    /** Fetches devices and cameras, or returns the cache unless [refresh] is set. */
    suspend fun loadInventory(refresh: Boolean = false): CameraInventory = mutex.withLock {
        inventoryState.value?.takeIf { !refresh }?.let { return it }
        val acct = requireAccount()
        val devices = acct.listDevices()
        val cameras = devices.flatMap { acct.listCameras(it) }
        CameraInventory(devices, cameras).also { inventoryState.value = it }
    }

    /** Opens a live stream through the bound account. */
    fun openStream(
        camera: Camera,
        quality: StreamQuality,
        onStatus: (StreamStatus) -> Unit = {},
    ): Flow<AccessUnit> = requireAccount().openStream(camera, quality, settingsStore.verificationCode, onStatus)

    /** Server logout (best effort), then forgets session and settings. */
    suspend fun logout() {
        runCatching { account?.logout() }
        account = null
        inventoryState.value = null
        sessionStore.clearSession()
        settingsStore.clear()
    }

    private fun bind(session: com.itsluminous.hikviewer.core.auth.Session): CloudAccount =
        cloud.connect(session) { refreshed -> sessionStore.save(refreshed) }

    private fun requireAccount(): CloudAccount = checkNotNull(account) { "not logged in" }
}
