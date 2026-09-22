package com.rvillarroel.djrodri.data.sources

import com.rvillarroel.djrodri.data.http.PoliteHttp
import com.rvillarroel.djrodri.domain.dj.StyleFamily
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.domain.music.GenreVote
import com.rvillarroel.djrodri.domain.music.GenreVotes
import com.rvillarroel.djrodri.domain.music.Genres
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GenreSourcesTest {
    private val server = MockWebServer().apply { start() }
    private val http = PoliteHttp(OkHttpClient(), sleep = {})
    private val deezer = DeezerClient(http, server.url("/"))
    private val musicBrainz = MusicBrainzClient(http, server.url("/"))

    @AfterEach
    fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun `Deezer finds the song by its exact ISRC and votes for the label's genre by id, in any language`() = runTest {
        server.enqueue(json("""{"id":1,"title":"Levitating","artist":{"name":"Dua Lipa"},"album":{"id":176151062}}"""))
        server.enqueue(json("""{"id":176151062,"genre_id":132,"genres":{"data":[{"id":132,"name":"Pop"},{"id":113,"name":"Dance"}]}}"""))

        val votes = deezer.votesByIsrc("GBAHT2000942")!!

        assertEquals(listOf(GenreVote(Genres.POP, GenreVotes.LABEL_MAIN), GenreVote(Genres.EDM, GenreVotes.LABEL_OTHER)), votes.votes)
        assertEquals("/track/isrc:GBAHT2000942", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/album/176151062", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `a song Deezer doesn't have is a don't-know, not a failure`() = runTest {
        server.enqueue(json("""{"error":{"type":"DataException","message":"no data","code":800}}"""))

        assertNull(deezer.votesByIsrc("XXXX00000000"))
    }

    @Test
    fun `songs of one album ask Deezer for the album once`() = runTest {
        server.enqueue(json("""{"id":1,"album":{"id":7}}"""))
        server.enqueue(json("""{"id":7,"genre_id":106,"genres":{"data":[{"id":106,"name":"Electro"}]}}"""))
        server.enqueue(json("""{"id":2,"album":{"id":7}}"""))

        deezer.votesByIsrc("A")
        val second = deezer.votesByIsrc("B")!!

        assertEquals(3, server.requestCount)
        assertEquals(listOf(GenreVote(Genres.ELECTRONIC, GenreVotes.LABEL_MAIN)), second.votes)
    }

    @Test
    fun `MusicBrainz looks the ISRC up exactly and weighs each tag by its votes`() = runTest {
        server.enqueue(
            json(
                """{"isrc":"GBAHT2000942","recordings":[{"tags":[{"name":"pop","count":4},{"name":"house","count":1}],
                   "artist-credit":[{"artist":{"id":"mbid-dua","name":"Dua Lipa"}}]}]}""",
            ),
        )
        server.enqueue(json("""{"id":"mbid-dua","name":"Dua Lipa","tags":[{"name":"pop","count":10},{"name":"house","count":2}]}"""))

        val result = musicBrainz.votesByIsrc("GBAHT2000942")!!

        assertEquals("/ws/2/isrc/GBAHT2000942", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("mbid-dua", result.artistMbid)
        // One vote of "house" against four of "pop" is under the bar; so are two against ten.
        assertEquals(setOf("pop"), result.votes.map { it.tag }.toSet())
    }

    @Test
    fun `together the sources keep a pop song out of the electronic mix`() = runTest {
        val source = VotingGenreSource(
            listOf(
                fake(IsrcVotes(listOf(GenreVote(Genres.POP, GenreVotes.LABEL_MAIN)))),
                fake(IsrcVotes(GenreVotes.fromCounts(mapOf("pop" to 10, "dance-pop" to 10, "electropop" to 6, "house" to 3), GenreVotes.ARTIST_TAGS), artistMbid = "m")),
            ),
            musicBrainz,
        )

        val tags = source.genresByIsrc("X")!!

        assertEquals("m", tags.artistMbid)
        assertFalse(Genres.ELECTRONIC in tags.genres, "${tags.genres}")
        assertEquals(StyleFamily.POP, StyleFamily.of(TrackProfile(id = "x", artist = "a", durationMs = 1, genres = tags.genres)))
    }

    @Test
    fun `one source down still gives genres, all of them down is an error to retry later`() = runTest {
        val broken = object : GenreVoteSource {
            override suspend fun votesByIsrc(isrc: String): IsrcVotes? = throw SourceException("caído")
        }
        val working = VotingGenreSource(listOf(broken, fake(IsrcVotes(listOf(GenreVote(Genres.ROCK, 1.0))))), musicBrainz)
        assertEquals(setOf(Genres.ROCK), working.genresByIsrc("X")!!.genres)

        assertThrows<SourceException> { VotingGenreSource(listOf(broken), musicBrainz).genresByIsrc("X") }
    }

    private fun fake(answer: IsrcVotes?) = object : GenreVoteSource {
        override suspend fun votesByIsrc(isrc: String): IsrcVotes? = answer
    }
}
