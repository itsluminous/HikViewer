package com.itsluminous.hikviewer.player

import android.app.ActivityManager

/** The hardware facts [LiveBudget] reasons about. A plain data class so tests can describe any TV. */
data class DeviceCapabilities(
    /** `ActivityManager.isLowRamDevice()`: the OS itself expects memory pressure. */
    val isLowRamDevice: Boolean,
    /** Physical RAM in bytes. */
    val totalMemoryBytes: Long,
    /** CPU cores available to the process. */
    val cpuCores: Int,
) {
    /** RAM in whole megabytes, for logs and thresholds. */
    val totalMemoryMb: Long get() = totalMemoryBytes / BYTES_PER_MB

    /** Reads the running device. */
    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L

        /** Probes [activityManager] and the runtime. */
        fun of(activityManager: ActivityManager): DeviceCapabilities {
            val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            return DeviceCapabilities(
                isLowRamDevice = activityManager.isLowRamDevice,
                totalMemoryBytes = info.totalMem,
                cpuCores = Runtime.getRuntime().availableProcessors(),
            )
        }
    }
}
