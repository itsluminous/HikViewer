package com.itsluminous.hikviewer.ui

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.Matcher

/** Lets the main looper run for [millis]; used to wait out the (shortened) top-bar timer. */
fun waitFor(millis: Long) {
    onView(isRoot()).perform(
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()

            override fun getDescription(): String = "wait $millis ms"

            override fun perform(
                uiController: UiController,
                view: View,
            ) = uiController.loopMainThreadForAtLeast(millis)
        },
    )
}

/**
 * Finishes every Activity the test left behind (a login that moved on to the grid, a logout that
 * started a new login), so the next test starts on a clean task.
 */
fun finishAllActivities() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED, Stage.STARTED)) {
            registry.getActivitiesInStage(stage).forEach { it.finish() }
        }
    }
    instrumentation.waitForIdleSync()
}
