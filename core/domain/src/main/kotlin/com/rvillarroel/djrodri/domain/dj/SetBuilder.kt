package com.rvillarroel.djrodri.domain.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Orders a playlist the way a DJ would: each song follows one it goes well with, energy rises and gives the room a
 * breather in waves, and the same artist never plays twice in a row when there is an alternative.
 */
object SetBuilder {
    /** Songs per energy wave: roughly a peak every half hour. */
    private const val WAVE_LENGTH = 10
    private const val BASE_ENERGY = 0.6
    private const val WAVE_AMPLITUDE = 0.2
    private const val SAME_ARTIST_PENALTY = 0.5
    /** Small randomness so the same playlist does not always produce the same night. */
    private const val JITTER = 0.05

    /**
     * @param after the song playing now, so the first pick flows from it.
     * @param energyBias shifts the whole curve: positive for "más energía", negative for "más tranqui".
     */
    fun order(
        tracks: List<TrackProfile>,
        after: TrackProfile? = null,
        energyBias: Double = 0.0,
        random: Random = Random.Default,
    ): List<TrackProfile> {
        val remaining = tracks.toMutableList()
        val ordered = ArrayList<TrackProfile>(tracks.size)
        var previous = after
        while (remaining.isNotEmpty()) {
            val target = (targetEnergy(ordered.size) + energyBias).coerceIn(0.0, 1.0)
            val prev = previous
            val best = remaining.maxBy { score(prev, it, target) + random.nextDouble() * JITTER }
            remaining.remove(best)
            ordered += best
            previous = best
        }
        return ordered
    }

    internal fun targetEnergy(position: Int): Double =
        BASE_ENERGY + WAVE_AMPLITUDE * sin(2 * PI * position / WAVE_LENGTH)

    private fun score(previous: TrackProfile?, candidate: TrackProfile, targetEnergy: Double): Double {
        val energyFit = candidate.energy?.let { 1.0 - abs(it - targetEnergy) } ?: 0.5
        if (previous == null) return energyFit
        val sameArtist = previous.artist.equals(candidate.artist, ignoreCase = true)
        return 0.6 * Compatibility.score(previous, candidate) + 0.4 * energyFit - if (sameArtist) SAME_ARTIST_PENALTY else 0.0
    }
}
