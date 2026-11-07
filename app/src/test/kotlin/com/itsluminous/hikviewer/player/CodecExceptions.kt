package com.itsluminous.hikviewer.player

import android.media.MediaCodec
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/**
 * A `MediaCodec.CodecException` with the given [code], transient flag and [diagnosticInfo]; the
 * class has no public constructor, so the hidden fields are set the way the platform sets them.
 */
internal fun codecException(
    code: Int,
    transient: Boolean,
    diagnosticInfo: String? = null,
): MediaCodec.CodecException {
    val e = Shadow.newInstanceOf(MediaCodec.CodecException::class.java)
    ReflectionHelpers.setField(e, "mErrorCode", code)
    ReflectionHelpers.setField(e, "mActionCode", if (transient) ACTION_TRANSIENT else 0)
    ReflectionHelpers.setField(e, "mDiagnosticInfo", diagnosticInfo)
    return e
}

// MediaCodec.CodecException.ACTION_TRANSIENT (hidden constant).
private const val ACTION_TRANSIENT = 1
