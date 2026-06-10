package io.terminus.core.gtfs

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * Raw stop row from `stops.txt`.
 *
 * @property lat null when the row had no parseable latitude.
 * @property lon null when the row had no parseable longitude.
 * @property parentStation `parent_station`, null when blank.
 * @property zoneId `zone_id`, null when blank.
 * @property locationType `location_type` (0 = stop/platform, 1 = parent station); 0 when blank.
 * @property underground non-standard feed extension column `underground` ("0"/"1"/"true"/"false"),
 *   null when the feed does not provide it (ARCHITECTURE.md §3 step 5).
 */
data class GtfsStop(
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val parentStation: String? = null,
    val zoneId: String? = null,
    val locationType: Int = 0,
    val underground: Boolean? = null,
)

/** Raw route row from `routes.txt`. [colorHex] is `route_color` without leading '#', or null. */
data class GtfsRoute(
    val id: String,
    val shortName: String,
    val longName: String,
    val routeType: Int,
    val colorHex: String? = null,
)

/** Raw trip row from `trips.txt`. */
data class GtfsTrip(
    val id: String,
    val routeId: String,
    val serviceId: String,
)

/** Raw calendar row from `calendar.txt`. [activeDays] is Monday..Sunday, size 7. */
data class GtfsCalendar(
    val serviceId: String,
    val activeDays: List<Boolean>,
)

/** Raw calendar exception from `calendar_dates.txt` (1 = service added, 2 = removed). */
data class GtfsCalendarDate(
    val serviceId: String,
    val exceptionType: Int,
)

/**
 * The streaming aggregation of `stop_times.txt` (ARCHITECTURE.md §3: "aggregate per trip
 * pattern"). All trips sharing the exact same ordered stop sequence *and* the same
 * inter-stop travel times collapse into one pattern; only their trip ids accumulate.
 * This keeps memory proportional to the number of distinct patterns, never to the
 * number of `stop_times` rows.
 *
 * @property stopIds raw (unmerged) GTFS stop ids, in stop_sequence order.
 * @property travelTimesSec `travelTimesSec[i]` = seconds from `stopIds[i]` to `stopIds[i+1]`
 *   (departure minus departure, falling back to arrival); -1 when either time was missing
 *   or the delta was negative. Size = `stopIds.size - 1`.
 * @property tripIds every trip that follows this exact pattern.
 */
data class TripPattern(
    val stopIds: List<String>,
    val travelTimesSec: List<Int>,
    val tripIds: List<String>,
)

/**
 * Everything [GtfsParser] extracts from a GTFS zip, ready for [NetworkBuilder].
 *
 * @property stopsRead number of data rows read from `stops.txt` (the
 *   [GtfsImportReport.stopsRead] count).
 */
class GtfsFeed(
    val stops: List<GtfsStop>,
    val routes: List<GtfsRoute>,
    val trips: List<GtfsTrip>,
    val calendars: List<GtfsCalendar>,
    val calendarDates: List<GtfsCalendarDate>,
    val tripPatterns: List<TripPattern>,
    val stopsRead: Int,
    val warnings: List<String>,
)

/**
 * Streaming GTFS reader (ARCHITECTURE.md §1.1 `gtfs`, §3).
 *
 * - CSV per RFC 4180: quoted fields, `""` escapes, fields containing commas and
 *   newlines, CRLF/LF/CR record separators, and a tolerated UTF-8 BOM.
 * - Reads entries directly off the [ZipInputStream] in archive order; nothing is
 *   extracted to disk.
 * - `stop_times.txt` is never materialized: rows are buffered for one trip at a
 *   time and folded into [TripPattern]s (see that class for the memory argument).
 * - Unknown files (`shapes.txt`, …) are skipped.
 */
object GtfsParser {

    private const val SENTINEL_NO_TIME = -1

    /** Parses [zip] to a [GtfsFeed]. The stream is read to its end but not closed. */
    fun parse(zip: ZipInputStream): GtfsFeed {
        val acc = Accumulator()
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                // Tolerate feeds zipped inside a folder.
                val name = entry.name.substringAfterLast('/').lowercase()
                val reader = BufferedReader(InputStreamReader(zip, StandardCharsets.UTF_8))
                when (name) {
                    "stops.txt" -> acc.readStops(reader)
                    "routes.txt" -> acc.readRoutes(reader)
                    "trips.txt" -> acc.readTrips(reader)
                    "stop_times.txt" -> acc.readStopTimes(reader)
                    "calendar.txt" -> acc.readCalendar(reader)
                    "calendar_dates.txt" -> acc.readCalendarDates(reader)
                    else -> Unit // shapes.txt and friends are ignored (ARCHITECTURE.md §3)
                }
            }
            entry = zip.nextEntry
        }
        return acc.toFeed()
    }

    // ---------------------------------------------------------------- accumulation

    private class Accumulator {
        val stops = ArrayList<GtfsStop>()
        val routes = ArrayList<GtfsRoute>()
        val trips = ArrayList<GtfsTrip>()
        val calendars = ArrayList<GtfsCalendar>()
        val calendarDates = ArrayList<GtfsCalendarDate>()
        val warnings = ArrayList<String>()
        val seenFiles = HashSet<String>()
        var stopsRead = 0

        // stop_times streaming state
        val patterns = LinkedHashMap<String, MutablePattern>()
        val flushedTripIds = HashSet<String>()
        var skippedRows = 0
        var negativeOrMissingTimes = 0
        var ungroupedRows = 0

        fun readStops(reader: Reader) {
            seenFiles += "stops.txt"
            forEachRow(reader) { row ->
                stopsRead++
                val id = row["stop_id"]
                if (id == null) {
                    skippedRows++
                    return@forEachRow
                }
                stops += GtfsStop(
                    id = id,
                    name = row["stop_name"] ?: id,
                    lat = row["stop_lat"]?.toDoubleOrNull(),
                    lon = row["stop_lon"]?.toDoubleOrNull(),
                    parentStation = row["parent_station"],
                    zoneId = row["zone_id"],
                    locationType = row["location_type"]?.toIntOrNull() ?: 0,
                    underground = row["underground"]?.let(::parseBooleanish),
                )
            }
        }

        fun readRoutes(reader: Reader) {
            seenFiles += "routes.txt"
            forEachRow(reader) { row ->
                val id = row["route_id"] ?: run { skippedRows++; return@forEachRow }
                val shortName = row["route_short_name"]
                val longName = row["route_long_name"]
                routes += GtfsRoute(
                    id = id,
                    shortName = shortName ?: longName ?: id,
                    longName = longName ?: shortName ?: id,
                    routeType = row["route_type"]?.toIntOrNull() ?: 3,
                    colorHex = row["route_color"],
                )
            }
        }

        fun readTrips(reader: Reader) {
            seenFiles += "trips.txt"
            forEachRow(reader) { row ->
                val id = row["trip_id"]
                val routeId = row["route_id"]
                val serviceId = row["service_id"]
                if (id == null || routeId == null || serviceId == null) {
                    skippedRows++
                    return@forEachRow
                }
                trips += GtfsTrip(id, routeId, serviceId)
            }
        }

        fun readCalendar(reader: Reader) {
            seenFiles += "calendar.txt"
            val dayColumns = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
            forEachRow(reader) { row ->
                val serviceId = row["service_id"] ?: run { skippedRows++; return@forEachRow }
                calendars += GtfsCalendar(serviceId, dayColumns.map { row[it] == "1" })
            }
        }

        fun readCalendarDates(reader: Reader) {
            seenFiles += "calendar_dates.txt"
            forEachRow(reader) { row ->
                val serviceId = row["service_id"] ?: run { skippedRows++; return@forEachRow }
                calendarDates += GtfsCalendarDate(serviceId, row["exception_type"]?.toIntOrNull() ?: 0)
            }
        }

        fun readStopTimes(reader: Reader) {
            seenFiles += "stop_times.txt"
            var currentTripId: String? = null
            val currentRows = ArrayList<StopTimeRow>()

            fun flush() {
                val tripId = currentTripId ?: return
                if (currentRows.size >= 2) {
                    currentRows.sortBy { it.sequence }
                    val stopIds = currentRows.map { it.stopId }
                    val times = ArrayList<Int>(stopIds.size - 1)
                    for (i in 0 until currentRows.size - 1) {
                        val a = currentRows[i].timeSec
                        val b = currentRows[i + 1].timeSec
                        val delta = if (a != null && b != null) b - a else SENTINEL_NO_TIME
                        if (delta < 0) {
                            negativeOrMissingTimes++
                            times += SENTINEL_NO_TIME
                        } else {
                            times += delta
                        }
                    }
                    val key = stopIds.joinToString("\u0001") + "\u0002" + times.joinToString(",")
                    patterns.getOrPut(key) { MutablePattern(stopIds, times) }.tripIds += tripId
                }
                flushedTripIds += tripId
                currentRows.clear()
                currentTripId = null
            }

            forEachRow(reader) { row ->
                val tripId = row["trip_id"]
                val stopId = row["stop_id"]
                if (tripId == null || stopId == null) {
                    skippedRows++
                    return@forEachRow
                }
                if (tripId != currentTripId) {
                    flush()
                    if (tripId in flushedTripIds) {
                        // stop_times not grouped by trip; rows after the first block are dropped.
                        ungroupedRows++
                        return@forEachRow
                    }
                    currentTripId = tripId
                }
                val departure = row["departure_time"]?.let(::parseGtfsTimeSec)
                val arrival = row["arrival_time"]?.let(::parseGtfsTimeSec)
                currentRows += StopTimeRow(
                    sequence = row["stop_sequence"]?.toIntOrNull() ?: currentRows.size,
                    stopId = stopId,
                    // departure(B) - departure(A), fallback arrival (ARCHITECTURE.md §3 step 3)
                    timeSec = departure ?: arrival,
                )
            }
            flush()
        }

        fun toFeed(): GtfsFeed {
            for (required in listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt")) {
                if (required !in seenFiles) warnings += "Required file $required is missing from the GTFS zip"
            }
            if (skippedRows > 0) warnings += "$skippedRows rows skipped for missing required fields"
            if (negativeOrMissingTimes > 0) {
                warnings += "$negativeOrMissingTimes stop_times pairs had missing or non-increasing times; no edge sample taken"
            }
            if (ungroupedRows > 0) {
                warnings += "$ungroupedRows stop_times rows ignored because the file is not grouped by trip_id"
            }
            return GtfsFeed(
                stops = stops,
                routes = routes,
                trips = trips,
                calendars = calendars,
                calendarDates = calendarDates,
                tripPatterns = patterns.values.map { TripPattern(it.stopIds, it.travelTimesSec, it.tripIds) },
                stopsRead = stopsRead,
                warnings = warnings,
            )
        }
    }

    private class MutablePattern(val stopIds: List<String>, val travelTimesSec: List<Int>) {
        val tripIds = ArrayList<String>()
    }

    private class StopTimeRow(val sequence: Int, val stopId: String, val timeSec: Int?)

    // ---------------------------------------------------------------- row plumbing

    /** Header-keyed view of one CSV record. Blank fields read as null. */
    private class Row(private val header: Map<String, Int>, private val fields: List<String>) {
        operator fun get(column: String): String? =
            header[column]?.let { fields.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
    }

    private inline fun forEachRow(reader: Reader, action: (Row) -> Unit) {
        val csv = CsvReader(reader)
        val headerRecord = csv.readRecord() ?: return
        val header = headerRecord.withIndex().associate { (i, name) -> name.trim() to i }
        while (true) {
            val record = csv.readRecord() ?: break
            if (record.size == 1 && record[0].isBlank()) continue // blank line
            action(Row(header, record))
        }
    }

    /** Parses "H:MM:SS"/"HH:MM:SS" (hours may exceed 23) to seconds since midnight. */
    private fun parseGtfsTimeSec(text: String): Int? {
        val parts = text.split(':')
        if (parts.size != 3) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        val s = parts[2].trim().toIntOrNull() ?: return null
        if (h < 0 || m !in 0..59 || s !in 0..59) return null
        return h * 3600 + m * 60 + s
    }

    private fun parseBooleanish(text: String): Boolean? = when (text.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }

    // ---------------------------------------------------------------- CSV (RFC 4180)

    /**
     * Minimal streaming RFC 4180 reader: quoted fields (commas, quotes, embedded
     * newlines), `""` escapes, CR / LF / CRLF record separators, and a UTF-8 BOM
     * tolerated before the first record.
     */
    internal class CsvReader(private val reader: Reader) {
        private var pushback = NO_PUSHBACK
        private var firstChar = true

        private fun next(): Int {
            if (pushback != NO_PUSHBACK) {
                val c = pushback
                pushback = NO_PUSHBACK
                return c
            }
            return reader.read()
        }

        /** Returns the next record's fields, or null at end of input. */
        fun readRecord(): List<String>? {
            var c = next()
            if (firstChar) {
                firstChar = false
                if (c == BOM) c = next()
            }
            if (c == EOF) return null
            val fields = ArrayList<String>(8)
            val current = StringBuilder()
            var inQuotes = false
            var fieldStart = true
            while (c != EOF) {
                if (inQuotes) {
                    if (c == QUOTE) {
                        val n = next()
                        if (n == QUOTE) {
                            current.append('"')
                        } else {
                            inQuotes = false
                            pushback = n
                        }
                    } else {
                        current.append(c.toChar())
                    }
                } else when (c) {
                    QUOTE -> if (fieldStart) {
                        inQuotes = true
                        fieldStart = false
                    } else {
                        current.append('"') // stray quote mid-field; keep it
                    }
                    COMMA -> {
                        fields.add(current.toString())
                        current.setLength(0)
                        fieldStart = true
                    }
                    CR -> {
                        val n = next()
                        if (n != LF) pushback = n
                        break
                    }
                    LF -> break
                    else -> {
                        current.append(c.toChar())
                        fieldStart = false
                    }
                }
                c = next()
            }
            fields.add(current.toString())
            return fields
        }

        private companion object {
            const val NO_PUSHBACK = -2
            const val EOF = -1
            const val BOM = 0xFEFF
            const val QUOTE = '"'.code
            const val COMMA = ','.code
            const val CR = '\r'.code
            const val LF = '\n'.code
        }
    }
}
