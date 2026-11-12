package com.itsluminous.hikviewer.fakes

import com.itsluminous.hikviewer.core.auth.LoginError
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.auth.Session
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.Device
import com.itsluminous.hikviewer.core.playback.PlaybackWindow
import com.itsluminous.hikviewer.core.playback.RecordSegment
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.StreamException
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.data.CloudAccount
import com.itsluminous.hikviewer.data.CloudServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

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

    /** Device-list calls across all accounts. */
    var deviceListings = 0

    /** The recorder's `encryptPwd`; change it to simulate the code being changed on the recorder. */
    var encryptPwd: String? = if (encrypted) GOOD_CODE_DOUBLE_MD5 else null

    /** Footage per recorder day; the default is two continuous segments with a lunch gap. */
    var recordings: (LocalDate) -> List<RecordSegment> = ::defaultRecordings

    /** Exception the next record search fails with. */
    var nextSearchError: java.io.IOException? = null

    /** Whether playback flows run at the synthetic frame period (false = as fast as collected). */
    var playbackPaced: Boolean = true

    /**
     * When set, a playback flow stays open after its frames instead of ending (what a window looks
     * like while the relay is still streaming it); tests of the transport logic use this with
     * [playbackPaced] off so a window neither takes real time nor rolls into the next one.
     */
    var playbackHold: Boolean = false

    /** Frames per playback window; null derives them from the window length at 12 fps. */
    var playbackFramesPerWindow: Int? = null

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

        /** Exception the next stream fails with, whichever camera opens it. */
        var nextStreamError: StreamException? = null

        /**
         * Exception the next stream of one camera (by [Camera.key]) fails with. Unlike
         * [nextStreamError] it cannot be taken by another tile's stream that happens to reopen
         * first on a worker thread.
         */
        val nextStreamErrorFor = java.util.concurrent.ConcurrentHashMap<String, StreamException>()

        /** Playback windows opened, in order. */
        val playbackOpened = java.util.concurrent.CopyOnWriteArrayList<Pair<Camera, PlaybackWindow>>()

        /** Record searches made, in order. */
        val searched = java.util.concurrent.CopyOnWriteArrayList<Pair<Camera, LocalDate>>()

        /**
         * Live and playback flows being collected right now. A real stream owns a relay socket for
         * as long as it is collected, so this is the test's "open sockets" count.
         */
        val activeStreams = AtomicInteger()

        override suspend fun listDevices(): List<Device> {
            deviceListings++
            return listOf(
                Device(
                    serial = SERIAL,
                    name = "Test DVR",
                    model = "DS-7A16HQHI-K1",
                    isOnline = true,
                    isEncrypted = encrypted,
                    encryptPwd = encryptPwd,
                    channelCount = cameraCount,
                ),
            )
        }

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
            nextStreamErrorFor.remove(camera.key)?.let { e -> return flow { throw e } }
            nextStreamError?.let { e ->
                nextStreamError = null
                return flow { throw e }
            }
            return streams(camera, quality, onStatus).tracked()
        }

        override suspend fun searchRecordings(
            camera: Camera,
            day: LocalDate,
        ): List<RecordSegment> {
            searched += camera to day
            nextSearchError?.let {
                nextSearchError = null
                throw it
            }
            return recordings(day)
        }

        /**
         * The synthetic clip for the window's length (12 fps), presentation times from zero, then
         * `Ended` and completion, the shape `PlaybackStream` produces.
         */
        override fun openPlayback(
            camera: Camera,
            window: PlaybackWindow,
            verificationCode: String?,
            onStatus: (StreamStatus) -> Unit,
        ): Flow<AccessUnit> {
            playbackOpened += camera to window
            val frames =
                playbackFramesPerWindow
                    ?: (window.duration.seconds * FRAMES_PER_SECOND).toInt().coerceIn(1, MAX_PLAYBACK_FRAMES)
            return flow {
                SyntheticStream.flow(onStatus, paced = playbackPaced).take(frames).collect { emit(it) }
                if (playbackHold) kotlinx.coroutines.awaitCancellation()
                onStatus(StreamStatus.Ended)
            }.tracked()
        }

        private fun Flow<AccessUnit>.tracked(): Flow<AccessUnit> =
            onStart { activeStreams.incrementAndGet() }.onCompletion { activeStreams.decrementAndGet() }

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

        private const val FRAMES_PER_SECOND = 12
        private const val MAX_PLAYBACK_FRAMES = 12 * 60 * 10

        /** 06:00–12:30 and 14:00–24:00, continuous. */
        fun defaultRecordings(day: LocalDate): List<RecordSegment> = listOf(
            RecordSegment(day.atTime(6, 0), day.atTime(12, 30), RecordSegment.TYPE_TIMING),
            RecordSegment(day.atTime(14, 0), day.plusDays(1).atStartOfDay(), RecordSegment.TYPE_TIMING),
        )
    }
}
