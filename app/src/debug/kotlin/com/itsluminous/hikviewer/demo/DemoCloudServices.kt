package com.itsluminous.hikviewer.demo

import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.auth.Session
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.Device
import com.itsluminous.hikviewer.core.playback.PlaybackWindow
import com.itsluminous.hikviewer.core.playback.RecordSegment
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.data.CloudAccount
import com.itsluminous.hikviewer.data.CloudServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * A stand-in for the Hik-Connect cloud used by [DemoActivity]: one unencrypted "Demo DVR" with
 * [cameraCount] channels named like a house's cameras, every one with a signal, streaming
 * [DemoClips]. Logging in accepts any account and password; nothing leaves the device.
 *
 * Recordings cover the whole of every day except a mid-day gap, so the playback time bar has
 * something to draw and a gap to skip over; today's footage ends now, as a recorder's would, so
 * playback opens on "what just happened" like it does with a real account.
 */
class DemoCloudServices(
    private val clips: DemoClips,
    private val cameraCount: Int = DEFAULT_CAMERA_COUNT,
) : CloudServices {
    override suspend fun login(
        account: String,
        password: String,
        region: Region,
    ): Session = DEMO_SESSION

    override fun connect(
        session: Session,
        onSessionChanged: (Session) -> Unit,
    ): CloudAccount = DemoAccount()

    private inner class DemoAccount : CloudAccount {
        override suspend fun listDevices(): List<Device> = listOf(
            Device(
                serial = SERIAL,
                name = "Demo DVR",
                model = "DS-DEMO",
                isOnline = true,
                isEncrypted = false,
                encryptPwd = null,
                channelCount = cameraCount,
            ),
        )

        override suspend fun listCameras(device: Device): List<Camera> = (1..cameraCount).map { n ->
            Camera(SERIAL, n, cameraName(n), hasSignal = true, isOnline = true)
        }

        override fun openStream(
            camera: Camera,
            quality: StreamQuality,
            verificationCode: String?,
            onStatus: (StreamStatus) -> Unit,
        ): Flow<AccessUnit> = clips.stream(camera.name, camera.channelNo, onStatus)

        override suspend fun searchRecordings(
            camera: Camera,
            day: LocalDate,
        ): List<RecordSegment> {
            val now = LocalDateTime.now()
            val dayEnd = day.plusDays(1).atStartOfDay()
            val end = if (day == now.toLocalDate()) now.truncatedTo(ChronoUnit.MINUTES) else dayEnd
            return listOf(
                RecordSegment(day.atStartOfDay(), day.atTime(GAP_BEGIN_HOUR, 0), RecordSegment.TYPE_TIMING),
                RecordSegment(day.atTime(GAP_END_HOUR, 0), dayEnd, RecordSegment.TYPE_TIMING),
            ).filter { it.begin.isBefore(end) }.map { if (it.end.isAfter(end)) it.copy(end = end) else it }
        }

        override fun openPlayback(
            camera: Camera,
            window: PlaybackWindow,
            verificationCode: String?,
            onStatus: (StreamStatus) -> Unit,
        ): Flow<AccessUnit> = flow {
            val frames = (window.duration.seconds * FRAMES_PER_SECOND).toInt().coerceAtLeast(1)
            clips.stream(camera.name, camera.channelNo, onStatus).take(frames).collect { emit(it) }
            onStatus(StreamStatus.Ended)
        }

        override suspend fun logout() = Unit
    }

    /** Names and ids. */
    companion object {
        /** Channels the demo recorder has unless the caller asks for another count. */
        const val DEFAULT_CAMERA_COUNT: Int = 12

        /** The demo recorder's serial; the camera keys are `DEMO0000001/<channel>`. */
        const val SERIAL: String = "DEMO0000001"

        /** What any demo login returns and what [DemoActivity] stores to skip the login screen. */
        val DEMO_SESSION: Session = Session("demo-session", "demo-refresh", Region.DEFAULT.apiHost, "demo", null)

        private const val FRAMES_PER_SECOND = 12
        private const val GAP_BEGIN_HOUR = 12
        private const val GAP_END_HOUR = 14

        private val NAMES =
            listOf(
                "Front Door",
                "Driveway",
                "Garage",
                "Back Garden",
                "Porch",
                "Living Room",
                "Kitchen",
                "Hallway",
                "Side Gate",
                "Patio",
                "Office",
                "Nursery",
                "Workshop",
                "Pool",
                "Shed",
                "Balcony",
            )

        /** Placeholder name of channel [channelNo]; past the list it falls back to "Camera N". */
        fun cameraName(channelNo: Int): String = NAMES.getOrNull(channelNo - 1) ?: "Camera $channelNo"
    }
}
