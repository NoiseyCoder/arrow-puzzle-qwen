package com.arrowescape.game

/**
 * Core, engine-agnostic level model for Arrow Escape.
 *
 * A level is a [Level.width] x [Level.height] grid of cells. Every arrow occupies an ordered
 * list of adjacent (orthogonally connected) cells from tail to head; arrows never overlap and
 * some cells stay empty forever (deliberate gaps plus everything outside a non-rectangular
 * silhouette). Empty cells are simply unoccupied — there is no separate "shape mask" at
 * runtime; the mask only matters for rendering and generation.
 */

/** An immutable grid coordinate. x grows right, y grows down (screen convention). */
data class Point(val x: Int, val y: Int) {
    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)
}

/** The four orthogonal step directions. Ordinals match the 2-bit encoding used by LevelPack. */
enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    companion object {
        /** Decodes a 2-bit value written by [Direction.ordinal]; throws on corrupt data. */
        fun fromOrdinal(v: Int): Direction =
            entries.getOrNull(v) ?: throw IllegalStateException("corrupt direction: $v")
    }
}

/**
 * One arrow: an ordered list of at least two adjacent grid cells, stored tail-first.
 *
 * Invariants enforced by the pack decoder and by the offline generator:
 *  - `cells.size >= 2` (a single-cell arrow has no last step, so its head direction would be
 *    undefined; such arrows are a generation-time reject and a decode-time corruption error),
 *  - consecutive cells differ by exactly one cardinal step,
 *  - no cell appears twice inside the same arrow,
 *  - across a whole [Level], no cell belongs to two different arrows.
 */
data class Arrow(val id: Int, val cells: List<Point>) {
    init {
        require(cells.size >= 2) { "arrow $id must have at least 2 cells (direction needs the last step)" }
    }

    /** Tail cell (the "back" of the arrow). */
    val tail: Point get() = cells.first()

    /** Head cell (the pointy end that escapes first). */
    val head: Point get() = cells.last()

    /** Head direction = direction of the LAST step (head minus the cell before it). */
    val headDirection: Direction
        get() {
            val h = cells[cells.size - 1]
            val p = cells[cells.size - 2]
            return when {
                h.x > p.x -> Direction.RIGHT
                h.x < p.x -> Direction.LEFT
                h.y > p.y -> Direction.DOWN
                else -> Direction.UP
            }
        }

    /** True if this arrow occupies [p]. */
    fun occupies(p: Point): Boolean = cells.contains(p)
}

/**
 * A fully decoded, immutable level: the grid size plus all arrows.
 * Levels are pure data — identical for every user because they come from stored binary packs
 * (or from the deterministic fallback generator, which is seeded only by (packId, levelNumber)).
 */
data class Level(
    val number: Int,
    val width: Int,
    val height: Int,
    val arrows: List<Arrow>,
) {
    /** Total number of arrows == number of taps needed to clear the board. */
    val totalArrows: Int get() = arrows.size

    /** Optional tutorial caption shown under the header for the onboarding levels (1..5). */
    val tutorialText: String?
        get() = when (number) {
            1 -> "Tap an arrow pointing out to free it"
            2 -> "Arrows slide straight out of the board"
            3 -> "Blocked arrows turn red — try another one"
            4 -> "Clear every arrow to win the level"
            5 -> "You've got this! Tip: use the hint button anytime"
            else -> null
        }
}
