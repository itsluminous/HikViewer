package com.itsluminous.hikviewer.fakes

import com.itsluminous.hikviewer.core.auth.LoginError
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.auth.Session
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.Device
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.StreamException
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.data.CloudAccount
import com.itsluminous.hikviewer.data.CloudServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * In-memory Hik-Connect: one encrypted 16-channel recorder, [cameraCount] channels of which the
 * first [signalCount] have a signal. Login succeeds for [goodPassword]; the verification code is
 * [GOOD_CODE] (its `MD5(MD5())` is baked into the device). Streams come from [SyntheticStream].
 */
class FakeCloudServices(
    val cameraCount: Int = 16,
    val signalCount: Int = 12,
    val encrypted: Boolean = true,
    private val goodPassword: String = "secret",
    private val streams: (Camera, StreamQuality, (StreamStatus) -> Unit) -> Flow<AccessUnit> = { _, _, onStatus ->
        SyntheticStream.flow(onStatus)
    },
) : CloudServices {
    /** Every login call, for assertions. */
    val logins = mutableListOf<Triple<String, String, Region>>()

    /** Set to make the next login fail. */
    var nextLoginError: LoginError? = null

    /** Number of server logouts. */
    var logouts = 0

    /** The accounts handed out. */
    val accounts = mutableListOf<FakeAccount>()

    override suspend fun login(
        account: String,
        password: String,
        region: Region,
    ): Session {
        logins += Triple(account, password, region)
        nextLoginError?.let {
            nextLoginError = null
            throw it
        }
        if (password != goodPassword) throw LoginError.InvalidCredentials()
        return Session("sid-$account", "rid-$account", region.apiHost, "uid", 244)
    }

    override fun connect(
        session: Session,
        onSessionChanged: (Session) -> Unit,
    ): CloudAccount = FakeAccount(session, onSessionChanged).also(accounts::add)

    /** The bound account. */
    inner class FakeAccount(
        val session: Session,
        val onSessionChanged: (Session) -> Unit,
    ) : CloudAccount {
        /** Streams opened, by camera key. */
        val opened = java.util.concurrent.CopyOnWriteArrayList<Pair<Camera, StreamQuality>>()

        /** Exception the next stream fails with. */
        var nextStreamError: StreamException? = null

        override suspend fun listDevices(): List<Device> = listOf(
            Device(
                serial = SERIAL,
                name = "Test DVR",
                model = "DS-7A16HQHI-K1",
                isOnline = true,
                isEncrypted = encrypted,
                encryptPwd = if (encrypted) GOOD_CODE_DOUBLE_MD5 else null,
                channelCount = cameraCount,
            ),
        )

        override suspend fun listCameras(device: Device): List<Camera> = (1..cameraCount).map { n ->
            Camera(SERIAL, n, "Camera $n", hasSignal = n <= signalCount, isOnline = true)
        }

        override fun openStream(
            camera: Camera,
            quality: StreamQuality,
            verificationCode: String?,
            onStatus: (StreamStatus) -> Unit,
        ): Flow<AccessUnit> {
            opened += camera to quality
            nextStreamError?.let { e ->
                nextStreamError = null
                return flow { throw e }
            }
            return streams(camera, quality, onStatus)
        }

        override suspend fun logout() {
            logouts++
        }
    }

    companion object {
        const val SERIAL = "FAKE0000001"

        /** The verification code the fake recorder accepts. */
        const val GOOD_CODE = "ABCDEF"

        /** `MD5(MD5("ABCDEF"))` as the cloud would report it. */
        const val GOOD_CODE_DOUBLE_MD5 = "aa37ad52a5a65df39791a95818fb3298"
    }
}
