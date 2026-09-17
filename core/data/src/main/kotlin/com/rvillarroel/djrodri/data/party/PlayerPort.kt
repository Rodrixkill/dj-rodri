package com.rvillarroel.djrodri.data.party

import kotlinx.coroutines.flow.StateFlow

/** What the party needs to know about the player: which song, where, and since when. */
data class PlayerState(
    val trackUri: String?,
    val durationMs: Long,
    val positionMs: Long,
    val isPaused: Boolean,
    /** Clock time when this state arrived; positions in between are extrapolated. */
    val capturedAtMs: Long,
) {
    fun positionAt(nowMs: Long): Long =
        if (isPaused) positionMs else (positionMs + (nowMs - capturedAtMs)).coerceAtMost(durationMs)
}

/**
 * The only way the party touches Spotify. App Remote implements it today; if the phase 0 spike shows App Remote is
 * broken, a Web API implementation replaces it without touching the engine.
 */
interface PlayerPort {
    val state: StateFlow<PlayerState?>
    fun play(uri: String)
    fun seekTo(positionMs: Long)
    fun pause()
    fun resume()
}
