package com.arrowescape.game

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesizes ALL gameplay sounds as raw PCM written straight to AudioTrack in streaming mode:
 *  - whoosh: short filtered noise burst with a rising-pitch envelope; base pitch climbs with
 *    each quick successive clear (combo pitch),
 *  - thud: low decaying sine for errors,
 *  - chime: a few summed harmonics for level clear.
 * Deliberately NOT using ToneGenerator — it only emits fixed DTMF/dial tones and cannot render
 * a pitch-climbing whoosh or a harmonic chime. Haptics go through Vibrator (VibrationEffect on
 * API 26+, deprecated single-duration calls kept for minSdk 24 devices).
 */
class FeedbackManager(private val context: Context) {

    @Volatile var soundEnabled: Boolean = true
    @Volatile var hapticsEnabled: Boolean = true

    private val sampleRate = 44_100
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    // ------------------------------------------------------------------ sounds

    /** Soft whoosh; [pitchStep] 0..7 raises the band-pass center + sweep start for combos. */
    fun playWhoosh(pitchStep: Int) {
        if (!soundEnabled) return
        worker.execute {
            val durMs = 180L
            val n = (sampleRate * durMs / 1000).toInt()
            val out = ShortArray(n)
            val rng = Random(durMs * 31 + pitchStep) // deterministic per call type, cheap
            var lp = 0f
            val baseHz = 500f * Math.pow(1.12, pitchStep.toDouble()).toFloat()
            for (i in 0 until n) {
                val t = i.toFloat() / n
                val white = rng.nextFloat() * 2f - 1f
                lp += 0.25f * (white - lp)                     // one-pole low-pass = "filtered"
                val sweep = baseHz * (1f + 1.6f * t)          // rising pitch envelope
                val tone = sin(2 * PI * (sweep * i / sampleRate)).toFloat()
                val env = exp(-3.2f * t) * (if (t < 0.08f) t / 0.08f else 1f) // soft attack
                val s = (lp * 0.55f + tone * 0.35f) * env
                out[i] = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toShort()
            }
            writeTrack(out)
        }
    }

    /** Low decaying sine thud for blocked taps / heart loss. */
    fun playThud() {
        if (!soundEnabled) return
        worker.execute {
            val durMs = 220L
            val n = (sampleRate * durMs / 1000).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / n
                val f = 95f - 35f * t
                val s = sin(2 * PI * f * i / sampleRate).toFloat() * exp(-5.5f * t)
                out[i] = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.9f).toShort()
            }
            writeTrack(out)
        }
    }

    /** Bright bell: fundamental + two inharmonic-ish harmonics, exponential decay. */
    fun playChime() {
        if (!soundEnabled) return
        worker.execute {
            val durMs = 700L
            val n = (sampleRate * durMs / 1000).toInt()
            val out = ShortArray(n)
            val freqs = floatArrayOf(660f, 990f, 1320f)
            val amps = floatArrayOf(0.55f, 0.3f, 0.15f)
            for (i in 0 until n) {
                val t = i.toFloat() / n
                var s = 0f
                for (k in freqs.indices) {
                    s += sin(2 * PI * freqs[k] * i / sampleRate).toFloat() * amps[k] * exp(-(2.5f + k) * t)
                }
                val attack = if (t < 0.02f) t / 0.02f else 1f
                out[i] = ((s * attack).coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.8f).toShort()
            }
            writeTrack(out)
        }
    }

    /** Streaming-mode AudioTrack playback of a generated buffer, self-releasing when done. */
    private fun writeTrack(samples: ShortArray) {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
                AudioTrack.MODE_STREAM,
            )
            track.play()
            var off = 0
            while (off < samples.size) {
                val chunk = minOf(minBuf / 2, samples.size - off)
                val written = track.write(samples, off, chunk)
                if (written <= 0) break
                off += written
            }
            track.stop()
            track.release()
        } catch (_: Exception) {
            // Audio focus/hardware issues must never crash the game.
        }
    }

    // ------------------------------------------------------------------ haptics

    /** Light tick when an arrow is released/sent sliding. */
    fun tick() = vibrate(longArrayOf(0, 18), intArrayOf(0, 60))

    /** Short buzz on error (blocked tap / heart lost). */
    fun errorBuzz() = vibrate(longArrayOf(0, 45), intArrayOf(0, 90))

    /** Rising pattern on level clear. */
    fun levelClearPattern() = vibrate(longArrayOf(0, 30, 60, 30, 60, 90), intArrayOf(0, 80, 40, 80, 40, 120))

    private fun vibrate(timings: LongArray, amplitudes: IntArray) {
        if (!hapticsEnabled) return
        val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                ?: return
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        }
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(timings)
        }
    }

    fun shutdown() { worker.shutdownNow() }
}
