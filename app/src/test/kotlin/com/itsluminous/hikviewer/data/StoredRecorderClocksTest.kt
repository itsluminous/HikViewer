package com.itsluminous.hikviewer.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.playback.RecordSegment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class StoredRecorderClocksTest {
    private val prefs =
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getSharedPreferences("t", 0)
            .also { it.edit().clear().commit() }
    private val settings = SettingsStore(prefs)

    /** A phone on Pacific time, a recorder on Indian time (+13:30). */
    private var deviceNow = LocalDateTime.of(2026, 11, 12, 6, 43, 10)
    private val ist = Duration.ofMinutes(13 * 60 + 30)
    private val clocks = StoredRecorderClocks(settings) { deviceNow }
    private val today = LocalDate.of(2026, 11, 12)

    private fun footage(
        day: LocalDate,
        end: LocalDateTime,
    ) = listOf(RecordSegment(day.atTime(6, 0), end, RecordSegment.TYPE_TIMING))

    @Test
    fun `unknown recorders run on device time`() {
        val clock = clocks.clock("DVR")
        assertThat(clock.offset).isEqualTo(Duration.ZERO)
        assertThat(clock.now()).isEqualTo(deviceNow)
        assertThat(clock.isShifted).isFalse()
    }

    @Test
    fun `the newest footage of the recorder's today teaches the offset, which is persisted and logged`() {
        val clock = clocks.learn("DVR", today, footage(today, deviceNow.plus(ist).minusMinutes(12)))
        assertThat(clock.offset).isEqualTo(ist)
        assertThat(clock.now()).isEqualTo(deviceNow.plus(ist))
        assertThat(settings.recorderOffsetMinutes("DVR")).isEqualTo(810)
        assertThat(StoredRecorderClocks(settings) { deviceNow }.clock("DVR").offset).isEqualTo(ist)
        assertThat(clocks.clock("OTHER").offset).isEqualTo(Duration.ZERO)
        val line = ShadowLog.getLogsForTag("RecorderClocks").last().msg
        assertThat(line).startsWith("recorder offset +13:30 from segment end 2026-11-12T20:01:10 on 2026-11-12")
        assertThat(line).contains("was unknown")
    }

    @Test
    fun `a day recorded to midnight raises the offset to that midnight and moves today forward`() {
        deviceNow = LocalDateTime.of(2026, 11, 12, 11, 0, 0) // 00:30 on the 13th for the recorder
        val clock = clocks.learn("DVR", today, footage(today, today.plusDays(1).atStartOfDay()))
        assertThat(clock.offset).isEqualTo(Duration.ofHours(13))
        assertThat(clock.today()).isEqualTo(today.plusDays(1))
        // The next day's newest footage then gives the exact value.
        val tomorrow = today.plusDays(1)
        val exact = clocks.learn("DVR", tomorrow, footage(tomorrow, deviceNow.plus(ist).minusMinutes(4)))
        assertThat(exact.offset).isEqualTo(ist)
    }

    @Test
    fun `browsing yesterday never lowers a known offset`() {
        clocks.learn("DVR", today, footage(today, deviceNow.plus(ist).minusMinutes(2)))
        val yesterday = today.minusDays(1)
        // Yesterday ran to midnight (bound 17:15, below the known 13:30: untouched) ...
        assertThat(clocks.learn("DVR", yesterday, footage(yesterday, today.atStartOfDay())).offset)
            .isEqualTo(ist)
        // ... and a day that stopped early is history, not the recorder's now.
        assertThat(clocks.learn("DVR", yesterday, footage(yesterday, yesterday.atTime(23, 58))).offset)
            .isEqualTo(ist)
        assertThat(settings.recorderOffsetMinutes("DVR")).isEqualTo(810)
    }

    @Test
    fun `the day before today teaches when today was found empty, also negative offsets`() {
        // A phone in New Zealand: the recorder is at 19:30 yesterday.
        deviceNow = LocalDateTime.of(2026, 11, 12, 3, 0, 0)
        val nzst = Duration.ofMinutes(-(7 * 60 + 30))
        val yesterday = today.minusDays(1)
        val clock =
            clocks.learn(
                "DVR",
                yesterday,
                footage(yesterday, deviceNow.plus(nzst).minusMinutes(9)),
                todayWasEmpty = true,
            )
        assertThat(clock.offset).isEqualTo(nzst)
        assertThat(clock.today()).isEqualTo(yesterday)
        assertThat(settings.recorderOffsetMinutes("DVR")).isEqualTo(-450)
    }

    @Test
    fun `an empty day, or a day far from the device's date, teaches nothing`() {
        assertThat(clocks.learn("DVR", today, emptyList()).offset).isEqualTo(Duration.ZERO)
        val lastWeek = today.minusDays(7)
        assertThat(clocks.learn("DVR", lastWeek, footage(lastWeek, lastWeek.atTime(20, 0))).offset)
            .isEqualTo(Duration.ZERO)
        assertThat(settings.recorderOffsetMinutes("DVR")).isNull()
    }

    @Test
    fun `a zero offset is learnt and kept when the clocks agree`() {
        val clock = clocks.learn("DVR", today, footage(today, deviceNow.minusMinutes(5)))
        assertThat(clock.offset).isEqualTo(Duration.ZERO)
        assertThat(clock.isShifted).isFalse()
        assertThat(settings.recorderOffsetMinutes("DVR")).isEqualTo(0)
    }
}
