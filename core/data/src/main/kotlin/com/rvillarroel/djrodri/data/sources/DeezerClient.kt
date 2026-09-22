package com.rvillarroel.djrodri.data.sources

import com.rvillarroel.djrodri.data.http.PoliteHttp
import com.rvillarroel.djrodri.domain.music.GenreVote
import com.rvillarroel.djrodri.domain.music.GenreVotes
import com.rvillarroel.djrodri.domain.music.Genres
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Deezer's public catalogue: no key, no login. A song is found by its exact ISRC, and its album carries the genre
 * the record label declared, which is coarse (Pop, Dance, Latin) but rarely wrong. Genres are read by their id, so
 * the language Deezer answers in doesn't matter. Albums are cached: songs of one album share them.
 */
class DeezerClient(
    private val http: PoliteHttp,
    private val baseUrl: HttpUrl = "https://api.deezer.com/".toHttpUrl(),
) : GenreVoteSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val albumCache = mutableMapOf<Long, Album?>()

    override suspend fun votesByIsrc(isrc: String): IsrcVotes? {
        val track = get<Track>(baseUrl.newBuilder().addPathSegments("track/isrc:$isrc").build()) ?: return null
        val albumId = track.album?.id ?: return null
        val album = albumCache.getOrPut(albumId) { get<Album>(baseUrl.newBuilder().addPathSegments("album/$albumId").build()) } ?: return null
        val votes = album.genres.data.mapNotNull { genre ->
            val canonical = GENRES[genre.id] ?: return@mapNotNull null
            GenreVote(canonical, if (genre.id == album.genreId) GenreVotes.LABEL_MAIN else GenreVotes.LABEL_OTHER)
        }
        return IsrcVotes(votes, artistName = track.artist?.name)
    }

    /** Deezer answers 200 with an "error" object when it has no data: that is a "don't know", not a failure. */
    private suspend inline fun <reified T> get(url: HttpUrl): T? {
        val response = http.get(url.toString())
        if (response.code != 200 || response.body == null) throw SourceException("Deezer → HTTP ${response.code}")
        if (response.body.contains("\"error\"") && !response.body.contains("\"id\"")) return null
        return json.decodeFromString<T>(response.body)
    }

    @Serializable
    private data class Track(val id: Long = 0, val album: Ref? = null, val artist: Named? = null)

    @Serializable
    private data class Ref(val id: Long)

    @Serializable
    private data class Named(val name: String? = null)

    @Serializable
    private data class Album(@SerialName("genre_id") val genreId: Long = -1, val genres: GenreList = GenreList())

    @Serializable
    private data class GenreList(val data: List<GenreRef> = emptyList())

    @Serializable
    private data class GenreRef(val id: Long)

    companion object {
        /** Deezer's genre ids (GET /genre) in the app's vocabulary; film music and kids' songs say nothing useful. */
        val GENRES: Map<Long, String> = mapOf(
            132L to Genres.POP,
            116L to Genres.HIP_HOP,
            122L to Genres.REGGAETON,
            152L to Genres.ROCK,
            113L to Genres.EDM,
            106L to Genres.ELECTRONIC,
            165L to Genres.RNB,
            85L to Genres.INDIE,
            466L to Genres.FOLK,
            144L to Genres.REGGAE,
            129L to Genres.JAZZ,
            98L to Genres.CLASSICAL,
            464L to Genres.METAL,
            169L to Genres.SOUL,
            2L to Genres.AFROBEATS,
            153L to Genres.BLUES,
            71L to Genres.CUMBIA,
            197L to Genres.LATIN,
        )
    }
}

/**
 * Asks every source about a song and lets [GenreVotes] decide. A source that fails or doesn't know the song simply
 * doesn't vote; only when all of them fail is the error passed on, so the song is retried later.
 */
class VotingGenreSource(
    private val sources: List<GenreVoteSource>,
    private val byName: MusicBrainzClient,
) : GenreSource {
    override suspend fun genresByIsrc(isrc: String): ArtistTags? {
        var failure: Exception? = null
        val answers = sources.mapNotNull { source ->
            try {
                source.votesByIsrc(isrc)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                null
            }
        }
        if (answers.isEmpty()) return failure?.let { throw it }
        return ArtistTags(
            artistMbid = answers.firstNotNullOfOrNull { it.artistMbid },
            artistName = answers.firstNotNullOfOrNull { it.artistName },
            genres = GenreVotes.decide(answers.flatMap { it.votes }),
        )
    }

    override suspend fun genresByArtistName(name: String): ArtistTags? = byName.genresByArtistName(name)
}
