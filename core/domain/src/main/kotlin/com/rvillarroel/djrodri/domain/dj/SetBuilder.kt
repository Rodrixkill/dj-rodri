package com.rvillarroel.djrodri.domain.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Orders a playlist the way a DJ would: each song follows one it goes well with, the energy moves along the curve
 * you asked for, what you requested weighs in, and the same artist never plays twice in a row when there is a choice.
 */
object SetBuilder {
    /** Songs per energy wave: roughly a peak every half hour. */
    private const val WAVE_LENGTH = 10

    /** Songs a build or a wind-down takes to get all the way there. */
    private const val RAMP_LENGTH = 12
    private const val BASE_ENERGY = 0.6
    private const val WAVE_AMPLITUDE = 0.2
    private const val RAMP_RANGE = 0.3
    private const val SAME_ARTIST_PENALTY = 0.5

    /** Small randomness so the same playlist does not always produce the same night. */
    private const val JITTER = 0.05

    /**
     * How many songs each pick looks at. Comparing every song with every other one grows with the square of the
     * playlist: 1,200 likes froze the phone for seconds. A DJ with a crate that big doesn't weigh every record for
     * every change either; a fair handful of them is plenty, and it adds variety.
     */
    const val CANDIDATES = 150

    /**
     * @param after the song playing now, so the first pick flows from it.
     * @param energyBias shifts the whole curve: positive for "más energía", negative for "más tranqui".
     * @param intent what you asked the DJ for, already turned into numbers.
     */
    fun order(
        tracks: List<TrackProfile>,
        after: TrackProfile? = null,
        energyBias: Double = 0.0,
        intent: DjIntent = DjIntent.Default,
        random: Random = Random.Default,
    ): List<TrackProfile> {
        val remaining = tracks.toMutableList()
        val ordered = ArrayList<TrackProfile>(tracks.size)
        var previous = after
        while (remaining.isNotEmpty()) {
            val target = (targetEnergy(ordered.size, intent) + energyBias).coerceIn(0.0, 1.0)
            val prev = previous
            val best = if (remaining.size <= CANDIDATES) {
                remaining.maxBy { score(prev, it, target, intent) + random.nextDouble() * JITTER }.also { remaining.remove(it) }
            } else {
                // A random handful; the winner leaves by swapping with the last song, so taking it out costs nothing.
                var bestIndex = -1
                var bestScore = Double.NEGATIVE_INFINITY
                repeat(CANDIDATES) {
                    val index = random.nextInt(remaining.size)
                    val value = score(prev, remaining[index], target, intent) + random.nextDouble() * JITTER
                    if (value > bestScore) {
                        bestScore = value
                        bestIndex = index
                    }
                }
                val chosen = remaining[bestIndex]
                remaining[bestIndex] = remaining[remaining.lastIndex]
                remaining.removeAt(remaining.lastIndex)
                chosen
            }
            ordered += best
            previous = best
        }
        return ordered
    }

    internal fun targetEnergy(position: Int, intent: DjIntent = DjIntent.Default): Double {
        val base = intent.targetEnergy ?: BASE_ENERGY
        val progress = (position.toDouble() / RAMP_LENGTH).coerceAtMost(1.0)
        return when (intent.curve) {
            EnergyCurve.STEADY -> base
            EnergyCurve.BUILD -> base - RAMP_RANGE / 2 + RAMP_RANGE * progress
            EnergyCurve.WAVE -> base + WAVE_AMPLITUDE * sin(2 * PI * position / WAVE_LENGTH)
            EnergyCurve.WIND_DOWN -> base + RAMP_RANGE / 2 - RAMP_RANGE * progress
        }.coerceIn(0.0, 1.0)
    }

    private fun score(previous: TrackProfile?, candidate: TrackProfile, targetEnergy: Double, intent: DjIntent): Double {
        val energyFit = candidate.energy?.let { 1.0 - abs(it - targetEnergy) } ?: 0.5
        val intentFit = intent.fit(candidate)
        if (previous == null) return 0.5 * energyFit + 0.5 * intentFit
        val sameArtist = previous.artist.equals(candidate.artist, ignoreCase = true)
        return 0.4 * Compatibility.score(previous, candidate) +
            0.25 * energyFit +
            0.35 * intentFit -
            if (sameArtist) SAME_ARTIST_PENALTY else 0.0
    }
}
