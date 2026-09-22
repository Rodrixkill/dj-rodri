package com.rvillarroel.djrodri.domain.mix

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The sound pads of a DJ, synthesized instead of shipped: no audio files, no licenses, and each one built in a few
 * milliseconds. Stereo, interleaved, -1..1, at [PadSounds.SAMPLE_RATE].
 */
enum class Pad(val label: String) {
    AIR_HORN("Bocina"),
    SIREN("Sirena"),
    LASER("Láser"),
    RISER("Subida"),
    IMPACT("Impacto"),
    CLAPS("Aplausos"),
}

object PadSounds {
    const val SAMPLE_RATE = 44_100

    fun render(pad: Pad): FloatArray = when (pad) {
        Pad.AIR_HORN -> airHorn()
        Pad.SIREN -> siren()
        Pad.LASER -> laser()
        Pad.RISER -> riser()
        Pad.IMPACT -> impact()
        Pad.CLAPS -> claps()
    }

    /** Three blasts of a brassy chord: the reggae and dancehall horn. */
    private fun airHorn(): FloatArray = stereo(1.6) { t ->
        val blasts = listOf(0.0 to 0.22, 0.28 to 0.5, 0.58 to 1.55)
        val blast = blasts.firstOrNull { t in it.first..it.second } ?: return@stereo 0.0
        val local = t - blast.first
        val env = minOf(1.0, local / 0.01) * minOf(1.0, (blast.second - t) / 0.05)
        val chord = listOf(233.0, 293.7, 349.2, 466.2).sumOf { saw(it * (1 + 0.004 * sin(2 * PI * 6 * t)), t) } / 4
        0.55 * env * chord
    }

    /** A wailing siren: a square wave sweeping up and down three times. */
    private fun siren(): FloatArray = stereo(2.4) { t ->
        val env = minOf(1.0, t / 0.05) * minOf(1.0, (2.4 - t) / 0.2)
        0.28 * env * square(phaseOf(t, 700.0, 600.0, 0.8))
    }

    /** Three falling zaps: a tone diving from 2.5 kHz, its phase integrated so the dive is smooth. */
    private fun laser(): FloatArray = stereo(1.1) { t ->
        val zap = (t / 0.35).toInt()
        val local = t - zap * 0.35
        if (zap > 2 || local > 0.3) return@stereo 0.0
        val phase = 2_400 / 9.0 * (1 - exp(-local * 9)) + 150 * local
        0.4 * exp(-local * 6) * sin(2 * PI * phase)
    }

    /** White noise that brightens and swells for four seconds: the build before a drop. */
    private fun riser(): FloatArray {
        val random = Random(7)
        var lp = 0.0
        return stereo(4.0) { t ->
            val progress = t / 4.0
            val noise = random.nextDouble(-1.0, 1.0)
            val cutoff = 300 + 9_000 * progress * progress
            val coef = 1 - exp(-2 * PI * cutoff / SAMPLE_RATE)
            lp += coef * (noise - lp)
            0.5 * progress * progress * lp + 0.15 * progress * sin(2 * PI * (200 + 800 * progress) * t)
        }
    }

    /** A sub boom with a click on top: the hit that lands a drop. */
    private fun impact(): FloatArray {
        val random = Random(3)
        return stereo(1.8) { t ->
            val boom = sin(2 * PI * (48 + 60 * exp(-t * 25)) * t) * exp(-t * 2.2)
            val click = if (t < 0.02) random.nextDouble(-1.0, 1.0) * (1 - t / 0.02) else 0.0
            0.8 * boom + 0.4 * click
        }
    }

    /** A burst of hands: many short noise claps, scattered like a crowd. */
    private fun claps(): FloatArray {
        val random = Random(11)
        val onsets = List(40) { random.nextDouble(0.0, 1.6) }.sorted()
        val out = FloatArray((SAMPLE_RATE * 2.0).toInt() * 2)
        for (onset in onsets) {
            val start = (onset * SAMPLE_RATE).toInt()
            val gain = random.nextDouble(0.15, 0.35)
            val pan = random.nextDouble(0.2, 0.8)
            var bp = 0.0
            for (i in 0 until (SAMPLE_RATE * 0.06).toInt()) {
                val frame = start + i
                if (frame * 2 + 1 >= out.size) break
                val noise = random.nextDouble(-1.0, 1.0)
                bp += 0.35 * (noise - bp)
                val v = (noise - bp) * exp(-i / (SAMPLE_RATE * 0.012)) * gain
                out[frame * 2] = (out[frame * 2] + v * (1 - pan)).toFloat().coerceIn(-1f, 1f)
                out[frame * 2 + 1] = (out[frame * 2 + 1] + v * pan).toFloat().coerceIn(-1f, 1f)
            }
        }
        return out
    }

    private fun stereo(seconds: Double, sample: (Double) -> Double): FloatArray {
        val frames = (SAMPLE_RATE * seconds).toInt()
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = sample(i.toDouble() / SAMPLE_RATE).coerceIn(-1.0, 1.0).toFloat()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    private fun saw(freq: Double, t: Double): Double {
        val p = freq * t
        return 2 * (p - kotlin.math.floor(p + 0.5))
    }

    private fun square(phase: Double): Double = if (phase - kotlin.math.floor(phase) < 0.5) 1.0 else -1.0

    /** Phase of a tone whose frequency swings sinusoidally: the integral, so the sweep has no clicks. */
    private fun phaseOf(t: Double, base: Double, depth: Double, period: Double): Double {
        val w = 2 * PI / period
        return (base + depth / 2) * t - depth / 2 * sin(w * t) / w
    }
}
