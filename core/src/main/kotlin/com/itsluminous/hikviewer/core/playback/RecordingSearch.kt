package com.itsluminous.hikviewer.core.playback

import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.json.CloudJson
import com.itsluminous.hikviewer.core.json.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Lists the recorded segments of a channel: `GET /v3/streaming/records` (the v1 endpoint; the
 * app's newer `/v3/streaming/v2/records` and `/common/records` answer `code 2004` on the
 * reference DVR). Times in the request and the response are the recorder's wall clock.
 *
 * The response shape varies by firmware: `records` is either a JSON list or a base64 string of
 * zlib-compressed JSON, and each item names its span `begin`/`end`, `B`/`E` or
 * `startTime`/`stopTime`. All three are read, which is also why the reference client's reader
 * (`B`/`startTime` only) silently returned nothing for this DVR.
 */
public class RecordingSearch(
    private val session: HikConnectSession,
) {
    /** Segments of [camera] overlapping `[start, end)`, merged over pages, in time order. */
    public suspend fun segments(
        camera: Camera,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<RecordSegment> {
        val out = mutableListOf<RecordSegment>()
        var from = start
        repeat(MAX_PAGES) {
            val body =
                session.get(
                    RECORDS_PATH,
                    mapOf(
                        "deviceSerial" to camera.deviceSerial,
                        "channelNo" to camera.channelNo.toString(),
                        // The per-channel serial; on the reference DVR every channel reports the
                        // recorder's own serial, which is what [Camera.deviceSerial] carries.
                        "channelSerial" to camera.deviceSerial,
                        "startTime" to REQUEST_TIME.format(from),
                        "stopTime" to REQUEST_TIME.format(end),
                        "size" to PAGE_SIZE.toString(),
                    ),
                )
            val page = parseRecords(body["records"])
            out += page
            val finished = body.str("isFinished")?.equals("TRUE", ignoreCase = true) ?: true
            val last = page.maxOfOrNull { it.end }
            if (finished || page.isEmpty() || last == null || !last.isBefore(end)) return out.sortedBy { it.begin }
            from = last.plusSeconds(1)
        }
        return out.sortedBy { it.begin }
    }

    /** Segments of [camera] on [day] (recorder date), clipped to that day. */
    public suspend fun segmentsOn(
        camera: Camera,
        day: LocalDate,
    ): List<RecordSegment> {
        val dayStart = day.atStartOfDay()
        val dayEnd = day.plusDays(1).atStartOfDay()
        return segments(camera, dayStart, dayEnd.minusSeconds(1))
            .filter { it.end.isAfter(dayStart) && it.begin.isBefore(dayEnd) }
            .map { it.copy(begin = maxOf(it.begin, dayStart), end = minOf(it.end, dayEnd)) }
    }

    /** Parsing, exposed for known-answer tests. */
    public companion object {
        private const val RECORDS_PATH = "/v3/streaming/records"
        private const val PAGE_SIZE = 200
        private const val MAX_PAGES = 20
        private val REQUEST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val RESPONSE_TIMES =
            listOf(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                REQUEST_TIME,
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"),
            )
        private val BEGIN_KEYS = listOf("begin", "B", "startTime", "beginTime")
        private val END_KEYS = listOf("end", "E", "stopTime", "endTime")
        private val TYPE_KEYS = listOf("type", "T", "recType")

        /** Turns the `records` element (list, or base64+zlib string of a list) into segments. */
        public fun parseRecords(records: JsonElement?): List<RecordSegment> {
            val array =
                when (records) {
                    is JsonArray -> records
                    is JsonPrimitive -> inflate(records.content)
                    else -> return emptyList()
                }
            return array.mapNotNull { (it as? JsonObject)?.let(::parseSegment) }
        }

        private fun inflate(encoded: String): JsonArray {
            if (encoded.isBlank()) return JsonArray(emptyList())
            val compressed = Base64.getDecoder().decode(encoded.trim())
            val inflater = Inflater()
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(INFLATE_CHUNK)
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, n)
                }
            } catch (e: DataFormatException) {
                throw RecordingSearchException("records payload is not zlib JSON", e)
            } finally {
                inflater.end()
            }
            return CloudJson.parser.parseToJsonElement(out.toString(Charsets.UTF_8.name())) as? JsonArray
                ?: throw RecordingSearchException("inflated records payload is not a JSON list")
        }

        private fun parseSegment(item: JsonObject): RecordSegment? {
            val begin = BEGIN_KEYS.firstNotNullOfOrNull { item.str(it)?.let(::parseTime) } ?: return null
            val end = END_KEYS.firstNotNullOfOrNull { item.str(it)?.let(::parseTime) } ?: return null
            if (!end.isAfter(begin)) return null
            val type = TYPE_KEYS.firstNotNullOfOrNull { item.str(it) } ?: RecordSegment.TYPE_TIMING
            return RecordSegment(begin, end, type)
        }

        private fun parseTime(text: String): LocalDateTime? {
            val t = text.trim()
            for (format in RESPONSE_TIMES) {
                try {
                    return LocalDateTime.parse(t, format)
                } catch (_: DateTimeParseException) {
                    // try the next shape
                }
            }
            return null
        }

        private const val INFLATE_CHUNK = 8192
    }
}

/** The record list could not be decoded. */
public class RecordingSearchException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)
