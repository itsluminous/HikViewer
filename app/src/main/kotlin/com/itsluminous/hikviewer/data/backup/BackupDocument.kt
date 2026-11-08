package com.itsluminous.hikviewer.data.backup

import kotlinx.serialization.Serializable

/**
 * The one JSON document a backup file holds: the user's settings choices and the grid
 * arrangements, versioned by [format] so a later HikViewer can tell what it is reading.
 *
 * Deliberately not in it: the session, the account and the video verification code. A backup is
 * meant to be copied around (`adb pull` from a TV, a cloud drive on a phone), and none of those
 * belong in a plain file. Camera keys (`serial/channel`) are copied as they are, known or not: a
 * recorder the account will see again keeps its tile positions.
 *
 * The field names are the file format; renaming one breaks every backup already written.
 */
@Serializable
data class BackupDocument(
    /** Version of this document's shape; [BackupCodec.FORMAT] is the only one this build reads. */
    val format: Int,
    /** Always "HikViewer": tells any other JSON file apart at the first field. */
    val app: String,
    /** The version of the app that wrote the file; informational. */
    val version: String,
    /** When the file was written, ISO-8601 with offset; informational. */
    val created: String,
    val settings: BackupSettings,
    val arrangements: BackupArrangements,
)

/** [com.itsluminous.hikviewer.data.SettingsStore.Choices] as written to the file. */
@Serializable
data class BackupSettings(
    /** Ordered camera keys shown in the grid; null when the user never chose. */
    val visibleCameras: List<String>? = null,
    /** A `StreamQuality` name. */
    val fullscreenQuality: String,
    val showCameraNames: Boolean,
    val showFrameRate: Boolean,
    val keepScreenOn: Boolean,
)

/** [com.itsluminous.hikviewer.data.GridArrangementStore.Snapshot] as written to the file. */
@Serializable
data class BackupArrangements(
    /** A `GridLayoutMode` name: the layout of a TV or a phone held sideways. */
    val lastLayout: String,
    /** A `GridLayoutMode` name: the layout of a phone held upright. */
    val lastLayoutPortrait: String,
    /** Camera-key order per `GridLayoutMode` name; a 1 + N layout's first key is its hero. */
    val orders: Map<String, List<String>> = emptyMap(),
)
