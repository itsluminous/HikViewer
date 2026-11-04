package com.itsluminous.hikviewer.core.stream

import com.itsluminous.hikviewer.core.devices.VtmResource
import com.itsluminous.hikviewer.core.playback.PlaybackWindow
import java.io.IOException
import java.time.format.DateTimeFormatter

/** A VTM protocol or cloud metadata error. Retryable unless a subclass says otherwise. */
public open class VtmException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Which encoding of a channel to request from the cloud. */
public enum class StreamQuality(
    /** `stream` parameter of the `ysproto://` URL. */
    public val streamType: Int,
) {
    /** Main stream: full resolution (HEVC 720p on the reference DVR). */
    MAIN(1),

    /** Sub stream: small profile (H.264 960x480 on the reference DVR); right for grid tiles. */
    SUB(2),
}

/**
 * Builds and parses the `ysproto://host:port/live?...` and `/playback?...` URLs a relay expects
 * in its `StreamInfoReq`.
 *
 * The query is the channel's `streamBizUrl` parameters followed by the device/channel/stream
 * selection and the VTDU token (`ssn`). `cln` 9 is the client type the relay accepts for this
 * token kind. Parameter order matters to nobody but is kept stable for tests.
 */
public object VtmUrl {
    /** Default `cln` (client type) parameter. */
    public const val CLIENT_TYPE: Int = 9

    /** The live relay URL for [resource] at [quality] using [vtduToken]. */
    public fun build(
        resource: VtmResource,
        quality: StreamQuality,
        vtduToken: String,
        timestampMillis: Long = System.currentTimeMillis(),
    ): String {
        val params = linkedMapOf<String, String>()
        params += parseQuery(resource.streamBizUrl)
        params["dev"] = resource.deviceSerial
        params["chn"] = resource.channelNo.toString()
        params["stream"] = quality.streamType.toString()
        params += commonParams(vtduToken, timestampMillis)
        return "ysproto://${authority(resource)}/live?${query(params)}"
    }

    /**
     * The playback relay URL for the recording of [resource] between the window's `begin` and
     * `end`, which are the **recorder's wall clock** formatted `yyyyMMdd'T'HHmmss'Z'`: the `Z` is
     * literal, not UTC (the official app formats with the device's default time zone). The relay
     * ignores `stream` for playback (it always serves the recorded main quality) and rejects any
     * other time format or extra `weakstream`/`seg` parameters with `result 6103`, so only this
     * minimal, proven set is sent. [channelSerial] is the channel's own serial (`serial`).
     */
    public fun buildPlayback(
        resource: VtmResource,
        window: PlaybackWindow,
        channelSerial: String,
        vtduToken: String,
        timestampMillis: Long = System.currentTimeMillis(),
    ): String {
        val params = linkedMapOf<String, String>()
        params += parseQuery(resource.streamBizUrl)
        params["dev"] = resource.deviceSerial
        params["chn"] = resource.channelNo.toString()
        params["stream"] = StreamQuality.MAIN.streamType.toString()
        params["begin"] = PLAYBACK_TIME.format(window.begin)
        params["end"] = PLAYBACK_TIME.format(window.end)
        params["serial"] = channelSerial
        params += commonParams(vtduToken, timestampMillis)
        return "ysproto://${authority(resource)}/playback?${query(params)}"
    }

    private fun commonParams(
        vtduToken: String,
        timestampMillis: Long,
    ): Map<String, String> = linkedMapOf(
        "cln" to CLIENT_TYPE.toString(),
        "isp" to "0",
        "auth" to "1",
        "ssn" to vtduToken,
        "vip" to "0",
        "timestamp" to timestampMillis.toString(),
    )

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

    private fun authority(resource: VtmResource): String = "${hostForUrl(resource.relay.host)}:${resource.relay.port}"

    /** Host and port of a `ysproto://` URL, as the relay client needs them to connect. */
    public fun endpoint(url: String): Pair<String, Int> {
        if (!url.startsWith(SCHEME)) throw VtmException("Invalid VTM URL scheme")
        val authority = url.removePrefix(SCHEME).substringBefore('/')
        val portText = authority.substringAfterLast(':', "")
        val host = authority.substringBeforeLast(':').removeSurrounding("[", "]")
        val port = portText.toIntOrNull()?.takeIf { it in 1..MAX_PORT }
        val hostValid = host.isNotEmpty() && !authority.endsWith("]")
        if (!hostValid || port == null) throw VtmException("Invalid VTM URL host or port")
        return host to port
    }

    private const val SCHEME = "ysproto://"
    private const val MAX_PORT = 65535
    private val PLAYBACK_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    private fun parseQuery(streamBizUrl: String): Map<String, String> {
        val query = streamBizUrl.substringAfter('?', streamBizUrl).trimStart('?')
        if (query.isBlank()) return emptyMap()
        return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            decode(k) to decode(v)
        }
    }

    private fun hostForUrl(host: String): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun decode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
}
