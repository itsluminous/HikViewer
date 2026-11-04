package com.itsluminous.hikviewer.data

import com.itsluminous.hikviewer.core.auth.FeatureCodeStore
import com.itsluminous.hikviewer.core.auth.HikConnectClient
import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.auth.Session
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.Device
import com.itsluminous.hikviewer.core.devices.DeviceRepository
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.LiveStream
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.core.stream.VtduTokenProvider
import kotlinx.coroutines.flow.Flow

/**
 * The slice of the Hik-Connect cloud the app uses, behind an interface so UI tests can swap in a
 * fake that never opens a socket. [HikConnectCloudServices] is the production implementation.
 */
interface CloudServices {
    /** Logs in exactly as `:core` does: the account string is sent as typed. */
    suspend fun login(
        account: String,
        password: String,
        region: Region,
    ): Session

    /** Binds [session] to a [CloudAccount]; [onSessionChanged] fires after every token refresh. */
    fun connect(
        session: Session,
        onSessionChanged: (Session) -> Unit,
    ): CloudAccount
}

/** Everything that needs a logged-in session. */
interface CloudAccount {
    /** Devices in the account. */
    suspend fun listDevices(): List<Device>

    /** Channels of [device]. */
    suspend fun listCameras(device: Device): List<Camera>

    /** Cold flow of decrypted pictures; see [LiveStream.open]. */
    fun openStream(
        camera: Camera,
        quality: StreamQuality,
        verificationCode: String?,
        onStatus: (StreamStatus) -> Unit,
    ): Flow<AccessUnit>

    /** Server-side logout; failures are ignored. */
    suspend fun logout()
}

/** Production [CloudServices] over `:core`. */
class HikConnectCloudServices(
    featureCodes: FeatureCodeStore,
) : CloudServices {
    private val client = HikConnectClient(featureCodes)

    override suspend fun login(
        account: String,
        password: String,
        region: Region,
    ): Session = client.login(account, password, region)

    override fun connect(
        session: Session,
        onSessionChanged: (Session) -> Unit,
    ): CloudAccount = HikConnectAccount(HikConnectSession(client, session, onSessionChanged))
}

private class HikConnectAccount(
    private val session: HikConnectSession,
) : CloudAccount {
    private val devices = DeviceRepository(session)
    private val tokens = VtduTokenProvider(session)

    // The VTM page list is fetched once per stream open; LiveStream calls the lambda lazily so a
    // stale relay assignment is picked up on the next reconnect without a restart.
    private val live = LiveStream(pageList = { devices.vtmPageList() }, tokens = tokens)

    override suspend fun listDevices(): List<Device> = devices.listDevices()

    override suspend fun listCameras(device: Device): List<Camera> = devices.listCameras(device)

    override fun openStream(
        camera: Camera,
        quality: StreamQuality,
        verificationCode: String?,
        onStatus: (StreamStatus) -> Unit,
    ): Flow<AccessUnit> = live.open(camera, quality, verificationCode, onStatus)

    override suspend fun logout() = session.logout()
}
