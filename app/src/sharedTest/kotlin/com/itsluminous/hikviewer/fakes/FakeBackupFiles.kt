package com.itsluminous.hikviewer.fakes

import android.net.Uri
import com.itsluminous.hikviewer.data.backup.BackupEntry
import com.itsluminous.hikviewer.data.backup.BackupFiles
import java.io.IOException
import java.time.Instant

/**
 * An in-memory directory for [com.itsluminous.hikviewer.data.backup.TvBackupStorage] tests:
 * Robolectric has no MediaStore, and the chooser's decision is what the tests are about.
 * [put] plants a file as `adb push` would; [failWrites] makes [write] throw as a full disk would.
 */
class FakeBackupFiles(
    override val location: String = "/sdcard/Download/",
) : BackupFiles {
    /** Name to content. */
    val contents = linkedMapOf<String, String>()

    /** Name to modification time. */
    val modified = mutableMapOf<String, Instant>()

    /** When true, every [write] throws an [IOException]. */
    var failWrites: Boolean = false

    /** The clock for files written through [write]. */
    var now: Instant = Instant.parse("2026-11-08T10:00:00Z")

    /** Plants [name] with [text], as a file copied in from outside would appear. */
    fun put(
        name: String,
        text: String,
        at: Instant = now,
    ) {
        contents[name] = text
        modified[name] = at
    }

    override fun write(
        name: String,
        text: String,
    ): BackupEntry {
        if (failWrites) throw IOException("disk full")
        put(name, text)
        return entry(name)
    }

    override fun list(): List<BackupEntry> = contents.keys.map(::entry)

    override fun owns(entry: BackupEntry): Boolean = entry.uri.toString().startsWith("fake://$location")

    override fun read(entry: BackupEntry): String = contents[entry.name] ?: throw IOException("${entry.name} is gone")

    private fun entry(name: String) =
        BackupEntry(name, modified[name], Uri.parse("fake://$location$name"), location + name)
}
