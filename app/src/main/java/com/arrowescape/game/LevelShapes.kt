package com.arrowescape.game

/**
 * Signature silhouettes for milestone levels, defined as normalized polygons (x right, y UP
 * positive; the rasterizer flips y for the grid). Shared by the offline builder and the
 * on-device fallback generator so both produce identical masks.
 */
object LevelShapes {

    val SHAPE_ORDER = listOf("HEART", "STAR", "CAT", "DOG", "ANCHOR", "TROPHY", "TREE", "FISH")

    private fun f(v: Double) = v.toFloat()

    fun polygonFor(name: String): List<Pair<Float, Float>> = when (name) {
        "HEART" -> listOf(-1f to -0.6f, -0.75f to 0.35f, -0.45f to 0.85f, 0f to 0.45f, 0.45f to 0.85f, 0.75f to 0.35f, 1f to -0.6f, 0f to -1f)
        "CAT" -> listOf(-0.9f to 0.75f, -0.6f to 1.0f, -0.45f to 0.6f, 0.45f to 0.6f, 0.6f to 1.0f, 0.9f to 0.75f, 0.95f to -0.9f, -0.95f to -0.9f)
        "DOG" -> listOf(-1f to 0.2f, -0.7f to 0.8f, -0.3f to 0.55f, 0.3f to 0.55f, 0.5f to 0.9f, 0.85f to 0.6f, 1f to -0.2f, 0.6f to -0.95f, -0.6f to -0.95f)
        "ANCHOR" -> listOf(0f to 1f, 0.35f to 0.75f, 0.15f to 0.6f, 0.15f to -0.2f, 0.85f to -0.45f, 0.6f to -1f, 0f to -0.7f, -0.6f to -1f, -0.85f to -0.45f, -0.15f to -0.2f, -0.15f to 0.6f, -0.35f to 0.75f)
        "TROPHY" -> listOf(-0.8f to 0.9f, -0.8f to 0.2f, -0.45f to -0.1f, -0.2f to -0.1f, -0.2f to -0.5f, -0.5f to -0.9f, -0.5f to -1f, 0.5f to -1f, 0.5f to -0.9f, 0.2f to -0.5f, 0.2f to -0.1f, 0.45f to -0.1f, 0.8f to 0.2f, 0.8f to 0.9f)
        "STAR" -> listOf(0f to 1f, f(0.2245) to f(0.309), f(0.951) to f(0.309), f(0.363) to f(-0.118), f(0.588) to f(-0.809), 0f to f(-0.382), f(-0.588) to f(-0.809), f(-0.363) to f(-0.118), f(-0.951) to f(0.309), f(-0.2245) to f(0.309))
        "TREE" -> listOf(0f to 1f, -0.5f to 0.35f, -0.25f to 0.35f, -0.75f to -0.35f, -0.35f to -0.35f, -0.85f to -0.95f, 0.85f to -0.95f, 0.35f to -0.35f, 0.75f to -0.35f, 0.25f to 0.35f, 0.5f to 0.35f)
        "FISH" -> listOf(-1f to 0f, f(-0.55) to 0.5f, 0.25f to 0.55f, 0.95f to 0.15f, 0.95f to -0.15f, 0.25f to -0.55f, f(-0.55) to -0.5f)
        else -> emptyList()
    }

    /** Shape used by a milestone level number (must be a multiple of 25 for meaningful answers). */
    fun shapeFor(levelNumber: Int): String = SHAPE_ORDER[((levelNumber / 25) - 1) % SHAPE_ORDER.size]

    /** Rasterizes [polygon] into grid cells of a w x h board (pixel-center test, even-odd rule). */
    fun rasterize(w: Int, h: Int, polygon: List<Pair<Float, Float>>): Set<Point> {
        val cells = HashSet<Point>()
        if (polygon.isEmpty()) return cells
        for (gy in 0 until h) {
            for (gx in 0 until w) {
                val nx = ((gx + 0.5f) / w) * 2f - 1f
                val ny = -(((gy + 0.5f) / h) * 2f - 1f) // flip: polygon y is up-positive
                if (insidePolygon(nx, ny, polygon)) cells.add(Point(gx, gy))
            }
        }
        return cells
    }

    /** Standard even-odd ray-crossing point-in-polygon test. */
    fun insidePolygon(px: Float, py: Float, poly: List<Pair<Float, Float>>): Boolean {
        var inside = false
        val n = poly.size
        var j = n - 1
        for (i in 0 until n) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if (((yi > py) != (yj > py)) && (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) inside = !inside
            j = i
        }
        return inside
    }
}
