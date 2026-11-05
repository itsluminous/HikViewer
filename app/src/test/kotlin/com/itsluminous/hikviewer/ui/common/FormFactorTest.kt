package com.itsluminous.hikviewer.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class FormFactorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageManager get() = shadowOf(context.packageManager)
    private val uiModeManager get() = shadowOf(context.getSystemService(UiModeManager::class.java))

    @Test
    fun `a touch screen without leanback or a TV ui mode is a phone`() {
        packageManager.setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        uiModeManager.currentModeType = Configuration.UI_MODE_TYPE_NORMAL
        assertThat(FormFactor.isTelevision(context)).isFalse()
    }

    @Test
    fun `the leanback feature means television even with a touch screen`() {
        packageManager.setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        packageManager.setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertThat(FormFactor.isTelevision(context)).isTrue()
    }

    @Test
    fun `the TV ui mode from the system service means television`() {
        packageManager.setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        uiModeManager.currentModeType = Configuration.UI_MODE_TYPE_TELEVISION
        assertThat(FormFactor.isTelevision(context)).isTrue()
    }

    @Test
    @Config(qualifiers = "television")
    fun `the TV ui mode from the configuration means television`() {
        packageManager.setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        uiModeManager.currentModeType = Configuration.UI_MODE_TYPE_NORMAL
        assertThat(FormFactor.isTelevision(context)).isTrue()
    }

    @Test
    fun `no touch screen at all means television`() {
        packageManager.setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, false)
        uiModeManager.currentModeType = Configuration.UI_MODE_TYPE_NORMAL
        assertThat(FormFactor.isTelevision(context)).isTrue()
    }
}
