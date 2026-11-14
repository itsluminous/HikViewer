package com.itsluminous.hikviewer.ui.playback

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.playback.RecorderClock
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import java.util.TimeZone
import android.icu.text.SimpleDateFormat as IcuDateFormat
import android.icu.util.TimeZone as IcuTimeZone

/** The two lines under the bar, for a phone in Pacific time looking at a recorder on Indian time. */
@RunWith(AndroidJUnit4::class)
class RecorderTimeLabelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val timeLabel = TextView(context)
    private val localLabel = TextView(context)
    private val recorderTime = LocalDateTime.of(2026, 11, 14, 21, 37, 43)
    private val ist = Duration.ofMinutes(13 * 60 + 30)
    private val pacific = TimeZone.getTimeZone("America/Los_Angeles")
    private lateinit var previousZone: TimeZone

    @Before
    fun setUp() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(pacific)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(previousZone)
    }

    @Test
    fun `with no offset line 1 is the plain time and line 2 is gone`() {
        RecorderTimeLabel.bind(timeLabel, localLabel, recorderTime, RecorderClock(Duration.ZERO))
        assertThat(timeLabel.text.toString()).isEqualTo("21:37:43")
        assertThat(localLabel.visibility).isEqualTo(View.GONE)
        assertThat(localLabel.text.toString()).isEmpty()
        assertThat(RecorderTimeLabel.localLine(context.resources, recorderTime, RecorderClock(Duration.ZERO))).isNull()
    }

    @Test
    fun `with the recorder 13 h 30 ahead line 2 gives the device time, the zone and the offset`() {
        val zone = RecorderTimeLabel.zoneName(pacific)
        RecorderTimeLabel.bind(timeLabel, localLabel, recorderTime, RecorderClock(ist))
        assertThat(timeLabel.text.toString()).isEqualTo("21:37:43")
        assertThat(localLabel.visibility).isEqualTo(View.VISIBLE)
        assertThat(localLabel.text.toString()).isEqualTo("Local time  08:07 $zone (+13:30)")
    }

    @Test
    fun `the zone name is ICU's short generic name of the default zone`() {
        val expected = IcuDateFormat("v", Locale.getDefault()).apply { timeZone = IcuTimeZone.getTimeZone(pacific.id) }
        assertThat(RecorderTimeLabel.zoneName()).isNotEmpty()
        assertThat(RecorderTimeLabel.zoneName()).isEqualTo(expected.format(java.util.Date()))
        assertThat(RecorderTimeLabel.zoneName(pacific, Locale.US)).isEqualTo("PT")
    }

    @Test
    fun `a recorder behind the device formats a negative offset and a later device time`() {
        val behind = RecorderClock(Duration.ofMinutes(-(3 * 60 + 45)))
        val line = RecorderTimeLabel.localLine(context.resources, recorderTime, behind, pacific)
        assertThat(line).isEqualTo("Local time  01:22 ${RecorderTimeLabel.zoneName(pacific)} (-03:45)")
    }

    @Test
    fun `line 2 crosses midnight with the device time, the date being line 1's business`() {
        val line =
            RecorderTimeLabel.localLine(
                context.resources,
                LocalDateTime.of(2026, 11, 15, 0, 30),
                RecorderClock(ist),
            )
        assertThat(line).startsWith("Local time  11:00 ")
    }
}
