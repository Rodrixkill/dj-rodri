package com.rvillarroel.djrodri.domain.stats

import kotlin.math.ceil

data class LatencySummary(val count: Int, val p50: Long, val p95: Long, val max: Long, val mean: Long) {
    override fun toString() = "n=$count p50=${p50}ms p95=${p95}ms max=${max}ms media=${mean}ms"
}

object LatencyStats {
    fun summarize(samples: List<Long>): LatencySummary? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        return LatencySummary(
            count = sorted.size,
            p50 = percentile(sorted, 50),
            p95 = percentile(sorted, 95),
            max = sorted.last(),
            mean = sorted.average().toLong(),
        )
    }

    /** Nearest-rank percentile: always a value that was actually measured. */
    private fun percentile(sorted: List<Long>, p: Int): Long =
        sorted[(ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
}
