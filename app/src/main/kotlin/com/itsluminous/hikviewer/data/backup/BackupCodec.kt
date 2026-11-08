package com.itsluminous.hikviewer.data.backup

import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.data.GridArrangementStore
import com.itsluminous.hikviewer.data.SettingsStore
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A backup file this build cannot use; [message] is fit to show the user. */
class BackupFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Turns the two stores into a [BackupDocument] and back.
 *
 * Writing reads [SettingsStore.choices] and [GridArrangementStore.snapshot]; nothing else, so the
 * verification code, the session and the account can never end up in a file. Reading is in two
 * halves on purpose: [decode] parses and checks the whole document (format, app, every enum
 * name) and throws [BackupFormatException] before anything is written; only then does [apply]
 * write both stores, each in a single edit. A malformed file therefore changes nothing. Unknown
 * JSON fields are ignored, so a file from a later build that only added fields still restores;
 * a later [format] is refused, since its meaning is unknown.
 */
class BackupCodec(
    private val settings: SettingsStore,
    private val arrangement: GridArrangementStore,
    /** The app version written into the file (informational). */
    private val appVersion: String,
    /** The clock for [created] and [suggestedFileName]; tests pin it. */
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    /** The current choices and arrangements as a pretty-printed JSON document. */
    fun encode(): String = json.encodeToString(BackupDocument.serializer(), document())

    /** The current stores as a document. */
    fun document(): BackupDocument {
        val choices = settings.choices()
        val snapshot = arrangement.snapshot()
        return BackupDocument(
            format = FORMAT,
            app = APP,
            version = appVersion,
            created = now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            settings =
                BackupSettings(
                    visibleCameras = choices.visibleCameraKeys,
                    fullscreenQuality = choices.fullscreenQuality.name,
                    showCameraNames = choices.showCameraNames,
                    showFrameRate = choices.showFrameRate,
                    keepScreenOn = choices.keepScreenOn,
                ),
            arrangements =
                BackupArrangements(
                    lastLayout = snapshot.lastLayout.name,
                    lastLayoutPortrait = snapshot.lastLayoutPortrait.name,
                    orders = snapshot.orders.entries.associate { (mode, keys) -> mode.name to keys },
                ),
        )
    }

    /** The file name for a backup written now: `HikViewer-backup-YYYYMMDD-HHMM.json`. */
    fun suggestedFileName(): String = "$FILE_PREFIX${now().format(FILE_STAMP)}$FILE_SUFFIX"

    /**
     * Parses and validates [text]. Throws [BackupFormatException] for anything this build cannot
     * restore: not JSON, not a HikViewer backup, another [format], or a quality or layout name
     * this build does not know.
     */
    fun decode(text: String): Restorable {
        val doc = parse(text)
        if (doc.app != APP) throw BackupFormatException("Not a HikViewer backup file (app: ${doc.app})")
        if (doc.format != FORMAT) {
            throw BackupFormatException(
                "This backup is format ${doc.format}, written by HikViewer ${doc.version}; " +
                    "this version reads format $FORMAT",
            )
        }
        val quality =
            StreamQuality.entries.firstOrNull { it.name == doc.settings.fullscreenQuality }
                ?: throw BackupFormatException("Unknown fullscreen quality: ${doc.settings.fullscreenQuality}")
        val orders =
            doc.arrangements.orders.entries
                .associate { (name, keys) -> layout(name) to keys }
        return Restorable(
            doc,
            SettingsStore.Choices(
                visibleCameraKeys = doc.settings.visibleCameras,
                fullscreenQuality = quality,
                showCameraNames = doc.settings.showCameraNames,
                showFrameRate = doc.settings.showFrameRate,
                keepScreenOn = doc.settings.keepScreenOn,
            ),
            GridArrangementStore.Snapshot(
                lastLayout = layout(doc.arrangements.lastLayout),
                lastLayoutPortrait = layout(doc.arrangements.lastLayoutPortrait),
                orders = orders,
            ),
        )
    }

    /** JSON to [BackupDocument]; a `SerializationException` is an `IllegalArgumentException`. */
    private fun parse(text: String): BackupDocument = try {
        json.decodeFromString(BackupDocument.serializer(), text)
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException("Not a HikViewer backup file: ${e.message?.lineSequence()?.first()}", e)
    }

    /** Writes a validated document into both stores. Nothing else on the device is touched. */
    fun apply(restorable: Restorable) {
        settings.applyChoices(restorable.choices)
        arrangement.applySnapshot(restorable.snapshot)
    }

    /** [decode] then [apply]: the whole restore, which leaves the stores alone when it throws. */
    fun restore(text: String): BackupDocument = decode(text).also(::apply).document

    /** A validated document with the store values it maps to. */
    class Restorable(
        val document: BackupDocument,
        val choices: SettingsStore.Choices,
        val snapshot: GridArrangementStore.Snapshot,
    )

    private fun layout(name: String): GridLayoutMode = GridLayoutMode.entries.firstOrNull { it.name == name }
        ?: throw BackupFormatException("Unknown grid layout: $name")

    /** Format constants. */
    companion object {
        /** The only document format this build writes and reads. */
        const val FORMAT: Int = 1

        /** The [BackupDocument.app] marker. */
        const val APP: String = "HikViewer"

        /** Backups are named `HikViewer-backup-YYYYMMDD-HHMM.json`; the TV restore list matches on this. */
        const val FILE_PREFIX: String = "HikViewer-backup-"

        /** The file extension. */
        const val FILE_SUFFIX: String = ".json"

        /** The MIME type a documents UI is asked to create and open. */
        const val MIME_TYPE: String = "application/json"

        /** True for a name a backup file carries, whoever wrote it. */
        fun isBackupFileName(name: String): Boolean = name.startsWith(FILE_PREFIX) &&
            name.endsWith(FILE_SUFFIX) &&
            name.length > FILE_PREFIX.length + FILE_SUFFIX.length

        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

        private val json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            }
    }
}
