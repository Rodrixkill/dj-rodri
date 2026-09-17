package com.rvillarroel.djrodri.domain.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LatencyStatsTest {

    @Test
    fun `percentiles are values that were really measured`() {
        val summary = LatencyStats.summarize((1L..20L).map { it * 10 })!!

        assertEquals(100, summary.p50)
        assertEquals(190, summary.p95)
        assertEquals(200, summary.max)
    }

    @Test
    fun `no samples means no summary instead of zeros that look like a result`() {
        assertNull(LatencyStats.summarize(emptyList()))
    }
}
