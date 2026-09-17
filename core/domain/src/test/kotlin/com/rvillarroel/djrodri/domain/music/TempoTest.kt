package com.rvillarroel.djrodri.domain.music

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TempoTest {

    @Test
    fun `a tempo reported at double speed folds back to the one you dance to`() {
        assertEquals(88.964, Tempo.fold(177.928), 0.001)
        assertEquals(96.0, Tempo.fold(48.0), 0.001)
    }

    @Test
    fun `songs an octave apart are still compatible`() {
        assertTrue(Tempo.areCompatible(90.0, 180.0))
    }

    @Test
    fun `six percent apart mixes, ten percent does not`() {
        assertTrue(Tempo.areCompatible(100.0, 106.0))
        assertFalse(Tempo.areCompatible(100.0, 110.0))
    }
}
