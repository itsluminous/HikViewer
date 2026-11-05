package com.itsluminous.hikviewer.ui.settings

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.VerificationCode
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.data.AccountRepository
import com.itsluminous.hikviewer.data.GridArrangementStore
import com.itsluminous.hikviewer.data.SettingsStore
import com.itsluminous.hikviewer.databinding.ActivitySettingsBinding
import com.itsluminous.hikviewer.databinding.ItemCameraChoiceBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import com.itsluminous.hikviewer.ui.grid.VerificationCodeDialog
import com.itsluminous.hikviewer.ui.login.LoginActivity
import kotlinx.coroutines.launch

/**
 * Settings: layout, visible cameras and order, fullscreen quality, encryption password, about,
 * logout. There is no hero setting: the hero is whatever sits at position 0 of a 1 + N layout,
 * chosen on the grid with Move or Change. Every change is written to [SettingsStore] immediately; the grid
 * re-reads the store when this screen returns. The camera list is a plain `LinearLayout` of rows
 * (at most a few dozen cameras) whose "move" handle implements pick-up / UP-DOWN / drop.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsStore
    private lateinit var arrangement: GridArrangementStore
    private lateinit var account: AccountRepository
    private lateinit var editor: CameraOrderEditor
    private var allCameras: List<Camera> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.of(this)
        settings = graph.settingsStore
        arrangement = graph.arrangementStore
        account = graph.account
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        allCameras =
            account.inventory.value
                ?.cameras
                .orEmpty()
        editor = CameraOrderEditor(allCameras, settings.visibleCameraKeys)
        bindLayout()
        bindCameraList()
        bindQuality()
        bindOverlays()
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
                editor.toggleVisible(index)
                saveOrder()
            }
            row.handle.setOnClickListener {
                val moving = editor.togglePickUp(index)
                row.moving.visibility = if (moving) View.VISIBLE else View.GONE
                if (!moving) {
                    saveOrder()
                    rebuildList(focusHandleAt = index)
                }
            }
            row.handle.setOnKeyListener { _, keyCode, event -> onHandleKey(keyCode, event) }
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
        val to = editor.moveBy(delta) ?: return true
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

    /** Persists the camera choice; the layout spinner depends on how many cameras are shown. */
    private fun saveOrder() {
        settings.visibleCameraKeys = editor.visibleKeys()
        bindLayout()
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

    private fun bindAbout() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        binding.about.text = getString(R.string.settings_about_version, version)
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
