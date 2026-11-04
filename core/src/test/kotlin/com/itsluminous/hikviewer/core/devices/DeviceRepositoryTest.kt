package com.itsluminous.hikviewer.core.devices

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.HikConnectClient
import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.auth.Session
import com.itsluminous.hikviewer.core.testutil.Fixtures
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeviceRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: DeviceRepository

    @BeforeEach
    fun start() {
        server.start()
        val client = HikConnectClient({ "fc" }, OkHttpClient()) { _ -> server.url("/") }
        repository =
            DeviceRepository(HikConnectSession(client, Session("s", "r", "apiiindia.hik-connect.com", null, null)))
    }

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `listDevices merges deviceInfos with statusInfos`() = runTest {
        server.enqueue(json(Fixtures.text("devices_pagelist.json")))

        val devices = repository.listDevices()

        assertThat(devices).hasSize(1)
        val dvr = devices.single()
        assertThat(dvr.serial).isEqualTo("FAKE0000001")
        assertThat(dvr.name).isEqualTo("Home DVR")
        assertThat(dvr.model).isEqualTo("DS-7A16HQHI-K1")
        assertThat(dvr.isOnline).isTrue()
        assertThat(dvr.isEncrypted).isTrue()
        assertThat(dvr.encryptPwd).isEqualTo("5a7a8c3c2d1f0e9b8a7c6d5e4f3a2b1c")
        assertThat(dvr.channelCount).isEqualTo(40)
        val url = server.takeRequest().requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/v3/userdevices/v1/devices/pagelist")
        assertThat(url.queryParameter("groupId")).isEqualTo("-1")
        assertThat(url.queryParameter("limit")).isEqualTo("50")
        assertThat(url.queryParameter("offset")).isEqualTo("0")
        assertThat(url.queryParameter("filter")).contains("STATUS")
        assertThat(url.queryParameter("filter")).contains("VTM")
    }

    @Test
    fun `listDevices without statusInfos falls back to deviceInfos status and unencrypted`() = runTest {
        server.enqueue(
            json(
                """{"meta":{"code":200},"page":{"hasNext":false},
                "deviceInfos":[{"deviceSerial":"X1","name":"  ","deviceType":"IPC","status":0},
                {"name":"no serial"}]}""",
            ),
        )

        val devices = repository.listDevices()

        assertThat(devices).hasSize(1)
        assertThat(devices[0].name).isEqualTo("X1")
        assertThat(devices[0].model).isEqualTo("IPC")
        assertThat(devices[0].isOnline).isFalse()
        assertThat(devices[0].isEncrypted).isFalse()
        assertThat(devices[0].encryptPwd).isNull()
        assertThat(devices[0].channelCount).isEqualTo(1)
    }

    @Test
    fun `listCameras maps channel number name and signal`() = runTest {
        server.enqueue(json(Fixtures.text("cameras_info.json")))
        val device = Device("FAKE0000001", "Home DVR", null, true, true, null, 40)

        val cameras = repository.listCameras(device)

        assertThat(cameras.map { it.channelNo }).containsExactly(1, 2, 13, 17).inOrder()
        assertThat(cameras.map { it.name }).containsExactly("0.Ghar", "1.Balcony", "Camera 13", "Camera 17").inOrder()
        assertThat(cameras.map { it.hasSignal }).containsExactly(true, true, false, false).inOrder()
        assertThat(cameras.all { it.isOnline }).isTrue()
        assertThat(cameras[0].key).isEqualTo("FAKE0000001/1")
        val url = server.takeRequest().requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/v3/userdevices/v1/cameras/info")
        assertThat(url.queryParameter("deviceSerial")).isEqualTo("FAKE0000001")
    }

    @Test
    fun `cameras of an offline device are offline`() = runTest {
        server.enqueue(json(Fixtures.text("cameras_info.json")))
        val device = Device("FAKE0000001", "Home DVR", null, false, true, null, 40)

        val cameras = repository.listCameras(device)

        assertThat(cameras.none { it.isOnline }).isTrue()
    }

    @Test
    fun `listAllCameras chains devices and cameras`() = runTest {
        server.enqueue(json(Fixtures.text("devices_pagelist.json")))
        server.enqueue(json(Fixtures.text("cameras_info.json")))

        val cameras = repository.listAllCameras()

        assertThat(cameras).hasSize(4)
        assertThat(cameras.count { it.hasSignal }).isEqualTo(2)
    }

    @Test
    fun `vtmPageList merges pages and drops unusable relays`() = runTest {
        server.enqueue(json(Fixtures.text("resources_pagelist_page1.json")))
        server.enqueue(json(Fixtures.text("resources_pagelist_page2.json")))

        val list = repository.vtmPageList()

        assertThat(list.resources.map { it.channelNo }).containsExactly(0, 1, 2).inOrder()
        val ch1 = list.resourceFor("FAKE0000001", 1)!!
        assertThat(ch1.relay).isEqualTo(VtmRelay("yducvtm.ezvizlife.com", 10554))
        assertThat(ch1.streamBizUrl).isEqualTo("?biz=1&env=prod")
        assertThat(ch1.name).isEqualTo("0.Ghar")
        assertThat(ch1.resourceId).isEqualTo("RES-CH1")
        // page 2: relay given only as an internal IP string port
        assertThat(list.resourceFor("FAKE0000001", 2)!!.relay).isEqualTo(VtmRelay("152.32.156.219", 10554))
        // orphan (no relay), foreign relay host and bad port are not streamable
        assertThat(list.resourceFor("FAKE0000001", 3)).isNull()
        assertThat(list.resourceFor("FAKE0000001", 4)).isNull()
        assertThat(list.resourceFor("FAKE0000001", 5)).isNull()
        assertThat(list.resourceFor(Camera("FAKE0000001", 1, "", true, true))).isSameInstanceAs(ch1)

        val first = server.takeRequest().requestUrl!!
        val second = server.takeRequest().requestUrl!!
        assertThat(first.encodedPath).isEqualTo("/v3/userdevices/v1/resources/pagelist")
        assertThat(first.queryParameter("filter")).isEqualTo("VTM")
        assertThat(first.queryParameter("offset")).isEqualTo("0")
        assertThat(second.queryParameter("offset")).isEqualTo("2")
    }

    @Test
    fun `pagination stops on an empty page even when hasNext lies`() = runTest {
        server.enqueue(json("""{"meta":{"code":200},"page":{"hasNext":true},"resourceInfos":[],"VTM":{}}"""))

        val list = repository.vtmPageList()

        assertThat(list.resources).isEmpty()
        assertThat(server.requestCount).isEqualTo(1)
    }

    private fun json(body: String) = MockResponse().setBody(body)
}
