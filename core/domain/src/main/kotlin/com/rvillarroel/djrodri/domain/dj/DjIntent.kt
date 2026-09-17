package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.music.Tempo
import kotlin.math.abs

enum class EnergyCurve {
    /** Hold the level it is at. */
    STEADY,

    /** Climb song after song. */
    BUILD,

    /** Peaks and breathers, the default for a long night. */
    WAVE,

    /** Wind the room down. */
    WIND_DOWN,
}

/**
 * What you asked for, expressed only in numbers the analysis already knows: energy, danceability, mood and tempo.
 *
 * This is the whole contract with the language model: it turns "pon algo para perrear" into these numbers and
 * **never sees a single song**. Choosing the music from them is deterministic, and stays out of the model's hands.
 */
data class DjIntent(
    val label: String,
    val targetEnergy: Double? = null,
    val curve: EnergyCurve = EnergyCurve.WAVE,
    val minEnergy: Double? = null,
    val maxEnergy: Double? = null,
    val minDanceability: Double? = null,
    val minValence: Double? = null,
    val maxValence: Double? = null,
    /** Folded BPM range (see [Tempo.fold]), so half/double tempos do not exclude a song. */
    val minBpm: Double? = null,
    val maxBpm: Double? = null,
    val excludeArtists: List<String> = emptyList(),
    /** A request like "a fondo" can also change how boldly the DJ cuts. */
    val mode: DjMode? = null,
) {
    /** 0..1: how well a song fits. Missing data scores neutral, so a song is never excluded for lacking analysis. */
    fun fit(track: TrackProfile): Double {
        if (excludeArtists.any { track.artist.contains(it, ignoreCase = true) }) return 0.0
        val scores = buildList {
            track.energy?.let { energy ->
                minEnergy?.let { add(band(energy, it, 1.0)) }
                maxEnergy?.let { add(band(energy, 0.0, it)) }
                targetEnergy?.let { add(1.0 - abs(energy - it)) }
            }
            track.danceability?.let { d -> minDanceability?.let { add(band(d, it, 1.0)) } }
            track.valence?.let { v ->
                minValence?.let { add(band(v, it, 1.0)) }
                maxValence?.let { add(band(v, 0.0, it)) }
            }
            track.bpm?.let { bpm ->
                val folded = Tempo.fold(bpm)
                minBpm?.let { add(band(folded, Tempo.fold(it), Double.MAX_VALUE, tolerance = 12.0)) }
                maxBpm?.let { add(band(folded, 0.0, Tempo.fold(it), tolerance = 12.0)) }
            }
        }
        return if (scores.isEmpty()) NEUTRAL_FIT else scores.average()
    }

    /** Inside the range scores 1; outside it fades instead of dropping to zero, so a near miss can still play. */
    private fun band(value: Double, min: Double, max: Double, tolerance: Double = 0.25): Double = when {
        value in min..max -> 1.0
        value < min -> (1.0 - (min - value) / tolerance).coerceAtLeast(0.0)
        else -> (1.0 - (value - max) / tolerance).coerceAtLeast(0.0)
    }

    companion object {
        const val NEUTRAL_FIT = 0.5

        /** Shown when nothing was asked for: the DJ just reads the room's energy. */
        val Default = DjIntent(label = "Como venga")
    }
}
