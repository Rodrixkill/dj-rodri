package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.lyrics.ChorusAnalysis
import com.rvillarroel.djrodri.domain.lyrics.VocalProfile
import com.rvillarroel.djrodri.domain.music.CamelotKey
import com.rvillarroel.djrodri.domain.music.Tempo

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
    /** Canonical genres from MusicBrainz tags (see Genres). Empty when the song was not matched. */
    val genres: Set<String> = emptySet(),
    val chorus: ChorusAnalysis? = null,
    /** Where you tapped "¡Drop!" while listening. Beats any estimate. */
    val userDropMs: Long? = null,
    /** Where it is sung and where it breathes, from the synced lyrics. Null without them. */
    val vocals: VocalProfile? = null,
)

data class CuePoint(val positionMs: Long, val confidence: Double)

/**
 * [cuts] are natural places to leave the song, in playback order. [swapPoint] is where its second drop hits, the
 * moment a drop swap hands over to the next song's drop. [drops] is every drop or chorus start, in order.
 */
data class Cues(
    val drop: CuePoint?,
    val cuts: List<CuePoint>,
    val swapPoint: CuePoint? = null,
    val drops: List<Long> = emptyList(),
)

object CueEstimator {
    /** A "drop" in the first seconds is just the song starting; entering there adds nothing. */
    const val MIN_DROP_MS = 15_000L

    /** Never leave before the song has had its first minute. */
    const val MIN_CUT_MS = 60_000L

    /** Cutting in the last seconds is indistinguishable from letting it end. */
    const val MIN_TAIL_MS = 10_000L

    /** After the last chorus, this much left means an outro worth skipping. */
    const val LONG_OUTRO_MS = 25_000L

    /** Electronic music is built in phrases of eight bars; DJs cut exactly there. */
    const val BEATS_PER_BAR = 4
    const val BARS_PER_PHRASE = 8

    /**
     * How much a phrase boundary is trusted with no lyrics to confirm it. High enough for the DJ to use it in
     * Fiesta mode, because leaving an instrumental track on a phrase boundary is musically safe, and lower than
     * a chorus detected from real lyrics.
     */
    const val GRID_CUT_CONFIDENCE = 0.72

    /** A guessed drop is never confident enough to jump into: for that you tap "¡Drop!". */
    const val GRID_DROP_CONFIDENCE = 0.5

    fun estimate(track: TrackProfile): Cues {
        val chorus = track.chorus
        val grid = phraseGrid(track)
        val drop = when {
            track.userDropMs != null -> CuePoint(track.userDropMs, 1.0)
            chorus != null -> chorus.occurrences.first().startMs
                .takeIf { it >= MIN_DROP_MS }
                ?.let { CuePoint(it, chorus.confidence) }
            // No lyrics (most electronic tracks): the first drop tends to land a quarter of the way in.
            grid != null -> grid.boundaryNear(track.durationMs / 4)
                .takeIf { it >= MIN_DROP_MS }
                ?.let { CuePoint(it, GRID_DROP_CONFIDENCE) }
            else -> null
        }

        val cuts = buildList {
            if (chorus == null) {
                // Without lyrics, cut where the music itself turns: on a phrase boundary, past the middle.
                grid?.let { g ->
                    add(CuePoint(g.boundaryNear((track.durationMs * 0.6).toLong()), GRID_CUT_CONFIDENCE))
                    add(CuePoint(g.boundaryNear((track.durationMs * 0.78).toLong()), GRID_CUT_CONFIDENCE * 0.95))
                }
                return@buildList
            }
            val occurrences = chorus.occurrences
            // With three or more choruses, leaving after the second still gives the crowd the hook twice.
            if (occurrences.size >= 3) add(CuePoint(occurrences[1].endMs, chorus.confidence))
            val last = occurrences.last()
            if (track.durationMs - last.endMs >= LONG_OUTRO_MS) add(CuePoint(last.endMs, chorus.confidence * 0.9))
            // Once the hook has been heard twice, an instrumental stretch is the cleanest way out: no line cut in half.
            val hookHeardTwice = occurrences.getOrNull(1)?.endMs ?: return@buildList
            track.vocals?.instrumentals
                ?.filter { it.startMs >= hookHeardTwice }
                ?.forEach { add(CuePoint(it.startMs, chorus.confidence * 0.95)) }
        }
            .filter { it.positionMs >= MIN_CUT_MS && it.positionMs <= track.durationMs - MIN_TAIL_MS }
            .distinctBy { it.positionMs }
            .sortedBy { it.positionMs }

        // The second drop: the crowd already had the first, and the build-up to this one is what gets swapped.
        val swapPoint = when {
            chorus != null -> chorus.occurrences.getOrNull(1)?.let { CuePoint(it.startMs, chorus.confidence) }
            grid != null -> CuePoint(grid.boundaryNear(track.durationMs / 2), GRID_CUT_CONFIDENCE)
            else -> null
        }?.takeIf { it.positionMs >= MIN_CUT_MS && it.positionMs <= track.durationMs - MIN_TAIL_MS }

        val drops = buildList {
            track.userDropMs?.let(::add)
            chorus?.occurrences?.forEach { add(it.startMs) }
            if (chorus == null && grid != null) {
                add(grid.boundaryNear(track.durationMs / 4))
                add(grid.boundaryNear(track.durationMs / 2))
            }
        }.filter { it >= MIN_DROP_MS && it <= track.durationMs - MIN_TAIL_MS }.distinct().sorted()

        return Cues(drop, cuts, swapPoint, drops)
    }

    /** The phrase grid of a song, counted from its start: only possible when the tempo is known. */
    private fun phraseGrid(track: TrackProfile): PhraseGrid? {
        val bpm = track.bpm?.takeIf { it > 0 } ?: return null
        val phraseMs = (60_000.0 / Tempo.fold(bpm) * BEATS_PER_BAR * BARS_PER_PHRASE).toLong()
        return PhraseGrid(phraseMs).takeIf { phraseMs in 5_000..40_000 }
    }

    /**
     * Phrases counted from the first beat. The song's real first beat is unknown (that would need the audio), so
     * this is off by the length of its intro; on a phrase boundary that error is the same everywhere and the cut
     * still lands where the music changes.
     */
    private class PhraseGrid(val phraseMs: Long) {
        fun boundaryNear(positionMs: Long): Long = (Math.round(positionMs.toDouble() / phraseMs)) * phraseMs
    }
}
