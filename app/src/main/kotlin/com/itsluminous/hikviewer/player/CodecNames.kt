package com.itsluminous.hikviewer.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Picks a decoder by name instead of letting `MediaCodec.createDecoderByType` choose, because the
 * policy needs to force a *software* decoder for background tiles while the platform default is
 * always the hardware one.
 */
object CodecNames {
    /** Name of a decoder for [mimeType]; software-only when [software], hardware preferred otherwise. */
    fun pick(
        mimeType: String,
        software: Boolean,
        codecs: List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList(),
    ): String? {
        val decoders = codecs.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
        val wanted = decoders.filter { isSoftware(it) == software }
        return (wanted.ifEmpty { decoders }).firstOrNull()?.name
    }

    /** True for the platform's software decoders (`OMX.google.*`, `c2.android.*`). */
    fun isSoftware(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        info.isSoftwareOnly
    } else {
        isSoftwareName(info.name)
    }

    /** Name-based heuristic for API < 29. */
    fun isSoftwareName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.contains(".sw.")
    }
}
