package io.terminus.core.cityfile

import io.terminus.core.geo.BoundingBox
import io.terminus.core.transit.RouteLine
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitEdge
import kotlinx.serialization.Serializable

/**
 * The on-disk city format (ARCHITECTURE.md §3.1): GZIP-compressed JSON with extension
 * `.city.json.gz`. Stations/routes/edges mirror the `transit` domain classes exactly.
 *
 * Encoding/decoding (`CityCodec`, kotlinx-serialization + GZIP) is W2's; this DTO is
 * the frozen Phase 0 schema.
 *
 * @property schemaVersion format version; current version is 1.
 * @property cityId stable id, used as the file stem `<cityId>.city.json.gz` (ARCHITECTURE.md §5).
 * @property displayName name shown in the city manager and setup screens.
 * @property attribution data source attribution text.
 * @property isSynthetic true for hand-authored demo cities (ARCHITECTURE.md §4).
 * @property bbox bounding box of the full network extent.
 * @property stations all merged network nodes.
 * @property routes all route lines.
 * @property edges all directed edges including walking transfers.
 * @property defaultStartStationId default start station (GAME_DESIGN.md §8 item 4).
 */
@Serializable
data class CityFile(
    val schemaVersion: Int = 1,
    val cityId: String,
    val displayName: String,
    val attribution: String,
    val isSynthetic: Boolean,
    val bbox: BoundingBox,
    val stations: List<Station>,
    val routes: List<RouteLine>,
    val edges: List<TransitEdge>,
    val defaultStartStationId: String,
)
