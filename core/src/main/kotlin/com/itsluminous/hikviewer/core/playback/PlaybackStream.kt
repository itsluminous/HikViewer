package com.itsluminous.hikviewer.core.playback

import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.VtmPageList
import com.itsluminous.hikviewer.core.devices.VtmRelay
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.Clock
import com.itsluminous.hikviewer.core.stream.DecodePipeline
import com.itsluminous.hikviewer.core.stream.StreamException
import com.itsluminous.hikviewer.core.stream.StreamInfoResponse
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.core.stream.VtduTokenProvider
import com.itsluminous.hikviewer.core.stream.VtmRelayClient
import com.itsluminous.hikviewer.core.stream.VtmStallException
import com.itsluminous.hikviewer.core.stream.VtmUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import java.io.IOException

/** Tunables of [PlaybackStream]; defaults come from the playback spike. */
public data class PlaybackStreamConfig(
    /** Relay connect timeout. */
    val connectTimeoutMillis: Int = VtmRelayClient.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    /** Socket read timeout. */
    val readTimeoutMillis: Int = VtmRelayClient.DEFAULT_READ_TIMEOUT_MILLIS,
    /** No complete picture for this long while packets still arrive → reconnect. */
    val stallTimeoutMillis: Long = DEFAULT_STALL_MILLIS,
    /** Wait between attempts after `result 6103` (relay busy) or a transport failure before any picture. */
    val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    /** Attempts before giving up on a window that never delivered a picture. */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    /** Defaults. */
    public companion object {
        /** First frame took 0.35–0.46 s in the spike; 10 s leaves room for a slow relay. */
        public const val DEFAULT_STALL_MILLIS: Long = 10_000

        /** `6103` cleared within a few seconds in every observed case. */
        public const val DEFAULT_RETRY_DELAY_MILLIS: Long = 2_000

        /** Four tries covers the longest observed busy spell. */
        public const val DEFAULT_MAX_ATTEMPTS: Int = 4
    }
}

/**
 * Plays one [PlaybackWindow] of a channel's recording as a cold [Flow] of decrypted
 * [AccessUnit]s, then completes.
 *
 * Playback uses the live relay transport unchanged (page list → VTDU token → `StreamInfoReq` →
 * MPEG-PS → AES NAL decryption); only the URL path differs ([VtmUrl.buildPlayback]). What differs
 * in behaviour:
 *  * the relay answers `result 6103` when several playback sessions open in a short span; that is
 *    retried with a **fresh** connection after [PlaybackStreamConfig.retryDelayMillis], because a
 *    reused client never recovers;
 *  * the window ends: the relay sends a frame on channel `0x02` and closes. That completes the flow
 *    normally after an [StreamStatus.Ended] status. A relay that closes the socket after pictures
 *    flowed is treated the same way, so a mid-window drop surfaces as an early end and the owner
 *    simply reopens at its playhead;
 *  * there is no seek, pause or speed opcode in use: the official app over this path tears the
 *    stream down and reopens a new window, and so does the caller of this class.
 *
 * Terminal problems ([StreamException]) end the flow with the exception.
 */
public class PlaybackStream(
    private val pageList: suspend () -> VtmPageList,
    private val tokens: VtduTokenProvider,
    private val config: PlaybackStreamConfig = PlaybackStreamConfig(),
    private val clock: Clock = Clock.SYSTEM,
    private val relayFactory: (VtmRelay, String) -> VtmRelayClient = { _, url ->
        VtmRelayClient(
            url,
            connectTimeoutMillis = config.connectTimeoutMillis,
            readTimeoutMillis = config.readTimeoutMillis,
        )
    },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Pictures of [camera]'s recording over [window], decrypted with [verificationCode] (null for
     * unencrypted recorders). [onStatus] is called from the collecting coroutine.
     */
    public fun open(
        camera: Camera,
        window: PlaybackWindow,
        verificationCode: String?,
        onStatus: (StreamStatus) -> Unit = {},
    ): Flow<AccessUnit> = flow {
        var attempt = 0
        while (true) {
            attempt++
            onStatus(StreamStatus.Connecting(attempt))
            val progress = Progress()
            try {
                playOnce(camera, window, verificationCode, onStatus, progress)
                onStatus(StreamStatus.Ended)
                return@flow
            } catch (e: StreamException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (progress.pictures > 0) {
                    // The relay dropped us after delivering footage: the window is over as far as
                    // this stream can tell. The owner reopens at its playhead if it wants more.
                    onStatus(StreamStatus.Ended)
                    return@flow
                }
                if (attempt >= config.maxAttempts) throw e
                onStatus(StreamStatus.Reconnecting(attempt, config.retryDelayMillis, e))
                sleep(config.retryDelayMillis)
            }
        }
    }.flowOn(Dispatchers.IO)

    private class Progress {
        var pictures = 0
    }

    private suspend fun FlowCollector<AccessUnit>.playOnce(
        camera: Camera,
        window: PlaybackWindow,
        verificationCode: String?,
        onStatus: (StreamStatus) -> Unit,
        progress: Progress,
    ) {
        val resource = pageList().resourceFor(camera) ?: throw StreamException.ChannelNotStreamable(camera)
        val url = VtmUrl.buildPlayback(resource, window, camera.deviceSerial, tokens.token())
        val relay = relayFactory(resource.relay, url)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { relay.close() }
        try {
            relay.connect()
            val info = relay.start()
            when {
                info.isBusy -> throw RelayBusyException()
                info.isNoStream -> throw StreamException.ChannelOffline(camera)
                (info.result ?: 0L) != 0L -> throw StreamException.RelayRejected(info.result!!)
            }
            pump(relay, verificationCode, onStatus, progress)
        } finally {
            cancelHandle.dispose()
            relay.close()
        }
    }

    private suspend fun FlowCollector<AccessUnit>.pump(
        relay: VtmRelayClient,
        verificationCode: String?,
        onStatus: (StreamStatus) -> Unit,
        progress: Progress,
    ) {
        val pipeline = DecodePipeline(verificationCode)
        var lastPictureAt = clock.nowMillis()
        while (!relay.endOfStream) {
            currentCoroutineContext().ensureActive()
            val pictures = relay.readStreamPacket()?.let(pipeline::process).orEmpty()
            for (picture in pictures) {
                lastPictureAt = clock.nowMillis()
                progress.pictures++
                if (progress.pictures == 1) onStatus(StreamStatus.Streaming)
                emit(picture)
            }
            if (clock.nowMillis() - lastPictureAt > config.stallTimeoutMillis) {
                throw VtmStallException("No picture for ${config.stallTimeoutMillis} ms")
            }
        }
    }
}

/** The relay answered `result 6103`: another playback session is still being torn down. Retryable. */
public class RelayBusyException : IOException("Relay busy (result ${StreamInfoResponse.BUSY_RESULT})")
