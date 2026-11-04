package com.itsluminous.hikviewer.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * Picks a decoder by name instead of letting `MediaCodec.createDecoderByType` choose, because the
 * policy needs to force a *software* decoder for background tiles while the platform default is
 * always the hardware one.
 *
 * "Software" is decided by the platform flag ([MediaCodecInfo.isSoftwareOnly], API 29+) **or** the
 * well-known name prefixes, because vendor builds ship the Google decoders from `/vendor/etc` and
 * then flag them as vendor codecs: on the MiTV the flag alone found no software decoder and the
 * keyframe pool was handed the hardware `OMX.MS.AVC.Decoder`, whose two instances were already
 * taken, so every keyframe job failed with error 1100 and the slideshow tiles stayed black.
 * For the same reason a request for a software decoder never falls back to a hardware one.
 */
object CodecNames {
    private val logged = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Name of a decoder for [mimeType]: a software one when [software] (null if the device has
     * none), otherwise hardware preferred with software as the fallback.
     */
    fun pick(
        mimeType: String,
        software: Boolean,
        codecs: List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList(),
    ): String? {
        val decoders = codecs.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
        val (soft, hard) = decoders.partition(::isSoftware)
        if (logged.putIfAbsent(mimeType, true) == null) {
            Log.i(TAG, "$mimeType decoders: hardware=${hard.map { it.name }} software=${soft.map { it.name }}")
        }
        return if (software) soft.firstOrNull()?.name else (hard.ifEmpty { soft }).firstOrNull()?.name
    }

    /** True for the platform's software decoders (`OMX.google.*`, `c2.android.*`) by flag or name. */
    fun isSoftware(info: MediaCodecInfo): Boolean {
        val byFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly
        return byFlag || isSoftwareName(info.name)
    }

    /** Name-based classification; also the only signal on API < 29. */
    fun isSoftwareName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.contains(".sw.")
    }

    private const val TAG = "CodecNames"
}
