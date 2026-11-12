package com.itsluminous.hikviewer.fakes

import android.content.Context
import com.itsluminous.hikviewer.data.RecorderClocks
import com.itsluminous.hikviewer.data.SettingsStore
import com.itsluminous.hikviewer.data.backup.BackupStorage
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.player.DeviceCapabilities
import com.itsluminous.hikviewer.player.PlaybackFactory
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
        /** Overrides the recording decoders, e.g. with real `MediaCodec`s on an emulator. */
        playbackFactory: PlaybackFactory = decoders.playbackFactory(playbackDispatcher),
        /** Where Backup and Restore keep files; the real chooser unless a test plants a fake directory. */
        backupStorage: (Context) -> BackupStorage = BackupStorage::choose,
        /**
         * Recorder clocks. The default never learns and keeps every recorder on the device's time,
         * because the fake footage runs to midnight on whatever day is asked for, which a real
         * recorder's today never does; tests of the learning build a
         * [com.itsluminous.hikviewer.data.StoredRecorderClocks] on the graph's settings store.
         */
        recorderClocks: (SettingsStore) -> RecorderClocks = { FixedRecorderClocks() },
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
            playbackFactory,
            backupStorage,
            recorderClocks = recorderClocks,
        )
    }
}
