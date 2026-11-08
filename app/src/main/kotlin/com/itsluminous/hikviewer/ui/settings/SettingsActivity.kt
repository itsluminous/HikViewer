package com.itsluminous.hikviewer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.devices.VerificationCode
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.data.AccountRepository
import com.itsluminous.hikviewer.data.GridArrangementStore
import com.itsluminous.hikviewer.data.SettingsStore
import com.itsluminous.hikviewer.data.backup.BackupCodec
import com.itsluminous.hikviewer.databinding.ActivitySettingsBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.common.KeepScreenOn
import com.itsluminous.hikviewer.ui.common.LambdaViewModelFactory
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import com.itsluminous.hikviewer.ui.grid.VerificationCodeDialog
import com.itsluminous.hikviewer.ui.login.LoginActivity
import kotlinx.coroutines.launch

/**
 * Settings, top to bottom: grid layout, tile overlays, fullscreen quality, screen (phones),
 * backup, restore, visible cameras and order, encryption password, about (version, View Source,
 * Donate), logout. There is no hero setting: the hero is whatever sits at position 0 of a 1 + N layout,
 * chosen on the grid with Move or Change. Every change is written to [SettingsStore] immediately;
 * the grid re-reads the store when this screen returns. The camera list is a `RecyclerView` of
 * rows (at most a few dozen cameras, all laid out) reordered with the D-pad on a TV and a drag
 * handle on a phone ([CameraRowAdapter]); the rows belong to the [SettingsViewModel] so a
 * rotation keeps them.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsStore
    private lateinit var arrangement: GridArrangementStore
    private lateinit var account: AccountRepository
    private val viewModel: SettingsViewModel by viewModels {
        val graph = AppGraph.of(this)
        LambdaViewModelFactory { SettingsViewModel(graph.account, graph.settingsStore) }
    }
    private val editor: CameraOrderEditor get() = viewModel.editor
    private lateinit var backup: BackupController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.of(this)
        settings = graph.settingsStore
        arrangement = graph.arrangementStore
        account = graph.account
        // Registers its launchers, so before the activity starts.
        backup =
            BackupController(
                this,
                BackupCodec(settings, arrangement, versionName()),
                graph.backupStorage(this),
                onRestored = ::rebindAfterRestore,
            )
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindLayout()
        bindOverlays()
        bindQuality()
        bindScreen()
        bindBackup()
        bindCameraList()
        bindCode()
        bindAbout()
        bindLogout()
    }

    /**
     * Same filtered list as the grid's Layout menu for the orientation this screen is held in, and
     * it edits that orientation's layout (a phone keeps one per orientation); a stored layout the
     * list hides is still shown as selected.
     */
    private fun bindLayout() {
        val visibleCount = editor.items.count { it.visible }
        val portraitPhone =
            !FormFactor.isTelevision(this) &&
                resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val current = arrangement.layout(portraitPhone)
        val modes =
            (GridLayoutMode.available(visibleCount, portraitPhone) + current).distinct().sortedBy { it.ordinal }
        binding.layoutSpinner.adapter = spinnerAdapter(modes.map { getString(it.labelRes) })
        binding.layoutSpinner.setSelection(modes.indexOf(current))
        binding.layoutSpinner.onItemSelected { arrangement.setLayout(portraitPhone, modes[it]) }
    }

    /**
     * The camera rows ([CameraRowAdapter]): on a TV each row ends in the D-pad handle (OK picks
     * up, UP/DOWN move, OK drops), on a phone it starts with a drag handle to hold and drag. The
     * help line says which. The rows belong to the [SettingsViewModel], so a rotation keeps them;
     * a RecyclerView never saves its rows' view state, so their shared ids cannot restore into one
     * another (the round-7 bug).
     */
    private fun bindCameraList() {
        val touch = !FormFactor.isTelevision(this)
        binding.camerasHelp.setText(if (touch) R.string.settings_cameras_help_phone else R.string.settings_cameras_help)
        binding.cameraList.itemAnimator = null
        binding.cameraList.adapter = CameraRowAdapter(viewModel, touch, onVisibilityChanged = ::bindLayout)
    }

    private fun bindQuality() {
        binding.qualityMain.isChecked = settings.fullscreenQuality == StreamQuality.MAIN
        binding.qualityMain.setOnCheckedChangeListener { _, checked ->
            settings.fullscreenQuality = if (checked) StreamQuality.MAIN else StreamQuality.SUB
        }
    }

    private fun bindOverlays() {
        binding.showNames.isChecked = settings.showCameraNames
        binding.showNames.setOnCheckedChangeListener { _, checked -> settings.showCameraNames = checked }
        binding.showFps.isChecked = settings.showFrameRate
        binding.showFps.setOnCheckedChangeListener { _, checked -> settings.showFrameRate = checked }
    }

    /** Keep screen on: phones only, and applied to this window at once so the toggle is felt here. */
    private fun bindScreen() {
        if (FormFactor.isTelevision(this)) {
            binding.screenSection.visibility = View.GONE
            return
        }
        binding.keepScreenOn.isChecked = settings.keepScreenOn
        binding.keepScreenOn.setOnCheckedChangeListener { _, checked ->
            settings.keepScreenOn = checked
            KeepScreenOn.apply(this)
        }
    }

    private fun bindBackup() {
        binding.backup.setOnClickListener { backup.backup() }
        binding.restore.setOnClickListener { backup.restore() }
    }

    /**
     * A restore wrote both stores behind this screen's back: rebuild the camera rows and re-read
     * every switch and spinner, and apply keep-screen-on to this window as the toggle would.
     */
    private fun rebindAfterRestore() {
        viewModel.reloadFromStore()
        bindLayout()
        bindOverlays()
        bindQuality()
        bindScreen()
        bindCameraList()
        KeepScreenOn.apply(this)
    }

    private fun versionName(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    private fun bindCode() {
        val encryptPwd = account.inventory.value?.encryptPwd
        binding.changeCode.isEnabled = encryptPwd != null
        binding.changeCode.setOnClickListener {
            VerificationCodeDialog.show(this, rejected = false) { code ->
                VerificationCode.matches(code.trim(), encryptPwd).also { ok ->
                    if (ok) {
                        settings.verificationCode =
                            code.trim()
                    }
                }
            }
        }
    }

    /**
     * About: the version line and two buttons that depend on the device. On a phone, View Source
     * opens the GitHub address in the browser and Donate opens the UPI app with the payment URI;
     * a phone without a UPI app gets the UPI QR screen instead. On a TV, which has neither a
     * browser nor a UPI app, both open [QrActivity]: the GitHub code with the address above it,
     * the UPI code with the UPI id under it, for a phone to scan or to type.
     */
    private fun bindAbout() {
        binding.about.text = getString(R.string.settings_about_version, versionName())
        val television = FormFactor.isTelevision(this)
        binding.viewSource.setOnClickListener {
            if (television) {
                startActivity(QrActivity.source(this))
            } else {
                openOrShowQr(getString(R.string.settings_source_url), QrActivity.source(this))
            }
        }
        binding.donate.setOnClickListener {
            if (television) {
                startActivity(QrActivity.donate(this))
            } else {
                openOrShowQr(getString(R.string.upi_pay_uri), QrActivity.donate(this))
            }
        }
    }

    /** ACTION_VIEW on [uri]; when no app handles it (a phone without a UPI app), [fallback] shows the QR code instead. */
    private fun openOrShowQr(
        uri: String,
        fallback: Intent,
    ) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
        } catch (_: ActivityNotFoundException) {
            startActivity(fallback)
        }
    }

    private fun bindLogout() {
        binding.logout.setOnClickListener {
            AlertDialog
                .Builder(this)
                .setMessage(R.string.settings_logout_confirm)
                .setPositiveButton(R.string.settings_logout) { _, _ -> logout() }
                .setNegativeButton(R.string.code_cancel, null)
                .show()
        }
    }

    private fun logout() {
        binding.logout.isEnabled = false
        lifecycleScope.launch {
            account.logout()
            startActivity(
                Intent(this@SettingsActivity, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            finish()
        }
    }

    private fun spinnerAdapter(labels: List<String>) = ArrayAdapter(
        this,
        R.layout.item_spinner,
        labels,
    ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }

    private fun android.widget.Spinner.onItemSelected(block: (Int) -> Unit) {
        onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) = block(position)

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }
}
