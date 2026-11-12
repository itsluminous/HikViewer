package com.itsluminous.hikviewer.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class RecorderClockTest {
    /** A phone in Pacific time looking at a recorder on Indian time: 13 h 30 ahead. */
    private val deviceNow = LocalDateTime.of(2026, 11, 12, 6, 43, 10)
    private val ist = Duration.ofMinutes(13 * 60 + 30)

    @Test
    fun `offset is the newest segment end minus device now, rounded to the quarter hour`() {
        val recorderNow = deviceNow.plus(ist) // 20:13:10
        val newestEnd = recorderNow.minusMinutes(3) // the cloud index lags a little
        assertThat(RecorderClock.estimateOffset(newestEnd, deviceNow)).isEqualTo(ist)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 5, 7, 8, 12, 14])
    fun `an index lag anywhere up to fourteen minutes is absorbed`(lagMinutes: Int) {
        val newestEnd = deviceNow.plus(ist).minusMinutes(lagMinutes.toLong())
        assertThat(RecorderClock.estimateOffset(newestEnd, deviceNow)).isEqualTo(ist)
    }

    @Test
    fun `a negative offset works the same way`() {
        val nzst = Duration.ofMinutes(-(7 * 60 + 30)) // a phone in New Zealand, recorder in India
        val newestEnd = deviceNow.plus(nzst).minusMinutes(9)
        assertThat(RecorderClock.estimateOffset(newestEnd, deviceNow)).isEqualTo(nzst)
    }

    @Test
    fun `zero offset when the clocks agree`() {
        assertThat(RecorderClock.estimateOffset(deviceNow.minusMinutes(4), deviceNow)).isEqualTo(Duration.ZERO)
        assertThat(RecorderClock(Duration.ZERO).isShifted).isFalse()
    }

    @Test
    fun `the recorder may already be on the next day`() {
        val lateDevice = LocalDateTime.of(2026, 11, 12, 11, 0, 0) // 00:30 the next day on the recorder
        val newestEnd = LocalDateTime.of(2026, 11, 13, 0, 22, 0)
        val clock = RecorderClock(RecorderClock.estimateOffset(newestEnd, lateDevice)) { lateDevice }
        assertThat(clock.offset).isEqualTo(ist)
        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 11, 13))
        assertThat(clock.now()).isEqualTo(LocalDateTime.of(2026, 11, 13, 0, 30, 0))
    }

    @Test
    fun `a day recorded to its end puts the recorder at least at that midnight`() {
        val lateDevice = LocalDateTime.of(2026, 11, 12, 11, 0, 0)
        val dayEnd = LocalDate.of(2026, 11, 13).atStartOfDay()
        // 13:00 exactly; the recorder is at or past 00:00 on the 13th.
        assertThat(RecorderClock.minimumOffset(dayEnd, lateDevice)).isEqualTo(Duration.ofHours(13))
        // Three minutes past the hour rounds up, never down into the finished day.
        assertThat(RecorderClock.minimumOffset(dayEnd, lateDevice.minusMinutes(3)))
            .isEqualTo(Duration.ofMinutes(13 * 60 + 15))
    }

    @Test
    fun `translation goes both ways`() {
        val clock = RecorderClock(ist) { deviceNow }
        val recorder = clock.toRecorder(deviceNow)
        assertThat(recorder).isEqualTo(LocalDateTime.of(2026, 11, 12, 20, 13, 10))
        assertThat(clock.toDevice(recorder)).isEqualTo(deviceNow)
        assertThat(clock.now()).isEqualTo(recorder)
        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 11, 12))
        assertThat(clock.isShifted).isTrue()
    }

    @Test
    fun `offset labels`() {
        assertThat(RecorderClock(ist).offsetLabel()).isEqualTo("+13:30")
        assertThat(RecorderClock(Duration.ofMinutes(-(3 * 60 + 45))).offsetLabel()).isEqualTo("-03:45")
        assertThat(RecorderClock(Duration.ZERO).offsetLabel()).isEqualTo("+00:00")
    }

    @Test
    fun `rounding helpers`() {
        assertThat(RecorderClock.roundToStep(Duration.ofMinutes(7))).isEqualTo(Duration.ZERO)
        assertThat(RecorderClock.roundToStep(Duration.ofMinutes(8))).isEqualTo(Duration.ofMinutes(15))
        assertThat(RecorderClock.roundToStep(Duration.ofMinutes(-8))).isEqualTo(Duration.ofMinutes(-15))
        assertThat(RecorderClock.roundToStep(Duration.ofMinutes(-7))).isEqualTo(Duration.ZERO)
        assertThat(RecorderClock.ceilToStep(Duration.ofMinutes(1))).isEqualTo(Duration.ofMinutes(15))
        assertThat(RecorderClock.ceilToStep(Duration.ofMinutes(15))).isEqualTo(Duration.ofMinutes(15))
        assertThat(RecorderClock.ceilToStep(Duration.ofMinutes(-16))).isEqualTo(Duration.ofMinutes(-15))
    }
}
