package com.itsluminous.hikviewer.ui.grid

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.TestGraph
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

// A 1080p television in landscape; Robolectric's default device is a portrait phone.
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class VerificationCodeDialogTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud = FakeCloudServices(cameraCount = 16, signalCount = 12)
    private val decoders = RecordingDecoderFactory()

    @Before
    fun setUp() {
        app.graph = TestGraph.create(app, cloud, topBarAutoHideMillis = 1_000, decoders = decoders)
        kotlinx.coroutines.runBlocking { app.graph.account.login("a", "secret", Region.INDIA) }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
    }

    private fun ActivityScenario<GridActivity>.grid(block: (GridActivity, CameraGridLayout) -> Unit) {
        ShadowLooper.idleMainLooper()
        onActivity { a -> block(a, a.findViewById(R.id.grid)) }
    }

    @Test
    fun `asks for the verification code when none is stored and accepts the right one`() {
        app.graph.settingsStore.verificationCode = null
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { _, _ ->
                val dialog =
                    shadowOf(
                        androidx.test.core.app.ApplicationProvider
                            .getApplicationContext<android.app.Application>(),
                    ).let {
                        org.robolectric.shadows.ShadowDialog
                            .getLatestDialog() as AlertDialog
                    }
                assertThat(dialog.isShowing).isTrue()
                assertThat(cloud.accounts.single().opened).isEmpty()
                val input = dialog.findViewById<android.widget.EditText>(R.id.code_input)!!
                val error = dialog.findViewById<TextView>(R.id.code_error)!!
                input.setText("wrong")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertThat(dialog.isShowing).isTrue()
                assertThat(error.visibility).isEqualTo(View.VISIBLE)
                input.setText(FakeCloudServices.GOOD_CODE)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(dialog.isShowing).isFalse()
                assertThat(app.graph.settingsStore.verificationCode).isEqualTo(FakeCloudServices.GOOD_CODE)
                assertThat(cloud.accounts.single().opened).hasSize(12)
            }
        }
    }

    @Test
    fun `the keyboard's Done key submits the verification code like OK`() {
        app.graph.settingsStore.verificationCode = null
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { _, _ ->
                val dialog =
                    org.robolectric.shadows.ShadowDialog
                        .getLatestDialog() as AlertDialog
                val input = dialog.findViewById<android.widget.EditText>(R.id.code_input)!!
                val error = dialog.findViewById<TextView>(R.id.code_error)!!
                assertThat(input.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
                    .isEqualTo(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
                input.setText("wrong")
                input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
                assertThat(dialog.isShowing).isTrue()
                assertThat(error.visibility).isEqualTo(View.VISIBLE)
                input.setText(FakeCloudServices.GOOD_CODE)
                input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
                ShadowLooper.idleMainLooper()
                assertThat(dialog.isShowing).isFalse()
                assertThat(app.graph.settingsStore.verificationCode).isEqualTo(FakeCloudServices.GOOD_CODE)
            }
        }
    }
}
