package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.VideoCodec
import org.junit.Test

class KeyframeGateTest {
    private fun unit(
        key: Boolean,
        pts: Long = 0L,
    ) = AccessUnit(VideoCodec.H265, ByteArray(4), pts, isKeyframe = key, config = null)

    @Test
    fun `drops everything until the first keyframe, then admits the GOP`() {
        val gate = KeyframeGate()
        assertThat(gate.isAwaiting).isTrue()
        assertThat(gate.admits(unit(false))).isFalse()
        assertThat(gate.admits(unit(false))).isFalse()
        assertThat(gate.admits(unit(true))).isTrue()
        assertThat(gate.isAwaiting).isFalse()
        assertThat(gate.admits(unit(false))).isTrue()
        assertThat(gate.dropped).isEqualTo(2)
        assertThat(gate.keyframes).isEqualTo(1)
    }

    @Test
    fun `await re-arms the gate after a flush, so a P-frame fed after a seek never reaches the codec`() {
        val gate = KeyframeGate()
        gate.admits(unit(true))
        gate.admits(unit(false))
        gate.await()
        assertThat(gate.admits(unit(false))).isFalse()
        assertThat(gate.admits(unit(false))).isFalse()
        assertThat(gate.admits(unit(true))).isTrue()
        assertThat(gate.admits(unit(false))).isTrue()
        assertThat(gate.dropped).isEqualTo(2)
    }

    @Test
    fun `keyframes only admits every keyframe and nothing else, in the order they come`() {
        val gate = KeyframeGate().apply { keyframesOnly = true }
        val pictures = listOf(unit(true, 30), unit(false, 31), unit(false, 32), unit(true, 10), unit(false, 11))
        val admitted = pictures.filter(gate::admits)
        assertThat(admitted.map { it.presentationTimeUs }).containsExactly(30L, 10L).inOrder()
        assertThat(gate.dropped).isEqualTo(3)
    }

    @Test
    fun `leaving keyframes only waits for a keyframe before admitting P-frames again`() {
        val gate = KeyframeGate().apply { keyframesOnly = true }
        gate.admits(unit(true))
        gate.keyframesOnly = false
        assertThat(gate.isAwaiting).isTrue()
        assertThat(gate.admits(unit(false))).isFalse()
        assertThat(gate.admits(unit(true))).isTrue()
        assertThat(gate.admits(unit(false))).isTrue()
    }

    @Test
    fun `describe names the state and the counters`() {
        val gate = KeyframeGate()
        gate.admits(unit(false))
        assertThat(gate.describe()).isEqualTo("gate=awaiting keyframes=0 gateDropped=1")
        gate.keyframesOnly = true
        gate.admits(unit(true))
        assertThat(gate.describe()).isEqualTo("gate=open/keyframesOnly keyframes=1 gateDropped=1")
    }
}
