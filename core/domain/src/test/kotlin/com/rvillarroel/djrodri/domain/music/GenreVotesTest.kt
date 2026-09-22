package com.rvillarroel.djrodri.domain.music

import com.rvillarroel.djrodri.domain.dj.StyleFamily
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenreVotesTest {
    private fun familyOf(genres: Set<String>) = StyleFamily.of(TrackProfile(id = "t", artist = "a", durationMs = 200_000, genres = genres))

    @Test
    fun `a pop star tagged house by two listeners stays pop`() {
        // Levitating, as Deezer and MusicBrainz really describe it.
        val votes = listOf(GenreVote("Pop", GenreVotes.LABEL_MAIN)) +
            GenreVotes.fromCounts(mapOf("pop" to 3, "dance-pop" to 2, "synth-pop" to 1, "disco" to 1, "nu disco" to 1), GenreVotes.SONG_TAGS) +
            GenreVotes.fromCounts(
                mapOf("dance-pop" to 10, "pop" to 10, "contemporary r&b" to 6, "electropop" to 6, "synth-pop" to 5, "nu disco" to 3, "house" to 2),
                GenreVotes.ARTIST_TAGS,
            )

        val genres = GenreVotes.decide(votes)

        assertFalse(Genres.HOUSE in genres, "$genres")
        assertFalse(Genres.ELECTRONIC in genres, "$genres")
        assertEquals(StyleFamily.POP, familyOf(genres))
    }

    @Test
    fun `a label that only says latin still lets the tags tell it is reggaeton`() {
        val votes = listOf(GenreVote("Latin Music", GenreVotes.LABEL_MAIN)) +
            GenreVotes.fromCounts(mapOf("reggaeton" to 4, "latin trap" to 1), GenreVotes.SONG_TAGS) +
            GenreVotes.fromCounts(mapOf("reggaeton" to 12, "latin" to 6, "trap latino" to 5), GenreVotes.ARTIST_TAGS)

        assertEquals(StyleFamily.REGGAETON, familyOf(GenreVotes.decide(votes)))
    }

    @Test
    fun `a dance label and house tags make an electronic song`() {
        val votes = listOf(GenreVote("Dance", GenreVotes.LABEL_MAIN)) +
            GenreVotes.fromCounts(mapOf("progressive house" to 3, "house" to 2), GenreVotes.SONG_TAGS)

        val genres = GenreVotes.decide(votes)

        assertTrue(Genres.HOUSE in genres && Genres.ELECTRONIC in genres, "$genres")
        assertEquals(StyleFamily.ELECTRONIC, familyOf(genres))
    }

    @Test
    fun `tags nobody really agrees on are ignored`() {
        val votes = GenreVotes.fromCounts(mapOf("rock" to 20, "techno" to 1, "blues" to 5), GenreVotes.SONG_TAGS)

        assertEquals(listOf("rock"), votes.map { it.tag })
    }

    @Test
    fun `with no genre anyone knows, nothing is guessed`() {
        assertEquals(emptySet<String>(), GenreVotes.decide(listOf(GenreVote("seen live", 1.0), GenreVote("2020s", 1.0))))
    }

    @Test
    fun `every style asked for by hand comes back as the same style`() {
        // A manual correction is stored as the style's genres: reading them back must give that style.
        StyleFamily.entries.filter { it != StyleFamily.UNKNOWN }.forEach { family ->
            assertEquals(family, familyOf(StyleFamily.genresOf(family)), family.name)
        }
    }
}
