package com.rvillarroel.djrodri.domain.mix

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds the pulse of a track from its onset envelope (how much the sound "attacks" over time, one value every
 * [hopMs]). Decoding the audio into that envelope is the Android side's job; this is the arithmetic, and it runs
 * on any machine, which is why it can be tested without a phone.
 *
 * The method is the classic one: autocorrelation of the envelope to find the beat period, then a comb filter to
 * find where the first beat falls.
 */
object BeatTracker {
    const val MIN_BPM = 60.0
    const val MAX_BPM = 200.0

    fun estimate(envelope: FloatArray, hopMs: Int): BeatGrid? {
        if (envelope.size < MIN_FRAMES || hopMs <= 0) return null
        val flux = accentuate(envelope)
        if (flux.all { it == 0.0 }) return null

        val minLag = ((60_000.0 / MAX_BPM) / hopMs).roundToInt().coerceAtLeast(1)
        val maxLag = ((60_000.0 / MIN_BPM) / hopMs).roundToInt().coerceAtMost(flux.size / 2)
        if (maxLag <= minLag) return null

        var bestLag = minLag
        var best = 0.0
        var total = 0.0
        for (lag in minLag..maxLag) {
            // Two beat periods are the same pulse heard twice: adding the double keeps halves and doubles together.
            val score = correlation(flux, lag) + 0.5 * correlation(flux, lag * 2)
            total += score
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        if (best <= 0.0) return null

        val bpm = 60_000.0 / (bestLag * hopMs.toDouble())
        val phase = bestPhase(flux, bestLag)
        val mean = total / (maxLag - minLag + 1)
        // How much the winning period stands out from every other one: a steady dance track scores far above a mean.
        val confidence = ((best - mean) / best).coerceIn(0.0, 1.0)
        return BeatGrid(
            firstBeatMs = (phase * hopMs).toLong(),
            bpm = bpm,
            confidence = confidence,
        )
    }

    /** Keeps only what grows: a beat is where the sound rises, not where it merely is loud. */
    private fun accentuate(envelope: FloatArray): DoubleArray {
        val flux = DoubleArray(envelope.size)
        for (i in 1 until envelope.size) flux[i] = max(0.0, (envelope[i] - envelope[i - 1]).toDouble())
        val mean = flux.average()
        for (i in flux.indices) flux[i] = max(0.0, flux[i] - mean)
        return flux
    }

    private fun correlation(flux: DoubleArray, lag: Int): Double {
        if (lag >= flux.size) return 0.0
        var sum = 0.0
        for (i in lag until flux.size) sum += flux[i] * flux[i - lag]
        return sum / (flux.size - lag)
    }

    /** Which offset inside one beat period lines the comb of beats up with the music. */
    private fun bestPhase(flux: DoubleArray, lag: Int): Int {
        var bestPhase = 0
        var best = -1.0
        for (phase in 0 until lag) {
            var sum = 0.0
            var index = phase
            while (index < flux.size) {
                sum += flux[index]
                index += lag
            }
            if (sum > best) {
                best = sum
                bestPhase = phase
            }
        }
        return bestPhase
    }

    /**
     * A click track, for tests and for tuning: a drum-like hit at [bpm] starting at [firstBeatMs], loud on the
     * beat and decaying fast, which is what an onset envelope of real percussion looks like.
     */
    fun clickEnvelope(bpm: Double, firstBeatMs: Long, lengthMs: Long, hopMs: Int, noise: (Int) -> Float = { 0f }): FloatArray {
        val frames = (lengthMs / hopMs).toInt()
        val beatFrames = (60_000.0 / bpm / hopMs)
        return FloatArray(frames) { frame ->
            val sinceFirst = frame - (firstBeatMs.toDouble() / hopMs)
            val hit = if (sinceFirst < 0) 0.0 else exp(-6.0 * (sinceFirst % beatFrames) / beatFrames)
            hit.toFloat() + noise(frame)
        }
    }

    private const val MIN_FRAMES = 200
}
