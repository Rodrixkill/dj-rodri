package com.rvillarroel.djrodri.domain.lyrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LrcParserTest {

    @Test
    fun `hundredths, tenths and milliseconds all land on the same clock`() {
        val lines = LrcParser.parse("[00:01.50] a\n[00:02.5] b\n[00:03.250] c\n[01:04] d")

        assertEquals(listOf(1_500L, 2_500L, 3_250L, 64_000L), lines.map { it.timeMs })
    }

    @Test
    fun `a line with several timestamps becomes one line per timestamp, in time order`() {
        val lines = LrcParser.parse("[00:40.00][00:10.00] coro\n[00:20.00] verso")

        assertEquals(listOf(LrcLine(10_000, "coro"), LrcLine(20_000, "verso"), LrcLine(40_000, "coro")), lines)
    }

    @Test
    fun `metadata tags and untimed lines are dropped but blank timed lines stay as gaps`() {
        val lines = LrcParser.parse("[ar:Artista]\n[ti:Tema]\nsin tiempo\n[00:05.00] hola\n[00:09.00]")

        assertEquals(listOf(LrcLine(5_000, "hola"), LrcLine(9_000, "")), lines)
    }
}
