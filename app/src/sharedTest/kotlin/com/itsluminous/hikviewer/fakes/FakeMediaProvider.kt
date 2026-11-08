package com.itsluminous.hikviewer.fakes

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore

/**
 * A stand-in for the `media` provider's Downloads collection, enough for
 * `DownloadsBackupFiles`: rows by id, a `DISPLAY_NAME LIKE ?` query, `IS_PENDING` updates and
 * deletes. Like the real one it renames an inserted file whose name a row already carries
 * ("name (1).json"), and it stamps every new row with [MODIFIED]. Streams are not its business:
 * the shadow resolver serves registered ones for the row Uris.
 */
class FakeMediaProvider : ContentProvider() {
    /** Every row, by id. */
    val rows = linkedMapOf<Long, ContentValues>()

    /** Every `IS_PENDING` value written, inserts and updates alike, in order. */
    val pendingHistory = mutableListOf<Int>()

    /** The last `query` selection and its arguments. */
    var lastSelection: String? = null
    var lastSelectionArgs: Array<String>? = null

    private var nextId = 1L

    override fun onCreate(): Boolean = true

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        val row = ContentValues(values ?: ContentValues())
        val name = row.getAsString(MediaStore.Downloads.DISPLAY_NAME)
        if (name != null && rows.values.any { it.getAsString(MediaStore.Downloads.DISPLAY_NAME) == name }) {
            val renamed = name.substringBeforeLast('.') + " (1)." + name.substringAfterLast('.')
            row.put(MediaStore.Downloads.DISPLAY_NAME, renamed)
        }
        row.put(MediaStore.Downloads.DATE_MODIFIED, MODIFIED)
        row.getAsInteger(MediaStore.Downloads.IS_PENDING)?.let(pendingHistory::add)
        val id = nextId++
        rows[id] = row
        return ContentUris.withAppendedId(uri, id)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val cursor = MatrixCursor(columns)
        val chosen =
            if (uri.lastPathSegment?.toLongOrNull() != null) {
                listOfNotNull(ContentUris.parseId(uri).let { id -> rows[id]?.let { id to it } })
            } else {
                lastSelection = selection
                lastSelectionArgs = selectionArgs
                rows.entries.map { it.key to it.value }.filter { (_, row) -> matches(row, selection, selectionArgs) }
            }
        chosen.forEach { (id, row) ->
            cursor.addRow(columns.map { column -> if (column == MediaStore.Downloads._ID) id else row.get(column) })
        }
        return cursor
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        values?.keySet()?.forEach { key -> row.put(key, values.getAsString(key)) }
        values?.getAsInteger(MediaStore.Downloads.IS_PENDING)?.let(pendingHistory::add)
        return 1
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0

    override fun getType(uri: Uri): String? {
        val row = rows[ContentUris.parseId(uri)] ?: return null
        return row.getAsString(MediaStore.Downloads.MIME_TYPE)
    }

    private fun matches(
        row: ContentValues,
        selection: String?,
        args: Array<String>?,
    ): Boolean {
        if (selection == null) return true
        val column = selection.substringBefore(" LIKE ?")
        val pattern = args?.firstOrNull() ?: return true
        val regex = Regex(pattern.split("%").joinToString(".*") { Regex.escape(it) })
        return row.getAsString(column)?.matches(regex) == true
    }

    /** The `DATE_MODIFIED` (seconds) every inserted row gets. */
    companion object {
        const val MODIFIED: Long = 1_762_600_000L
    }
}
