package com.itsluminous.hikviewer.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.hikviewer.core.auth.HikConnectClient
import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.DeviceRepository
import com.itsluminous.hikviewer.core.devices.VerificationCode
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
