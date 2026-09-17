package com.rvillarroel.djrodri.domain.dj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DjIntentTest {

    private val perreo = DjIntent("Perreo", targetEnergy = 0.8, minDanceability = 0.7, minBpm = 85.0, maxBpm = 105.0)

    private fun track(id: String, energy: Double?, dance: Double?, bpm: Double?, artist: String = "A$id") =
        song(id, artist = artist, energy = energy, bpm = bpm).copy(danceability = dance)

    @Test
    fun `a song that matches the request beats one that does not`() {
        val fits = track("fits", energy = 0.8, dance = 0.8, bpm = 95.0)
        val ballad = track("ballad", energy = 0.2, dance = 0.3, bpm = 70.0)

        assertTrue(perreo.fit(fits) > 0.9)
        assertTrue(perreo.fit(ballad) < 0.4)
    }

    @Test
    fun `a song reported at double tempo still counts as the same tempo`() {
        assertEquals(perreo.fit(track("x", 0.8, 0.8, 95.0)), perreo.fit(track("y", 0.8, 0.8, 190.0)))
    }

    @Test
    fun `just outside the range still has a chance, it does not drop to zero`() {
        val slightlyCalmer = track("x", energy = 0.62, dance = 0.72, bpm = 95.0)

        assertTrue(perreo.fit(slightlyCalmer) > 0.7)
    }

    @Test
    fun `an excluded artist never plays, however well it would fit`() {
        val intent = perreo.copy(excludeArtists = listOf("Bad Bunny"))

        assertEquals(0.0, intent.fit(track("x", 0.8, 0.8, 95.0, artist = "Bad Bunny feat. Jhayco")))
    }

    @Test
    fun `a song without analysis is neither preferred nor excluded`() {
        assertEquals(DjIntent.NEUTRAL_FIT, perreo.fit(track("x", energy = null, dance = null, bpm = null)))
    }

    @Test
    fun `the request decides what comes next in the set`() {
        val playlist = listOf(
            track("balada", energy = 0.2, dance = 0.3, bpm = 70.0),
            track("perreo", energy = 0.8, dance = 0.85, bpm = 95.0),
            track("rock", energy = 0.75, dance = 0.4, bpm = 130.0),
        )

        val ordered = SetBuilder.order(playlist, intent = perreo, random = Random(3))

        assertEquals("perreo", ordered.first().id)
    }

    @Test
    fun `a build rises song after song and a wind-down falls`() {
        val build = (0..12).map { SetBuilder.targetEnergy(it, DjIntent("x", targetEnergy = 0.6, curve = EnergyCurve.BUILD)) }
        val down = (0..12).map { SetBuilder.targetEnergy(it, DjIntent("x", targetEnergy = 0.6, curve = EnergyCurve.WIND_DOWN)) }

        assertTrue(build.first() < build.last())
        assertTrue(down.first() > down.last())
        assertEquals(build.sorted(), build)
        assertEquals(down.sortedDescending(), down)
    }

    @Test
    fun `asking for something mid-party reorders what is left and can change the mode`() {
        val session = DjSession(
            listOf(
                track("balada", 0.2, 0.3, 70.0),
                track("perreo", 0.8, 0.85, 95.0),
                track("rock", 0.75, 0.4, 130.0),
            ),
            DjMode.NATURAL,
            Random(4),
        )
        session.start()

        session.applyIntent(perreo.copy(mode = DjMode.HORA_LOCA))

        assertEquals("perreo", session.upNext!!.id)
        assertEquals(DjMode.HORA_LOCA, session.mode)
        assertEquals("Perreo", session.intent.label)
    }
}
