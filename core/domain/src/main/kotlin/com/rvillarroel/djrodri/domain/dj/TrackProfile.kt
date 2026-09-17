package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.lyrics.ChorusAnalysis
import com.rvillarroel.djrodri.domain.music.CamelotKey

/**
 * Everything the DJ knows about one song, all from open sources or from you — never from Spotify's audio.
 * Any field can be missing; missing data makes the DJ more conservative, never wrong.
 */
data class TrackProfile(
    val id: String,
    val artist: String,
    val durationMs: Long,
    val bpm: Double? = null,
    val key: CamelotKey? = null,
    /** 0..1, as ReccoBeats/AcousticBrainz report it. */
    val energy: Double? = null,
    val danceability: Double? = null,
    val valence: Double? = null,
    val chorus: ChorusAnalysis? = null,
    /** Where you tapped "¡Drop!" while listening. Beats any estimate. */
    val userDropMs: Long? = null,
)

data class CuePoint(val positionMs: Long, val confidence: Double)

/** [cuts] are natural places to leave the song, in playback order. */
data class Cues(val drop: CuePoint?, val cuts: List<CuePoint>)

object CueEstimator {
    /** A "drop" in the first seconds is just the song starting; entering there adds nothing. */
    const val MIN_DROP_MS = 15_000L

    /** Never leave before the song has had its first minute. */
    const val MIN_CUT_MS = 60_000L

    /** Cutting in the last seconds is indistinguishable from letting it end. */
    const val MIN_TAIL_MS = 10_000L

    /** After the last chorus, this much left means an outro worth skipping. */
    const val LONG_OUTRO_MS = 25_000L

    fun estimate(track: TrackProfile): Cues {
        val chorus = track.chorus
        val drop = when {
            track.userDropMs != null -> CuePoint(track.userDropMs, 1.0)
            chorus != null -> chorus.occurrences.first().startMs
                .takeIf { it >= MIN_DROP_MS }
                ?.let { CuePoint(it, chorus.confidence) }
            else -> null
        }

        val cuts = buildList {
            if (chorus == null) return@buildList
            val occurrences = chorus.occurrences
            // With three or more choruses, leaving after the second still gives the crowd the hook twice.
            if (occurrences.size >= 3) add(CuePoint(occurrences[1].endMs, chorus.confidence))
            val last = occurrences.last()
            if (track.durationMs - last.endMs >= LONG_OUTRO_MS) add(CuePoint(last.endMs, chorus.confidence * 0.9))
        }
            .filter { it.positionMs >= MIN_CUT_MS && it.positionMs <= track.durationMs - MIN_TAIL_MS }
            .distinctBy { it.positionMs }
            .sortedBy { it.positionMs }

        return Cues(drop, cuts)
    }
}
