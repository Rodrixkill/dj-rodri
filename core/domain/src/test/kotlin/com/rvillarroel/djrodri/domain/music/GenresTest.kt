package com.rvillarroel.djrodri.domain.music

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenresTest {

    @Test
    fun `the many ways a genre is written all land on the same one`() {
        assertEquals(Genres.HOUSE, Genres.canonical("Progressive House"))
        assertEquals(Genres.HOUSE, Genres.canonical("deep house"))
        assertEquals(Genres.HIP_HOP, Genres.canonical("hip-hop"))
        assertEquals(Genres.REGGAETON, Genres.canonical("Reggaetón"))
        assertEquals(Genres.BALLAD, Genres.canonical("baladas"))
    }

    @Test
    fun `a genre also counts as the families it belongs to`() {
        val houseSong = Genres.expand(listOf("progressive house"))

        assertTrue(Genres.HOUSE in houseSong)
        assertTrue(Genres.EDM in houseSong)
        assertTrue(Genres.ELECTRONIC in houseSong)
        assertTrue(Genres.REGGAETON in Genres.expand(listOf("perreo")))
        assertTrue(Genres.LATIN in Genres.expand(listOf("perreo")))
    }

    @Test
    fun `a tag that is not a genre is not invented into one`() {
        assertNull(Genres.canonical("2019"))
        assertNull(Genres.canonical("favoritas de la abuela"))
    }

    @Test
    fun `genres named in a request are picked out of the sentence`() {
        val mentioned = Genres.mentioned("pon algo de house y un poco de rock, nada de reggaetón")

        assertTrue(Genres.HOUSE in mentioned)
        assertTrue(Genres.ROCK in mentioned)
        assertTrue(Genres.REGGAETON in mentioned)
    }
}
