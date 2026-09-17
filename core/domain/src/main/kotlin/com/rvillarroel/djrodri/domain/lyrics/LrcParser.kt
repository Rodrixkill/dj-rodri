package com.rvillarroel.djrodri.domain.lyrics

/** One timed line of a synced lyric. Blank [text] marks an instrumental gap, which still tells us where a block ends. */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * Parses LRC ("[mm:ss.xx] texto"), the format LRCLIB returns in `syncedLyrics`.
 *
 * A line may carry several timestamps ("[00:12.00][01:40.00] coro") when the source compresses repeats,
 * so each timestamp becomes its own line. Metadata tags like "[ar:...]" never match the time pattern and are dropped.
 */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String): List<LrcLine> =
        lrc.lineSequence()
            .flatMap { raw ->
                val tags = timeTag.findAll(raw).toList()
                if (tags.isEmpty()) return@flatMap emptySequence()
                val text = raw.substring(tags.last().range.last + 1).trim()
                tags.asSequence().map { LrcLine(toMillis(it), text) }
            }
            .sortedBy { it.timeMs }
            .toList()

    private fun toMillis(match: MatchResult): Long {
        val (minutes, seconds, fraction) = match.destructured
        // "5" is tenths, "50" hundredths, "500" milliseconds: pad right to three digits.
        val millis = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').toLong()
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + millis
    }
}
