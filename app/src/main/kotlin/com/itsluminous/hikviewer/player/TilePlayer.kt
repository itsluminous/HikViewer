package com.itsluminous.hikviewer.player

import android.media.MediaCodec
import android.util.Log
import android.view.Surface
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.CodecConfig
import com.itsluminous.hikviewer.core.stream.StreamException
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.IOException

/** What a tile's badge shows. */
sealed class TileState {
    /** Waiting for the relay or the first picture. */
    object Connecting : TileState()

    /** Every frame is decoded and shown. */
    object Live : TileState()

    /** One keyframe per second is shown (budget fallback). */
    object Keyframe : TileState()

    /** The stream or the decoder failed for good. */
    data class Error(
        /** Short user-facing text. */
        val message: String,
        /** True when the failure is the verification code; the grid re-prompts. */
        val wrongVerificationCode: Boolean = false,
    ) : TileState()
}

/** Opens streams; the production implementation is `AccountRepository.openStream`. */
fun interface StreamOpener {
    /** Cold flow of pictures for [camera]. */
    fun open(
        camera: Camera,
        quality: StreamQuality,
        onStatus: (StreamStatus) -> Unit,
    ): Flow<AccessUnit>
}

/**
 * Plays one camera into one surface according to a [TileMode].
 *
 * Collects the camera's [AccessUnit] flow on the caller's scope and routes pictures either to a
 * private [TileDecoder] (live modes) or to the shared [KeyframeDecoderPool]. Decoders are created
 * lazily at the first keyframe that carries parameter sets, and recreated at the next keyframe
 * after a mode change, a parameter-set change, a stall or a surface change. A hardware codec that
 * refuses to start ("insufficient resources", error 1100 on the MiTV) is retried as software once.
 */
class TilePlayer(
    /** The camera shown. */
    val camera: Camera,
    private val opener: StreamOpener,
    private val pool: KeyframeDecoderPool,
    private val quality: StreamQuality = StreamQuality.SUB,
    private val pickCodec: (mimeType: String, software: Boolean) -> String? = CodecNames::pick,
    private val decoderFactory: (String, CodecConfig, Surface, TileDecoder.Listener) -> TileDecoder = ::TileDecoder,
    /** Where the stream is collected and pictures are queued; a test dispatcher in tests. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TileDecoder.Listener {
    private val lock = Any()
    private val stateFlow = MutableStateFlow<TileState>(TileState.Connecting)
    private var surface: Surface? = null
    private var decoder: TileDecoder? = null
    private var decoderConfig: CodecConfig? = null
    private var forceSoftware = false
    private var decoderDisabled = false
    private var job: Job? = null
    private var streaming = false

    /** For the badge. */
    val state: StateFlow<TileState> = stateFlow.asStateFlow()

    /** Current mode; changing it releases the private decoder, the next keyframe builds the new one. */
    var mode: TileMode = TileMode.KEYFRAME
        set(value) {
            synchronized(lock) {
                if (field == value) return
                field = value
                releaseDecoderLocked()
                decoderDisabled = false
                if (!value.isLive) pool.cancel(this)
                if (streaming) stateFlow.value = if (value.isLive) TileState.Connecting else TileState.Keyframe
            }
        }

    /** Decoder input backlog, for [LiveBudget]. */
    val queuedFrames: Int get() = synchronized(lock) { decoder?.queuedFrames ?: 0 }

    /** Starts collecting the stream on [scope]. */
    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (job != null) return
            stateFlow.value = TileState.Connecting
            // Off the main thread: codec creation takes ~100 ms and every picture is copied into
            // an input buffer; the UI must not pay for either.
            job =
                scope.launch(dispatcher) {
                    opener
                        .open(camera, quality, ::onStatus)
                        .catch { e -> onStreamFailed(e) }
                        .collect(::onAccessUnit)
                }
        }
    }

    /** Stops the stream and releases the decoder. */
    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            streaming = false
            releaseDecoderLocked()
            pool.cancel(this)
        }
    }

    /** The tile's surface appeared or vanished; decoding restarts at the next keyframe. */
    fun attachSurface(surface: Surface?) {
        synchronized(lock) {
            if (this.surface == surface) return
            this.surface = surface
            releaseDecoderLocked()
            decoderDisabled = false
        }
    }

    /** Periodic watchdog; recreates a wedged decoder. */
    fun tick(nowMillis: Long) {
        synchronized(lock) {
            if (decoder?.checkStall(nowMillis) == true) {
                Log.w(TAG, "${camera.key}: decoder stalled, recreating")
                releaseDecoderLocked()
            }
        }
    }

    override fun onFirstFrame(decoder: TileDecoder) {
        synchronized(lock) { if (this.decoder === decoder) stateFlow.value = TileState.Live }
    }

    override fun onError(
        decoder: TileDecoder,
        exception: MediaCodec.CodecException,
    ) {
        synchronized(lock) {
            if (this.decoder !== decoder) return
            Log.w(TAG, "${camera.key}: codec error ${exception.errorCode} transient=${exception.isTransient}")
            releaseDecoderLocked()
            if (DecoderPolicy.shouldFallBackToSoftware(exception.isTransient, exception.errorCode)) {
                forceSoftware = true
            } else {
                failDecoder("Decoder error")
            }
        }
    }

    private fun onStatus(status: StreamStatus) {
        synchronized(lock) {
            when (status) {
                is StreamStatus.Connecting -> if (!streaming) stateFlow.value = TileState.Connecting
                is StreamStatus.Streaming -> streaming = true
                is StreamStatus.Reconnecting -> {
                    streaming = false
                    releaseDecoderLocked()
                    decoderDisabled = false
                    stateFlow.value = TileState.Connecting
                }
            }
        }
    }

    private fun onStreamFailed(e: Throwable) {
        val state =
            when (e) {
                is StreamException.WrongVerificationCode -> TileState.Error("Wrong code", wrongVerificationCode = true)
                is StreamException.ChannelOffline -> TileState.Error("No video")
                is StreamException.ChannelNotStreamable -> TileState.Error("No cloud stream")
                is StreamException -> TileState.Error("Relay refused")
                is IOException -> TileState.Error("Offline")
                else -> TileState.Error("Error")
            }
        Log.w(TAG, "${camera.key}: stream failed: ${e.message}")
        synchronized(lock) {
            releaseDecoderLocked()
            stateFlow.value = state
        }
    }

    private fun onAccessUnit(unit: AccessUnit) {
        synchronized(lock) {
            val target = surface ?: return
            if (!mode.isLive) {
                if (unit.isKeyframe) {
                    pool.submit(this, target, unit)
                    stateFlow.value = TileState.Keyframe
                }
                return
            }
            if (decoderDisabled) return
            val config = unit.config
            if (decoder == null || (config != null && config != decoderConfig)) {
                if (!unit.isKeyframe || config == null) return
                releaseDecoderLocked()
                decoder = createDecoder(config, target) ?: return
                decoderConfig = config
            }
            decoder?.feed(unit)
        }
    }

    private fun createDecoder(
        config: CodecConfig,
        target: Surface,
    ): TileDecoder? {
        val software = forceSoftware || mode != TileMode.HW_LIVE
        val name = pickCodec(config.codec.mimeType, software)
        if (name == null) {
            failDecoder("No ${config.codec} decoder")
            return null
        }
        val d = decoderFactory(name, config, target, this)
        return try {
            d.start()
            Log.i(TAG, "${camera.key}: ${config.codec} via $name (${if (software) "software" else "hardware"})")
            d
        } catch (e: MediaCodec.CodecException) {
            d.release()
            if (!software && DecoderPolicy.shouldFallBackToSoftware(e.isTransient, e.errorCode)) {
                Log.w(TAG, "${camera.key}: hardware decoder unavailable (${e.errorCode}), using software")
                forceSoftware = true
                createDecoder(config, target)
            } else {
                failDecoder("Decoder error")
                null
            }
        } catch (e: IOException) {
            d.release()
            Log.w(TAG, "${camera.key}: cannot create $name", e)
            failDecoder("Decoder error")
            null
        }
    }

    /** A decoder failure a retry cannot fix: stop trying until the mode, surface or stream changes. */
    private fun failDecoder(message: String) {
        decoderDisabled = true
        stateFlow.value = TileState.Error(message)
    }

    private fun releaseDecoderLocked() {
        decoder?.release()
        decoder = null
        decoderConfig = null
    }

    private companion object {
        const val TAG = "TilePlayer"
    }
}
