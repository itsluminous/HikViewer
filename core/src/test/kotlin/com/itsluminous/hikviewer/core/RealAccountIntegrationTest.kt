package com.itsluminous.hikviewer.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.hikviewer.core.auth.HikConnectClient
import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.DeviceRepository
import com.itsluminous.hikviewer.core.devices.VerificationCode
import com.itsluminous.hikviewer.core.playback.PlaybackStream
import com.itsluminous.hikviewer.core.playback.PlaybackWindow
import com.itsluminous.hikviewer.core.playback.RecordingSearch
import com.itsluminous.hikviewer.core.stream.LiveStream
import com.itsluminous.hikviewer.core.stream.LiveStreamConfig
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.VideoCodec
import com.itsluminous.hikviewer.core.stream.VtduTokenProvider
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.LocalDate

/**
 * End-to-end check against the real Hik-Connect account. Runs only when `HIKVIEWER_LOCAL_ENV` points
 * at the gitignored `local.env` (`{"mobile","password","encryption"}`), so CI never executes it.
 *
 * Prints counts and channel numbers only. Never prints serials, ids, tokens or any credential.
 */
@EnabledIfEnvironmentVariable(named = "HIKVIEWER_LOCAL_ENV", matches = ".+")
class RealAccountIntegrationTest {
    private class Credentials(
        val account: String,
        val password: String,
        val verificationCode: String,
    )

    private fun credentials(): Credentials {
        val env = Json.parseToJsonElement(File(System.getenv("HIKVIEWER_LOCAL_ENV")).readText()).jsonObject
        return Credentials(
            account = env.getValue("mobile").jsonPrimitive.content,
            password = env.getValue("password").jsonPrimitive.content,
            verificationCode = env.getValue("encryption").jsonPrimitive.content,
        )
    }

    @Test
    fun `login, list cameras and decrypt ten keyframes of the first sub-stream`() = runBlocking {
        val creds = credentials()
        val client = HikConnectClient({ FEATURE_CODE })
        val login = client.login(creds.account, creds.password, Region.INDIA)
        println("login ok: apiDomain=${login.apiDomain} areaId=${login.areaId}")
        val session = HikConnectSession(client, login)
        try {
            val repository = DeviceRepository(session)
            val withSignal = listCamerasWithSignal(repository, creds.verificationCode)
            val pageList = repository.vtmPageList()
            println("vtm resources: ${pageList.resources.size}")

            val live = LiveStream({ pageList }, VtduTokenProvider(session), LiveStreamConfig(maxAttempts = 2))
            val result = collectKeyframes(live, withSignal.first(), creds.verificationCode)

            println(result)
            assertThat(result.keyframes).isAtLeast(10)
            assertThat(result.codec).isEqualTo(VideoCodec.H264)
            assertThat(result.spsSeen).isTrue()
            assertThat(result.statuses).contains("Streaming")
        } finally {
            session.logout()
        }
    }

    /**
     * Playback over the same relay: at least one recorded segment for yesterday (recorder date,
     * assumed within a day of the local date) and at least five decrypted keyframes from a 60 s
     * window inside it. Prints counts only.
     */
    @Test
    fun `list yesterday's recordings and decrypt five keyframes of a 60 s playback window`() = runBlocking {
        val creds = credentials()
        val client = HikConnectClient({ FEATURE_CODE })
        val login = client.login(creds.account, creds.password, Region.INDIA)
        val session = HikConnectSession(client, login)
        try {
            val repository = DeviceRepository(session)
            val camera = listCamerasWithSignal(repository, creds.verificationCode).first()
            val yesterday = LocalDate.now().minusDays(1)
            val segments = RecordingSearch(session).segmentsOn(camera, yesterday)
            val types = segments.map { it.type }.toSet()
            println("segments for ch${camera.channelNo} on D-1: ${segments.size}, types=$types")
            assertThat(segments).isNotEmpty()

            val longest = segments.maxBy { it.duration }
            val begin = longest.begin.plusSeconds(minOf(longest.duration.seconds / 2, 3_600))
            val window = PlaybackWindow(begin, minOf(begin.plusSeconds(60), longest.end))
            val pageList = repository.vtmPageList()
            val playback = PlaybackStream({ pageList }, VtduTokenProvider(session))
            val result = collectPlaybackKeyframes(playback, camera, window, creds.verificationCode)

            println(result)
            assertThat(result.keyframes).isAtLeast(5)
            assertThat(result.codec).isEqualTo(VideoCodec.H265)
            assertThat(result.spsSeen).isTrue()
            assertThat(result.statuses).contains("Streaming")
        } finally {
            session.logout()
        }
    }

    private suspend fun collectPlaybackKeyframes(
        playback: PlaybackStream,
        camera: Camera,
        window: PlaybackWindow,
        code: String,
    ): StreamResult {
        val statuses = mutableListOf<String>()
        var keyframes = 0
        var pictures = 0
        var codec: VideoCodec? = null
        var spsSeen = false
        val startedAt = System.nanoTime()
        withTimeout(40_000) {
            playback
                .open(camera, window, code) { statuses += it::class.simpleName.orEmpty() }
                .takeWhile { keyframes < 5 }
                .collect { unit ->
                    pictures++
                    codec = unit.codec
                    if (unit.isKeyframe) {
                        keyframes++
                        if (unit.config != null) spsSeen = true
                    }
                    if (pictures == 1) {
                        println("first playback picture after ${(System.nanoTime() - startedAt) / 1_000_000} ms: $unit")
                    }
                }
        }
        return StreamResult(pictures, keyframes, codec, spsSeen, statuses)
    }

    private suspend fun listCamerasWithSignal(
        repository: DeviceRepository,
        verificationCode: String,
    ): List<Camera> {
        val devices = repository.listDevices()
        println("devices: ${devices.size}, encrypted: ${devices.count { it.isEncrypted }}")
        assertThat(devices).isNotEmpty()
        devices.filter { it.isEncrypted }.forEach {
            assertWithMessage("verification code matches encryptPwd")
                .that(VerificationCode.matches(verificationCode, it.encryptPwd))
                .isTrue()
        }
        val cameras = devices.flatMap { repository.listCameras(it) }
        val withSignal = cameras.filter { it.hasSignal }
        println(
            "cameras: ${cameras.size}, with signal: ${withSignal.size}, channels=${withSignal.map { it.channelNo }}",
        )
        assertThat(withSignal).isNotEmpty()
        return withSignal
    }

    private data class StreamResult(
        val pictures: Int,
        val keyframes: Int,
        val codec: VideoCodec?,
        val spsSeen: Boolean,
        val statuses: List<String>,
    )

    private suspend fun collectKeyframes(
        live: LiveStream,
        camera: Camera,
        code: String,
    ): StreamResult {
        val statuses = mutableListOf<String>()
        var keyframes = 0
        var pictures = 0
        var codec: VideoCodec? = null
        var spsSeen = false
        val startedAt = System.nanoTime()
        withTimeout(20_000) {
            live
                .open(camera, StreamQuality.SUB, code) { statuses += it::class.simpleName.orEmpty() }
                .takeWhile { keyframes < 10 }
                .collect { unit ->
                    pictures++
                    codec = unit.codec
                    if (unit.isKeyframe) {
                        keyframes++
                        if (unit.config != null) spsSeen = true
                    }
                    if (pictures ==
                        1
                    ) {
                        println("first picture after ${(System.nanoTime() - startedAt) / 1_000_000} ms: $unit")
                    }
                }
        }
        return StreamResult(pictures, keyframes, codec, spsSeen, statuses)
    }

    private companion object {
        /** A fixed test terminal id so repeated runs look like one device to the cloud. */
        const val FEATURE_CODE = "6b1f0e3c9a7d4e5f8c2b1a0d9e8f7c6b"
    }
}
