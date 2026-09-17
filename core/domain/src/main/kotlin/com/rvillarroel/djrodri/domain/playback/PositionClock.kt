package com.rvillarroel.djrodri.domain.playback

/**
 * App Remote only emits a PlayerState when something changes, never while a song simply plays.
 * The position between events is extrapolated from the last snapshot; the spike measures how far that drifts.
 */
data class PlaybackSnapshot(
    val trackUri: String,
    val positionMs: Long,
    val capturedAtMs: Long,
    val isPaused: Boolean,
    val speed: Float = 1f,
) {
    fun positionAt(nowMs: Long): Long =
        if (isPaused) positionMs else positionMs + ((nowMs - capturedAtMs) * speed).toLong()

    /** Milliseconds until [targetMs] is reached, or null when paused (a paused song never gets there). */
    fun msUntil(targetMs: Long, nowMs: Long): Long? =
        if (isPaused) null else ((targetMs - positionAt(nowMs)) / speed).toLong()
}
