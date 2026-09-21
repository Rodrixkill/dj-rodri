package com.rvillarroel.djrodri.domain.lyrics

/**
 * Where a song is sung and where it breathes, read off its synced lyrics.
 *
 * [density] is the share of the song with someone singing, 0..1. [instrumentals] are the stretches with nobody
 * singing: intro, solos, beat breaks, outro. That is where a DJ changes songs without cutting a sung line in half.
 */
data class VocalProfile(val density: Double, val instrumentals: List<TimeRange>) {
    /** Two songs like this should never be blended over each other: the voices would fight. */
    val heavy: Boolean get() = density >= HEAVY_DENSITY

    /** The instrumental stretch [positionMs] falls in, if any. */
    fun instrumentalAt(positionMs: Long): TimeRange? = instrumentals.firstOrNull { positionMs in it.startMs until it.endMs }

    companion object {
        const val HEAVY_DENSITY = 0.55
    }
}

object VocalAnalyzer {
    /** A line is sung for about this long at most; beyond it, the gap to the next line is music alone. */
    const val LINE_MS = 4_000L

    /** A silence shorter than this is a breath, not an instrumental. */
    const val MIN_INSTRUMENTAL_MS = 8_000L

    fun analyze(lines: List<LrcLine>, durationMs: Long): VocalProfile? {
        val sung = lines.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
        if (sung.size < MIN_LINES || durationMs <= 0) return null

        var singingMs = 0L
        val instrumentals = mutableListOf<TimeRange>()
        if (sung.first().timeMs >= MIN_INSTRUMENTAL_MS) instrumentals += TimeRange(0, sung.first().timeMs)
        sung.zipWithNext { line, next ->
            val gap = next.timeMs - line.timeMs
            singingMs += minOf(gap, LINE_MS)
            if (gap - LINE_MS >= MIN_INSTRUMENTAL_MS) instrumentals += TimeRange(line.timeMs + LINE_MS, next.timeMs)
        }
        val last = sung.last().timeMs
        singingMs += minOf(LINE_MS, durationMs - last).coerceAtLeast(0)
        if (durationMs - (last + LINE_MS) >= MIN_INSTRUMENTAL_MS) instrumentals += TimeRange(last + LINE_MS, durationMs)

        return VocalProfile((singingMs.toDouble() / durationMs).coerceIn(0.0, 1.0), instrumentals)
    }

    private const val MIN_LINES = 4
}
