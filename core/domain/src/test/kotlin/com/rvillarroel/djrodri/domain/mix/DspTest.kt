package com.rvillarroel.djrodri.domain.mix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class DspTest {
    private val rate = 44_100

    private fun db(gain: Double) = 20 * log10(gain)

    /** A stereo sine at [hz], a second long. */
    private fun sine(hz: Double, seconds: Double = 1.0): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * hz * (i / 2) / rate)).toFloat() }
    }

    private fun rms(samples: FloatArray, skipFrames: Int = 4_410): Double {
        var sum = 0.0
        var n = 0
        for (i in skipFrames * 2 until samples.size) { sum += samples[i] * samples[i]; n++ }
        return kotlin.math.sqrt(sum / n)
    }

    @Test
    fun `a flat EQ leaves the sound untouched`() {
        val eq = ThreeBandEq(rate)
        val input = sine(440.0)
        val output = input.copyOf()

        eq.process(output, output.size / 2)

        assertTrue(input.contentEquals(output))
    }

    @Test
    fun `killing the low band removes the kick and leaves the hi-hats`() {
        val eq = ThreeBandEq(rate).apply { set(lowDb = ThreeBandEq.KILL_DB, midDb = 0.0, highDb = 0.0) }
        val bass = sine(60.0)
        val hats = sine(8_000.0)
        val bassBefore = rms(bass)
        val hatsBefore = rms(hats)

        eq.process(bass, bass.size / 2)
        ThreeBandEq(rate).apply { set(ThreeBandEq.KILL_DB, 0.0, 0.0) }.process(hats, hats.size / 2)

        assertTrue(db(rms(bass) / bassBefore) < -18, "bajo: ${db(rms(bass) / bassBefore)} dB")
        assertTrue(abs(db(rms(hats) / hatsBefore)) < 1.0, "agudos: ${db(rms(hats) / hatsBefore)} dB")
    }

    @Test
    fun `the shelves hit the gain they are asked for, where they are asked`() {
        val lowBoost = Biquad.lowShelf(rate, 250.0, 6.0)
        val highCut = Biquad.highShelf(rate, 4_000.0, -12.0)

        assertEquals(6.0, db(lowBoost.magnitudeAt(40.0, rate)), 0.5)
        assertEquals(0.0, db(lowBoost.magnitudeAt(8_000.0, rate)), 0.5)
        assertEquals(-12.0, db(highCut.magnitudeAt(15_000.0, rate)), 0.8)
        assertEquals(0.0, db(highCut.magnitudeAt(100.0, rate)), 0.5)
    }

    @Test
    fun `the filter knob muffles to the left, thins to the right, and is off in the middle`() {
        val filter = FilterFx(rate)

        filter.set(0.0)
        assertEquals(null, filter.cutoffHz)

        filter.set(-1.0)
        val hats = sine(6_000.0)
        filter.process(hats, hats.size / 2)
        assertTrue(db(rms(hats) / 0.3535) < -30, "low-pass a fondo: ${db(rms(hats) / 0.3535)} dB")

        val highPass = FilterFx(rate).apply { set(1.0) }
        val bass = sine(60.0)
        highPass.process(bass, bass.size / 2)
        assertTrue(db(rms(bass) / 0.3535) < -30, "high-pass a fondo: ${db(rms(bass) / 0.3535)} dB")
    }

    @Test
    fun `echo repeats a click one delay later, quieter`() {
        val echo = EchoFx(rate).apply { set(mix = 1.0, delayMs = 100.0) }
        val frames = rate / 2
        val signal = FloatArray(frames * 2)
        signal[0] = 1f
        signal[1] = 1f

        echo.process(signal, frames)

        val delay = rate / 10
        assertTrue(signal[delay * 2] > 0.5f, "primer eco: ${signal[delay * 2]}")
        assertTrue(signal[delay * 4] in 0.2f..0.6f, "segundo eco: ${signal[delay * 4]}")
    }

    @Test
    fun `the echo follows the tempo`() {
        assertEquals(351.5625, EchoFx.delayMs(128.0), 0.001) // three quarters of a beat at 128 BPM
    }

    @Test
    fun `a loop starts on the beat and wraps back to it`() {
        val grid = BeatGrid(firstBeatMs = 100, bpm = 120.0, confidence = 0.9) // a beat every 500 ms
        val loop = LoopRegion.onBeat(grid, positionMs = 1_320, beats = 4, bpmFallback = 120.0, sampleRate = 1_000)

        assertEquals(1_100L, loop.startFrame) // the beat at 1.1 s, not 1.32 s
        assertEquals(2_000L, loop.lengthFrames)
        assertEquals(1_100L, loop.wrap(3_100))
        assertEquals(1_600L, loop.wrap(3_600))
        assertEquals(500L, loop.wrap(500))
    }

    @Test
    fun `the waveform shows bass as bass and hats as highs`() {
        val bass = FloatArray(rate) { i -> (0.8 * sin(2 * PI * 60 * i / rate)).toFloat() }
        val hats = FloatArray(rate) { i -> (0.8 * sin(2 * PI * 8_000 * i / rate)).toFloat() }

        val wave = Waveform.of(bass + hats, rate, points = 100)

        assertTrue(wave.low.take(40).average() > wave.low.drop(60).average() * 3, "graves arriba en la primera mitad")
        assertTrue(wave.high.drop(60).average() > wave.high.take(40).average() * 3, "agudos arriba en la segunda mitad")
    }

    @Test
    fun `a four-on-the-floor kick is found at its tempo from the audio itself`() {
        // 124 BPM kicks: short 55 Hz bursts, the envelope the beat tracker reads comes from the samples.
        val seconds = 30
        val beat = rate * 60.0 / 124
        val mono = FloatArray(rate * seconds) { i ->
            val sinceBeat = (i % beat) / rate
            if (sinceBeat < 0.08) (sin(2 * PI * 55 * sinceBeat) * (1 - sinceBeat / 0.08)).toFloat() else 0f
        }

        val grid = BeatTracker.estimate(OnsetEnvelope.of(mono, rate), hopMs = 10)!!

        assertTrue(listOf(124.0, 62.0, 248.0).any { abs(grid.bpm - it) / it < 0.02 }, "bpm=${grid.bpm}")
    }

    @Test
    fun `the silence after the last note is not counted as music`() {
        val mono = FloatArray(rate * 10) { i -> if (i < rate * 8) kotlin.math.sin(i * 0.05f) * 0.8f else 0f }

        val end = Waveform.of(mono, rate, points = 100).lastSound()

        assertTrue(abs(end - 0.8f) <= 0.02f, "fin=$end")
    }
}
