package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.lyrics.ChorusAnalysis
import com.rvillarroel.djrodri.domain.lyrics.TimeRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CueEstimatorTest {

    @Test
    fun `the drop is the first chorus unless you marked one yourself`() {
        assertEquals(CuePoint(40_000, 0.9), CueEstimator.estimate(song("a")).drop)
        assertEquals(CuePoint(52_000, 1.0), CueEstimator.estimate(song("a", userDropMs = 52_000)).drop)
    }

    @Test
    fun `a chorus in the first seconds is not a drop worth jumping to`() {
        val early = song("a").let {
            it.copy(chorus = it.chorus!!.copy(occurrences = listOf(TimeRange(8_000, 20_000), TimeRange(90_000, 100_000))))
        }

        assertNull(CueEstimator.estimate(early).drop)
    }

    @Test
    fun `natural exits are after the second chorus and before a long outro`() {
        val cuts = CueEstimator.estimate(song("a")).cuts

        assertEquals(listOf(120_000L, 180_000L), cuts.map { it.positionMs })
        assertTrue(cuts[1].confidence < cuts[0].confidence)
    }

    @Test
    fun `a song whose last chorus runs to the end has no outro to skip`() {
        val tight = song("a", durationMs = 195_000).let {
            it.copy(chorus = ChorusAnalysis(listOf(TimeRange(40_000, 60_000), TimeRange(170_000, 190_000)), 4, 0.9))
        }

        assertTrue(CueEstimator.estimate(tight).cuts.isEmpty())
    }

    @Test
    fun `without lyrics there is nowhere safe to cut`() {
        assertTrue(CueEstimator.estimate(song("a", chorusConfidence = null)).cuts.isEmpty())
    }
}
