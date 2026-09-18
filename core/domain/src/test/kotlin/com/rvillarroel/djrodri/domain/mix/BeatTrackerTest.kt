package com.rvillarroel.djrodri.domain.mix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class BeatTrackerTest {
    private val hopMs = 10

    @Test
    fun `a steady club tempo is found, and so is where its first beat falls`() {
        val envelope = BeatTracker.clickEnvelope(bpm = 128.0, firstBeatMs = 300, lengthMs = 60_000, hopMs = hopMs)

        val grid = BeatTracker.estimate(envelope, hopMs)!!

        assertTrue(abs(grid.bpm - 128.0) < 1.0, "bpm=${grid.bpm}")
        assertTrue(abs(grid.firstBeatMs - 300) <= 20, "primer golpe=${grid.firstBeatMs}")
        assertTrue(grid.confidence > BeatGrid.MIN_MIXABLE_CONFIDENCE, "confianza=${grid.confidence}")
    }

    @Test
    fun `the pulse survives a noisy track, which is what a real recording is`() {
        val random = Random(7)
        val envelope = BeatTracker.clickEnvelope(95.0, firstBeatMs = 120, lengthMs = 90_000, hopMs = hopMs) { random.nextFloat() * 0.35f }

        val grid = BeatTracker.estimate(envelope, hopMs)!!

        assertTrue(abs(grid.bpm - 95.0) < 1.5, "bpm=${grid.bpm}")
    }

    @Test
    fun `tempos across the range the app cares about are all found, give or take an octave`() {
        // Hearing 174 as 87 is the classic ambiguity, and harmless: at half tempo the beats still line up.
        listOf(90.0, 100.0, 120.0, 128.0, 140.0, 174.0).forEach { bpm ->
            val grid = BeatTracker.estimate(BeatTracker.clickEnvelope(bpm, 0, 60_000, hopMs), hopMs)!!

            assertTrue(sameTempoAllowingOctave(grid.bpm, bpm), "pedido $bpm, encontrado ${grid.bpm}")
        }
    }

    @Test
    fun `music with no pulse is reported as unsure, so it is never beatmatched`() {
        val random = Random(3)
        val noise = FloatArray(6_000) { random.nextFloat() }

        val grid = BeatTracker.estimate(noise, hopMs)

        assertTrue(grid == null || grid.confidence < BeatGrid.MIN_MIXABLE_CONFIDENCE, "confianza=${grid?.confidence}")
    }

    @Test
    fun `a clip too short to hold a beat gives nothing instead of a guess`() {
        assertNull(BeatTracker.estimate(FloatArray(50) { 1f }, hopMs))
        assertNull(BeatTracker.estimate(FloatArray(0), hopMs))
    }

    @Test
    fun `the grid answers the questions a mix asks`() {
        val grid = BeatGrid(firstBeatMs = 300, bpm = 120.0, confidence = 0.9) // 500 ms per beat, 16 s per phrase

        assertEquals(800L, grid.beatAt(1))
        assertEquals(2_300L, grid.nearestBeat(2_400))
        assertEquals(50L, grid.offBeatMs(2_350))
        assertEquals(16_300L, grid.phraseStartBefore(20_000))
        assertEquals(32_300L, grid.phraseStartAfter(20_000))
    }

    private fun sameTempoAllowingOctave(found: Double, asked: Double): Boolean =
        listOf(0.25, 0.5, 1.0, 2.0, 4.0).any { octave -> abs(found - asked * octave) / (asked * octave) < 0.02 }
}
