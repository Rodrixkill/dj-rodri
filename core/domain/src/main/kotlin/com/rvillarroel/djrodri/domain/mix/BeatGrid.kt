package com.rvillarroel.djrodri.domain.mix

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Where every beat of a track falls. This is what real mixing needs and what Spotify can never give us:
 * it only exists for your own audio files, which we are allowed to analyze.
 */
data class BeatGrid(
    val firstBeatMs: Long,
    val bpm: Double,
    /** 0..1: how clear the pulse was. Below [MIN_MIXABLE_CONFIDENCE] the track is played, not mixed. */
    val confidence: Double,
) {
    val beatMs: Double get() = 60_000.0 / bpm
    val barMs: Double get() = beatMs * BEATS_PER_BAR
    val phraseMs: Double get() = barMs * BARS_PER_PHRASE

    fun beatAt(index: Int): Long = firstBeatMs + (beatMs * index).roundToLong()

    /** The beat closest to [positionMs], the one a cut should land on. */
    fun nearestBeat(positionMs: Long): Long = beatAt(((positionMs - firstBeatMs) / beatMs).roundToLong().toInt())

    /** The start of the phrase at or before [positionMs]: where a DJ brings the next track in. */
    fun phraseStartBefore(positionMs: Long): Long {
        val phrases = floor((positionMs - firstBeatMs) / phraseMs).coerceAtLeast(0.0)
        return firstBeatMs + (phrases * phraseMs).roundToLong()
    }

    fun phraseStartAfter(positionMs: Long): Long {
        val start = phraseStartBefore(positionMs)
        return if (start >= positionMs) start else start + phraseMs.roundToLong()
    }

    /** How far off the beat a position is, in milliseconds: zero means perfectly on the grid. */
    fun offBeatMs(positionMs: Long): Long = abs(positionMs - nearestBeat(positionMs))

    companion object {
        const val BEATS_PER_BAR = 4
        const val BARS_PER_PHRASE = 8
        const val MIN_MIXABLE_CONFIDENCE = 0.55
    }
}
