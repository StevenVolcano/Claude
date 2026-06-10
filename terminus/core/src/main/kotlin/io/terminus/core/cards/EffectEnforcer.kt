package io.terminus.core.cards

import io.terminus.core.game.GameEvent
import io.terminus.core.game.GameRules
import io.terminus.core.game.PlayerId
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.transit.RouteLine
import io.terminus.core.transit.Station
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** One timestamped GPS fix of the human player (game time, GAME_DESIGN.md §2.2). */
@Serializable
data class GpsSample(
    val gameTimeMillis: Long,
    val position: LatLng,
)

/**
 * A detected human rule violation (GAME_DESIGN.md §1.3, §4.1): the listed penalty
 * minutes are added to the hider's score and, for the first violation of a timed
 * curse, the curse timer restarts (max [GameRules.MAX_CURSE_RESTARTS] per curse).
 */
data class Violation(
    val playerId: PlayerId,
    val effectType: CardType,
    val penaltyMinutes: Double,
    val description: String,
    val curseTimerRestarted: Boolean,
    val gameTimeMillis: Long,
) {
    /** The events the engine logs for this violation (§1.3: detection then penalty). */
    fun toEvents(): List<GameEvent> = listOf(
        GameEvent.ViolationDetected(playerId, effectType, description, gameTimeMillis),
        GameEvent.PenaltyApplied(playerId, penaltyMinutes, description, gameTimeMillis),
    )
}

/**
 * Per-effect enforcement bookkeeping carried between 5 s check ticks, keyed by
 * `"<type>@<startGameMillis>"`. Serializable so sim-mode autosave can round-trip it.
 */
@Serializable
data class EffectMemory(
    /** C4: play-time anchor position (re-anchored after each violation). */
    val anchor: LatLng? = null,
    /** C9: the anchor station id (re-anchored after each violation). */
    val anchorStationId: String? = null,
    /** C5: stations currently being dwelt at → game time the 75 m circle was entered. */
    val dwellEnteredAt: Map<String, Long> = emptyMap(),
    /** C5: stations whose 30 s dwell has been completed. */
    val dwellSatisfied: Set<String> = emptySet(),
    /** C5: stations already penalized (one missed-dwell penalty per station). */
    val dwellPenalized: Set<String> = emptySet(),
    /** C10: meters advanced along the banned corridor since entering it. */
    val corridorProgressMeters: Double = 0.0,
    /** C10: arc-length position (meters from corridor start) of the last in-corridor fix. */
    val lastCorridorArcMeters: Double? = null,
)

/** All enforcement bookkeeping for one human seeker, carried between check ticks. */
@Serializable
data class EnforcementMemory(
    /** Newest sample timestamp already processed; older samples are never re-checked. */
    val lastProcessedMillis: Long = Long.MIN_VALUE,
    val effects: Map<String, EffectMemory> = emptyMap(),
)

/** Result of [EffectEnforcer.checkSeeker]. */
data class SeekerCheckResult(
    val violations: List<Violation>,
    /** The input effects with violation-triggered timer restarts applied. */
    val effects: List<ActiveEffect>,
    val memory: EnforcementMemory,
)

/** Hider-zone excursion bookkeeping (GAME_DESIGN.md §5.2), carried between check ticks. */
@Serializable
data class HiderZoneMemory(
    val lastProcessedMillis: Long? = null,
    /** Cumulative millis of the current excursion beyond 300 m; reset on return. */
    val excursionMillis: Long = 0L,
)

/** Result of [EffectEnforcer.checkHiderZone]. */
data class HiderZoneResult(
    /** True while the current excursion exceeds 60 cumulative seconds (§5.2). */
    val scoreAccrualPaused: Boolean,
    val memory: HiderZoneMemory,
)

/**
 * GPS-mode compliance checks on the human player, evaluated every 5 s check tick
 * (GAME_DESIGN.md §1.3, §4.1 enforcement, §5.2). Pure: takes positions + effects +
 * bookkeeping, returns violations and updated copies; the engine (W6) applies the
 * penalties and logs the events. AI players never come through here — the
 * simulation obeys curses perfectly.
 *
 * Each call processes the track samples newer than the memory's high-water mark,
 * in order, so violations are detected identically whether ticks arrive one sample
 * or many samples at a time.
 */
object EffectEnforcer {

    /** C4: maximum movement from the play-time anchor, meters (§4.2 C4). */
    const val STALLED_TRAIN_MOVE_LIMIT_METERS: Double = 100.0

    /** C4 penalty per violation, minutes (§4.2 C4: +2:00). */
    const val STALLED_TRAIN_PENALTY_MINUTES: Double = 2.0

    /** C5: dwell radius around a passed station, meters (§4.2 C5). */
    const val LOCAL_SERVICE_DWELL_RADIUS_METERS: Double = 75.0

    /** C5: required continuous dwell, millis (§4.2 C5: 30 s). */
    const val LOCAL_SERVICE_DWELL_MILLIS: Long = 30_000L

    /** C5 penalty per missed dwell, minutes (§4.2 C5: +1:00). */
    const val LOCAL_SERVICE_PENALTY_MINUTES: Double = 1.0

    /** C9: maximum distance from the anchor station, meters (§4.2 C9). */
    const val TICKET_INSPECTION_RADIUS_METERS: Double = 100.0

    /** C9 penalty per violation, minutes (§4.2 C9: +1:30). */
    const val TICKET_INSPECTION_PENALTY_MINUTES: Double = 1.5

    /** C10: corridor half-width around the banned route's polyline, meters (§4.2 C10). */
    const val DETOUR_CORRIDOR_WIDTH_METERS: Double = 150.0

    /** C10: advance along the corridor that triggers a violation, meters (§4.2 C10). */
    const val DETOUR_ADVANCE_LIMIT_METERS: Double = 200.0

    /** C10 penalty per violation, minutes (§4.2 C10: +2:00). */
    const val DETOUR_PENALTY_MINUTES: Double = 2.0

    /** The §4.2 violation penalty in minutes for a GPS-enforced curse, or null. */
    fun penaltyMinutes(type: CardType): Double? = when (type) {
        CardType.STALLED_TRAIN -> STALLED_TRAIN_PENALTY_MINUTES
        CardType.LOCAL_SERVICE -> LOCAL_SERVICE_PENALTY_MINUTES
        CardType.TICKET_INSPECTION -> TICKET_INSPECTION_PENALTY_MINUTES
        CardType.DETOUR -> DETOUR_PENALTY_MINUTES
        else -> null
    }

    // ---------------------------------------------------------------------------------
    // Seeker curse compliance (C4 / C5 / C9 / C10)
    // ---------------------------------------------------------------------------------

    /**
     * Evaluates the human seeker's [track] against every GPS-enforced active effect.
     *
     * @param effects the current active effects (non-enforced types are ignored).
     * @param seekerId the human seeker.
     * @param track the seeker's recent GPS fixes, ascending by time. Must reach back
     *   at least to each enforced curse's start so the play-time anchor can be fixed.
     * @param stations all network stations (C5 dwell detection, C9 anchoring).
     * @param routesById routes keyed by id (C10 corridor polyline).
     * @param memory bookkeeping from the previous tick ([EnforcementMemory()] initially).
     * @param nowGameMillis the check-tick time; samples after it are left for later ticks.
     */
    fun checkSeeker(
        effects: List<ActiveEffect>,
        seekerId: PlayerId,
        track: List<GpsSample>,
        stations: List<Station>,
        routesById: Map<String, RouteLine>,
        memory: EnforcementMemory,
        nowGameMillis: Long,
    ): SeekerCheckResult {
        val newSamples = track.filter {
            it.gameTimeMillis > memory.lastProcessedMillis && it.gameTimeMillis <= nowGameMillis
        }
        val stationsById = stations.associateBy { it.id }
        val violations = mutableListOf<Violation>()
        val updatedEffects = ArrayList<ActiveEffect>(effects.size)
        val updatedMemories = mutableMapOf<String, EffectMemory>()

        for (effect in effects) {
            val key = effectKey(effect)
            val mem = memory.effects[key] ?: EffectMemory()
            val check = when (effect.type) {
                CardType.STALLED_TRAIN -> checkStalledTrain(effect, mem, track, newSamples, seekerId)
                CardType.LOCAL_SERVICE -> checkLocalService(effect, mem, newSamples, stations, seekerId)
                CardType.TICKET_INSPECTION ->
                    checkTicketInspection(effect, mem, track, newSamples, stations, seekerId)
                CardType.DETOUR -> checkDetour(effect, mem, newSamples, stationsById, routesById, seekerId)
                else -> null
            }
            if (check == null) {
                updatedEffects += effect
            } else {
                violations += check.violations
                updatedEffects += check.effect
                updatedMemories[key] = check.memory
            }
        }

        val highWater = maxOf(
            memory.lastProcessedMillis,
            newSamples.lastOrNull()?.gameTimeMillis ?: memory.lastProcessedMillis,
        )
        return SeekerCheckResult(
            violations = violations,
            effects = updatedEffects,
            memory = EnforcementMemory(lastProcessedMillis = highWater, effects = updatedMemories),
        )
    }

    private data class EffectCheck(
        val violations: List<Violation>,
        val effect: ActiveEffect,
        val memory: EffectMemory,
    )

    private fun effectKey(effect: ActiveEffect): String =
        "${effect.type.name}@${effect.startGameMillis}"

    /** Samples inside the effect's active window given its current expiry. */
    private fun inWindow(effect: ActiveEffect, sample: GpsSample): Boolean =
        sample.gameTimeMillis >= effect.startGameMillis &&
            (effect.expiryGameMillis == null || sample.gameTimeMillis <= effect.expiryGameMillis!!)

    /**
     * Restarts the curse timer after a violation when allowed (§4.1: once per curse):
     * the expiry becomes `violation time + full duration`; `restartsUsed` increments.
     */
    private fun maybeRestart(effect: ActiveEffect, atMillis: Long): Pair<ActiveEffect, Boolean> {
        val duration = CardEngine.effectDurationMillis(effect.type)
        return if (duration != null && effect.restartsUsed < GameRules.MAX_CURSE_RESTARTS) {
            effect.copy(
                expiryGameMillis = atMillis + duration,
                restartsUsed = effect.restartsUsed + 1,
            ) to true
        } else {
            effect to false
        }
    }

    /** The track sample anchoring an effect: the last fix at/before its start, else the first after. */
    private fun anchorSample(track: List<GpsSample>, startGameMillis: Long): GpsSample? =
        track.lastOrNull { it.gameTimeMillis <= startGameMillis }
            ?: track.firstOrNull { it.gameTimeMillis > startGameMillis }

    /**
     * C4 Stalled Train: any fix more than 100 m from the play-time anchor is a
     * violation (+2:00). After a violation the anchor moves to the violating fix so
     * a sustained stop is not re-penalized every tick but further movement is.
     */
    private fun checkStalledTrain(
        startEffect: ActiveEffect,
        startMemory: EffectMemory,
        track: List<GpsSample>,
        newSamples: List<GpsSample>,
        seekerId: PlayerId,
    ): EffectCheck {
        var effect = startEffect
        var anchor = startMemory.anchor ?: anchorSample(track, effect.startGameMillis)?.position
        val violations = mutableListOf<Violation>()
        for (sample in newSamples) {
            if (!inWindow(effect, sample)) continue
            if (anchor == null) {
                anchor = sample.position
                continue
            }
            val moved = GeoMath.haversineMeters(sample.position, anchor)
            if (moved > STALLED_TRAIN_MOVE_LIMIT_METERS) {
                val (restarted, didRestart) = maybeRestart(effect, sample.gameTimeMillis)
                effect = restarted
                violations += Violation(
                    playerId = seekerId,
                    effectType = CardType.STALLED_TRAIN,
                    penaltyMinutes = STALLED_TRAIN_PENALTY_MINUTES,
                    description = "Stalled Train: moved ${moved.toInt()} m " +
                        "(limit ${STALLED_TRAIN_MOVE_LIMIT_METERS.toInt()} m) from the play-time position",
                    curseTimerRestarted = didRestart,
                    gameTimeMillis = sample.gameTimeMillis,
                )
                anchor = sample.position
            }
        }
        return EffectCheck(violations, effect, startMemory.copy(anchor = anchor))
    }

    /**
     * C5 Local Service: whenever the track passes within 75 m of a station, the
     * seeker must remain within 75 m for 30 s. Leaving earlier is a missed dwell
     * (+1:00, once per station). Completed dwells stay satisfied for the curse.
     */
    private fun checkLocalService(
        startEffect: ActiveEffect,
        startMemory: EffectMemory,
        newSamples: List<GpsSample>,
        stations: List<Station>,
        seekerId: PlayerId,
    ): EffectCheck {
        var effect = startEffect
        val enteredAt = startMemory.dwellEnteredAt.toMutableMap()
        val satisfied = startMemory.dwellSatisfied.toMutableSet()
        val penalized = startMemory.dwellPenalized.toMutableSet()
        val violations = mutableListOf<Violation>()

        for (sample in newSamples) {
            if (!inWindow(effect, sample)) continue
            for (station in stations) {
                val inside = GeoMath.haversineMeters(sample.position, station.latLng) <=
                    LOCAL_SERVICE_DWELL_RADIUS_METERS
                val entered = enteredAt[station.id]
                if (inside) {
                    if (station.id in satisfied) continue
                    if (entered == null) {
                        enteredAt[station.id] = sample.gameTimeMillis
                    } else if (sample.gameTimeMillis - entered >= LOCAL_SERVICE_DWELL_MILLIS) {
                        satisfied += station.id
                        enteredAt.remove(station.id)
                    }
                } else if (entered != null) {
                    enteredAt.remove(station.id)
                    if (station.id !in satisfied && station.id !in penalized) {
                        penalized += station.id
                        val (restarted, didRestart) = maybeRestart(effect, sample.gameTimeMillis)
                        effect = restarted
                        violations += Violation(
                            playerId = seekerId,
                            effectType = CardType.LOCAL_SERVICE,
                            penaltyMinutes = LOCAL_SERVICE_PENALTY_MINUTES,
                            description = "Local Service: missed the 30 s dwell at ${station.name}",
                            curseTimerRestarted = didRestart,
                            gameTimeMillis = sample.gameTimeMillis,
                        )
                    }
                }
            }
        }
        return EffectCheck(
            violations,
            effect,
            startMemory.copy(
                dwellEnteredAt = enteredAt,
                dwellSatisfied = satisfied,
                dwellPenalized = penalized,
            ),
        )
    }

    /**
     * C9 Ticket Inspection: the seeker must stay within 100 m of the station nearest
     * to them at curse start. A violation costs +1:30; afterwards the anchor becomes
     * the station nearest the violating fix so continued travel is penalized per new
     * breach, not per tick.
     */
    private fun checkTicketInspection(
        startEffect: ActiveEffect,
        startMemory: EffectMemory,
        track: List<GpsSample>,
        newSamples: List<GpsSample>,
        stations: List<Station>,
        seekerId: PlayerId,
    ): EffectCheck {
        if (stations.isEmpty()) return EffectCheck(emptyList(), startEffect, startMemory)
        var effect = startEffect
        var anchorId = startMemory.anchorStationId
            ?: anchorSample(track, effect.startGameMillis)?.let { nearestStation(stations, it.position).id }
        val violations = mutableListOf<Violation>()

        for (sample in newSamples) {
            if (!inWindow(effect, sample)) continue
            if (anchorId == null) {
                anchorId = nearestStation(stations, sample.position).id
                continue
            }
            val anchorStation = stations.firstOrNull { it.id == anchorId } ?: continue
            val distance = GeoMath.haversineMeters(sample.position, anchorStation.latLng)
            if (distance > TICKET_INSPECTION_RADIUS_METERS) {
                val (restarted, didRestart) = maybeRestart(effect, sample.gameTimeMillis)
                effect = restarted
                violations += Violation(
                    playerId = seekerId,
                    effectType = CardType.TICKET_INSPECTION,
                    penaltyMinutes = TICKET_INSPECTION_PENALTY_MINUTES,
                    description = "Ticket Inspection: strayed ${distance.toInt()} m " +
                        "(limit ${TICKET_INSPECTION_RADIUS_METERS.toInt()} m) from ${anchorStation.name}",
                    curseTimerRestarted = didRestart,
                    gameTimeMillis = sample.gameTimeMillis,
                )
                anchorId = nearestStation(stations, sample.position).id
            }
        }
        return EffectCheck(violations, effect, startMemory.copy(anchorStationId = anchorId))
    }

    private fun nearestStation(stations: List<Station>, point: LatLng): Station =
        stations.minWith(
            compareBy({ GeoMath.haversineMeters(point, it.latLng) }, { it.id }),
        )

    /**
     * C10 Detour: a violation when the track advances more than 200 m along the
     * banned route's station-to-station corridor (fixes within 150 m of its
     * polyline). Progress accumulates between consecutive in-corridor fixes and
     * resets when the seeker leaves the corridor or after a violation.
     */
    private fun checkDetour(
        startEffect: ActiveEffect,
        startMemory: EffectMemory,
        newSamples: List<GpsSample>,
        stationsById: Map<String, Station>,
        routesById: Map<String, RouteLine>,
        seekerId: PlayerId,
    ): EffectCheck {
        val routeId = (startEffect.params as? EffectParams.DetourParams)?.routeId
            ?: return EffectCheck(emptyList(), startEffect, startMemory)
        val polyline = routesById[routeId]?.orderedStationIds
            ?.mapNotNull { stationsById[it]?.latLng }
            .orEmpty()
        if (polyline.size < 2) return EffectCheck(emptyList(), startEffect, startMemory)

        var effect = startEffect
        var progress = startMemory.corridorProgressMeters
        var lastArc = startMemory.lastCorridorArcMeters
        val violations = mutableListOf<Violation>()

        for (sample in newSamples) {
            if (!inWindow(effect, sample)) continue
            val projection = projectOntoPolyline(sample.position, polyline)
            if (projection.distanceMeters <= DETOUR_CORRIDOR_WIDTH_METERS) {
                if (lastArc != null) progress += abs(projection.arcMeters - lastArc)
                lastArc = projection.arcMeters
                if (progress > DETOUR_ADVANCE_LIMIT_METERS) {
                    val (restarted, didRestart) = maybeRestart(effect, sample.gameTimeMillis)
                    effect = restarted
                    violations += Violation(
                        playerId = seekerId,
                        effectType = CardType.DETOUR,
                        penaltyMinutes = DETOUR_PENALTY_MINUTES,
                        description = "Detour: advanced ${progress.toInt()} m " +
                            "(limit ${DETOUR_ADVANCE_LIMIT_METERS.toInt()} m) along the banned route",
                        curseTimerRestarted = didRestart,
                        gameTimeMillis = sample.gameTimeMillis,
                    )
                    progress = 0.0
                }
            } else {
                lastArc = null
                progress = 0.0
            }
        }
        return EffectCheck(
            violations,
            effect,
            startMemory.copy(corridorProgressMeters = progress, lastCorridorArcMeters = lastArc),
        )
    }

    private data class PolylineProjection(
        val distanceMeters: Double,
        /** Arc-length position of the projected point, meters from the polyline start. */
        val arcMeters: Double,
    )

    /**
     * Projects [point] onto [polyline] in a local equirectangular plane centered on
     * the point (the same approximation as `GeoMath.distanceToPolylineMeters`),
     * returning the perpendicular distance and the arc-length position.
     */
    private fun projectOntoPolyline(point: LatLng, polyline: List<LatLng>): PolylineProjection {
        val metersPerLatDeg = Math.toRadians(1.0) * GeoMath.EARTH_RADIUS_METERS
        val metersPerLonDeg = metersPerLatDeg * cos(Math.toRadians(point.lat))

        var bestDistance = Double.MAX_VALUE
        var bestArc = 0.0
        var arcBase = 0.0
        var x1 = GeoMath.wrapLon180(polyline[0].lon - point.lon) * metersPerLonDeg
        var y1 = (polyline[0].lat - point.lat) * metersPerLatDeg
        for (i in 1 until polyline.size) {
            val x2 = x1 + GeoMath.wrapLon180(polyline[i].lon - polyline[i - 1].lon) * metersPerLonDeg
            val y2 = (polyline[i].lat - point.lat) * metersPerLatDeg
            val dx = x2 - x1
            val dy = y2 - y1
            val segmentLength = hypot(dx, dy)
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0 else ((-x1 * dx - y1 * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val distance = hypot(x1 + t * dx, y1 + t * dy)
            if (distance < bestDistance) {
                bestDistance = distance
                bestArc = arcBase + t * segmentLength
            }
            arcBase += segmentLength
            x1 = x2
            y1 = y2
        }
        return PolylineProjection(bestDistance, bestArc)
    }

    // ---------------------------------------------------------------------------------
    // Hider zone compliance (GAME_DESIGN.md §5.2)
    // ---------------------------------------------------------------------------------

    /**
     * Accumulates the hider's excursion time beyond 300 m of [zoneCenter]: once an
     * excursion exceeds 60 cumulative seconds, score accrual pauses until the hider
     * returns inside the zone (no penalty — the score simply stops, §5.2). Returning
     * resets the accumulator. The engine compares [HiderZoneResult.scoreAccrualPaused]
     * with `GameState.scoreAccrualPaused` and emits `ScoreAccrualChanged` on change.
     *
     * Not applied while a Transfer Slip relocation window is open — the engine must
     * skip this check (and reset the memory) while `hiderRelocationDeadlineMillis`
     * is set or `hiderZoneStationId` is null.
     */
    fun checkHiderZone(
        track: List<GpsSample>,
        zoneCenter: LatLng,
        memory: HiderZoneMemory,
        nowGameMillis: Long,
    ): HiderZoneResult {
        var lastProcessed = memory.lastProcessedMillis
        var excursionMillis = memory.excursionMillis
        for (sample in track) {
            if (lastProcessed != null && sample.gameTimeMillis <= lastProcessed) continue
            if (sample.gameTimeMillis > nowGameMillis) break
            val outside = GeoMath.haversineMeters(sample.position, zoneCenter) >
                GameRules.HIDING_ZONE_RADIUS_METERS
            if (outside) {
                if (lastProcessed != null) excursionMillis += sample.gameTimeMillis - lastProcessed
            } else {
                excursionMillis = 0L
            }
            lastProcessed = sample.gameTimeMillis
        }
        return HiderZoneResult(
            scoreAccrualPaused = excursionMillis > GameRules.EXCURSION_GRACE_SECONDS * 1_000L,
            memory = HiderZoneMemory(lastProcessed, excursionMillis),
        )
    }
}
