package com.itsluminous.hikviewer

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Instruments the app with [EspressoApp] so every Activity sees the fake cloud instead of Hik-Connect. */
class TestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(cl, EspressoApp::class.java.name, context)
}

/** Application for instrumented tests: plain preferences, [com.itsluminous.hikviewer.fakes.FakeCloudServices], real decoders. */
class EspressoApp : HikViewerApp() {
    override fun onCreate() {
        // Not calling super: production wiring would create the key-store backed preferences.
        graph =
            com.itsluminous.hikviewer.fakes.TestGraph
                .create(this, topBarAutoHideMillis = TOP_BAR_HIDE_MILLIS)
    }

    companion object {
        /** Short enough for a test to wait out, long enough to assert the bar is still visible first. */
        const val TOP_BAR_HIDE_MILLIS: Long = 1_500
    }
}
