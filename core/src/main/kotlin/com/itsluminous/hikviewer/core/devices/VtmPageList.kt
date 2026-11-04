package com.itsluminous.hikviewer.core.devices

import com.itsluminous.hikviewer.core.CloudDomains
import com.itsluminous.hikviewer.core.json.int
import com.itsluminous.hikviewer.core.json.obj
import com.itsluminous.hikviewer.core.json.str
import kotlinx.serialization.json.JsonObject

/**
 * Where to fetch a channel's live stream from, taken from the VTM resource page list.
 *
 * The cloud assigns each channel (`resource`) to a relay server; the stream URL is built from the
 * relay address, the channel's `streamBizUrl` parameters and a VTDU token.
 */
public data class VtmResource(
    /** Owning device serial. */
    val deviceSerial: String,
    /** Channel number (`localIndex`); 0 is the recorder itself, not a camera. */
    val channelNo: Int,
    /** Cloud resource id, the key into the relay map. */
    val resourceId: String,
    /** Channel name as the cloud shows it. */
    val name: String,
    /** Query-string fragment with the cloud's stream business parameters. */
    val streamBizUrl: String,
    /** Relay serving this resource. */
    val relay: VtmRelay,
) {
    /** Stable key matching [Camera.key]. */
    val key: String get() = "$deviceSerial/$channelNo"
}

/** A VTM relay endpoint. */
public data class VtmRelay(
    /** Host name or IP of the relay (`externalIp`, `domain` or `internalIp`, in that order). */
    val host: String,
    /** TCP port of the relay. */
    val port: Int,
)

/** All VTM resources of an account, indexed by camera key. */
public class VtmPageList(
    /** Streamable channels, excluding channel 0 placeholders. */
    public val resources: List<VtmResource>,
) {
    private val byKey = resources.associateBy { it.key }

    /** The resource for [camera], or null when the cloud lists no stream for that channel. */
    public fun resourceFor(camera: Camera): VtmResource? = byKey[camera.key]

    /** Lookup by serial and channel. */
    public fun resourceFor(
        deviceSerial: String,
        channelNo: Int,
    ): VtmResource? = byKey["$deviceSerial/$channelNo"]

    internal companion object {
        /** Builds the list from one or more merged `resources/pagelist?filter=VTM` pages. */
        fun parse(
            resourceInfos: List<JsonObject>,
            relays: Map<String, JsonObject>,
        ): VtmPageList = VtmPageList(resourceInfos.mapNotNull { parseResource(it, relays) })

        private fun parseResource(
            info: JsonObject,
            relays: Map<String, JsonObject>,
        ): VtmResource? {
            val serial = info.str("deviceSerial") ?: return null
            val channel = info.int("localIndex") ?: return null
            val resourceId = info.str("resourceId") ?: return null
            val relay = relays[resourceId]?.let(::parseRelay) ?: return null
            return VtmResource(
                deviceSerial = serial,
                channelNo = channel,
                resourceId = resourceId,
                name = info.str("resourceName").orEmpty(),
                streamBizUrl = info.str("streamBizUrl").orEmpty(),
                relay = relay,
            )
        }

        private fun parseRelay(vtm: JsonObject): VtmRelay? {
            val host =
                listOf("externalIp", "domain", "internalIp")
                    .firstNotNullOfOrNull { vtm.str(it)?.trim()?.takeIf(String::isNotEmpty) } ?: return null
            val port = vtm.int("port")?.takeIf { it in 1..MAX_PORT } ?: return null
            // A literal IP cannot be checked against the domain allow-list; a host name can and must be.
            if (!host.isIpAddress() && !CloudDomains.isAllowed(host.lowercase())) return null
            return VtmRelay(host, port)
        }

        private fun String.isIpAddress(): Boolean = all { it.isDigit() || it == '.' } || contains(':')

        private const val MAX_PORT = 65535
    }
}

/** Convenience accessor used by [DeviceRepository] when merging pages. */
internal fun JsonObject.objectsIn(key: String): List<JsonObject> =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun JsonObject.objectMap(key: String): Map<String, JsonObject> =
    obj(key)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }?.toMap().orEmpty()
