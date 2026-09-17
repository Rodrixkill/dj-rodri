package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.music.Tempo
import kotlin.math.abs

/**
 * How well [b] follows [a], 0..1. Unknown data scores in the middle so a song without analysis is neither
 * preferred nor excluded.
 */
object Compatibility {
    private const val TEMPO_WEIGHT = 0.4
    private const val KEY_WEIGHT = 0.3
    private const val ENERGY_WEIGHT = 0.3
    private const val UNKNOWN = 0.5

    fun score(a: TrackProfile, b: TrackProfile): Double =
        TEMPO_WEIGHT * tempo(a, b) + KEY_WEIGHT * key(a, b) + ENERGY_WEIGHT * energy(a, b)

    private fun tempo(a: TrackProfile, b: TrackProfile): Double {
        if (a.bpm == null || b.bpm == null) return UNKNOWN
        return when {
            Tempo.areCompatible(a.bpm, b.bpm, tolerance = 0.06) -> 1.0
            Tempo.areCompatible(a.bpm, b.bpm, tolerance = 0.12) -> 0.5
            else -> 0.0
        }
    }

    private fun key(a: TrackProfile, b: TrackProfile): Double {
        if (a.key == null || b.key == null) return UNKNOWN
        return if (a.key.isCompatibleWith(b.key)) 1.0 else 0.3
    }

    private fun energy(a: TrackProfile, b: TrackProfile): Double {
        if (a.energy == null || b.energy == null) return UNKNOWN
        return 1.0 - abs(a.energy - b.energy)
    }
}
