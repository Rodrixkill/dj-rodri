package com.rvillarroel.djrodri.domain.music

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CamelotKeyTest {

    @Test
    fun `keys land on the published Camelot wheel`() {
        assertEquals("8B", CamelotKey.from(0, 1).toString())  // C major
        assertEquals("8A", CamelotKey.from(9, 0).toString())  // A minor
        assertEquals("1B", CamelotKey.from(11, 1).toString()) // B major
        assertEquals("10B", CamelotKey.from(2, 1).toString()) // D major (Despacito)
        assertEquals("5A", CamelotKey.from(0, 0).toString())  // C minor
    }

    @Test
    fun `neighbours on the wheel and the relative key mix, a jump across does not`() {
        val eightA = CamelotKey(8, isMinor = true)

        assertTrue(eightA.isCompatibleWith(CamelotKey(9, isMinor = true)))
        assertTrue(eightA.isCompatibleWith(CamelotKey(8, isMinor = false)))
        assertTrue(CamelotKey(12, isMinor = true).isCompatibleWith(CamelotKey(1, isMinor = true)))
        assertFalse(eightA.isCompatibleWith(CamelotKey(2, isMinor = true)))
        assertFalse(eightA.isCompatibleWith(CamelotKey(9, isMinor = false)))
    }

    @Test
    fun `a source without a key gives no Camelot key`() {
        assertNull(CamelotKey.from(-1, 1))
    }
}
