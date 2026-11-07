package com.itsluminous.hikviewer.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.databinding.ActivityQrBinding

/**
 * A QR code fullscreen on black, scaled to fit the screen with its aspect kept, with an optional
 * caption above it and a text under it, and a "Press Back to return" hint. A TV can open neither
 * a browser nor a UPI app, so this is how About's two buttons work there: the user points a phone
 * at the screen, or types the text. [donate] shows the UPI code with the UPI id under it; [source]
 * shows the GitHub code with the address above it. A phone without a UPI app gets [donate] too.
 * Any key (Back, OK, a D-pad arrow) finishes; the system bars are hidden on a phone so the picture
 * is as large as the display allows.
 */
class QrActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityQrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.qr.setImageResource(intent.getIntExtra(EXTRA_IMAGE, R.drawable.upi_qr))
        binding.qr.contentDescription = getString(intent.getIntExtra(EXTRA_DESCRIPTION, R.string.donate_qr_description))
        bindText(binding.caption, intent.getIntExtra(EXTRA_CAPTION, 0))
        bindText(binding.text, intent.getIntExtra(EXTRA_TEXT, 0))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun bindText(
        view: android.widget.TextView,
        @StringRes text: Int,
    ) {
        if (text == 0) {
            view.visibility = View.GONE
        } else {
            view.setText(text)
            view.visibility = View.VISIBLE
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

    /** The two screens About opens; the extras are resource ids, so the strings stay translatable. */
    companion object {
        /** Int: drawable of the QR code. */
        const val EXTRA_IMAGE: String = "image"

        /** Int: string for the image's content description. */
        const val EXTRA_DESCRIPTION: String = "description"

        /** Int: string shown above the code, 0 for none. */
        const val EXTRA_CAPTION: String = "caption"

        /** Int: string shown under the code, 0 for none. */
        const val EXTRA_TEXT: String = "text"

        /** The UPI QR code with the UPI id under it, for people who prefer typing. */
        fun donate(context: Context): Intent = intent(
            context,
            R.drawable.upi_qr,
            R.string.donate_qr_description,
            text = R.string.upi_id,
        )

        /** The GitHub QR code with the full address above it, to read off the screen. */
        fun source(context: Context): Intent = intent(
            context,
            R.drawable.github_qr,
            R.string.source_qr_description,
            caption = R.string.settings_source_url,
        )

        private fun intent(
            context: Context,
            @DrawableRes image: Int,
            @StringRes description: Int,
            @StringRes caption: Int = 0,
            @StringRes text: Int = 0,
        ): Intent = Intent(context, QrActivity::class.java)
            .putExtra(EXTRA_IMAGE, image)
            .putExtra(EXTRA_DESCRIPTION, description)
            .putExtra(EXTRA_CAPTION, caption)
            .putExtra(EXTRA_TEXT, text)
    }
}
