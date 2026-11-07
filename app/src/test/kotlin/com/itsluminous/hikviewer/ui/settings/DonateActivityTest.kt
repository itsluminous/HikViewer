package com.itsluminous.hikviewer.ui.settings

import android.graphics.Color
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class DonateActivityTest {
    @Test
    @Config(qualifiers = "television")
    fun `shows the QR code on black with the UPI id and the hint under it`() {
        ActivityScenario.launch(DonateActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val window = android.util.TypedValue()
                a.theme.resolveAttribute(android.R.attr.windowBackground, window, true)
                assertThat(window.data).isEqualTo(Color.BLACK)
                assertThat(a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).background).isNull()
                val qr = a.findViewById<ImageView>(R.id.qr)
                assertThat(qr.drawable).isNotNull()
                assertThat(qr.scaleType).isEqualTo(ImageView.ScaleType.FIT_CENTER)
                assertThat(qr.contentDescription.toString()).isEqualTo("UPI QR code for electricprakash@axisb")
                assertThat(a.findViewById<TextView>(R.id.upi_id).text.toString()).isEqualTo("electricprakash@axisb")
                assertThat(a.findViewById<TextView>(R.id.donate_hint).text.toString()).isEqualTo("Press Back to return")
                assertThat(a.supportActionBar).isNull()
                assertThat(a.isFinishing).isFalse()
            }
        }
    }

    @Test
    @Config(qualifiers = "television")
    fun `Back finishes the screen`() {
        ActivityScenario.launch(DonateActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.onBackPressedDispatcher.onBackPressed()
                assertThat(a.isFinishing).isTrue()
            }
        }
    }

    @Test
    @Config(qualifiers = "television")
    fun `OK and any other key finish the screen, a key repeat does not`() {
        ActivityScenario.launch(DonateActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val repeat = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1)
                assertThat(a.dispatchKeyEvent(repeat)).isTrue()
                assertThat(a.isFinishing).isFalse()
                val ok = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
                assertThat(a.dispatchKeyEvent(ok)).isTrue()
                assertThat(a.isFinishing).isTrue()
            }
        }
        ActivityScenario.launch(DonateActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                assertThat(a.isFinishing).isTrue()
            }
        }
    }
}
