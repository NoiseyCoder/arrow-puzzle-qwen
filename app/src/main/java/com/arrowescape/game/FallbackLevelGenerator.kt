package com.arrowescape.game

import kotlin.math.abs

/**
 * On-device FALLBACK level generator, used only when a shipped asset pack is missing or failed
 * CRC. It mirrors tools/LevelPackBuilder.kt exactly: same mulberry32 PRNG, integer-only math,
 * fixed iteration order, seeded ONLY by (packId, levelNumber) — never device time/id — so the
 * fallback still satisfies "Level N must be identical for every user". The generated pack file
 * is written to internal storage on first need and NEVER regenerated.
 */
object FallbackLevelGenerator {

    /** mulberry32 PRNG with 32-bit wrapping int math. Identical in builder + tests. */
    class Mulberry32(seed: Int) {
        private var state = seed
        fun nextInt(): Int {
            state += 0x6D2B79F5
            var t = state
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (((t xor (t ushr 7)) * (t or 61)) shr 14))
            return t xor (t ushr 14)
        }
        fun bounded(bound: Int): Int {
            require(bound > 0) { "bound must be positive" }
            return (nextInt().toLong() and 0xFFFFFFFFL).rem(bound.toLong()).toInt()
        }
        /** Deterministic float in [0,1) from the high 24 bits (no device entropy anywhere). */
        fun nextFloat(): Float = ((nextInt().toLong() and 0xFFFFFFFFL) ushr 8).toFloat() / 16777216f
    }

    private val DIR_X = intArrayOf(0, 0, -1, 1)  // UP DOWN LEFT RIGHT (Direction ordinals)
    private val DIR_Y = intArrayOf(-1, 1, 0, 0)

    /** Deterministic seed avalanche from (packId, levelNumber) only. */
    fun seedFor(packId: Int, levelNumber: Int): Int {
        var s = levelNumber * 2654435761 + packId * 974711
        s = s xor (s ushr 15); s *= 2246822519
        s = s xor (s ushr 13); s *= 3266489917
        s = s xor (s ushr 16)
        return s
    }

    data class Params(
        val w: Int, val h: Int, val maxArrows: Int,
        val minFillPct: Int, val maxFillPct: Int,
        val lenMin: Int, val lenMax: Int, val turnsMax: Int,
        val target: Double, val shape: String,
    )

    /** Difficulty rises with the level NUMBER only (never player behavior, never the device). */
    fun paramsFor(levelNumber: Int): Params {
        val n = levelNumber
        val isBreather = n % 10 == 0          // every 10th level: ~70% difficulty
        val isMilestone = n % 25 == 0         // every 25th: signature silhouette
        var target = 0.50 + minOf(n, 1000) / 1000.0 * 0.45
        if (isBreather) target *= 0.70
        val p: Params = when {
            n <= 5 -> Params(6 + (n - 1), 6 + (n - 1), 12, 80, 90, 2, 5, 2, target, "RECT")
            n <= 30 -> Params(12 + (n - 6) * 6 / 25, 12 + (n - 6) * 6 / 25, 60, 92, 98, 2, 8, 3, target, "RECT")
            n <= 150 -> Params(18 + (n - 31) * 8 / 120, 18 + (n - 31) * 8 / 120, 120, 92, 98, 3, 10, 4, target, "RECT")
            else -> {
                val side = minOf(26 + (n - 151) * 14 / 450, 60) // grid caps near 60x60 at 600+
                Params(side, side, 400, 92, 98, 3, 14, 5, target, "RECT")
            }
        }
        val shape = if (isMilestone && n >= 25) LevelShapes.shapeFor(n) else p.shape
        return p.copy(shape = shape)
    }

    /** Mask cells; falls back to full rectangle if a polygon rasterizes too small. */
    fun maskFor(p: Params): Set<Point> {
        if (p.shape == "RECT") return (0 until p.h).flatMap { y -> (0 until p.w).map { x -> Point(x, y) } }.toSet()
        val cells = LevelShapes.rasterize(p.w, p.h, LevelShapes.polygonFor(p.shape))
        return if (cells.size < 8) (0 until p.h).flatMap { y -> (0 until p.w).map { x -> Point(x, y) } }.toSet() else cells
    }

    /** Generates one deterministic level. */
    fun generate(packId: Int, levelNumber: Int): Level {
        val rng = Mulberry32(seedFor(packId, levelNumber))
        val p = paramsFor(levelNumber)
        val mask = maskFor(p)
        var bestDev = Double.MAX_VALUE
        var best: List<List<Point>>? = null
        repeat(6) { // build 6 candidates, keep the one closest to the target score
            val cand = tryBuild(rng, p, mask) ?: return@repeat
            val dev = abs(GameEngine.difficultyScore(Level(levelNumber, p.w, p.h, cand.mapIndexed { id, path -> Arrow(id, path) })) - p.target)
            if (dev < bestDev) { bestDev = dev; best = cand }
        }
        val arrows = (best ?: emptyList()).mapIndexed { id, path -> Arrow(id, path) }
        return Level(levelNumber, p.w, p.h, arrows)
    }

    /**
     * Places pieces in REVERSE removal order (see builder KDoc for the correctness argument):
     * a piece is accepted only when its head ray crosses no already-placed cell AND every body
     * cell except the head is currently clearable-to-edge, keeping all blocking relations
     * acyclic. The greedy pass at the end is a real safety net, not a formality.
     */
    private fun tryBuild(rng: Mulberry32, p: Params, mask: Set<Point>): List<List<Point>>? {
        val w = p.w; val h = p.h
        val owner = IntArray(w * h) { -1 }   // cell -> arrow index placed here, -1 free
        val paths = ArrayList<List<Point>>()
        val totalMask = mask.size
        val fillLo = totalMask * p.minFillPct / 100
        val fillHi = totalMask * p.maxFillPct / 100
        var filled = 0
        var guard = 0
        val hardCap = p.maxArrows * 2
        while (filled < fillLo && guard < 6000) {
            guard++
            if (paths.size >= hardCap) break
            val len = p.lenMin + rng.bounded(p.lenMax - p.lenMin + 1)
            var grown = growHeadFirst(rng, p, mask, owner, paths, len, p.turnsMax)
            if (grown == null && guard % 5 == 0) grown = growHeadFirst(rng, p, mask, owner, paths, p.lenMin, p.turnsMax + 2)
            if (grown == null) continue
            if (filled + grown.size > fillHi && filled >= fillLo) break
            val idx = paths.size
            for (c in grown) owner[c.y * w + c.x] = idx
            paths.add(grown)
            filled += grown.size
        }
        if (paths.isEmpty()) return null
        if (filled < fillLo && filled < fillLo / 2) return null
        // Safety net: greedy solve must clear (monotonicity => it always will for valid builds).
        val level = Level(0, w, h, paths.mapIndexed { id, path -> Arrow(id, path) })
        val active = paths.indices.toMutableList()
        while (active.isNotEmpty()) {
            val occ = GameEngine.Occupancy.build(level, active.toHashSet())
            val free = active.firstOrNull { GameEngine.raycast(level.arrows[it], occ) } ?: return null
            active.remove(free)
        }
        return paths
    }

    private fun growHeadFirst(
        rng: Mulberry32, p: Params, mask: Set<Point>, owner: IntArray,
        paths: List<List<Point>>, len: Int, turnsMax: Int,
    ): List<Point>? {
        val w = p.w; val h = p.h
        val freeCells = ArrayList<Point>()
        for (y in 0 until h) for (x in 0 until w) {
            val pt = Point(x, y)
            if (pt in mask && owner[y * w + x] == -1) freeCells.add(pt)
        }
        if (freeCells.isEmpty()) return null
        val head = freeCells[rng.bounded(freeCells.size)]
        val d0 = rng.bounded(4)
        // Escape ray leaves the head along d0: off-grid needs no check; inside the grid the
        // FIRST cell must be free. Crossings further out are fine — those blockers were placed
        // earlier and are removed before this arrow in the final solve order.
        val fx0 = head.x + DIR_X[d0]; val fy0 = head.y + DIR_Y[d0]
        if (fx0 in 0 until w && fy0 in 0 until h && owner[fy0 * w + fx0] != -1) return null
        // Grow BACKWARD from the head (tail direction = -d0).
        val px = head.x - DIR_X[d0]; val py = head.y - DIR_Y[d0]
        if (px !in 0 until w || py !in 0 until h) return null
        if (Point(px, py) !in mask || owner[py * w + px] != -1) return null
        val cells = ArrayList<Point>(); cells.add(head); cells.add(Point(px, py))
        val used = HashSet<Point>(); used.add(head); used.add(Point(px, py))
        val dirs = ArrayList<Int>(); dirs.add(d0)
        var cur = Point(px, py)
        var remainingTurns = turnsMax
        for (s in 0 until len - 2) {
            data class Opt(val d: Int, val nx: Int, val ny: Int, val pen: Int)
            val opts = ArrayList<Opt>(4)
            for (d in 0 until 4) {
                val nx = cur.x + DIR_X[d]; val ny = cur.y + DIR_Y[d]
                if (nx !in 0 until w || ny !in 0 until h) continue
                val pt = Point(nx, ny)
                if (pt !in mask || owner[ny * w + nx] != -1 || pt in used) continue
                val prev = dirs.last()
                val pen = when {
                    d == prev -> 0
                    abs(d - prev) == 2 -> 2
                    else -> 1
                }
                if (pen > remainingTurns) continue
                opts.add(Opt(d, nx, ny, pen))
            }
            if (opts.isEmpty()) break
            // Weighted pick: straight-on preferred, forward-open cells nudged up. Fixed order.
            val weights = FloatArray(opts.size)
            var sum = 0f
            for (k in opts.indices) {
                val o = opts[k]
                var wt = if (o.pen == 0) 3f else 1f
                val pd = dirs.last()
                if (DIR_X[pd] * DIR_X[o.d] + DIR_Y[pd] * DIR_Y[o.d] == 1) {
                    val fx = o.nx + DIR_X[o.d]; val fy = o.ny + DIR_Y[o.d]
                    if (fx in 0 until w && fy in 0 until h && Point(fx, fy) in mask &&
                        owner[fy * w + fx] == -1 && Point(fx, fy) !in used
                    ) wt += 1f
                }
                weights[k] = wt; sum += wt
            }
            var r = rng.nextFloat() * sum
            var chosen = opts.last()
            for (k in opts.indices) {
                r -= weights[k]
                if (r <= 0f) { chosen = opts[k]; break }
            }
            if (chosen.pen > 0) remainingTurns -= chosen.pen
            dirs.add(chosen.d)
            cur = Point(chosen.nx, chosen.ny)
            used.add(cur); cells.add(cur)
        }
        if (cells.size < 2) return null
        val path = cells.reversed() // tail -> head
        // Head ray must cross no already-placed cell.
        val hx = path.last().x; val hy = path.last().y
        var rx = hx + DIR_X[d0]; var ry = hy + DIR_Y[d0]
        while (rx in 0 until w && ry in 0 until h) {
            if (owner[ry * w + rx] != -1) return null
            rx += DIR_X[d0]; ry += DIR_Y[d0]
        }
        // All non-head cells must themselves be immediately clearable (acyclicity invariant).
        for (i in 0 until path.size - 1) {
            if (!cellClearable(path[i], p, owner)) return null
        }
        return path
    }

    /** Free cell with at least one fully-free straight line to an edge (outside-mask = free). */
    private fun cellClearable(c: Point, p: Params, owner: IntArray): Boolean {
        val w = p.w; val h = p.h
        if (owner[c.y * w + c.x] != -1) return false
        for (d in 0 until 4) {
            var rx = c.x + DIR_X[d]; var ry = c.y + DIR_Y[d]
            var ok = true
            while (rx in 0 until w && ry in 0 until h) {
                if (owner[ry * w + rx] != -1) { ok = false; break }
                rx += DIR_X[d]; ry += DIR_Y[d]
            }
            if (ok) return true
        }
        return false
    }
}
