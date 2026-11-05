package com.itsluminous.hikviewer.ui.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeekControllerTest {
    private var now = 10_000L
    private val seek = SeekController(clock = { now })

    @Test
    fun `the step grows with hold time on the schedule`() {
        assertThat(seek.stepFor(0)).isEqualTo(10)
        assertThat(seek.stepFor(999)).isEqualTo(10)
        assertThat(seek.stepFor(1_000)).isEqualTo(30)
        assertThat(seek.stepFor(2_499)).isEqualTo(30)
        assertThat(seek.stepFor(2_500)).isEqualTo(60)
        assertThat(seek.stepFor(4_000)).isEqualTo(5 * 60)
        assertThat(seek.stepFor(5_999)).isEqualTo(5 * 60)
        assertThat(seek.stepFor(6_000)).isEqualTo(15 * 60)
        assertThat(seek.stepFor(60_000)).isEqualTo(15 * 60)
    }

    @Test
    fun `a held key accelerates from its first press and a release starts over`() {
        assertThat(seek.press()).isEqualTo(10) // key down
        assertThat(seek.isHolding).isTrue()
        now += 500
        assertThat(seek.press()).isEqualTo(10) // first auto-repeat
        now += 600 // held 1.1 s
        assertThat(seek.press()).isEqualTo(30)
        now += 1_500 // 2.6 s
        assertThat(seek.press()).isEqualTo(60)
        now += 1_500 // 4.1 s
        assertThat(seek.press()).isEqualTo(300)
        now += 2_000 // 6.1 s
        assertThat(seek.press()).isEqualTo(900)

        seek.release()
        assertThat(seek.isHolding).isFalse()
        now += 50
        assertThat(seek.press()).isEqualTo(10) // a new hold, however soon
        now += 1_200
        assertThat(seek.press()).isEqualTo(30)
    }

    @Test
    fun `the reopen is due 600 ms after the last input and every input pushes it back`() {
        assertThat(seek.dueIn()).isNull() // nothing pending
        seek.press()
        assertThat(seek.dueIn()).isEqualTo(600)
        now += 599
        assertThat(seek.dueIn()).isEqualTo(1)
        seek.input() // a drag sample 599 ms later: the quiet time starts again
        assertThat(seek.dueIn()).isEqualTo(600)
        now += 300
        seek.press()
        now += 599
        assertThat(seek.dueIn()).isEqualTo(1)
        now += 1
        assertThat(seek.dueIn()).isEqualTo(0)
        now += 5_000
        assertThat(seek.dueIn()).isEqualTo(0) // overdue is still "now", never negative
        seek.settle()
        assertThat(seek.dueIn()).isNull()
    }

    @Test
    fun `a release keeps the pending reopen but reset drops everything`() {
        seek.press()
        seek.release()
        assertThat(seek.dueIn()).isEqualTo(600) // the key is up; the stream still reopens after the quiet time
        seek.press()
        seek.reset()
        assertThat(seek.isHolding).isFalse()
        assertThat(seek.dueIn()).isNull()
    }

    @Test
    fun `the debounce and the schedule are configurable`() {
        val custom =
            SeekController(
                clock = { now },
                debounceMillis = 100,
                schedule = listOf(SeekController.Stage(0, 1), SeekController.Stage(200, 5)),
            )
        assertThat(custom.press()).isEqualTo(1)
        assertThat(custom.dueIn()).isEqualTo(100)
        now += 200
        assertThat(custom.press()).isEqualTo(5)
    }
}
