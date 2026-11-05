package com.itsluminous.hikviewer.player

import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import kotlinx.coroutines.flow.Flow

/** What a tile shows besides the video. */
sealed class TileState {
    /** No picture yet: waiting for the relay or the first keyframe (spinner). */
    object Connecting : TileState()

    /** Every frame is decoded and shown. */
    object Live : TileState()

    /** One keyframe per second is shown (budget fallback). */
    object Keyframe : TileState()

    /** The stream or the decoder failed for good; the tile is dimmed with a Retry overlay. */
    data class Error(
        /** Short user-facing text. */
        val message: String,
        /**
         * True when the stream's decryptor gave up on the stored verification code. The grid checks
         * the code against the recorder before deciding whether that is a wrong code or a bad stream.
         */
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
