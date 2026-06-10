package io.terminus.core.questions

import io.terminus.core.cards.ActiveEffect
import io.terminus.core.cards.CardType
import io.terminus.core.game.CooldownMultiplier
import io.terminus.core.game.GameConfig
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameState
import io.terminus.core.game.Player
import io.terminus.core.game.PlayerId
import io.terminus.core.game.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** CooldownTracker tests (ARCHITECTURE.md §6 test plan item 4: cooldown logic). */
class CooldownTrackerTest {

    private fun Long.min(): Long = this * 60_000L

    private fun assertBlocked(
        status: CooldownStatus,
        reason: CooldownBlockReason,
        remainingMillis: Long,
        message: String? = null,
    ) {
        val blocked = assertIs<CooldownStatus.Blocked>(status, message)
        assertEquals(reason, blocked.reason, message)
        assertEquals(remainingMillis, blocked.remainingMillis, message)
    }

    // ------------------------------------------------------------ global vs category

    @Test
    fun `fresh tracker allows every category immediately`() {
        val tracker = CooldownTracker()
        for (category in QuestionCategory.entries) {
            assertEquals(CooldownStatus.Allowed, tracker.canAsk(category, 0L), "$category")
        }
    }

    @Test
    fun `recordAnswered starts both the category cooldown and the 2 minute global cooldown`() {
        val tracker = CooldownTracker().recordAnswered(QuestionCategory.RADIUS_PING, 0L)

        // Same category: blocked by its own 5:00 cooldown (the binding constraint).
        assertBlocked(
            tracker.canAsk(QuestionCategory.RADIUS_PING, 1_000L),
            CooldownBlockReason.CATEGORY_COOLDOWN,
            remainingMillis = 5L.min() - 1_000L,
        )
        // Other category: blocked only by the global 2:00 cooldown.
        assertBlocked(
            tracker.canAsk(QuestionCategory.LINE_CHECK, 1_000L),
            CooldownBlockReason.GLOBAL_COOLDOWN,
            remainingMillis = 2L.min() - 1_000L,
        )
        // Global elapses at exactly 2:00 (inclusive boundary).
        assertEquals(CooldownStatus.Allowed, tracker.canAsk(QuestionCategory.LINE_CHECK, 2L.min()))
        // Category elapses at exactly 5:00; one millisecond earlier it is still blocked.
        assertBlocked(
            tracker.canAsk(QuestionCategory.RADIUS_PING, 5L.min() - 1),
            CooldownBlockReason.CATEGORY_COOLDOWN,
            remainingMillis = 1L,
        )
        assertEquals(CooldownStatus.Allowed, tracker.canAsk(QuestionCategory.RADIUS_PING, 5L.min()))
    }

    @Test
    fun `blocked reason is the blocker with the largest remaining time`() {
        // LINE_CHECK: category 3:00, global 2:00 -> category binds early on...
        val tracker = CooldownTracker().recordAnswered(QuestionCategory.LINE_CHECK, 0L)
        assertBlocked(
            tracker.canAsk(QuestionCategory.LINE_CHECK, 0L),
            CooldownBlockReason.CATEGORY_COOLDOWN,
            remainingMillis = 3L.min(),
        )
        // ...and a different category at t=1:00 is bound by the global cooldown.
        assertBlocked(
            tracker.canAsk(QuestionCategory.THERMOMETER, 1L.min()),
            CooldownBlockReason.GLOBAL_COOLDOWN,
            remainingMillis = 1L.min(),
        )
    }

    // ---------------------------------------------------------------- multiplier

    @Test
    fun `cooldown multiplier scales both the category and the global cooldown`() {
        val half = CooldownTracker(multiplier = CooldownMultiplier.HALF)
            .recordAnswered(QuestionCategory.RADIUS_PING, 0L)
        assertEquals(1L.min(), half.globalUntilMillis) // 2:00 x 0.5
        assertEquals(150_000L, half.categoryUntilMillis.getValue(QuestionCategory.RADIUS_PING)) // 5:00 x 0.5

        val double = CooldownTracker(multiplier = CooldownMultiplier.DOUBLE)
            .recordAnswered(QuestionCategory.COMPASS_CALL, 0L)
        assertEquals(4L.min(), double.globalUntilMillis) // 2:00 x 2
        assertEquals(8L.min(), double.categoryUntilMillis.getValue(QuestionCategory.COMPASS_CALL)) // 4:00 x 2
    }

    // ------------------------------------------------------ Service Change bonuses

    @Test
    fun `Service Change bonuses stack and extend subsequent category cooldowns`() {
        val tracker = CooldownTracker()
            .withBonus(QuestionCategory.LINEUP, 5L.min())
            .withBonus(QuestionCategory.LINEUP, 5L.min()) // second copy on the same category stacks
        assertEquals(10L.min(), tracker.bonusFor(QuestionCategory.LINEUP))

        val answered = tracker.recordAnswered(QuestionCategory.LINEUP, 0L)
        // 5:00 base + 10:00 stacked bonus.
        assertEquals(15L.min(), answered.categoryUntilMillis.getValue(QuestionCategory.LINEUP))
        // Other categories are unaffected.
        assertEquals(0L, tracker.bonusFor(QuestionCategory.RADIUS_PING))
        // The global cooldown is unaffected by category bonuses.
        assertEquals(2L.min(), answered.globalUntilMillis)
    }

    @Test
    fun `multiplier scales the base cooldown but not the Service Change bonus`() {
        val tracker = CooldownTracker(multiplier = CooldownMultiplier.DOUBLE)
            .withBonus(QuestionCategory.LINEUP, 5L.min())
            .recordAnswered(QuestionCategory.LINEUP, 0L)
        // 5:00 x 2 + 5:00 flat bonus = 15:00 (not 20:00).
        assertEquals(15L.min(), tracker.categoryUntilMillis.getValue(QuestionCategory.LINEUP))
    }

    // ------------------------------------------------- disable and lockout windows

    @Test
    fun `Tunnel Vision style disable window blocks only that category until it expires`() {
        val tracker = CooldownTracker().withCategoryDisabled(QuestionCategory.RADIUS_PING, 8L.min())
        assertBlocked(
            tracker.canAsk(QuestionCategory.RADIUS_PING, 0L),
            CooldownBlockReason.CATEGORY_DISABLED,
            remainingMillis = 8L.min(),
        )
        assertEquals(CooldownStatus.Allowed, tracker.canAsk(QuestionCategory.COMPASS_CALL, 0L))
        assertEquals(CooldownStatus.Allowed, tracker.canAsk(QuestionCategory.RADIUS_PING, 8L.min()))
        // A shorter second window never shrinks an existing one.
        val shorter = tracker.withCategoryDisabled(QuestionCategory.RADIUS_PING, 1L.min())
        assertEquals(tracker, shorter)
    }

    @Test
    fun `Dead Zone style lockout blocks every category until it expires`() {
        val tracker = CooldownTracker().withLockout(6L.min())
        for (category in QuestionCategory.entries) {
            assertBlocked(
                tracker.canAsk(category, 1L.min()),
                CooldownBlockReason.LOCKED_OUT,
                remainingMillis = 5L.min(),
                message = "$category",
            )
            assertEquals(CooldownStatus.Allowed, tracker.canAsk(category, 6L.min()), "$category")
        }
        // A shorter second lockout never shrinks an existing one.
        assertEquals(tracker, tracker.withLockout(3L.min()))
    }

    @Test
    fun `a category cooldown outlasting a lockout is reported as the binding blocker`() {
        val tracker = CooldownTracker()
            .recordAnswered(QuestionCategory.THERMOMETER, 0L) // 6:00 category cooldown
            .withLockout(3L.min())
        // Lockout 3:00 < category 6:00 -> the category cooldown binds.
        assertBlocked(
            tracker.canAsk(QuestionCategory.THERMOMETER, 0L),
            CooldownBlockReason.CATEGORY_COOLDOWN,
            remainingMillis = 6L.min(),
        )
        // For another category the lockout binds (global is only 2:00).
        assertBlocked(
            tracker.canAsk(QuestionCategory.LINE_CHECK, 0L),
            CooldownBlockReason.LOCKED_OUT,
            remainingMillis = 3L.min(),
        )
    }

    // ------------------------------------------------------- GameState round-trip

    private fun gameState(
        multiplier: CooldownMultiplier = CooldownMultiplier.NORMAL,
        globalUntil: Long = 0L,
        categoryUntil: Map<QuestionCategory, Long> = emptyMap(),
        categoryBonus: Map<QuestionCategory, Long> = emptyMap(),
        effects: List<ActiveEffect> = emptyList(),
    ): GameState {
        val human = PlayerId("human")
        return GameState(
            config = GameConfig(cityId = "test-city", cooldownMultiplier = multiplier),
            roundIndex = 0,
            phase = GamePhase.SEEKING,
            gameTimeMillis = 0L,
            players = listOf(Player.HumanPlayer(human, "Hider")),
            roles = mapOf(human to Role.HIDER),
            positions = emptyMap(),
            activeEffects = effects,
            globalCooldownUntilMillis = globalUntil,
            categoryCooldownUntilMillis = categoryUntil,
            categoryCooldownBonusMillis = categoryBonus,
        )
    }

    @Test
    fun `fromGameState reads the cooldown fields, the multiplier, and effect windows`() {
        val state = gameState(
            multiplier = CooldownMultiplier.HALF,
            globalUntil = 90_000L,
            categoryUntil = mapOf(QuestionCategory.LINEUP to 4L.min()),
            categoryBonus = mapOf(QuestionCategory.LINEUP to 5L.min()),
            effects = listOf(
                ActiveEffect(CardType.TUNNEL_VISION, startGameMillis = 0L, expiryGameMillis = 8L.min()),
                ActiveEffect(CardType.DEAD_ZONE, startGameMillis = 0L, expiryGameMillis = 6L.min()),
                // Irrelevant and untimed effects are ignored.
                ActiveEffect(CardType.STALLED_TRAIN, startGameMillis = 0L, expiryGameMillis = 4L.min()),
                ActiveEffect(CardType.BIGGER_BAG, startGameMillis = 0L, expiryGameMillis = null),
            ),
        )
        val tracker = CooldownTracker.fromGameState(state)
        assertEquals(CooldownMultiplier.HALF, tracker.multiplier)
        assertEquals(90_000L, tracker.globalUntilMillis)
        assertEquals(4L.min(), tracker.categoryUntilMillis.getValue(QuestionCategory.LINEUP))
        assertEquals(5L.min(), tracker.bonusFor(QuestionCategory.LINEUP))
        assertEquals(8L.min(), tracker.categoryDisabledUntilMillis.getValue(QuestionCategory.RADIUS_PING))
        assertEquals(6L.min(), tracker.lockoutUntilMillis)

        // Behavioral check: Q1 is bound by Tunnel Vision past the Dead Zone window.
        assertBlocked(
            tracker.canAsk(QuestionCategory.RADIUS_PING, 7L.min()),
            CooldownBlockReason.CATEGORY_DISABLED,
            remainingMillis = 1L.min(),
        )
    }

    @Test
    fun `applyTo writes the three persistent fields back and leaves effects alone`() {
        val original = gameState(
            effects = listOf(ActiveEffect(CardType.DEAD_ZONE, startGameMillis = 0L, expiryGameMillis = 6L.min())),
        )
        val updated = CooldownTracker.fromGameState(original)
            .withBonus(QuestionCategory.RAIL_RANGE, 5L.min())
            .recordAnswered(QuestionCategory.RAIL_RANGE, 10L.min())
            .applyTo(original)

        assertEquals(12L.min(), updated.globalCooldownUntilMillis)
        assertEquals(20L.min(), updated.categoryCooldownUntilMillis.getValue(QuestionCategory.RAIL_RANGE))
        assertEquals(5L.min(), updated.categoryCooldownBonusMillis.getValue(QuestionCategory.RAIL_RANGE))
        // Effects (incl. the Dead Zone window) stay untouched — they are owned by the card engine.
        assertEquals(original.activeEffects, updated.activeEffects)

        // Round-trip: reading the updated state reproduces the tracker's persistent fields.
        val reread = CooldownTracker.fromGameState(updated)
        assertEquals(12L.min(), reread.globalUntilMillis)
        assertEquals(20L.min(), reread.categoryUntilMillis.getValue(QuestionCategory.RAIL_RANGE))
        assertEquals(5L.min(), reread.bonusFor(QuestionCategory.RAIL_RANGE))
        assertEquals(6L.min(), reread.lockoutUntilMillis)
        assertTrue(reread.categoryDisabledUntilMillis.isEmpty())
    }
}
