package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.music.CamelotKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SetBuilderTest {

    @Test
    fun `every song of the playlist plays exactly once`() {
        repeat(50) { seed ->
            val playlist = randomPlaylist(Random(seed))
            val ordered = SetBuilder.order(playlist, random = Random(seed))

            assertEquals(playlist.map { it.id }.sorted(), ordered.map { it.id }.sorted())
        }
    }

    @Test
    fun `the same artist never plays twice in a row while there is someone else to play`() {
        val playlist = (0 until 10).map { song("a$it", artist = "Bad Bunny") } + (0 until 10).map { song("b$it", artist = "Karol G") }

        val ordered = SetBuilder.order(playlist, random = Random(1))

        ordered.zipWithNext().forEach { (x, y) -> assertTrue(x.artist != y.artist, "${x.id} → ${y.id}") }
    }

    @Test
    fun `a song follows one it mixes with when there is a choice`() {
        val start = song("start", bpm = 95.0, key = CamelotKey(8, isMinor = true), energy = 0.6)
        val clash = song("clash", bpm = 140.0, key = CamelotKey(2, isMinor = false), energy = 0.6)
        val match = song("match", bpm = 96.0, key = CamelotKey(9, isMinor = true), energy = 0.6)

        val ordered = SetBuilder.order(listOf(clash, match), after = start, random = Random(3))

        assertEquals("match", ordered.first().id)
    }

    @Test
    fun `asking for more energy brings the loud songs forward`() {
        val playlist = (0 until 20).map { song("e$it", energy = it / 19.0, artist = "A$it") }

        fun firstFiveEnergy(bias: Double) =
            SetBuilder.order(playlist, energyBias = bias, random = Random(7)).take(5).mapNotNull { it.energy }.average()

        assertTrue(firstFiveEnergy(+0.3) > firstFiveEnergy(-0.3))
    }

    @Test
    fun `a thousand likes are ordered with every song exactly once, and the flow still follows the request`() {
        val random = Random(4)
        val genres = listOf("pop", "house", "reggaeton", "rock", "salsa")
        val tracks = (0 until 1_209).map { i ->
            TrackProfile(id = "t$i", artist = "a${i % 300}", durationMs = 200_000, bpm = 90.0 + i % 60, energy = (i % 10) / 10.0, genres = setOf(genres[i % 5]))
        }

        val ordered = SetBuilder.order(tracks, intent = DjIntent(label = "perreo", genres = setOf("reggaeton")), random = random)

        assertEquals(tracks.map { it.id }.sorted(), ordered.map { it.id }.sorted())
        // A fifth of the crate is reggaeton; asked for it, the opening stretch is mostly reggaeton anyway.
        assertTrue(ordered.take(20).count { "reggaeton" in it.genres } >= 15, ordered.take(20).joinToString { it.genres.first() })
    }
}
