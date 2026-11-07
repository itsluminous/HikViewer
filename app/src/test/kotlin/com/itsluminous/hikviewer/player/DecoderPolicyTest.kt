package com.itsluminous.hikviewer.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.player.TileMode.HW_LIVE
import com.itsluminous.hikviewer.player.TileMode.KEYFRAME
import com.itsluminous.hikviewer.player.TileMode.SW_LIVE
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DecoderPolicyTest {
    @Test
    fun `hero gets hardware, the rest software in display order up to the limit`() {
        val modes = DecoderPolicy.assign(tileCount = 6, heroIndex = 0, liveLimit = 4)
        assertThat(modes).containsExactly(HW_LIVE, SW_LIVE, SW_LIVE, SW_LIVE, KEYFRAME, KEYFRAME).inOrder()
    }

    /** Settings → Live cameras = 12 on a 4 x 4 grid: the first twelve in arrangement order, the last four stills. */
    @Test
    fun `a user ceiling below the tile count makes exactly that many live, in arrangement order`() {
        val flat = DecoderPolicy.assign(tileCount = 16, heroIndex = null, liveLimit = 12)
        assertThat(flat.count { it.isLive }).isEqualTo(12)
        assertThat(flat.take(12).all { it == SW_LIVE }).isTrue()
        assertThat(flat.drop(12)).containsExactly(KEYFRAME, KEYFRAME, KEYFRAME, KEYFRAME).inOrder()
        // In a 1 + 15 layout the hero is the first of the twelve.
        val hero = DecoderPolicy.assign(tileCount = 16, heroIndex = 0, liveLimit = 12)
        assertThat(hero.first()).isEqualTo(HW_LIVE)
        assertThat(hero.count { it.isLive }).isEqualTo(12)
        assertThat(hero.drop(12).all { it == KEYFRAME }).isTrue()
    }

    /** A ceiling of 12 or 16 on a 12-tile grid: every tile is live, no still anywhere. */
    @Test
    fun `a user ceiling at or above the tile count leaves no stills`() {
        assertThat(DecoderPolicy.assign(tileCount = 12, heroIndex = null, liveLimit = 12).all { it.isLive }).isTrue()
        assertThat(DecoderPolicy.assign(tileCount = 12, heroIndex = 0, liveLimit = 16).all { it.isLive }).isTrue()
        assertThat(DecoderPolicy.assign(tileCount = 4, heroIndex = null, liveLimit = 4).all { it == SW_LIVE }).isTrue()
    }

    @Test
    fun `a hero anywhere is live first, even with a budget of one`() {
        val modes = DecoderPolicy.assign(tileCount = 12, heroIndex = 11, liveLimit = 1)
        assertThat(modes.count { it.isLive }).isEqualTo(1)
        assertThat(modes[11]).isEqualTo(HW_LIVE)
    }

    @Test
    fun `hardware decoders are capped`() {
        val modes = DecoderPolicy.assign(5, heroIndex = 3, liveLimit = 5, maxHardwareDecoders = 0)
        assertThat(modes[3]).isEqualTo(SW_LIVE)
        assertThat(modes.count { it.isLive }).isEqualTo(5)
    }

    @Test
    fun `a flat grid is software in display order and leaves the hardware slots idle`() {
        val modes = DecoderPolicy.assign(tileCount = 5, heroIndex = null, liveLimit = 3)
        assertThat(modes).containsExactly(SW_LIVE, SW_LIVE, SW_LIVE, KEYFRAME, KEYFRAME).inOrder()
        // Measured on the MiTV: a paced hardware tile is jerkier than a paced software one (see the KDoc).
        assertThat(DecoderPolicy.assign(tileCount = 12, heroIndex = null, liveLimit = 8).count { it == HW_LIVE })
            .isEqualTo(0)
    }

    @Test
    fun `out of range hero and empty grids are harmless`() {
        assertThat(DecoderPolicy.assign(0, 0, 8)).isEmpty()
        val modes = DecoderPolicy.assign(2, heroIndex = -1, liveLimit = 8)
        assertThat(modes).containsExactly(SW_LIVE, SW_LIVE).inOrder()
        assertThat(DecoderPolicy.assign(2, heroIndex = 9, liveLimit = 8)).containsExactly(SW_LIVE, SW_LIVE).inOrder()
    }

    @Test
    fun `limit above the count makes everything live`() {
        assertThat(DecoderPolicy.assign(3, 0, 16).all { it.isLive }).isTrue()
    }

    @Test
    fun `hardware only keeps the hero live and nothing else, whatever the budget`() {
        val modes = DecoderPolicy.assign(tileCount = 6, heroIndex = 2, liveLimit = 6, hardwareOnly = true)
        assertThat(modes).containsExactly(KEYFRAME, KEYFRAME, HW_LIVE, KEYFRAME, KEYFRAME, KEYFRAME).inOrder()
        val noHero = DecoderPolicy.assign(tileCount = 4, heroIndex = null, liveLimit = 4, hardwareOnly = true)
        assertThat(noHero.none { it.isLive }).isTrue()
    }

    /** Round 18: hardware is a preference. Whatever the code, a hardware failure is answered with software. */
    @Test
    fun `any hardware failure retries with software, a software failure is the tile's error`() {
        assertThat(DecoderPolicy.afterDecoderFailure(software = false))
            .isEqualTo(DecoderPolicy.FailureAction.RETRY_WITH_SOFTWARE)
        assertThat(DecoderPolicy.afterDecoderFailure(software = true, hardwareTried = false))
            .isEqualTo(DecoderPolicy.FailureAction.RETRY_WITH_HARDWARE)
        assertThat(DecoderPolicy.afterDecoderFailure(software = true))
            .isEqualTo(DecoderPolicy.FailureAction.TILE_ERROR)
    }

    @Test
    fun `hardware slots follow the codec's instance limit, clamped to one and two, two when unknown`() {
        assertThat(DecoderPolicy.hardwareSlots(1)).isEqualTo(1) // Amlogic 1 GB boxes
        assertThat(DecoderPolicy.hardwareSlots(2)).isEqualTo(2) // the MiTV's OMX.MS.AVC.Decoder
        assertThat(DecoderPolicy.hardwareSlots(null)).isEqualTo(2)
        assertThat(DecoderPolicy.hardwareSlots(0)).isEqualTo(1) // the hero still asks; a refusal falls back
        assertThat(DecoderPolicy.hardwareSlots(32)).isEqualTo(2) // the platform default when a codec does not say
        assertThat(DecoderPolicy.hardwareSlots(-1)).isEqualTo(1)
    }

    @Test
    fun `a slot count of one still gives the hero hardware and nobody else`() {
        val modes = DecoderPolicy.assign(tileCount = 4, heroIndex = 0, liveLimit = 4, maxHardwareDecoders = 1)
        assertThat(modes).containsExactly(HW_LIVE, SW_LIVE, SW_LIVE, SW_LIVE).inOrder()
    }

    @Test
    fun `a failure is described with the codec name, code, flags and diagnostic, or the exception`() {
        val codec = codecException(1100, transient = true, diagnosticInfo = "android.media.MediaCodec.error_1100")
        assertThat(DecoderPolicy.describeFailure("OMX.MS.AVC.Decoder", codec))
            .isEqualTo(
                "codec=OMX.MS.AVC.Decoder error=1100 transient=true recoverable=false " +
                    "diag=android.media.MediaCodec.error_1100",
            )
        assertThat(
            DecoderPolicy.describeFailure("c2.android.avc.decoder", java.io.IOException("Failed to create codec")),
        ).isEqualTo("codec=c2.android.avc.decoder error=IOException: Failed to create codec")
        assertThat(DecoderPolicy.describeFailure("x", IllegalStateException()))
            .isEqualTo("codec=x error=IllegalStateException: null")
    }
}
