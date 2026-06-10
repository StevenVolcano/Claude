package io.terminus.core.gtfs

import kotlinx.serialization.Serializable

/**
 * Summary of a GTFS import run (ARCHITECTURE.md §1.1 `gtfs`, §3).
 *
 * Produced by W2's `GtfsParser`/`NetworkBuilder`; consumed by the import UI
 * (ARCHITECTURE.md §1.2 `ui.cities`) and the gtfs test plan (ARCHITECTURE.md §6 item 2).
 *
 * @property stopsRead raw stop rows read from `stops.txt`.
 * @property stopsMerged merged network nodes after parent_station/proximity merging (§3 step 2).
 * @property routes route lines built.
 * @property trips trips on the selected weekday service set (§3 step 1).
 * @property edges directed route edges built (§3 step 3).
 * @property transferEdges walking transfer edges added (§3 step 4).
 * @property droppedOutsideBoundary nodes dropped by the boundary clip (§3 step 6).
 * @property warnings human-readable import warnings.
 */
@Serializable
data class GtfsImportReport(
    val stopsRead: Int,
    val stopsMerged: Int,
    val routes: Int,
    val trips: Int,
    val edges: Int,
    val transferEdges: Int,
    val droppedOutsideBoundary: Int,
    val warnings: List<String> = emptyList(),
)
