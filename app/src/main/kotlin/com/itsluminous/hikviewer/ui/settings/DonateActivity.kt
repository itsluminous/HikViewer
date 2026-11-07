package com.itsluminous.hikviewer.ui.settings

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.itsluminous.hikviewer.databinding.ActivityDonateBinding

/**
 * The "Donate via UPI" screen: the UPI QR code on black, scaled to fit the screen with its aspect
 * kept, the UPI id as text under it for people who prefer typing, and a "Press Back to return"
 * hint. A TV can open neither a browser nor a UPI app, so this is the whole donation path: the
 * user points a phone at the screen. Any key (Back, OK, a D-pad arrow) finishes; the system bars
 * are hidden on a phone so the picture is as large as the display allows.
 */
class DonateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityDonateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Every key returns to Settings: there is nothing to operate on this screen. */
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (event.repeatCount == 0) finish()
        return true
    }
}
