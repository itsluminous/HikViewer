package com.itsluminous.hikviewer.core.stream

import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.VtmPageList
import com.itsluminous.hikviewer.core.devices.VtmRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import java.io.IOException

/** Monotonic time source, replaceable in tests. */
public fun interface Clock {
    /** Milliseconds from an arbitrary origin; only differences matter. */
    public fun nowMillis(): Long

    /** Built-in clocks. */
    public companion object {
        /** `System.nanoTime()` based. */
        public val SYSTEM: Clock = Clock { System.nanoTime() / NANOS_PER_MILLI }
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** A stream failed in a way a retry cannot fix; the flow terminates with one of these. */
public sealed class StreamException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    /** The verification code does not decrypt this stream. */
    public class WrongVerificationCode : StreamException("The verification code does not decrypt this stream")

    /** The cloud lists no stream for this channel (not in the VTM page list). */
    public class ChannelNotStreamable(
        camera: Camera,
    ) : StreamException("No cloud stream for ${camera.key}")

    /** The relay answered `result 6106`: the channel exists but produces no video (no camera on the input). */
    public class ChannelOffline(
        camera: Camera,
    ) : StreamException("Channel ${camera.key} has no video")

    /** The relay rejected the request with a code we do not know. */
    public class RelayRejected(
        result: Long,
    ) : StreamException("Relay refused the stream (result $result)")
}

/** Observable status changes of a [LiveStream], for a tile's overlay. */
public sealed class StreamStatus {
    /** Connecting to the relay (first attempt or after a failure). */
    public data class Connecting(
        val attempt: Int,
    ) : StreamStatus()

    /** Pictures are flowing. */
    public object Streaming : StreamStatus()

    /** The connection failed; another attempt follows after [delayMillis]. */
    public data class Reconnecting(
        val attempt: Int,
        val delayMillis: Long,
        val cause: Throwable,
    ) : StreamStatus()
}

/** Tunables of [LiveStream]; defaults are the measured cloud behaviour from the feasibility spike. */
public data class LiveStreamConfig(
    /** Relay connect timeout. */
    val connectTimeoutMillis: Int = VtmRelayClient.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    /** Socket read timeout; the relay sends a GOP burst every ~2 s, so this is the stall watchdog floor. */
    val readTimeoutMillis: Int = VtmRelayClient.DEFAULT_READ_TIMEOUT_MILLIS,
    /** No complete picture for this long while packets still arrive → reconnect. */
    val stallTimeoutMillis: Long = DEFAULT_STALL_MILLIS,
    /** First reconnect delay; doubles up to [maxBackoffMillis]. */
    val initialBackoffMillis: Long = DEFAULT_INITIAL_BACKOFF_MILLIS,
    /** Longest reconnect delay. */
    val maxBackoffMillis: Long = DEFAULT_MAX_BACKOFF_MILLIS,
    /** Give up (fail the flow) after this many consecutive failed attempts; null retries forever. */
    val maxAttempts: Int? = null,
) {
    /** Defaults. */
    public companion object {
        /** Stall watchdog: first frame takes up to 3.2 s cold, GOP bursts ~2 s apart. */
        public const val DEFAULT_STALL_MILLIS: Long = 10_000

        /** First backoff. */
        public const val DEFAULT_INITIAL_BACKOFF_MILLIS: Long = 1_000

        /** Backoff ceiling, as in the reference client. */
        public const val DEFAULT_MAX_BACKOFF_MILLIS: Long = 30_000
    }
}

/**
 * Opens a camera's cloud stream as a cold [Flow] of decrypted [AccessUnit]s.
 *
 * Each collection resolves the channel's relay from the VTM page list, fetches a VTDU token, connects,
 * and runs the demux → decrypt → assemble pipeline, reconnecting with exponential backoff on
 * transport errors and stalls. Terminal problems ([StreamException]) end the flow. All blocking I/O
 * happens on [Dispatchers.IO]; cancelling the collector closes the socket at once.
 */
public class LiveStream(
    private val pageList: suspend () -> VtmPageList,
    private val tokens: VtduTokenProvider,
    private val config: LiveStreamConfig = LiveStreamConfig(),
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
     * Pictures of [camera] at [quality], decrypted with [verificationCode] (null for unencrypted
     * devices). [onStatus] is called from the collecting coroutine on every state change.
     */
    public fun open(
        camera: Camera,
        quality: StreamQuality,
        verificationCode: String?,
        onStatus: (StreamStatus) -> Unit = {},
    ): Flow<AccessUnit> = flow {
        var attempt = 0
        var backoff = config.initialBackoffMillis
        while (true) {
            attempt++
            onStatus(StreamStatus.Connecting(attempt))
            val progress = Progress()
            try {
                streamOnce(camera, quality, verificationCode, onStatus, progress)
            } catch (e: StreamException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                // A connection that delivered pictures was healthy; start the backoff ladder afresh.
                if (progress.pictures > 0) {
                    attempt = 0
                    backoff = config.initialBackoffMillis
                }
                config.maxAttempts?.let { if (attempt >= it) throw e }
                onStatus(StreamStatus.Reconnecting(attempt, backoff, e))
                sleep(backoff)
                backoff = minOf(backoff * 2, config.maxBackoffMillis)
            }
        }
    }.flowOn(Dispatchers.IO)

    private class Progress {
        var pictures = 0
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AccessUnit>.streamOnce(
        camera: Camera,
        quality: StreamQuality,
        verificationCode: String?,
        onStatus: (StreamStatus) -> Unit,
        progress: Progress,
    ) {
        val resource = pageList().resourceFor(camera) ?: throw StreamException.ChannelNotStreamable(camera)
        val url = VtmUrl.build(resource, quality, tokens.token())
        val relay = relayFactory(resource.relay, url)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { relay.close() }
        try {
            relay.connect()
            val info = relay.start()
            when {
                info.isNoStream -> throw StreamException.ChannelOffline(camera)
                (info.result ?: 0L) != 0L -> throw StreamException.RelayRejected(info.result!!)
            }
            pump(relay, verificationCode, onStatus, progress)
        } finally {
            cancelHandle.dispose()
            relay.close()
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AccessUnit>.pump(
        relay: VtmRelayClient,
        verificationCode: String?,
        onStatus: (StreamStatus) -> Unit,
        progress: Progress,
    ) {
        val pipeline = DecodePipeline(verificationCode)
        var lastPictureAt = clock.nowMillis()
        while (true) {
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

/**
 * Relay packets in, pictures out: [AutoDemuxer] → [NalDecryptor] → [AccessUnitAssembler]. The
 * decryptor is created once the demuxer has seen data (so it gets the container's codec hint) and the
 * assembler once the decryptor knows the codec.
 */
public class DecodePipeline(
    private val verificationCode: String?,
) {
    private val demuxer = AutoDemuxer()
    private var decryptor: NalDecryptor? = null
    private var assembler: AccessUnitAssembler? = null

    /** Codec once known. */
    public val codec: VideoCodec? get() = decryptor?.codec

    /** The decryptor's variant once known, for diagnostics. */
    public val variant: NalDecryptor.Variant? get() = decryptor?.variant

    /** Feeds one relay packet; throws [StreamException.WrongVerificationCode] when the key is wrong. */
    public fun process(packet: ByteArray): List<AccessUnit> {
        val nals = demuxer.feed(packet)
        if (nals.isEmpty()) return emptyList()
        val d = decryptor ?: NalDecryptor(verificationCode, demuxer.codecHint).also { decryptor = it }
        val out = ArrayList<AccessUnit>()
        for (nal in nals) {
            val decrypted = d.feed(nal)
            if (d.keyError) throw StreamException.WrongVerificationCode()
            val codec = d.codec ?: continue
            val a = assembler ?: AccessUnitAssembler(codec).also { assembler = it }
            decrypted.forEach { unit -> a.feed(unit)?.let(out::add) }
        }
        return out
    }
}
