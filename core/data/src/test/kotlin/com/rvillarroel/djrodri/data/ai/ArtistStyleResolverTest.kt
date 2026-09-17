package com.rvillarroel.djrodri.data.ai

import com.rvillarroel.djrodri.data.sources.ArtistTags
import com.rvillarroel.djrodri.data.sources.GenreSource
import com.rvillarroel.djrodri.domain.dj.DjIntent
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.domain.music.Genres
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtistStyleResolverTest {

    private class FakeGenres(private val byName: Map<String, Set<String>> = emptyMap()) : GenreSource {
        var artistLookups = 0
        override suspend fun genresByIsrc(isrc: String): ArtistTags? = null
        override suspend fun genresByArtistName(name: String): ArtistTags? {
            artistLookups++
            return byName[name]?.let { ArtistTags("mbid", name, it) }
        }
    }

    private fun track(id: String, artist: String, energy: Double, dance: Double, bpm: Double, genres: Set<String> = emptySet()) =
        TrackProfile(id = id, artist = artist, durationMs = 220_000, bpm = bpm, energy = energy, danceability = dance, valence = 0.6, genres = genres)

    private val library = listOf(
        track("a1", "Avicii", energy = 0.85, dance = 0.7, bpm = 126.0, genres = setOf(Genres.HOUSE, Genres.EDM)),
        track("a2", "Avicii", energy = 0.9, dance = 0.75, bpm = 128.0, genres = setOf(Genres.HOUSE)),
        track("b1", "Los Kjarkas", energy = 0.3, dance = 0.4, bpm = 95.0, genres = setOf(Genres.FOLK)),
    )

    @Test
    fun `an artist you already listen to is described by their own songs, with no lookup`() = runTest {
        val source = FakeGenres()

        val intent = ArtistStyleResolver(source).resolve(DjIntent("Estilo Avicii", likeArtists = listOf("Avicii")), library)

        assertEquals(0.875, intent.targetEnergy!!, 0.001)
        assertTrue(Genres.HOUSE in intent.genres)
        assertTrue(intent.minBpm!! in 110.0..126.0)
        assertTrue(intent.maxBpm!! in 128.0..140.0)
        assertEquals(0, source.artistLookups)
    }

    @Test
    fun `an artist you do not have yet is looked up in MusicBrainz for its genres`() = runTest {
        val source = FakeGenres(mapOf("Daft Punk" to setOf(Genres.HOUSE, Genres.ELECTRONIC)))

        val intent = ArtistStyleResolver(source).resolve(DjIntent("Estilo Daft Punk", likeArtists = listOf("Daft Punk")), library)

        assertEquals(setOf(Genres.HOUSE, Genres.ELECTRONIC), intent.genres)
        assertEquals(1, source.artistLookups)
    }

    @Test
    fun `what you asked for explicitly is never overwritten by the style`() = runTest {
        val asked = DjIntent("Estilo Avicii pero tranqui", targetEnergy = 0.3, likeArtists = listOf("Avicii"))

        val intent = ArtistStyleResolver(FakeGenres()).resolve(asked, library)

        assertEquals(0.3, intent.targetEnergy)
    }

    @Test
    fun `a request without a style reference is left exactly as it was`() = runTest {
        val source = FakeGenres()
        val asked = DjIntent("Perreo", targetEnergy = 0.8)

        assertEquals(asked, ArtistStyleResolver(source).resolve(asked, library))
        assertEquals(0, source.artistLookups)
    }

    @Test
    fun `an unknown artist leaves the request alone instead of inventing a style`() = runTest {
        val asked = DjIntent("Estilo Fulanito", likeArtists = listOf("Fulanito"))

        assertEquals(asked, ArtistStyleResolver(FakeGenres()).resolve(asked, library))
    }
}
