package com.rvillarroel.djrodri.domain.lyrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChorusFinderTest {

    private fun song(vararg timedLines: Pair<Int, String>) = timedLines.map { (seconds, text) -> LrcLine(seconds * 1_000L, text) }

    private val chorus = listOf("Bailando toda la noche", "Hasta que salga el sol", "Nadie nos para", "Esta es nuestra canción")

    private fun typicalSong(): List<LrcLine> {
        var t = 10
        val lines = mutableListOf<Pair<Int, String>>()
        fun sing(text: String) { lines += t to text; t += 4 }
        listOf("Llegué temprano", "Te vi en la barra", "Pedí dos tragos", "Y me miraste").forEach(::sing)
        chorus.forEach(::sing)
        lines += t to ""; t += 8
        listOf("Segundo verso", "Otra historia", "Suena el bajo", "Nos movemos").forEach(::sing)
        chorus.forEach(::sing)
        listOf("Puente lento", "Todo se apaga").forEach(::sing)
        chorus.forEach(::sing)
        lines += t to ""
        return song(*lines.toTypedArray())
    }

    @Test
    fun `the four-line block sung three times is found as the chorus with its three entries`() {
        val analysis = ChorusFinder.find(typicalSong())!!

        assertEquals(4, analysis.linesPerBlock)
        assertEquals(listOf(26_000L, 66_000L, 90_000L), analysis.occurrences.map { it.startMs })
    }

    @Test
    fun `a chorus ends where the next timed line starts, including an instrumental gap`() {
        val analysis = ChorusFinder.find(typicalSong())!!

        // First chorus: lines at 26, 30, 34, 38 s, then a blank gap line at 42 s.
        assertEquals(42_000L, analysis.occurrences.first().endMs)
    }

    @Test
    fun `the last chorus never ends after the song does`() {
        val lines = typicalSong().dropLast(1) // no closing gap, so the end must be estimated
        // Last chorus sings from 90 s to 102 s; the song fades out at 104 s, before the estimated end at ~106 s.
        val analysis = ChorusFinder.find(lines, durationMs = 104_000)!!

        assertEquals(104_000L, analysis.occurrences.last().endMs)
    }

    @Test
    fun `accents, punctuation and ad-libs in parentheses do not hide a repeat`() {
        val lines = song(
            0 to "Intro", 4 to "¡Despacito!", 8 to "Quiero respirar tu cuello", 12 to "Algo distinto", 16 to "Otra cosa",
            20 to "despacito (oh yeah)", 24 to "quiero respirar tu cuello...", 28 to "Final",
        )

        val analysis = ChorusFinder.find(lines)!!

        assertEquals(listOf(4_000L, 20_000L), analysis.occurrences.map { it.startMs })
    }

    @Test
    fun `lyrics with nothing repeated have no chorus`() {
        val lines = song(0 to "uno", 4 to "dos", 8 to "tres", 12 to "cuatro", 16 to "cinco", 20 to "seis")

        assertNull(ChorusFinder.find(lines))
    }

    @Test
    fun `more repeats give more confidence, never full certainty`() {
        val analysis = ChorusFinder.find(typicalSong())!!

        assertTrue(analysis.confidence > 0.7)
        assertTrue(analysis.confidence < 1.0)
    }
}
