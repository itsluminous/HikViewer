package com.itsluminous.hikviewer.core.stream

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.devices.VtmRelay
import com.itsluminous.hikviewer.core.devices.VtmResource
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class VtmProtocolTest {
    @Nested
    inner class Url {
        private val resource =
            VtmResource(
                deviceSerial = "FAKE0000001",
                channelNo = 3,
                resourceId = "R",
                name = "cam",
                streamBizUrl = "?biz=1&env=prod&stream=1",
                relay = VtmRelay("yducvtm.ezvizlife.com", 10554),
            )

        @Test
        fun `build puts biz params first and our selection last`() {
            val url = VtmUrl.build(resource, StreamQuality.SUB, "tok en", timestampMillis = 1700000000000)

            assertThat(url).isEqualTo(
                "ysproto://yducvtm.ezvizlife.com:10554/live?biz=1&env=prod&stream=2&dev=FAKE0000001&chn=3" +
                    "&cln=9&isp=0&auth=1&ssn=tok+en&vip=0&timestamp=1700000000000",
            )
        }

        @Test
        fun `main stream type is 1 and ipv6 hosts are bracketed`() {
            val v6 = resource.copy(relay = VtmRelay("2001:db8::1", 10554), streamBizUrl = "")

            val url = VtmUrl.build(v6, StreamQuality.MAIN, "t", timestampMillis = 1)

            assertThat(url).startsWith("ysproto://[2001:db8::1]:10554/live?dev=FAKE0000001&chn=3&stream=1&")
            assertThat(VtmUrl.endpoint(url)).isEqualTo("2001:db8::1" to 10554)
        }

        @Test
        fun `endpoint parses host and port`() {
            assertThat(VtmUrl.endpoint("ysproto://1.2.3.4:8554/live?x=1")).isEqualTo("1.2.3.4" to 8554)
            assertThat(VtmUrl.endpoint("ysproto://relay.ezvizlife.com:10554")).isEqualTo("relay.ezvizlife.com" to 10554)
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "rtsp://a:1/live", "ysproto://host/live", "ysproto://:10554/live", "ysproto://h:0/", "ysproto://h:x/",
            ],
        )
        fun `endpoint rejects bad urls`(url: String) {
            assertThrows<VtmException> { VtmUrl.endpoint(url) }
        }
    }

    @Nested
    inner class Frame {
        @Test
        fun `encode decode round trip`() {
            val frame = VtmFrame(VtmChannel.STREAM, 0xBEEF, VtmMessageCode.STREAMINFO_RSP, byteArrayOf(1, 2, 3))

            val bytes = frame.encode()

            assertThat(bytes.copyOf(8)).isEqualTo(
                byteArrayOf(0x24, 0x01, 0x00, 0x03, 0xBE.toByte(), 0xEF.toByte(), 0x01, 0x3C),
            )
            assertThat(VtmFrame.decode(bytes)).isEqualTo(frame)
            assertThat(VtmFrame.decode(bytes).hashCode()).isEqualTo(frame.hashCode())
            assertThat(frame.isStream).isTrue()
            assertThat(frame.copy(channel = VtmChannel.MESSAGE).isStream).isFalse()
        }

        @Test
        fun `malformed frames are rejected with a reason`() {
            val good = VtmFrame(VtmChannel.MESSAGE, 1, 2, byteArrayOf(9)).encode()

            assertThat(assertThrows<VtmException> { VtmFrame.decodeHeader(good.copyOf(7)) }.message).contains("8 bytes")
            assertThat(assertThrows<VtmException> { VtmFrame.decode(byteArrayOf(0x24)) }.message).contains("shorter")
            assertThat(
                assertThrows<VtmException> { VtmFrame.decode(good.also { it[0] = 0x25 }) }.message,
            ).contains("magic")
            val badChannel = VtmFrame(VtmChannel.MESSAGE, 1, 2, byteArrayOf(9)).encode().also { it[1] = 0x05 }
            assertThat(assertThrows<VtmException> { VtmFrame.decode(badChannel) }.message).contains("channel")
            val truncated = VtmFrame(VtmChannel.MESSAGE, 1, 2, byteArrayOf(9, 9)).encode().copyOf(9)
            assertThat(assertThrows<VtmException> { VtmFrame.decode(truncated) }.message).contains("mismatch")
            assertThrows<VtmException> { VtmFrame(0, 0, 0, ByteArray(0x10000)).encode() }
        }
    }

    @Nested
    inner class Messages {
        @Test
        fun `stream info request matches the reference encoding`() {
            // field 1 = url, field 3 = client version, field 4 = varint 0, field 6 = client version
            val url = "ysproto://h:1/live?dev=X"
            val bytes = VtmMessages.streamInfoRequest(url)

            val expected =
                byteArrayOf(0x0A, url.length.toByte()) + url.toByteArray() +
                    byteArrayOf(0x1A, 15) + "v3.6.3.20221124".toByteArray() +
                    byteArrayOf(0x20, 0x00) +
                    byteArrayOf(0x32, 15) + "v3.6.3.20221124".toByteArray()
            assertThat(bytes).isEqualTo(expected)
        }

        @Test
        fun `stream info request carries the redirect key in field 2`() {
            val bytes = VtmMessages.streamInfoRequest("ysproto://h:1/", vtmStreamKey = "KEY")

            assertThat(bytes.toList()).containsAtLeast(0x12.toByte(), 3.toByte(), 'K'.code.toByte()).inOrder()
        }

        @Test
        fun `keepalive is field 1 with the session`() {
            assertThat(
                VtmMessages.keepaliveRequest("ab"),
            ).isEqualTo(byteArrayOf(0x0A, 2, 'a'.code.toByte(), 'b'.code.toByte()))
        }

        @Test
        fun `parse stream info response`() {
            val body =
                varint(1, 0) + varint(2, 7) + str(3, "head") + str(4, "ssn-1") + str(5, "key") +
                    str(7, "ysproto://other:10554/live") + str(9, "aesmd5") + str(12, "pds") + varint(99, 1)

            val info = VtmMessages.parseStreamInfoResponse(body)

            assertThat(info.result).isEqualTo(0)
            assertThat(info.dataKey).isEqualTo(7)
            assertThat(info.streamHead).isEqualTo("head")
            assertThat(info.streamSession).isEqualTo("ssn-1")
            assertThat(info.vtmStreamKey).isEqualTo("key")
            assertThat(info.redirectUrl).isEqualTo("ysproto://other:10554/live")
            assertThat(info.aesMd5).isEqualTo("aesmd5")
            assertThat(info.isRedirect).isFalse()
            assertThat(info.isNoStream).isFalse()
            assertThat(info.copy(result = 1).isRedirect).isTrue()
            assertThat(info.copy(result = 6106).isNoStream).isTrue()
            assertThat(info.isBusy).isFalse()
            assertThat(info.copy(result = 6103).isBusy).isTrue()
        }

        @Test
        fun `the playback end channel is a known channel`() {
            assertThat(VtmChannel.isKnown(VtmChannel.PLAYBACK_END)).isTrue()
            val marker = VtmFrame(VtmChannel.PLAYBACK_END, 1, 0, byteArrayOf(9))
            assertThat(VtmFrame.decode(marker.encode()).isStream).isFalse()
            assertThat(VtmChannel.isKnown(0x03)).isFalse()
        }

        @Test
        fun `multi-byte varints and empty bodies parse`() {
            val info = VtmMessages.parseStreamInfoResponse(varint(1, 6106))
            assertThat(info.result).isEqualTo(6106)
            assertThat(VtmMessages.parseStreamInfoResponse(ByteArray(0)).result).isNull()
        }

        @Test
        fun `malformed protobuf is rejected`() {
            assertThrows<VtmException> { VtmMessages.parseStreamInfoResponse(byteArrayOf(0x0A, 0x7F, 1)) }
            assertThrows<VtmException> { VtmMessages.parseStreamInfoResponse(byteArrayOf(0x0D, 1, 2, 3, 4)) }
            assertThrows<VtmException> { VtmMessages.parseStreamInfoResponse(byteArrayOf(0x80.toByte())) }
            val tooLong = ByteArray(11) { 0x80.toByte() }
            assertThrows<VtmException> { VtmMessages.parseStreamInfoResponse(tooLong) }
        }
    }

    companion object {
        private fun raw(value: Long): ByteArray {
            val out = mutableListOf<Byte>()
            var v = value
            do {
                val chunk = (v and 0x7F).toInt()
                v = v ushr 7
                out += (if (v != 0L) chunk or 0x80 else chunk).toByte()
            } while (v != 0L)
            return out.toByteArray()
        }

        fun varint(
            field: Int,
            value: Long,
        ): ByteArray = raw((field shl 3).toLong()) + raw(value)

        fun str(
            field: Int,
            value: String,
        ): ByteArray = raw(((field shl 3) or 2).toLong()) + raw(value.length.toLong()) + value.toByteArray()
    }
}
