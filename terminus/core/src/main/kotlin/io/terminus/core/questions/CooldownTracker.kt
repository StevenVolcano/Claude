package io.terminus.core.questions

import io.terminus.core.cards.CardType
import io.terminus.core.game.CooldownMultiplier
import io.terminus.core.game.GameState
import kotlinx.serialization.Serializable

/** Why a question may not be asked right now (GAME_DESIGN.md §3, §4.2 C6/C18). */
@Serializable
enum class CooldownBlockReason {
    /** A full question lockout is in force (Dead Zone, C18). */
    LOCKED_OUT,

    /** This category is disabled by a curse window (Tunnel Vision, C6, disables Q1). */
    CATEGORY_DISABLED,

    /** The 2:00 global cooldown between any two questions has not elapsed (§3). */
    GLOBAL_COOLDOWN,

    /** This category's own cooldown has not elapsed (§3 table + Service Change bonus). */
    CATEGORY_COOLDOWN,
}

/** Result of [CooldownTracker.canAsk]. */
@Serializable
sealed class CooldownStatus {
    /** The question may be asked now. */
    @Serializable
    data object Allowed : CooldownStatus()

    /**
     * The question is blocked. When several blockers are active simultaneously,
     * [reason] is the one with the **largest** [remainingMillis] (the binding
     * constraint — the earliest moment the category becomes askable again); ties
     * break by [CooldownBlockReason] declaration order.
     */
    @Serializable
    data class Blocked(
        val reason: CooldownBlockReason,
        val remainingMillis: Long,
    ) : CooldownStatus()
}

/**
 * Immutable (copy-on-update) question cooldown state (GAME_DESIGN.md §3 general
 * rules; ARCHITECTURE.md §1.1 `questions`). All times are game-time milliseconds.
 *
 * The persistent "until" fields mirror `GameState` exactly
 * ([GameState.globalCooldownUntilMillis], [GameState.categoryCooldownUntilMillis],
 * [GameState.categoryCooldownBonusMillis]); [fromGameState]/[applyTo] convert
 * between the two. Disable windows (Tunnel Vision, C6) and full lockouts
 * (Dead Zone, C18) are represented in `GameState` as `ActiveEffect`s and are
 * *derived* on [fromGameState]; [applyTo] therefore does not write them back —
 * the card engine (W4/W6) owns effect lifecycles.
 *
 * ### Cooldown arithmetic ([recordAnswered])
 * - global: `nowGameMillis + GLOBAL_COOLDOWN_MILLIS × multiplier`
 * - category: `nowGameMillis + baseCooldownMillis × multiplier + bonusMillis(category)`
 *
 * The setup multiplier (×0.5/×1/×2, §8 item 7) scales **all** cooldowns — both the
 * global 2:00 and every per-category base. The Service Change (C19) bonus is a flat
 * runtime addition of game time ("+5:00 for the rest of the round", stacking) and is
 * deliberately **not** scaled by the multiplier.
 *
 * Q3 Thermometer's cooldown "starts when resolved" (§3): the caller must invoke
 * [recordAnswered] at resolution time, not at arm time. Likewise, answers delayed by
 * Scrambled Signal (C7) start cooldowns at delivery (§4.2) — call [recordAnswered]
 * with the delivery time. A vetoed question (C11) still incurs both cooldowns (§4.2)
 * — call [recordAnswered] for it too.
 *
 * @property multiplier the round's cooldown multiplier (`GameConfig.cooldownMultiplier`).
 * @property globalUntilMillis no question of any category before this game time.
 * @property categoryUntilMillis per-category earliest next-ask game time.
 * @property categoryBonusMillis permanent per-category additions from Service Change (C19, stacks).
 * @property categoryDisabledUntilMillis per-category disable-window ends (Tunnel Vision, C6).
 * @property lockoutUntilMillis full question lockout end (Dead Zone, C18); 0 when none.
 */
@Serializable
data class CooldownTracker(
    val multiplier: CooldownMultiplier = CooldownMultiplier.NORMAL,
    val globalUntilMillis: Long = 0L,
    val categoryUntilMillis: Map<QuestionCategory, Long> = emptyMap(),
    val categoryBonusMillis: Map<QuestionCategory, Long> = emptyMap(),
    val categoryDisabledUntilMillis: Map<QuestionCategory, Long> = emptyMap(),
    val lockoutUntilMillis: Long = 0L,
) {

    /**
     * May a question of [category] be asked at [nowGameMillis]?
     *
     * A window whose "until" equals `nowGameMillis` has elapsed (blocked while
     * `now < until`). See [CooldownStatus.Blocked] for which reason is reported
     * when several blockers overlap.
     */
    fun canAsk(category: QuestionCategory, nowGameMillis: Long): CooldownStatus {
        val blockers = listOf(
            CooldownBlockReason.LOCKED_OUT to lockoutUntilMillis,
            CooldownBlockReason.CATEGORY_DISABLED to (categoryDisabledUntilMillis[category] ?: 0L),
            CooldownBlockReason.GLOBAL_COOLDOWN to globalUntilMillis,
            CooldownBlockReason.CATEGORY_COOLDOWN to (categoryUntilMillis[category] ?: 0L),
        )
        val binding = blockers
            .filter { (_, until) -> until > nowGameMillis }
            .maxByOrNull { (_, until) -> until }
            ?: return CooldownStatus.Allowed
        return CooldownStatus.Blocked(
            reason = binding.first,
            remainingMillis = binding.second - nowGameMillis,
        )
    }

    /**
     * Records that a question of [category] was answered (or vetoed) at
     * [nowGameMillis], starting the global cooldown and the category's cooldown
     * (see the class KDoc for the arithmetic and for *when* to call this for
     * Thermometer / Scrambled Signal / vetoes).
     */
    fun recordAnswered(category: QuestionCategory, nowGameMillis: Long): CooldownTracker = copy(
        globalUntilMillis = nowGameMillis + scaled(QuestionRules.GLOBAL_COOLDOWN_MILLIS),
        categoryUntilMillis = categoryUntilMillis +
            (category to nowGameMillis + scaled(category.baseCooldownMillis) + bonusFor(category)),
    )

    /**
     * Adds a permanent cooldown bonus to [category] — Service Change, C19
     * (+5:00 per copy, stacking; GAME_DESIGN.md §4.2). Affects subsequent
     * [recordAnswered] calls only; an already-running cooldown is not extended.
     */
    fun withBonus(category: QuestionCategory, additionalMillis: Long): CooldownTracker = copy(
        categoryBonusMillis = categoryBonusMillis + (category to bonusFor(category) + additionalMillis),
    )

    /**
     * Disables [category] until [untilGameMillis] — Tunnel Vision, C6 (disables Q1
     * for 8:00; GAME_DESIGN.md §4.2). An existing longer window is kept.
     */
    fun withCategoryDisabled(category: QuestionCategory, untilGameMillis: Long): CooldownTracker = copy(
        categoryDisabledUntilMillis = categoryDisabledUntilMillis +
            (category to maxOf(categoryDisabledUntilMillis[category] ?: 0L, untilGameMillis)),
    )

    /**
     * Locks out all questions until [untilGameMillis] — Dead Zone, C18 (6:00;
     * GAME_DESIGN.md §4.2). An existing longer lockout is kept.
     */
    fun withLockout(untilGameMillis: Long): CooldownTracker = copy(
        lockoutUntilMillis = maxOf(lockoutUntilMillis, untilGameMillis),
    )

    /** The accumulated Service Change bonus for [category] (0 when none). */
    fun bonusFor(category: QuestionCategory): Long = categoryBonusMillis[category] ?: 0L

    /**
     * Writes this tracker's persistent fields back into [state]
     * ([GameState.globalCooldownUntilMillis], [GameState.categoryCooldownUntilMillis],
     * [GameState.categoryCooldownBonusMillis]). Disable/lockout windows are *not*
     * written back: in `GameState` they live as `ActiveEffect`s owned by the card
     * engine (see class KDoc).
     */
    fun applyTo(state: GameState): GameState = state.copy(
        globalCooldownUntilMillis = globalUntilMillis,
        categoryCooldownUntilMillis = categoryUntilMillis,
        categoryCooldownBonusMillis = categoryBonusMillis,
    )

    private fun scaled(baseMillis: Long): Long = (baseMillis * multiplier.factor).toLong()

    companion object {
        /**
         * Builds a tracker from a [GameState] snapshot: the multiplier from
         * `state.config.cooldownMultiplier`, the three persistent fields verbatim,
         * and the disable/lockout windows derived from timed `ActiveEffect`s —
         * [CardType.TUNNEL_VISION] disables [QuestionCategory.RADIUS_PING] until its
         * expiry; [CardType.DEAD_ZONE] locks out all questions until its expiry
         * (GAME_DESIGN.md §4.2 C6/C18).
         */
        fun fromGameState(state: GameState): CooldownTracker {
            var tracker = CooldownTracker(
                multiplier = state.config.cooldownMultiplier,
                globalUntilMillis = state.globalCooldownUntilMillis,
                categoryUntilMillis = state.categoryCooldownUntilMillis,
                categoryBonusMillis = state.categoryCooldownBonusMillis,
            )
            for (effect in state.activeEffects) {
                val expiry = effect.expiryGameMillis ?: continue
                tracker = when (effect.type) {
                    CardType.TUNNEL_VISION ->
                        tracker.withCategoryDisabled(QuestionCategory.RADIUS_PING, expiry)
                    CardType.DEAD_ZONE -> tracker.withLockout(expiry)
                    else -> tracker
                }
            }
            return tracker
        }
    }
}
