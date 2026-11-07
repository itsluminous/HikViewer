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
        codecs: List<MediaCodecInfo> = allCodecs(),
    ): String? {
        val decoders = codecs.filter { it.isDecoderFor(mimeType) }
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

    /**
     * True when the decoder called [codecName] declares `FEATURE_LowLatency` for some type it
     * decodes (API 30+; the feature and the format key both arrived in R). [TileDecoder] sets
     * `KEY_LOW_LATENCY` only then: the key is defined as ignored by decoders that lack the feature,
     * but an Amlogic Android 11 box answered the grid's decoders with "Decoder error" and that
     * unconditional key was the one thing the grid did that fullscreen did not, so it is no longer
     * sent where the codec has not asked for it. An unknown name reads as unsupported. The
     * platform's answer is remembered per name: a decoder is built at every keyframe that needs
     * one, and the codec list does not change while the process lives.
     */
    fun supportsLowLatency(codecName: String): Boolean =
        lowLatencyByName.getOrPut(codecName) { supportsLowLatency(codecName, allCodecs()) }

    /** [supportsLowLatency] against an explicit codec list; what the tests call. */
    fun supportsLowLatency(
        codecName: String,
        codecs: List<MediaCodecInfo>,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val info = codecs.firstOrNull { it.name == codecName } ?: return false
        return info.supportedTypes.any { type ->
            runCatching {
                info
                    .getCapabilitiesForType(type)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
        }
    }

    private val lowLatencyByName = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * How many hardware decoders of [mimeType] this device runs at once, as [DecoderPolicy] counts
     * them: the preferred hardware codec's `getMaxSupportedInstances()` clamped to 1..2
     * ([DecoderPolicy.hardwareSlots]); two when there is no hardware decoder or it does not say
     * (the MiTV's `OMX.MS.AVC.Decoder` reports 2; Amlogic 1 GB boxes often report 1). Logged once
     * per type with the codec names, so a TV logcat starts with what the policy is working with.
     */
    fun hardwareSlots(
        mimeType: String,
        codecs: List<MediaCodecInfo> = allCodecs(),
    ): Int {
        val hardware = codecs.firstOrNull { it.isDecoderFor(mimeType) && !isSoftware(it) }
        val reported =
            hardware?.let { info ->
                runCatching { info.getCapabilitiesForType(mimeType).maxSupportedInstances }.getOrNull()
            }
        val slots = DecoderPolicy.hardwareSlots(reported)
        if (slotsLogged.putIfAbsent(mimeType, true) == null) {
            Log.i(TAG, "$mimeType hardware slots=$slots (${hardware?.name ?: "no hardware decoder"} max=$reported)")
        }
        return slots
    }

    private fun MediaCodecInfo.isDecoderFor(mimeType: String): Boolean =
        !isEncoder && supportedTypes.any { it.equals(mimeType, true) }

    private fun allCodecs(): List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()

    private val slotsLogged = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private const val TAG = "CodecNames"
}
