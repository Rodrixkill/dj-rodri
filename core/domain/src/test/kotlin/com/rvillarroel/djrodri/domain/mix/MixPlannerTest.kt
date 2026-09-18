package com.rvillarroel.djrodri.domain.mix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class MixPlannerTest {

    private fun track(
        id: String,
        bpm: Double?,
        confidence: Double = 0.9,
        durationMs: Long = 300_000,
        firstBeatMs: Long = 0,
        mixInMs: Long? = null,
        mixOutMs: Long? = null,
    ) = LocalTrack(
        id = id,
        title = "Tema $id",
        artist = "Artista",
        durationMs = durationMs,
        grid = bpm?.let { BeatGrid(firstBeatMs, it, confidence) },
        mixInMs = mixInMs,
        mixOutMs = mixOutMs,
    )

    @Test
    fun `two tracks at nearly the same tempo are blended on the beat`() {
        // 128 BPM: a bar is 1875 ms and a phrase 15 s.
        val a = track("a", bpm = 128.0, mixOutMs = 240_000)
        val b = track("b", bpm = 126.0, mixInMs = 20_000)

        val plan = MixPlanner.plan(a, b) as MixPlan.Blend

        assertEquals(240_000L, plan.fadeStartAMs) // 16 phrases exactly
        // B keeps its own grid: at 126 BPM a phrase lasts 15238 ms, so the phrase after 20 s starts at 30476.
        assertEquals(30_476L, plan.startBMs)
        assertEquals(7_500L, plan.fadeMs) // four bars
        assertTrue(abs(plan.speedRatioB - 128.0 / 126.0) < 0.0001)
        assertTrue(plan.beatsAligned)
    }

    @Test
    fun `tempos too far apart are cut, because stretching that much is heard`() {
        val plan = MixPlanner.plan(track("a", bpm = 128.0), track("b", bpm = 95.0, mixInMs = 12_000))

        assertTrue(plan is MixPlan.Cut, "plan=$plan")
    }

    @Test
    fun `a track with no clear pulse is played, not mixed`() {
        val unsure = track("b", bpm = 128.0, confidence = 0.2, mixInMs = 5_000)

        val plan = MixPlanner.plan(track("a", bpm = 128.0), unsure)

        assertTrue(plan is MixPlan.Cut)
        assertEquals(5_000L, (plan as MixPlan.Cut).startBMs)
    }

    @Test
    fun `half and double tempo count as the same tempo, so they still blend`() {
        val plan = MixPlanner.plan(track("a", bpm = 128.0), track("b", bpm = 64.0))

        assertTrue(plan is MixPlan.Blend, "plan=$plan")
    }

    @Test
    fun `the fade never runs past the end of the outgoing track`() {
        val short = track("a", bpm = 128.0, durationMs = 60_000)

        val plan = MixPlanner.plan(short, track("b", bpm = 128.0)) as MixPlan.Blend

        assertTrue(plan.fadeStartAMs + plan.fadeMs <= short.durationMs, "fin=${plan.fadeStartAMs + plan.fadeMs}")
    }

    @Test
    fun `the middle of the fade is as loud as either side alone`() {
        val (outgoing, incoming) = MixPlanner.gains(0.5)

        assertTrue(abs(outgoing * outgoing + incoming * incoming - 1.0) < 0.0001)
        assertEquals(1.0, MixPlanner.gains(0.0).first, 0.0001)
        assertEquals(1.0, MixPlanner.gains(1.0).second, 0.0001)
    }

    @Test
    fun `a tempo nudge over six percent is refused`() {
        assertNull(MixPlanner.tempoRatio(128.0, 110.0))
        assertTrue(MixPlanner.tempoRatio(128.0, 124.0)!! > 1.0)
    }
}
