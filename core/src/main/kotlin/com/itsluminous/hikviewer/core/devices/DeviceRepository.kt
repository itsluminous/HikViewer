package com.itsluminous.hikviewer.core.devices

import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.json.flag
import com.itsluminous.hikviewer.core.json.int
import com.itsluminous.hikviewer.core.json.obj
import com.itsluminous.hikviewer.core.json.str
import kotlinx.serialization.json.JsonObject

/**
 * Lists devices, their channels and the VTM resources needed to open streams.
 *
 * Three endpoints are involved, all paginated by `limit`/`offset` with `page.hasNext`:
 *  * `devices/pagelist` with the filter set the official app uses, which inlines `statusInfos`
 *    (online state, `isEncrypt`, `encryptPwd`),
 *  * `cameras/info` per device for channel names and signal state,
 *  * `resources/pagelist?filter=VTM`, which maps every channel to its relay server.
 *
 * The first two drive the UI; the third is what [com.itsluminous.hikviewer.core.stream.LiveStream]
 * needs. All three are cheap enough to fetch on every launch (about 1 s each on a 40-channel DVR).
 */
public class DeviceRepository(
    private val session: HikConnectSession,
) {
    /** All devices in the account, in cloud order. */
    public suspend fun listDevices(): List<Device> {
        val devices = mutableListOf<Device>()
        paginate(DEVICES_PATH, mapOf("filter" to DEVICE_FILTER)) { page ->
            val status = page.objectMap("statusInfos")
            val infos = page.objectsIn("deviceInfos")
            infos.forEach { info -> parseDevice(info, status[info.str("deviceSerial")])?.let(devices::add) }
            infos.size
        }
        return devices
    }

    /** Channels of [device], in channel order. Offline recorders yield cameras with `isOnline = false`. */
    public suspend fun listCameras(device: Device): List<Camera> {
        val body = session.get(CAMERAS_PATH, mapOf("deviceSerial" to device.serial))
        return body
            .objectsIn("cameraInfos")
            .mapNotNull { parseCamera(it, device) }
            .sortedBy { it.channelNo }
    }

    /** Every device's cameras, flattened, in device then channel order. */
    public suspend fun listAllCameras(): List<Camera> = listDevices().flatMap { listCameras(it) }

    /** The VTM resource list, merged over all pages; needed once per session to open streams. */
    public suspend fun vtmPageList(): VtmPageList {
        val resources = mutableListOf<JsonObject>()
        val relays = mutableMapOf<String, JsonObject>()
        paginate(RESOURCES_PATH, mapOf("filter" to "VTM")) { page ->
            val infos = page.objectsIn("resourceInfos")
            resources += infos
            relays += page.objectMap("VTM")
            infos.size
        }
        return VtmPageList.parse(resources, relays)
    }

    private suspend fun paginate(
        path: String,
        query: Map<String, String>,
        consume: (JsonObject) -> Int,
    ) {
        var offset = 0
        repeat(MAX_PAGES) {
            val page =
                session.get(
                    path,
                    query + mapOf("groupId" to "-1", "limit" to PAGE_LIMIT.toString(), "offset" to offset.toString()),
                )
            val count = consume(page)
            val hasNext = page.obj("page")?.flag("hasNext") ?: false
            if (count == 0 || !hasNext) return
            offset += count
        }
    }

    private fun parseDevice(
        info: JsonObject,
        status: JsonObject?,
    ): Device? {
        val serial = info.str("deviceSerial") ?: return null
        return Device(
            serial = serial,
            name = info.str("name")?.trim()?.takeIf(String::isNotEmpty) ?: serial,
            model = info.str("customType")?.takeIf(String::isNotEmpty) ?: info.str("deviceType"),
            isOnline = status?.int("globalStatus")?.let { it == 1 } ?: info.int("status")?.let { it == 1 },
            isEncrypted = status?.flag("isEncrypt") ?: false,
            encryptPwd = status?.str("encryptPwd")?.takeIf(String::isNotEmpty),
            channelCount = info.int("channelNumber") ?: 1,
        )
    }

    private fun parseCamera(
        info: JsonObject,
        device: Device,
    ): Camera? {
        val channel = info.int("channelNo") ?: return null
        val signal = info.obj("deviceChannelInfo")?.int("signalStatus") ?: info.int("signalStatus")
        return Camera(
            deviceSerial = info.str("deviceSerial") ?: device.serial,
            channelNo = channel,
            name = info.str("cameraName")?.trim()?.takeIf(String::isNotEmpty) ?: "Camera $channel",
            hasSignal = signal == 1,
            isOnline = device.isOnline ?: true,
        )
    }

    private companion object {
        const val DEVICES_PATH = "/v3/userdevices/v1/devices/pagelist"
        const val CAMERAS_PATH = "/v3/userdevices/v1/cameras/info"
        const val RESOURCES_PATH = "/v3/userdevices/v1/resources/pagelist"
        const val DEVICE_FILTER =
            "CLOUD,CONNECTION,SWITCH,STATUS,WIFI,NODISTURB,P2P,CHANNEL,VTM,FEATURE,UPGRADE,VIDEO_QUALITY,QOS"
        const val PAGE_LIMIT = 50
        const val MAX_PAGES = 40
    }
}
