package com.arrowescape.game

/**
 * Pure-Kotlin gameplay constants + the headless state reducer for one level attempt.
 *
 * [GameReducer] is deliberately free of Android/Compose types so the exact logic the ViewModel
 * runs (free/blocked taps, hearts, crimson markers, win detection, "Try again" reset semantics)
 * is covered by plain JVM unit tests. The Compose layer only animates what this produces.
 */

/** Tunables shared between UI animation and tests. */
object GameRules {
    const val MAX_HEARTS = 3
    const val STARTING_HINTS = 3
    const val HINT_EVERY_N_LEVELS = 5
    const val LEVELS_PER_PACK = LevelPack.LEVELS_PER_PACK // 500

    /** Slide-out duration range; longer snakes accelerate earlier (ease-in). */
    fun slideDurationMs(pathCells: Int): Long = (350L + (pathCells - 2) * 18L).coerceAtMost(650L)

    const val SHAKE_MS = 120L          // blocked micro-shake (~2dp)
    const val BLOCKER_PULSE_MS = 400L  // single soft pulse on the blocking arrow
    const val DOT_WAVE_MS = 800L       // radial teal dot wave
    const val CONFETTI_MS = 1200L      // gravity confetti burst
    const val OVERLAY_MS = 1200L       // plum "NEXT LEVEL" overlay
    const val SPINNER_MIN_MS = 700L    // dotted-ring loading moment after skip
    const val SKIP_FLOOR_MS = 300L     // minimum before tap-to-skip engages
}

/** Immutable snapshot the reducer operates on (mirrors GameState minus UI-only fields). */
data class BoardState(
    val width: Int,
    val height: Int,
    val arrows: List<Arrow>,
    val activeIds: Set<Int>,
    val crimsonIds: Set<Int>,
    val hintId: Int? = null,
    val hearts: Int = GameRules.MAX_HEARTS,
) {
    private val byId: Map<Int, Arrow> = arrows.associateBy { it.id }
    fun arrow(id: Int): Arrow? = byId[id]
    val clearedCount: Int get() = arrows.size - activeIds.size
    val totalArrows: Int get() = arrows.size
}

/** Result of applying one player action to a [BoardState]. */
sealed interface ReducerResult {
    /** Nothing changed (e.g. tapping an already-removed arrow, or input while not playing). */
    data object NoOp : ReducerResult
    data class Freed(val state: BoardState, val arrow: Arrow, val won: Boolean) : ReducerResult
    data class Blocked(val state: BoardState, val arrow: Arrow, val blockerId: Int?) : ReducerResult
    data class OutOfHearts(val state: BoardState) : ReducerResult
}

/**
 * Headless rule engine for one attempt.
 *
 * Invariants encoded here (all proven in GameEngine.raycast's KDoc):
 *  - removability depends ONLY on the active set — crimson markers never change what is free,
 *  - a free tap removes the arrow from [BoardState.activeIds] synchronously (rapid tapping ok),
 *  - a blocked tap costs one heart and paints a cosmetic crimson marker that persists for the
 *    rest of the attempt (cleared only by a new attempt / Try again),
 *  - "Try again" restores every removed arrow + clears markers/hearts but NOT the layout,
 *  - "Save me" refills hearts WITHOUT touching removals or markers (progress preserved).
 */
class GameReducer {

    /** Applies a tap on [arrowId]; returns the transition outcome for the caller to animate. */
    fun tap(state: BoardState, arrowId: Int): ReducerResult {
        if (arrowId !in state.activeIds) return ReducerResult.NoOp
        val arrow = state.arrow(arrowId) ?: return ReducerResult.NoOp
        val level = Level(0, state.width, state.height, state.arrows)
        val occ = GameEngine.Occupancy.build(level, state.activeIds)
        return if (GameEngine.raycast(arrow, occ)) {
            val newActive = state.activeIds - arrowId
            val next = state.copy(
                activeIds = newActive,
                hintId = if (state.hintId == arrowId) null else state.hintId,
            )
            ReducerResult.Freed(next, arrow, won = newActive.isEmpty())
        } else {
            // Find the first cell the ray hits (for the blocker pulse animation).
            val d = arrow.headDirection
            var x = arrow.head.x + d.dx
            var y = arrow.head.y + d.dy
            var blockerId: Int? = null
            while (x in 0 until state.width && y in 0 until state.height) {
                val o = occ.ownerAt(x, y)
                if (o != -1 && o != arrowId) { blockerId = o; break }
                x += d.dx; y += d.dy
            }
            val hearts = (state.hearts - 1).coerceAtLeast(0)
            val next = state.copy(hearts = hearts, crimsonIds = state.crimsonIds + arrowId)
            if (hearts <= 0) ReducerResult.OutOfHearts(next)
            else ReducerResult.Blocked(next, arrow, blockerId)
        }
    }

    /** "Try again": full attempt reset, same layout. */
    fun tryAgain(state: BoardState): BoardState = state.copy(
        hearts = GameRules.MAX_HEARTS,
        activeIds = state.arrows.mapTo(HashSet()) { it.id },
        crimsonIds = emptySet(),
        hintId = null,
    )

    /** "Save me" success branch: hearts refill, attempt otherwise untouched. */
    fun saveMe(state: BoardState): BoardState = state.copy(hearts = GameRules.MAX_HEARTS)

    /** Places a hint on one currently-free arrow, preferring non-crimson ones. */
    fun withHint(state: BoardState): BoardState {
        val level = Level(0, state.width, state.height, state.arrows)
        val free = GameEngine.findFreeArrow(level, state.activeIds, avoid = state.crimsonIds)
        return state.copy(hintId = free?.id)
    }
}
