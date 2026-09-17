package com.rvillarroel.djrodri.domain.lyrics

import java.text.Normalizer

data class TimeRange(val startMs: Long, val endMs: Long)

/**
 * The block of lines that repeats most in a song, which in reggaetón, pop and cumbia is almost always the chorus.
 *
 * [confidence] is a heuristic in 0..1: more repeats and longer blocks are harder to hit by accident.
 */
data class ChorusAnalysis(
    val occurrences: List<TimeRange>,
    val linesPerBlock: Int,
    val confidence: Double,
)

/**
 * Finds the chorus from synced lyrics alone. We never have the audio (Spotify forbids it),
 * so repetition in the words is the signal we can trust.
 */
object ChorusFinder {
    private const val MAX_BLOCK_LINES = 8
    private const val MIN_BLOCK_LINES = 2

    fun find(lines: List<LrcLine>, durationMs: Long? = null): ChorusAnalysis? {
        val sung = lines.withIndex()
            .map { (index, line) -> SungLine(index, line.timeMs, normalize(line.text)) }
            .filter { it.key.isNotEmpty() }
        if (sung.size < MIN_BLOCK_LINES * 2) return null

        var best: Candidate? = null
        val longest = minOf(MAX_BLOCK_LINES, sung.size / 2)
        for (length in longest downTo MIN_BLOCK_LINES) {
            val startsByKey = HashMap<String, MutableList<Int>>()
            for (start in 0..sung.size - length) {
                val key = (start until start + length).joinToString("\n") { sung[it].key }
                startsByKey.getOrPut(key) { mutableListOf() }.add(start)
            }
            for (starts in startsByKey.values) {
                val separate = nonOverlapping(starts, length)
                if (separate.size < 2) continue
                val candidate = Candidate(separate, length)
                // Length counts twice: a long block repeated twice beats a two-line hook chanted five times inside it.
                if (best == null || candidate.score > best.score) best = candidate
            }
        }

        val chosen = best ?: return null
        val averageGap = averageGapMs(sung)
        val occurrences = chosen.starts.map { start ->
            val last = sung[start + chosen.length - 1]
            val next = lines.getOrNull(last.originalIndex + 1)
            val end = next?.timeMs ?: (last.timeMs + averageGap)
            TimeRange(sung[start].timeMs, durationMs?.let { minOf(end, it) } ?: end)
        }
        return ChorusAnalysis(occurrences, chosen.length, confidence(chosen))
    }

    internal fun normalize(text: String): String {
        val withoutAdLibs = text.replace(Regex("""\([^)]*\)"""), " ")
        val withoutAccents = Normalizer.normalize(withoutAdLibs, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
        return withoutAccents.lowercase()
            .replace(Regex("""[^\p{L}\p{N} ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun nonOverlapping(starts: List<Int>, length: Int): List<Int> {
        val kept = mutableListOf<Int>()
        for (start in starts) if (kept.isEmpty() || start >= kept.last() + length) kept += start
        return kept
    }

    private fun averageGapMs(sung: List<SungLine>): Long {
        val gaps = sung.zipWithNext { a, b -> b.timeMs - a.timeMs }.filter { it > 0 }
        return if (gaps.isEmpty()) 3_000 else gaps.average().toLong()
    }

    private fun confidence(candidate: Candidate): Double =
        (0.35 + 0.15 * (candidate.starts.size - 1) + 0.06 * candidate.length).coerceAtMost(0.95)

    private data class SungLine(val originalIndex: Int, val timeMs: Long, val key: String)

    private class Candidate(val starts: List<Int>, val length: Int) {
        val score = starts.size * length * length
    }
}
