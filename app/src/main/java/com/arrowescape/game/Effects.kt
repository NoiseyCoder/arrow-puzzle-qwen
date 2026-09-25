package com.arrowescape.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Frame-driven celebration effects, drawn on the board Canvas (no extra composition layers):
 *  - [DotWaveEffect]: grid dots light up in a radial wave from a origin cell (~800 ms),
 *  - [ConfettiSystem]: streamers + circles with gravity (~1.2 s burst),
 *  - [SparkleField]: white 4-point sparkles that twinkle and drift.
 * All are pure functions of elapsed time so they can be scrubbed/skipped safely.
 */

/** Radial teal dot wave emitted from the last cleared arrow's head cell. */
class DotWaveEffect(
    val originX: Int,
    val originY: Int,
    private val width: Int,
    private val height: Int,
    val durationMs: Long = 800L,
) {
    private var startNs = -1L
    val finished: Boolean get() = startNs > 0 && (System.nanoTime() - startNs) / 1_000_000 > durationMs

    fun restart(nowNs: Long = System.nanoTime()) { startNs = nowNs }

    /** Radius of the wavefront in cells at [elapsed] fraction [0,1]. */
    private fun frontCells(t: Float): Float = t * (width + height).toFloat()

    /**
     * Draws lit dots; returns early when finished. Dots brighten as the wavefront passes them
     * and fade out behind it, giving an expanding-ring shimmer rather than a hard circle.
     */
    fun draw(scope: DrawScope, dotCenter: (Int, Int) -> Offset, dotRadius: Float, color: Color) {
        if (startNs < 0 || finished) return
        val t = ((System.nanoTime() - startNs) / 1_000_000f / durationMs).coerceIn(0f, 1f)
        val front = frontCells(t)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val d = dist(x, y)
                if (d > front) continue
                val age = (front - d) / (width + height).coerceAtLeast(1)
                val alpha = (1f - age * 1.6f).coerceIn(0f, 1f)
                if (alpha <= 0.02f) continue
                scope.drawCircle(color.copy(alpha = alpha), dotRadius, dotCenter(x, y))
            }
        }
    }

    private fun dist(x: Int, y: Int): Float {
        val dx = x - originX; val dy = y - originY
        return kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
    }
}

/** One confetti particle: rectangle "streamer" or small circle, with gravity + spin. */
class ConfettiParticle(
    val x0: Float, val y0: Float,
    val vx: Float, val vy: Float,
    val size: Float,
    val color: Color,
    val streamer: Boolean,
    val spin: Float,
    val seedAngle: Float,
)

/** Gravity-confetti burst system (~1.2 s per burst). */
class ConfettiSystem(private val palette: List<Color>) {
    private val particles = ArrayList<ConfettiParticle>()
    private var startNs = -1L
    val durationMs = 1200L
    val active: Boolean get() = startNs > 0 && elapsed() < durationMs

    private fun elapsed(): Float = if (startNs < 0) 0f else (System.nanoTime() - startNs) / 1_000_000f

    /** Emits [count] particles from (originX, originY) px with upward-biased velocities. */
    fun burst(originX: Float, originY: Float, count: Int, spreadPx: Float, rng: Random = Random.Default) {
        startNs = System.nanoTime()
        repeat(count) {
            val ang = rng.nextFloat() * 2f * PI.toFloat()
            val speed = spreadPx * (0.35f + rng.nextFloat() * 0.65f)
            particles.add(
                ConfettiParticle(
                    x0 = originX, y0 = originY,
                    vx = cos(ang) * speed,
                    vy = sin(ang) * speed - spreadPx * 0.9f, // upward bias
                    size = 6f + rng.nextFloat() * 8f,
                    color = palette[rng.nextInt(palette.size)],
                    streamer = rng.nextBoolean(),
                    spin = (rng.nextFloat() - 0.5f) * 12f,
                    seedAngle = rng.nextFloat() * 360f,
                )
            )
        }
    }

    fun clear() { particles.clear(); startNs = -1L }

    /** Integrates position analytically from t (gravity g) — no per-frame state to desync. */
    fun draw(scope: DrawScope) {
        if (!active) return
        val t = elapsed() / 1000f
        val g = 1400f
        val fade = (1f - (t / (durationMs / 1000f))).coerceIn(0f, 1f)
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            val x = p.x0 + p.vx * t
            val y = p.y0 + p.vy * t + 0.5f * g * t * t
            if (y > scope.size.height + 40f) { iter.remove(); continue }
            val angle = p.seedAngle + p.spin * t * 60f
            val alpha = fade
            if (p.streamer) {
                scope.rotate(angle, pivot = Offset(x, y)) {
                    drawRoundRect(
                        color = p.color.copy(alpha = alpha),
                        topLeft = Offset(x - p.size / 2, y - p.size / 4),
                        size = androidx.compose.ui.geometry.Size(p.size, p.size / 2f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(p.size / 6f, p.size / 6f),
                    )
                }
            } else {
                scope.drawCircle(p.color.copy(alpha = alpha), p.size / 2.4f, Offset(x, y))
            }
        }
    }
}

/** One 4-point star sparkle. */
private class Sparkle(val x0: Float, val y0: Float, val drift: Float, val maxR: Float, val phase: Float)

/** Twinkling white 4-point sparkles used in the win overlay. */
class SparkleField {
    private val sparkles = ArrayList<Sparkle>()
    private var startNs = -1L
    val durationMs = 1400L
    val active: Boolean get() = startNs > 0 && (System.nanoTime() - startNs) / 1_000_000f < durationMs

    fun burst(cx: Float, cy: Float, radiusPx: Float, count: Int, rng: Random = Random.Default) {
        startNs = System.nanoTime()
        sparkles.clear()
        repeat(count) {
            val ang = rng.nextFloat() * 2f * PI.toFloat()
            val r = rng.nextFloat() * radiusPx
            sparkles.add(
                Sparkle(
                    x0 = cx + cos(ang) * r,
                    y0 = cy + sin(ang) * r,
                    drift = (rng.nextFloat() - 0.5f) * 40f,
                    maxR = 7f + rng.nextFloat() * 9f,
                    phase = rng.nextFloat(),
                )
            )
        }
    }

    fun clear() { sparkles.clear(); startNs = -1L }

    private val pathCache = Path()

    /** Classic 4-point star: concave diamond built from quadratic-ish straight segments. */
    fun draw(scope: DrawScope, color: Color) {
        if (!active) return
        val t = ((System.nanoTime() - startNs) / 1_000_000f / durationMs).coerceIn(0f, 1f)
        for (s in sparkles) {
            val local = ((t + s.phase) % 1f)
            val scale = sin(local * PI.toFloat()) // grow then shrink
            val r = s.maxR * scale
            if (r < 0.5f) continue
            val x = s.x0 + s.drift * t
            val y = s.y0 - 30f * t
            val inner = r * 0.28f
            pathCache.reset()
            pathCache.moveTo(x, y - r)
            pathCache.quadraticTo(x + inner * 0.4f, y - inner * 0.4f, x + r, y)
            pathCache.quadraticTo(x + inner * 0.4f, y + inner * 0.4f, x, y + r)
            pathCache.quadraticTo(x - inner * 0.4f, y + inner * 0.4f, x - r, y)
            pathCache.quadraticTo(x - inner * 0.4f, y - inner * 0.4f, x, y - r)
            pathCache.close()
            scope.drawPath(pathCache, color.copy(alpha = (1f - t).coerceIn(0f, 1f)))
        }
    }
}

/** Soft low-opacity radial glow behind animating arrows (also a non-color state cue). */
fun DrawScope.drawGlow(center: Offset, radius: Float, color: Color, alpha: Float = 0.28f) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
