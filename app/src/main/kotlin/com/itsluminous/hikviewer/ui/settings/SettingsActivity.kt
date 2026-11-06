package com.itsluminous.hikviewer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
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
import com.itsluminous.hikviewer.databinding.ActivitySettingsBinding
import com.itsluminous.hikviewer.databinding.ItemCameraChoiceBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.player.LiveBudget
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.common.KeepScreenOn
import com.itsluminous.hikviewer.ui.common.LambdaViewModelFactory
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import com.itsluminous.hikviewer.ui.grid.VerificationCodeDialog
import com.itsluminous.hikviewer.ui.login.LoginActivity
import kotlinx.coroutines.launch

/**
 * Settings, top to bottom: grid layout, tile overlays, fullscreen quality, screen (phones), live
 * cameras, visible cameras and order, encryption password, about (version, source code, donation),
 * logout. There is no hero setting: the hero is whatever sits at position 0 of a 1 + N layout,
 * chosen on the grid with Move or Change. Every change is written to [SettingsStore] immediately;
 * the grid re-reads the store when this screen returns. The camera list is a plain `LinearLayout`
 * of rows (at most a few dozen cameras) whose "move" handle implements pick-up / UP-DOWN / drop;
 * the rows belong to the [SettingsViewModel] so a rotation keeps them, and they are excluded
 * from view state saving because their shared ids would otherwise restore into one another.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.of(this)
        settings = graph.settingsStore
        arrangement = graph.arrangementStore
        account = graph.account
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindLayout()
        bindOverlays()
        bindQuality()
        bindScreen()
        bindLiveCameras()
        bindCameraList()
        bindCode()
        bindAbout()
        bindLogout()
    }

    /** Same filtered list as the grid's Layout menu; a stored layout the list hides is still shown as selected. */
    private fun bindLayout() {
        val visibleCount = editor.items.count { it.visible }
        val current = arrangement.lastLayout
        val portraitPhone =
            !FormFactor.isTelevision(this) &&
                resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val modes =
            (GridLayoutMode.available(visibleCount, portraitPhone) + current).distinct().sortedBy { it.ordinal }
        binding.layoutSpinner.adapter = spinnerAdapter(modes.map { getString(it.labelRes) })
        binding.layoutSpinner.setSelection(modes.indexOf(current))
        binding.layoutSpinner.onItemSelected { arrangement.lastLayout = modes[it] }
    }

    private fun bindCameraList() {
        val list = binding.cameraList
        list.removeAllViews()
        editor.items.forEachIndexed { index, choice ->
            val row = ItemCameraChoiceBinding.inflate(LayoutInflater.from(this), list, false)
            row.check.isChecked = choice.visible
            row.name.text = choice.camera.name
            row.signal.setText(
                if (choice.camera.hasSignal) R.string.settings_camera_signal else R.string.settings_camera_no_signal,
            )
            row.check.setOnCheckedChangeListener { _, _ ->
                viewModel.toggleVisible(index)
                bindLayout()
            }
            row.handle.setOnClickListener {
                val moving = viewModel.togglePickUp(index)
                row.moving.visibility = if (moving) View.VISIBLE else View.GONE
                if (!moving) rebuildList(focusHandleAt = index)
            }
            row.handle.setOnKeyListener { _, keyCode, event -> onHandleKey(keyCode, event) }
            // Every row shares the same child ids, so a saved hierarchy state would restore the
            // last row's checkbox into all of them; the ViewModel is the state that survives.
            row.root.isSaveFromParentEnabled = false
            list.addView(row.root)
        }
    }

    private fun onHandleKey(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || editor.pickedUp == null) return false
        val delta =
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> -1
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                else -> return false
            }
        val to = viewModel.moveBy(delta) ?: return true
        rebuildList(focusHandleAt = to, moving = true)
        return true
    }

    private fun rebuildList(
        focusHandleAt: Int,
        moving: Boolean = false,
    ) {
        bindCameraList()
        val row = binding.cameraList.getChildAt(focusHandleAt) ?: return
        val rowBinding = ItemCameraChoiceBinding.bind(row)
        rowBinding.moving.visibility = if (moving) View.VISIBLE else View.GONE
        rowBinding.handle.requestFocus()
    }

    private fun bindQuality() {
        binding.qualityMain.isChecked = settings.fullscreenQuality == StreamQuality.MAIN
        binding.qualityMain.setOnCheckedChangeListener { _, checked ->
            settings.fullscreenQuality = if (checked) StreamQuality.MAIN else StreamQuality.SUB
        }
    }

    /**
     * Live cameras: Automatic (the device ceiling, named so the user knows what it resolves to) or
     * one of the explicit counts. The chooser is a single-choice `AlertDialog` list, like the
     * tile menu's Change: already focusable for a remote and tappable for a finger. The store is
     * written at once; the grid re-applies it when this screen returns.
     */
    private fun bindLiveCameras() {
        val automatic = LiveBudget.staticCeiling(AppGraph.of(this).capabilities)
        val choices = SettingsStore.LIVE_CAMERA_CHOICES

        fun summary(): String =
            settings.liveCameras?.toString() ?: getString(R.string.settings_live_cameras_automatic_summary, automatic)
        binding.liveCameras.text = summary()
        binding.liveCameras.setOnClickListener {
            val labels =
                (listOf(getString(R.string.settings_live_cameras_automatic)) + choices.map { it.toString() })
                    .toTypedArray<CharSequence>()
            val checked = settings.liveCameras?.let { choices.indexOf(it) + 1 } ?: 0
            AlertDialog
                .Builder(this)
                .setTitle(R.string.settings_live_cameras)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    settings.liveCameras = if (which == 0) null else choices[which - 1]
                    binding.liveCameras.text = summary()
                    dialog.dismiss()
                }.setNegativeButton(R.string.code_cancel, null)
                .show()
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
     * About: the version line and two link rows. Each row fires `ACTION_VIEW` on its address; a TV
     * usually has neither a browser nor a UPI app, so [ActivityNotFoundException] is caught and the
     * address is shown as selectable text in a dialog instead, for the user to type on a phone.
     */
    private fun bindAbout() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        binding.about.text = getString(R.string.settings_about_version, version)
        val sourceUrl = getString(R.string.url_source_code)
        binding.sourceCode.setOnClickListener {
            openLink(sourceUrl, R.string.settings_source_code, R.string.settings_open_link_failed, sourceUrl)
        }
        binding.donate.setOnClickListener {
            openLink(
                getString(R.string.url_donate_upi),
                R.string.settings_donate,
                R.string.settings_donate_failed,
                getString(R.string.upi_id),
            )
        }
    }

    private fun openLink(
        uri: String,
        titleRes: Int,
        failedRes: Int,
        fallbackText: String,
    ) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
        } catch (_: ActivityNotFoundException) {
            val dialog =
                AlertDialog
                    .Builder(this)
                    .setTitle(titleRes)
                    .setMessage(getString(failedRes) + "\n\n" + fallbackText)
                    .setPositiveButton(R.string.code_ok, null)
                    .show()
            dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
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
