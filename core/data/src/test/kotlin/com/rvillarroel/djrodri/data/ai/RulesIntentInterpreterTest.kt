package com.rvillarroel.djrodri.data.ai

import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.EnergyCurve
import com.rvillarroel.djrodri.domain.music.Genres
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RulesIntentInterpreterTest {
    private val rules = RulesIntentInterpreter()

    @Test
    fun `perreo asks for danceable songs around ninety BPM`() {
        val intent = rules.parse("pon algo para perrear")

        assertEquals("Perreo", intent.label)
        assertEquals(85.0, intent.minBpm)
        assertEquals(105.0, intent.maxBpm)
        assertTrue(intent.minDanceability!! >= 0.7)
    }

    @Test
    fun `asking to calm down lowers the energy and winds the room down`() {
        val intent = rules.parse("esto está muy fuerte, algo más tranqui")

        assertEquals(EnergyCurve.WIND_DOWN, intent.curve)
        assertTrue(intent.targetEnergy!! < 0.5)
    }

    @Test
    fun `accents and capitals do not change what it understands`() {
        assertEquals(rules.parse("algo para BAILAR").targetEnergy, rules.parse("algo para bailár").targetEnergy)
    }

    @Test
    fun `two requests at once land in the middle`() {
        val dance = rules.parse("para bailar").targetEnergy!!
        val calm = rules.parse("tranqui").targetEnergy!!

        val both = rules.parse("algo para bailar pero tranqui")

        assertTrue(both.targetEnergy!! < dance)
        assertTrue(both.targetEnergy!! > calm)
        assertEquals("Para bailar + Más tranqui", both.label)
    }

    @Test
    fun `going all in also makes the DJ cut more`() {
        assertEquals(DjMode.HORA_LOCA, rules.parse("vamos a fondo!").mode)
        assertEquals(DjMode.NATURAL, rules.parse("ya vamos cerrando la noche").mode)
    }

    @Test
    fun `an artist you do not want tonight is excluded`() {
        val intent = rules.parse("pon reggaetón pero sin Bad Bunny")

        assertEquals(listOf("Bad Bunny"), intent.excludeArtists)
        assertEquals(85.0, intent.minBpm)
    }

    @Test
    fun `an exclusion on its own still counts as a request`() {
        val intent = rules.parse("nada de Maluma")

        assertEquals(listOf("Maluma"), intent.excludeArtists)
        assertNull(intent.targetEnergy)
    }

    @Test
    fun `a phrase it does not know leaves the DJ as it was`() {
        val intent = rules.parse("ehhh no sé, lo que quieras")

        assertEquals("Como venga", intent.label)
        assertNull(intent.targetEnergy)
        assertTrue(intent.excludeArtists.isEmpty())
    }

    @Test
    fun `the genre you name is part of the request`() {
        val intent = rules.parse("pon house y techno")

        assertTrue(Genres.HOUSE in intent.genres)
        assertTrue(Genres.TECHNO in intent.genres)
        assertTrue(intent.label.contains("house"))
    }

    @Test
    fun `asking for a style keeps the artist as the reference, not as an exclusion`() {
        val intent = rules.parse("algo estilo Avicii")

        assertEquals(listOf("Avicii"), intent.likeArtists)
        assertTrue(intent.excludeArtists.isEmpty())
        assertTrue(intent.label.contains("Avicii"))
    }

    @Test
    fun `a genre after "como" is a genre, not an artist called House`() {
        assertTrue(rules.parse("algo como house").likeArtists.isEmpty())
    }

    @Test
    fun `the rules understand the words a party actually uses`() {
        listOf(
            "sube la energía", "pon cumbia", "algo de despecho", "música romántica",
            "electrónica", "rock", "algo para correr", "hora loca",
        ).forEach { request ->
            assertNotNull(rules.parse(request).targetEnergy, request)
        }
    }
}
