package com.itsluminous.hikviewer.ui.login

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.grid.GridActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class LoginActivityTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud = FakeCloudServices()

    @Before
    fun setUp() {
        app.graph = TestGraph.create(app, cloud)
    }

    @Test
    fun `is registered for both launchers`() {
        val pm = app.packageManager
        for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(app.packageName)
            val names = pm.queryIntentActivities(intent, 0).map { it.activityInfo.name }
            assertWithMessage(category).that(names).containsExactly(LoginActivity::class.java.name)
        }
    }

    @Test
    fun `hint asks for the bare mobile number and the help explains the region code`() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val account = a.findViewById<EditText>(R.id.account)
                assertThat(account.hint.toString()).isEqualTo("Mobile number")
                assertThat(a.findViewById<TextView>(R.id.account_help).text.toString()).contains("India → 91")
                assertThat(account.text.toString()).isEmpty()
                assertThat(account.isFocused).isTrue()
                val region = a.findViewById<Spinner>(R.id.region)
                assertThat(region.selectedItemPosition).isEqualTo(Region.entries.indexOf(Region.INDIA))
                assertThat(region.selectedItem).isEqualTo("India")
            }
        }
    }

    @Test
    fun `empty submit shows the local error, edits clear it`() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<Button>(R.id.login).performClick()
                ShadowLooper.idleMainLooper()
                val error = a.findViewById<TextView>(R.id.error)
                assertThat(error.visibility).isEqualTo(View.VISIBLE)
                assertThat(error.text.toString()).isEqualTo(a.getString(R.string.login_error_empty))
                a.findViewById<EditText>(R.id.account).setText("91")
                ShadowLooper.idleMainLooper()
                assertThat(error.visibility).isEqualTo(View.INVISIBLE)
            }
        }
    }

    @Test
    fun `wrong credentials show the country-code reminder`() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<EditText>(R.id.account).setText("1234567890")
                a.findViewById<EditText>(R.id.password).setText("bad")
                a.findViewById<Button>(R.id.login).performClick()
                ShadowLooper.idleMainLooper()
                val error = a.findViewById<TextView>(R.id.error)
                assertThat(error.text.toString()).isEqualTo(a.getString(R.string.login_error_credentials))
                assertThat(a.findViewById<Button>(R.id.login).isEnabled).isTrue()
            }
        }
    }

    @Test
    fun `successful login prefixes the region code, opens the grid and finishes`() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<EditText>(R.id.account).setText("1234567890")
                a.findViewById<EditText>(R.id.password).setText("secret")
                a.findViewById<Spinner>(R.id.region).setSelection(Region.entries.indexOf(Region.INDIA))
                a.findViewById<Button>(R.id.login).performClick()
                ShadowLooper.idleMainLooper()
                val next = shadowOf(a).nextStartedActivity
                assertThat(next.component?.className).isEqualTo(GridActivity::class.java.name)
                assertThat(a.isFinishing).isTrue()
            }
        }
        assertThat(cloud.logins.single()).isEqualTo(Triple("911234567890", "secret", Region.INDIA))
    }

    @Test
    fun `a stored session skips the form`() {
        kotlinx.coroutines.runBlocking { app.graph.account.login("a", "secret", Region.INDIA) }
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            assertThat(scenario.state).isEqualTo(androidx.lifecycle.Lifecycle.State.DESTROYED)
            val next = shadowOf(app).nextStartedActivity
            assertThat(next.component?.className).isEqualTo(GridActivity::class.java.name)
        }
    }
}
