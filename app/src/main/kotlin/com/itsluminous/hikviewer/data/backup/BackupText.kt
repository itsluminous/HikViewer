package com.itsluminous.hikviewer.data.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The bytes of a backup file, shared by every storage.
 *
 * A backup is a few hundred bytes, a few kilobytes with many cameras. The documents UI lets the
 * user pick any file at all (`OpenDocument` is asked for every MIME type, so a provider that does
 * not know `application/json` still shows the file), so [read] stops at [MAX_BYTES] instead of pulling a
 * video into memory on the main thread; the error names the limit. Text is UTF-8 both ways,
 * whatever the platform default.
 */
internal object BackupText {
    /** The most a backup file may be: 1 MiB. */
    const val MAX_BYTES: Int = 1 shl 20

    private const val BUFFER_BYTES = 8 * 1024

    /** [text] as the bytes a backup file holds. */
    fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /**
     * Reads [stream] to its end as UTF-8, throwing [IOException] as soon as it passes [MAX_BYTES].
     * The caller closes the stream.
     */
    fun read(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw tooLarge()
            out.write(buffer, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** The error for a file past [MAX_BYTES], before or during the read. */
    fun tooLarge(): IOException = IOException("The file is larger than 1 MiB, so it is not a HikViewer backup")

    /**
     * Runs [block], turning the `SecurityException` a content provider throws (a grant that is
     * gone, a provider that refuses the Uri) into the [IOException] the screens report.
     */
    inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw IOException(e.message ?: "Access to the file was refused", e)
    }
}
