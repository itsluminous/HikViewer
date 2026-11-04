package com.itsluminous.hikviewer

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 35])
class MainActivityTest {
    @Test
    fun `shows the application name`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val label = activity.findViewById<TextView>(R.id.app_name)
                assertThat(label.visibility).isEqualTo(View.VISIBLE)
                assertThat(label.text.toString()).isEqualTo(activity.getString(R.string.app_name))
            }
        }
    }

    @Test
    fun `is registered for both the TV and the phone launcher`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val launchers = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
                for (category in launchers) {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(activity.packageName)
                    val matches = activity.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
                    assertWithMessage(category).that(matches).contains(MainActivity::class.java.name)
                }
            }
        }
    }
}
