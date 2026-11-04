package com.itsluminous.hikviewer

import com.itsluminous.hikviewer.fakes.TestGraph

/** Application used by Robolectric tests: plain preferences and the fake cloud instead of the key store. */
class TestApp : HikViewerApp() {
    override fun onCreate() {
        graph = TestGraph.create(this)
        // Deliberately not calling super: production wiring would touch AndroidKeyStore.
    }
}
