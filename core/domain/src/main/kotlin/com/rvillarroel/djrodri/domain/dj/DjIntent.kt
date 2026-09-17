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
    /** Canonical genres asked for ("house", "rock"); see [com.rvillarroel.djrodri.domain.music.Genres]. */
    val genres: Set<String> = emptySet(),
    val excludeGenres: Set<String> = emptySet(),
    /** "algo estilo Avicii": kept for the label and for how the numbers above were worked out. */
    val likeArtists: List<String> = emptyList(),
    /** A request like "a fondo" can also change how boldly the DJ cuts. */
    val mode: DjMode? = null,
) {
    /** 0..1: how well a song fits. Missing data scores neutral, so a song is never excluded for lacking analysis. */
    fun fit(track: TrackProfile): Double {
        if (excludeArtists.any { track.artist.contains(it, ignoreCase = true) }) return 0.0
        if (excludeGenres.isNotEmpty() && track.genres.any { it in excludeGenres }) return 0.0

        val scored = buildList {
            track.energy?.let { energy ->
                minEnergy?.let { add(1.0 to band(energy, it, 1.0)) }
                maxEnergy?.let { add(1.0 to band(energy, 0.0, it)) }
                targetEnergy?.let { add(1.0 to (1.0 - abs(energy - it))) }
            }
            track.danceability?.let { d -> minDanceability?.let { add(1.0 to band(d, it, 1.0)) } }
            track.valence?.let { v ->
                minValence?.let { add(1.0 to band(v, it, 1.0)) }
                maxValence?.let { add(1.0 to band(v, 0.0, it)) }
            }
            track.bpm?.let { bpm ->
                val folded = Tempo.fold(bpm)
                minBpm?.let { add(1.0 to band(folded, Tempo.fold(it), Double.MAX_VALUE, tolerance = 12.0)) }
                maxBpm?.let { add(1.0 to band(folded, 0.0, Tempo.fold(it), tolerance = 12.0)) }
            }
            // Asking for a style is the most concrete thing you can say, so it weighs more than any single number.
            if (genres.isNotEmpty()) {
                val match = when {
                    track.genres.isEmpty() -> NEUTRAL_FIT
                    track.genres.any { it in genres } -> 1.0
                    else -> OFF_GENRE_FIT
                }
                add(GENRE_WEIGHT to match)
            }
        }
        if (scored.isEmpty()) return NEUTRAL_FIT
        return scored.sumOf { (weight, value) -> weight * value } / scored.sumOf { it.first }
    }

    /** Inside the range scores 1; outside it fades instead of dropping to zero, so a near miss can still play. */
    private fun band(value: Double, min: Double, max: Double, tolerance: Double = 0.25): Double = when {
        value in min..max -> 1.0
        value < min -> (1.0 - (min - value) / tolerance).coerceAtLeast(0.0)
        else -> (1.0 - (value - max) / tolerance).coerceAtLeast(0.0)
    }

    companion object {
        const val NEUTRAL_FIT = 0.5

        /** A song of another genre is not banned: with nothing better around, it still plays. */
        const val OFF_GENRE_FIT = 0.1
        const val GENRE_WEIGHT = 3.0

        /** Shown when nothing was asked for: the DJ just reads the room's energy. */
        val Default = DjIntent(label = "Como venga")
    }
}
