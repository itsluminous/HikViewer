package com.itsluminous.hikviewer.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.login.LoginActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginFlowTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()

    private lateinit var scenario: ActivityScenario<LoginActivity>

    // The graph must be replaced before the Activity starts, so the scenario is launched here
    // rather than through ActivityScenarioRule (whose launch precedes @Before).
    @Before
    fun freshGraph() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        // An unencrypted recorder, so a successful login lands on the grid without the code dialog.
        app.graph = TestGraph.create(app, FakeCloudServices(encrypted = false))
        scenario = ActivityScenario.launch(LoginActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        finishAllActivities()
    }

    @Test
    fun hintAsksForTheCountryCodeAndEmptySubmitIsRejectedLocally() {
        onView(withId(R.id.account)).check(matches(withHint(R.string.login_account_hint)))
        onView(withId(R.id.login)).perform(click())
        onView(withId(R.id.error)).check(matches(isDisplayed()))
        onView(withId(R.id.error)).check(matches(withText(R.string.login_error_empty)))
    }

    @Test
    fun wrongPasswordShowsTheCountryCodeHint() {
        onView(withId(R.id.account)).perform(replaceText("1234567890"), closeSoftKeyboard())
        onView(withId(R.id.password)).perform(replaceText("nope"), closeSoftKeyboard())
        onView(withId(R.id.login)).perform(click())
        onView(withId(R.id.error)).check(matches(withText(R.string.login_error_credentials)))
    }

    @Test
    fun validLoginReachesTheGrid() {
        onView(withId(R.id.account)).perform(replaceText("911234567890"), closeSoftKeyboard())
        onView(withId(R.id.password)).perform(replaceText("secret"), closeSoftKeyboard())
        onView(withId(R.id.login)).perform(click())
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
    }
}
