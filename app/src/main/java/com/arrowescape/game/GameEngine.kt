package com.arrowescape.game

import kotlin.math.abs

/**
 * Pure-Kotlin game rules engine: raycast removability, the greedy solver / hint finder, and
 * difficulty scoring. No Android dependencies — everything here is unit-testable on the JVM.
 */
object GameEngine {

    /**
     * Immutable occupancy map from cell -> owning arrow id for one board state.
     * Built once per active-set change; raycasts are then O(distance to edge).
     */
    class Occupancy private constructor(
        private val grid: IntArray,   // row-major, -1 = free
        val width: Int,
        val height: Int,
    ) {
        /** Arrow id occupying (x, y), or -1 when the cell is free (including all cells that
         *  were never assigned to any arrow — deliberate gaps and outside-silhouette cells). */
        fun ownerAt(x: Int, y: Int): Int = if (x in 0 until width && y in 0 until height) grid[y * width + x] else -1

        companion object {
            fun build(level: Level, activeIds: Set<Int>): Occupancy {
                val g = IntArray(level.width * level.height) { -1 }
                for (a in level.arrows) {
                    if (a.id !in activeIds) continue
                    for (p in a.cells) g[p.y * level.width + p.x] = a.id
                }
                return Occupancy(g, level.width, level.height)
            }
        }
    }

    /**
     * Raycast collision test — the single rule that decides whether a tap frees an arrow.
     *
     * ### Algorithm
     * Start at the arrow's head cell `h`. Compute the head direction `d` (the step from
     * `cells[size-2]` to `cells[size-1]`). Step one cell at a time along `d`:
     * `h+d, h+2d, h+3d, ...` while the stepped-to index is still inside the W x H grid
     * (i.e. until the row/col index leaves the board). For every visited cell, look up its
     * owner in [Occupancy]. The ray is **BLOCKED** if any visited cell belongs to another
     * ACTIVE arrow; it is **FREE** otherwise. The head cell itself is not tested (it trivially
     * belongs to the queried arrow), and the query arrow's own body cells can never be hit by
     * its own forward ray because the ray starts strictly ahead of the head and moves away
     * from the body along a straight line.
     *
     * ### Why no "inside silhouette" check is needed
     * Cells outside the shape are simply cells that no arrow was ever assigned to, so their
     * occupancy entry is permanently `-1` and the ray passes through them for free. The shape
     * mask therefore only matters for rendering and generation, never for [raycast].
     *
     * ### Why checking ONLY the occupied cells on the ray is enough ("Why the raycast check is enough")
     * An escaping arrow slides along a straight axis-aligned path exactly one cell wide, so
     * geometrically it can only ever collide with something that occupies a cell of that
     * corridor. Two facts close the argument:
     *  1. *Soundness (no false positives).* If some ray cell c is owned by an active arrow B,
     *     B does not move during A's slide (only A moves), so A's swept corridor contains the
     *     still-occupied cell c before A has travelled far enough to clear it — A genuinely
     *     collides. Conversely, if every ray cell is free, each other arrow C either (a) owns
     *     no ray cell, so its entire footprint is disjoint from A's swept corridor and A
     *     passes beside it, or (b) lies beyond the corridor's exit, which A reaches only after
     *     fully leaving the grid — i.e. after it is already out. Hence "no active arrow owns a
     *     ray cell" <=> "A can slide off the board without hitting anything".
     *  2. *Monotonicity.* Removing arrows only ever frees cells and never re-occupies them, so
     *     freeness is monotone in the set of removed arrows: freeing more can never block
     *     anything. This is what makes greedy play complete: if the board is solvable at all,
     *     ANY sequence that repeatedly removes a currently-free arrow clears the whole board
     *     (a stuck position would contradict solvability, since blocked arrows stay blocked
     *     only while their blockers remain). It is also why the in-app hint needs no search or
     *     backtracking: "find one currently-free arrow" is the entire algorithm, and why a
     *     crimson "known blocked" marker can be purely cosmetic — removability depends only on
     *     which arrows have been REMOVED, never on which ones were previously tapped-and-blocked.
     *
     * Complexity: O(W + H) worst case per call (at most max(W,H) steps), O(1) per step thanks
     * to the precomputed [Occupancy].
     *
     * @return true if the arrow can slide off now; false if the ray hits another active arrow.
     */
    fun raycast(arrow: Arrow, occupancy: Occupancy): Boolean {
        val d = arrow.headDirection
        var x = arrow.head.x + d.dx
        var y = arrow.head.y + d.dy
        while (x in 0 until occupancy.width && y in 0 until occupancy.height) {
            val owner = occupancy.ownerAt(x, y)
            if (owner != -1 && owner != arrow.id) return false // blocked by another active arrow
            x += d.dx
            y += d.dy
        }
        return true // ray left the grid without crossing another active arrow
    }

    /** Convenience overload: builds occupancy for [activeIds] and runs [raycast]. */
    fun isFree(level: Level, arrowId: Int, activeIds: Set<Int>): Boolean {
        val arrow = level.arrows.firstOrNull { it.id == arrowId } ?: return false
        return raycast(arrow, Occupancy.build(level, activeIds))
    }

    /**
     * Finds one currently-free arrow, preferring ids not in [avoid] (used to avoid re-hinting
     * arrows already marked crimson). Returns null iff the board is deadlocked.
     *
     * Because freeness is monotone (see [raycast] KDoc), this scan IS the complete solver
     * heuristic: repeated calls interleaved with removals either clear the board or prove no
     * free arrow exists at that moment.
     */
    fun findFreeArrow(level: Level, activeIds: Set<Int>, occupancy: Occupancy = Occupancy.build(level, activeIds), avoid: Set<Int> = emptySet()): Arrow? {
        var fallback: Arrow? = null
        for (a in level.arrows) {
            if (a.id !in activeIds) continue
            if (raycast(a, occupancy)) {
                if (a.id !in avoid) return a
                if (fallback == null) fallback = a
            }
        }
        return fallback
    }

    /**
     * Greedy solver: repeatedly remove any free arrow until none remains.
     *
     * @return the removal order (full list == level is solvable), or null if the solver got
     *         stuck with [remaining] arrows left. Given monotonicity, "stuck" means unsolvable,
     *         so this doubles as a decision procedure used by the generator's safety net and by
     *         unit tests over every stored level.
     */
    fun solveGreedy(level: Level): List<Int>? {
        val active = level.arrows.mapTo(HashSet()) { it.id }
        val order = ArrayList<Int>(active.size)
        while (active.isNotEmpty()) {
            val occ = Occupancy.build(level, active)
            val free = findFreeArrow(level, active, occ) ?: return null
            active.remove(free.id)
            order.add(free.id)
        }
        return order
    }

    /**
     * Difficulty score in roughly [0, 1] derived from the blocking graph of a full board.
     *
     * Nodes are arrows; an edge A -> B means "B's ray crosses a cell of A" (A blocks B). We
     * compute:
     *  - [longestDependencyChain]: longest path in the blocking DAG (memoized DFS; cycles, if a
     *    hand-made level contained them, are cut at the visiting node and counted conservatively),
     *  - initially-free-arrow ratio (more free arrows => easier),
     *  - average number of distinct blockers per ray (density of entanglement).
     *
     * Score = 0.45 * min(chain/12, 1) + 0.30 * (1 - freeRatio) + 0.25 * min(avgBlockers/4, 1).
     * Used offline only (builder picks candidates closest to the target curve); the app never
     * displays difficulty anywhere (explicit design rule: no difficulty chips or labels).
     */
    fun difficultyScore(level: Level): Double {
        val n = level.arrows.size
        if (n == 0) return 0.0
        val occ = Occupancy.build(level, level.arrows.mapTo(HashSet()) { it.id })
        val deps = Array(n) { ArrayList<Int>() } // deps[b] = blocker ids (as indices)
        val indexById = HashMap<Int, Int>(n)
        level.arrows.forEachIndexed { i, a -> indexById[a.id] = i }
        var totalBlockers = 0
        var initialFree = 0
        val adjacency = Array(n) { HashSet<Int>() }
        for ((i, a) in level.arrows.withIndex()) {
            val d = a.headDirection
            var x = a.head.x + d.dx
            var y = a.head.y + d.dy
            val seen = HashSet<Int>()
            while (x in 0 until level.width && y in 0 until level.height) {
                val owner = occ.ownerAt(x, y)
                if (owner != -1 && owner != a.id) {
                    val j = indexById.getValue(owner)
                    if (seen.add(j)) adjacency[i].add(j)
                }
                x += d.dx
                y += d.dy
            }
            if (seen.isEmpty()) initialFree++
            totalBlockers += seen.size
            deps[i] = ArrayList(seen)
        }
        // Longest chain via memoized DFS with cycle cutting.
        val memo = IntArray(n) { -1 }
        val visiting = BooleanArray(n)
        fun dfs(i: Int): Int {
            if (memo[i] >= 0) return memo[i]
            if (visiting[i]) return 0 // cycle guard (shouldn't occur for generated levels)
            visiting[i] = true
            var best = 0
            for (j in deps[i]) {
                val v = dfs(j) + 1
                if (v > best) best = v
            }
            visiting[i] = false
            memo[i] = best
            return best
        }
        var longest = 0
        for (i in 0 until n) longest = maxOf(longest, dfs(i))
        val normLongest = minOf(longest / 12.0, 1.0)
        val freeRatio = initialFree.toDouble() / n
        val avgBlockers = totalBlockers.toDouble() / n
        val normDensity = minOf(avgBlockers / 4.0, 1.0)
        return 0.45 * normLongest + 0.30 * (1.0 - freeRatio) + 0.25 * normDensity
    }

    /** Manhattan distance helper used by hit-testing and effects placement. */
    fun manhattan(a: Point, b: Point): Int = abs(a.x - b.x) + abs(a.y - b.y)
}
