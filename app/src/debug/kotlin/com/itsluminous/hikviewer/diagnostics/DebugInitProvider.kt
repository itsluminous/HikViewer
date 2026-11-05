package com.itsluminous.hikviewer.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.StrictMode

/**
 * Debug-only process hook: a `ContentProvider` runs before `Application.onCreate`, so declaring one
 * in the debug manifest installs the diagnostics without a line of release code. Installs
 * [MainThreadWatchdog] and a logging `StrictMode` thread policy (disk and network on the main
 * thread, slow calls), the two things the brief asks a TV logcat to show when the remote lags.
 * Never queried; every content method is a stub.
 */
class DebugInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        MainThreadWatchdog().install()
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
