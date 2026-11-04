package com.itsluminous.hikviewer.fakes

import android.content.Context
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.player.DeviceCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Builds an [AppGraph] on plain preferences with [FakeCloudServices]; shared by Robolectric and Espresso tests. */
object TestGraph {
    /** A roomy TV: 2 GB, 4 cores. */
    val tvCapabilities = DeviceCapabilities(isLowRamDevice = false, totalMemoryBytes = 2_048L shl 20, cpuCores = 4)

    /** Fresh graph; preferences are cleared first so tests do not leak state. */
    fun create(
        context: Context,
        cloud: FakeCloudServices = FakeCloudServices(),
        topBarAutoHideMillis: Long = 1_500,
        capabilities: DeviceCapabilities = tvCapabilities,
        decoders: RecordingDecoderFactory = RecordingDecoderFactory(),
        playbackDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): AppGraph {
        val session =
            context
                .getSharedPreferences(
                    "test-session",
                    Context.MODE_PRIVATE,
                ).also { it.edit().clear().commit() }
        val settings =
            context
                .getSharedPreferences(
                    "test-settings",
                    Context.MODE_PRIVATE,
                ).also { it.edit().clear().commit() }
        return AppGraph(
            session,
            settings,
            cloud,
            capabilities,
            topBarAutoHideMillis,
            decoders.playbackFactory(playbackDispatcher),
        )
    }
}
