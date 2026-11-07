package com.itsluminous.hikviewer.ui.settings

import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class QrActivityTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    @Config(qualifiers = "television")
    fun `donate shows the UPI code on black with the UPI id and the hint under it, no caption`() {
        ActivityScenario.launch<QrActivity>(QrActivity.donate(context)).use { scenario ->
            scenario.onActivity { a ->
                val window = android.util.TypedValue()
                a.theme.resolveAttribute(android.R.attr.windowBackground, window, true)
                assertThat(window.data).isEqualTo(Color.BLACK)
                assertThat(a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).background).isNull()
                val qr = a.findViewById<ImageView>(R.id.qr)
                assertThat(shadowOf(qr.drawable).createdFromResId).isEqualTo(R.drawable.upi_qr)
                assertThat(qr.scaleType).isEqualTo(ImageView.ScaleType.FIT_CENTER)
                assertThat(qr.contentDescription.toString()).isEqualTo("UPI QR code for electricprakash@axisb")
                assertThat(a.findViewById<TextView>(R.id.caption).visibility).isEqualTo(View.GONE)
                val text = a.findViewById<TextView>(R.id.text)
                assertThat(text.visibility).isEqualTo(View.VISIBLE)
                assertThat(text.text.toString()).isEqualTo("electricprakash@axisb")
                assertThat(a.findViewById<TextView>(R.id.qr_hint).text.toString()).isEqualTo("Press Back to return")
                assertThat(a.supportActionBar).isNull()
                assertThat(a.isFinishing).isFalse()
            }
        }
    }

    @Test
    @Config(qualifiers = "television")
    fun `source shows the GitHub code with the full address above it and nothing under it`() {
        ActivityScenario.launch<QrActivity>(QrActivity.source(context)).use { scenario ->
            scenario.onActivity { a ->
                val qr = a.findViewById<ImageView>(R.id.qr)
                assertThat(shadowOf(qr.drawable).createdFromResId).isEqualTo(R.drawable.github_qr)
                assertThat(qr.contentDescription.toString()).contains("github.com/itsluminous/HikViewer")
                val caption = a.findViewById<TextView>(R.id.caption)
                assertThat(caption.visibility).isEqualTo(View.VISIBLE)
                assertThat(caption.text.toString()).isEqualTo("https://github.com/itsluminous/HikViewer")
                // Above the code: the caption's bottom is at or above the image's top.
                val column = caption.parent as ViewGroup
                assertThat(column.indexOfChild(caption)).isLessThan(column.indexOfChild(qr))
                assertThat(a.findViewById<TextView>(R.id.text).visibility).isEqualTo(View.GONE)
                assertThat(a.findViewById<TextView>(R.id.qr_hint).visibility).isEqualTo(View.VISIBLE)
            }
        }
    }

    /**
     * The GitHub code is the user's 300 x 300 image stored losslessly (a VP8L WebP), never
     * downscaled or lossy-compressed, so it stays scannable. Robolectric does not decode bitmaps,
     * so the file's own header is read: `RIFF....WEBPVP8L`, a 0x2F signature, then width - 1 and
     * height - 1 as 14-bit fields.
     */
    @Test
    fun `the GitHub QR is a lossless WebP of 300 x 300`() {
        val bytes = java.io.File("src/main/res/drawable-nodpi/github_qr.webp").readBytes()
        assertThat(String(bytes, 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(String(bytes, 8, 8, Charsets.US_ASCII)).isEqualTo("WEBPVP8L")
        assertThat(bytes[20].toInt() and 0xFF).isEqualTo(0x2F)
        val bits = (0 until 4).fold(0L) { acc, i -> acc or ((bytes[21 + i].toLong() and 0xFF) shl (8 * i)) }
        val width = (bits and 0x3FFF).toInt() + 1
        val height = ((bits shr 14) and 0x3FFF).toInt() + 1
        assertThat(width to height).isEqualTo(300 to 300)
    }

    @Test
    @Config(qualifiers = "television")
    fun `Back finishes the screen`() {
        ActivityScenario.launch<QrActivity>(QrActivity.donate(context)).use { scenario ->
            scenario.onActivity { a ->
                a.onBackPressedDispatcher.onBackPressed()
                assertThat(a.isFinishing).isTrue()
            }
        }
    }

    @Test
    @Config(qualifiers = "television")
    fun `OK and any other key finish the screen, a key repeat does not`() {
        ActivityScenario.launch<QrActivity>(QrActivity.source(context)).use { scenario ->
            scenario.onActivity { a ->
                val repeat = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1)
                assertThat(a.dispatchKeyEvent(repeat)).isTrue()
                assertThat(a.isFinishing).isFalse()
                val ok = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
                assertThat(a.dispatchKeyEvent(ok)).isTrue()
                assertThat(a.isFinishing).isTrue()
            }
        }
        ActivityScenario.launch<QrActivity>(QrActivity.donate(context)).use { scenario ->
            scenario.onActivity { a ->
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                assertThat(a.isFinishing).isTrue()
            }
        }
    }
}
