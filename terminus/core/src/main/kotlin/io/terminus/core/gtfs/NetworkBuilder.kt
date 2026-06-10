package io.terminus.core.gtfs

import io.terminus.core.cityfile.CityFile
import io.terminus.core.geo.BoundingBox
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.geo.Polygon
import io.terminus.core.transit.RouteLine
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitEdge
import io.terminus.core.transit.TransitMode
import io.terminus.core.transit.TransitNetwork
import java.security.MessageDigest
import java.util.TreeMap
import kotlin.math.roundToInt

/**
 * Turns a parsed [GtfsFeed] into a playable [CityFile], implementing the build steps of
 * ARCHITECTURE.md §3 exactly:
 *
 * 1. weekday service selection ([selectServices]; with no calendar data all services are active),
 * 2. stop merging by `parent_station`, else 50 m + normalized name,
 * 3. directed route edges with median travel times clamped to [30 s, 30 min] per (from, to, route),
 * 4. walking transfers under 250 m at 1.2 m/s + 60 s — emitted as **two directed edges** per pair
 *    (the binding [TransitNetwork] edge-direction convention),
 * 5. station attributes (isInterchange, isTerminus from dominant patterns, zoneId majority,
 *    underground null unless the feed provides it),
 * 6. boundary clip via [TransitNetwork.clipTo] (largest connected component kept).
 */
object NetworkBuilder {

    const val MERGE_RADIUS_METERS = 50.0
    const val TRANSFER_RADIUS_METERS = 250.0
    const val WALK_SPEED_MPS = 1.2
    const val TRANSFER_PENALTY_SEC = 60.0
    const val MIN_EDGE_TIME_SEC = 30
    const val MAX_EDGE_TIME_SEC = 1800

    /** A finished import: the city plus its [GtfsImportReport]. */
    data class Result(val cityFile: CityFile, val report: GtfsImportReport)

    /**
     * Builds the network from [feed], clipped to [boundary]. Counts in the report are
     * as-built (pre-clip) except [GtfsImportReport.droppedOutsideBoundary], which is the
     * number of merged nodes removed by the clip (outside the polygon or disconnected).
     */
    fun build(
        feed: GtfsFeed,
        boundary: Polygon,
        cityId: String,
        displayName: String,
        attribution: String,
    ): Result {
        val warnings = ArrayList(feed.warnings)

        // Step 1 — weekday service selection.
        val selectedServices = selectServices(feed, warnings)
        val selectedTrips = feed.trips.filter { it.serviceId in selectedServices }
        val tripById = selectedTrips.associateBy { it.id }

        // Step 2 — stop merging.
        val nodes = mergeStops(feed.stops, warnings)
        val nodeByStopId = HashMap<String, MergedNode>()
        for (node in nodes) for (memberId in node.memberStopIds) nodeByStopId[memberId] = node

        // Step 3 — edge samples per (from, to, route), aggregated from trip patterns.
        val samples = HashMap<EdgeKey, TreeMap<Int, Int>>()
        val routePatternCounts = HashMap<String, HashMap<List<String>, Int>>()
        val routesPerNode = HashMap<String, MutableSet<String>>()
        var unknownStopRefs = 0
        for (pattern in feed.tripPatterns) {
            val tripsByRoute = pattern.tripIds
                .mapNotNull { tripById[it] }
                .groupingBy { it.routeId }
                .eachCount()
            if (tripsByRoute.isEmpty()) continue
            val nodeIds = pattern.stopIds.map { stopId ->
                nodeByStopId[stopId]?.id ?: run { unknownStopRefs++; null }
            }
            val mergedSequence = ArrayList<String>(nodeIds.size)
            for (id in nodeIds) {
                if (id != null && id != mergedSequence.lastOrNull()) mergedSequence += id
            }
            if (mergedSequence.size < 2) continue
            for ((routeId, tripCount) in tripsByRoute) {
                routePatternCounts.getOrPut(routeId) { HashMap() }
                    .merge(mergedSequence, tripCount, Int::plus)
                for (nodeId in mergedSequence) routesPerNode.getOrPut(nodeId) { HashSet() } += routeId
                for (i in 0 until pattern.stopIds.size - 1) {
                    val from = nodeIds[i] ?: continue
                    val to = nodeIds[i + 1] ?: continue
                    if (from == to) continue // merged into the same node
                    val time = pattern.travelTimesSec[i]
                    if (time < 0) continue // missing/invalid sample (already warned by the parser)
                    samples.getOrPut(EdgeKey(from, to, routeId)) { TreeMap() }
                        .merge(time, tripCount, Int::plus)
                }
            }
        }
        if (unknownStopRefs > 0) {
            warnings += "$unknownStopRefs stop_times references to unknown stop_ids were ignored"
        }

        // Median per (from, to, route), clamped to [30 s, 30 min].
        val nodeById = nodes.associateBy { it.id }
        var clamped = 0
        val routeEdges = samples.entries
            .sortedWith(compareBy({ it.key.routeId }, { it.key.fromId }, { it.key.toId }))
            .map { (key, histogram) ->
                val median = weightedMedian(histogram)
                val time = median.coerceIn(MIN_EDGE_TIME_SEC, MAX_EDGE_TIME_SEC)
                if (time != median) clamped++
                TransitEdge(
                    fromId = key.fromId,
                    toId = key.toId,
                    routeId = key.routeId,
                    travelTimeSec = time,
                    distanceMeters = GeoMath.haversineMeters(
                        nodeById.getValue(key.fromId).latLng,
                        nodeById.getValue(key.toId).latLng,
                    ),
                )
            }
        if (clamped > 0) warnings += "$clamped edge travel times clamped to [30 s, 30 min]"

        // Routes with their dominant (most frequent) merged stop pattern.
        val routeLines = feed.routes
            .filter { it.id in routePatternCounts }
            .map { route ->
                val dominant = dominantPattern(routePatternCounts.getValue(route.id))
                RouteLine(
                    id = route.id,
                    shortName = route.shortName,
                    longName = route.longName,
                    mode = modeForRouteType(route.routeType),
                    colorHex = normalizeColor(route.colorHex),
                    orderedStationIds = dominant,
                )
            }
        val routeModeById = routeLines.associate { it.id to it.mode }
        val terminusNodeIds = routeLines
            .flatMap { listOf(it.orderedStationIds.first(), it.orderedStationIds.last()) }
            .toSet()

        // Step 5 — station attributes. Nodes never served by a selected trip are dropped.
        val unservedNodes = nodes.count { routesPerNode[it.id].isNullOrEmpty() }
        if (unservedNodes > 0) warnings += "$unservedNodes merged stops had no service on the selected weekday"
        val stations = nodes
            .filter { !routesPerNode[it.id].isNullOrEmpty() }
            .map { node ->
                val routeIds = routesPerNode.getValue(node.id).sorted()
                Station(
                    id = node.id,
                    name = node.name,
                    latLng = node.latLng,
                    mode = dominantMode(routeIds.map { routeModeById.getValue(it) }),
                    routeIds = routeIds,
                    isInterchange = routeIds.size >= 2,
                    isTerminus = node.id in terminusNodeIds,
                    zoneId = node.zoneId,
                    underground = node.underground,
                )
            }
            .sortedBy { it.id }

        // Step 4 — walking transfers: two directed edges per qualifying pair.
        val transferEdges = ArrayList<TransitEdge>()
        for (i in stations.indices) {
            for (j in i + 1 until stations.size) {
                val d = GeoMath.haversineMeters(stations[i].latLng, stations[j].latLng)
                if (d <= TRANSFER_RADIUS_METERS) {
                    val time = (d / WALK_SPEED_MPS + TRANSFER_PENALTY_SEC).roundToInt()
                    transferEdges += TransitEdge(stations[i].id, stations[j].id, null, time, d)
                    transferEdges += TransitEdge(stations[j].id, stations[i].id, null, time, d)
                }
            }
        }

        // Step 6 — boundary clip (drops outside nodes, dangling edges, minor components).
        val preClip = TransitNetwork(stations, routeLines, routeEdges + transferEdges)
        val clipped = preClip.clipTo(boundary)
        val dropped = preClip.stations.size - clipped.stations.size
        if (dropped > 0) warnings += "$dropped stations dropped by the boundary clip"

        val defaultStart = defaultStartStation(clipped)
        if (defaultStart == null) warnings += "Network is empty after the boundary clip"

        val cityFile = CityFile(
            schemaVersion = 1,
            cityId = cityId,
            displayName = displayName,
            attribution = attribution,
            isSynthetic = false,
            bbox = boundingBox(clipped.stations.map { it.latLng }),
            stations = clipped.stations,
            routes = clipped.routes,
            edges = clipped.edges,
            defaultStartStationId = defaultStart ?: "",
        )
        val report = GtfsImportReport(
            stopsRead = feed.stopsRead,
            stopsMerged = nodes.size,
            routes = routeLines.size,
            trips = selectedTrips.size,
            edges = routeEdges.size,
            transferEdges = transferEdges.size,
            droppedOutsideBoundary = dropped,
            warnings = warnings,
        )
        return Result(cityFile, report)
    }

    // ------------------------------------------------------------ service selection

    /**
     * ARCHITECTURE.md §3 step 1: the weekday (Mon–Fri) `service_id` set with the most
     * trips. With no `calendar.txt`, falls back to `calendar_dates.txt` additions; with
     * neither, all services are assumed active.
     */
    private fun selectServices(feed: GtfsFeed, warnings: MutableList<String>): Set<String> {
        if (feed.calendars.isNotEmpty()) {
            var best: Set<String> = emptySet()
            var bestCount = -1
            for (day in 0..4) { // Monday..Friday
                val services = feed.calendars.filter { it.activeDays.getOrNull(day) == true }
                    .map { it.serviceId }
                    .toSet()
                val count = feed.trips.count { it.serviceId in services }
                if (count > bestCount) {
                    best = services
                    bestCount = count
                }
            }
            return best
        }
        if (feed.calendarDates.isNotEmpty()) {
            warnings += "No calendar.txt; services taken from calendar_dates.txt additions"
            return feed.calendarDates.filter { it.exceptionType == 1 }.map { it.serviceId }.toSet()
        }
        warnings += "No calendar.txt or calendar_dates.txt; assuming all services are active"
        return feed.trips.map { it.serviceId }.toSet()
    }

    // ------------------------------------------------------------ stop merging

    private class MergedNode(
        val id: String,
        val name: String,
        val latLng: LatLng,
        val zoneId: String?,
        val underground: Boolean?,
        val memberStopIds: List<String>,
    )

    /**
     * ARCHITECTURE.md §3 step 2: group by `parent_station` when present; otherwise merge
     * stops within 50 m sharing a normalized (lowercased, punctuation-stripped) name.
     * Node coordinate = centroid; node id = stable hash of the member stop_ids.
     */
    private fun mergeStops(stops: List<GtfsStop>, warnings: MutableList<String>): List<MergedNode> {
        val byId = stops.associateBy { it.id }
        val groups = ArrayList<List<GtfsStop>>()

        val parented = stops.filter { it.parentStation != null && it.locationType != 1 }
        val parentIds = parented.mapNotNull { it.parentStation }.toSet()
        for ((parentId, children) in parented.groupBy { it.parentStation!! }.toSortedMap()) {
            val parentRow = byId[parentId]
            groups += if (parentRow != null) children + parentRow else children
        }

        // Remaining plain stops: cluster by normalized name within 50 m (union-find).
        val loose = stops.filter {
            it.parentStation == null && it.locationType != 1 && it.id !in parentIds
        }
        val unplaceable = loose.filter { it.lat == null || it.lon == null }
        if (unplaceable.isNotEmpty()) {
            warnings += "${unplaceable.size} stops without coordinates were dropped"
        }
        val placeable = loose - unplaceable.toSet()
        for (nameGroup in placeable.groupBy { normalizeName(it.name) }.values) {
            val parent = IntArray(nameGroup.size) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                var c = x
                while (parent[c] != c) {
                    val n = parent[c]
                    parent[c] = r
                    c = n
                }
                return r
            }
            for (i in nameGroup.indices) {
                for (j in i + 1 until nameGroup.size) {
                    val d = GeoMath.haversineMeters(
                        LatLng(nameGroup[i].lat!!, nameGroup[i].lon!!),
                        LatLng(nameGroup[j].lat!!, nameGroup[j].lon!!),
                    )
                    if (d <= MERGE_RADIUS_METERS) parent[find(i)] = find(j)
                }
            }
            groups += nameGroup.indices.groupBy { find(it) }.values.map { idx -> idx.map { nameGroup[it] } }
        }

        // Orphan parent rows (location_type=1, no children) are not boarding locations.
        val orphanParents = stops.count { it.locationType == 1 && it.id !in parentIds }
        if (orphanParents > 0) warnings += "$orphanParents parent stations without child stops were ignored"

        return groups.mapNotNull { members -> buildNode(members) }.sortedBy { it.id }
    }

    private fun buildNode(members: List<GtfsStop>): MergedNode? {
        val located = members.filter { it.lat != null && it.lon != null }
        if (located.isEmpty()) return null
        val memberIds = members.map { it.id }.sorted()
        val parentRow = members.firstOrNull { it.locationType == 1 }
        val name = parentRow?.name
            ?: members.groupingBy { it.name }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .first().key
        val zoneId = members.mapNotNull { it.zoneId }
            .groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
        // Underground only when the feed is unanimous about it; otherwise unknown (null).
        val underground = members.mapNotNull { it.underground }.distinct().singleOrNull()
        return MergedNode(
            id = "n" + sha1Hex(memberIds.joinToString(" ")).substring(0, 12),
            name = name,
            latLng = LatLng(
                located.sumOf { it.lat!! } / located.size,
                located.sumOf { it.lon!! } / located.size,
            ),
            zoneId = zoneId,
            underground = underground,
            memberStopIds = memberIds,
        )
    }

    /** Lowercase, punctuation stripped, whitespace collapsed. */
    private fun normalizeName(name: String): String =
        name.lowercase().replace(NON_ALNUM, " ").trim()

    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ helpers

    private data class EdgeKey(val fromId: String, val toId: String, val routeId: String)

    /** GTFS `route_type` → [TransitMode] (0 tram, 1 metro, 2 rail, 3 bus, 4 ferry, else bus). */
    fun modeForRouteType(routeType: Int): TransitMode = when (routeType) {
        0 -> TransitMode.TRAM
        1 -> TransitMode.METRO
        2 -> TransitMode.RAIL
        3 -> TransitMode.BUS
        4 -> TransitMode.FERRY
        else -> TransitMode.BUS
    }

    /** Median of a value→count histogram; even totals take the mean of the two middles. */
    private fun weightedMedian(histogram: TreeMap<Int, Int>): Int {
        val total = histogram.values.sum()
        val loIndex = (total - 1) / 2
        val hiIndex = total / 2
        var seen = 0
        var lo = -1
        for ((value, count) in histogram) {
            if (lo < 0 && seen + count > loIndex) lo = value
            if (seen + count > hiIndex) return (lo + value) / 2
            seen += count
        }
        return lo // unreachable for non-empty histograms
    }

    /** Most frequent pattern; ties resolved by the lexicographically smallest id sequence. */
    private fun dominantPattern(counts: Map<List<String>, Int>): List<String> =
        counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<List<String>, Int>> { it.value }
                    .thenBy { it.key.joinToString(" ") },
            )
            .first().key

    /** Most frequent mode among serving routes; ties resolved by enum ordinal. */
    private fun dominantMode(modes: List<TransitMode>): TransitMode =
        modes.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<TransitMode, Int>> { it.value }.thenBy { it.key.ordinal })
            .first().key

    private fun normalizeColor(raw: String?): String {
        val hex = raw?.trim()?.removePrefix("#")?.uppercase()
        return if (hex != null && hex.length == 6 && hex.all { it.isDigit() || it in 'A'..'F' }) {
            "#$hex"
        } else {
            "#9E9E9E"
        }
    }

    /** Highest route count, then highest edge degree, then lowest id (GAME_DESIGN.md §8 item 4). */
    private fun defaultStartStation(network: TransitNetwork): String? {
        if (network.stations.isEmpty()) return null
        val degree = HashMap<String, Int>()
        for (edge in network.edges) {
            degree.merge(edge.fromId, 1, Int::plus)
            degree.merge(edge.toId, 1, Int::plus)
        }
        return network.stations
            .sortedWith(
                compareByDescending<Station> { it.routeIds.size }
                    .thenByDescending { degree[it.id] ?: 0 }
                    .thenBy { it.id },
            )
            .first().id
    }

    private fun boundingBox(points: List<LatLng>): BoundingBox =
        if (points.isEmpty()) {
            BoundingBox(0.0, 0.0, 0.0, 0.0)
        } else {
            BoundingBox(
                minLat = points.minOf { it.lat },
                minLon = points.minOf { it.lon },
                maxLat = points.maxOf { it.lat },
                maxLon = points.maxOf { it.lon },
            )
        }
}
