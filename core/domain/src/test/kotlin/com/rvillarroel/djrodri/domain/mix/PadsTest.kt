package com.rvillarroel.djrodri.domain.mix

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PadsTest {
    @Test
    fun `every pad is a short, loud but never clipping stereo sound`() {
        Pad.entries.forEach { pad ->
            val sound = PadSounds.render(pad)
            val seconds = sound.size / 2.0 / PadSounds.SAMPLE_RATE
            val peak = sound.maxOf { abs(it) }

            assertTrue(sound.size % 2 == 0, "${pad.label}: estéreo")
            assertTrue(seconds in 0.5..5.0, "${pad.label}: dura $seconds s")
            assertTrue(peak in 0.15f..1f, "${pad.label}: pico $peak")
        }
    }

    @Test
    fun `the riser swells, it does not start at full volume`() {
        val riser = PadSounds.render(Pad.RISER)
        val quarter = riser.size / 4
        val start = riser.take(quarter).maxOf { abs(it) }
        val end = riser.takeLast(quarter).maxOf { abs(it) }

        assertTrue(end > start * 3, "inicio $start, final $end")
    }
}
