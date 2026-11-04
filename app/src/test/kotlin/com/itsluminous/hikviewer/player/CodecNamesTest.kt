package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CodecNamesTest {
    @Test
    fun `software decoder names`() {
        assertThat(CodecNames.isSoftwareName("OMX.google.h264.decoder")).isTrue()
        assertThat(CodecNames.isSoftwareName("c2.android.avc.decoder")).isTrue()
        assertThat(CodecNames.isSoftwareName("OMX.MS.AVC.Decoder")).isFalse()
        assertThat(CodecNames.isSoftwareName("c2.goldfish.h264.decoder")).isFalse()
    }
}
