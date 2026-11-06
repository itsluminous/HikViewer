package com.itsluminous.hikviewer

import android.app.Application
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.ui.common.KeepScreenOn

/**
 * Application singleton that owns the hand-built dependency graph.
 *
 * `graph` is a `var` on purpose: instrumented tests replace it with a graph whose cloud services
 * are fakes before any Activity starts, which keeps the UI tests off the network.
 */
open class HikViewerApp : Application() {
    /** Process-wide dependencies; see [AppGraph]. */
    lateinit var graph: AppGraph

    init {
        // Here rather than in onCreate so the test applications, which skip super.onCreate to stay
        // off the key store, get the same window behaviour as production.
        registerActivityLifecycleCallbacks(KeepScreenOn)
    }

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.production(this)
    }
}
