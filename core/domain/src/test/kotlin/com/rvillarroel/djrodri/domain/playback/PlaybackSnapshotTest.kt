package com.rvillarroel.djrodri.domain.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlaybackSnapshotTest {

    private val playing = PlaybackSnapshot("spotify:track:x", positionMs = 30_000, capturedAtMs = 1_000, isPaused = false)

    @Test
    fun `a playing song keeps advancing between player events`() {
        assertEquals(32_500, playing.positionAt(nowMs = 3_500))
    }

    @Test
    fun `a paused song stays where it stopped and never reaches a cut`() {
        val paused = playing.copy(isPaused = true)

        assertEquals(30_000, paused.positionAt(nowMs = 60_000))
        assertNull(paused.msUntil(targetMs = 40_000, nowMs = 2_000))
    }

    @Test
    fun `time until a cut accounts for what already played since the event`() {
        assertEquals(8_000, playing.msUntil(targetMs = 40_000, nowMs = 3_000))
    }
}
