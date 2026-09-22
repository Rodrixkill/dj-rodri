package com.rvillarroel.djrodri.data.sources

import com.rvillarroel.djrodri.data.http.PoliteHttp
import com.rvillarroel.djrodri.domain.music.GenreVote
import com.rvillarroel.djrodri.domain.music.GenreVotes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ArtistTags(val artistMbid: String?, val artistName: String?, val genres: Set<String>)

interface GenreSource {
    /** Genres for one song, looked up by its ISRC (an industry code, not a Spotify id). */
    suspend fun genresByIsrc(isrc: String): ArtistTags?

    /** Genres for an artist by name, for requests like "algo estilo Avicii". */
    suspend fun genresByArtistName(name: String): ArtistTags?
}

/** What one source says about a song's genre, as weighted votes; [GenreVotes] turns everyone's votes into genres. */
data class IsrcVotes(val votes: List<GenreVote>, val artistMbid: String? = null, val artistName: String? = null)

interface GenreVoteSource {
    /** Votes for the song with this exact ISRC, or null when the source doesn't know it. */
    suspend fun votesByIsrc(isrc: String): IsrcVotes?
}

/**
 * MusicBrainz: open, CC0 music metadata where listeners tag songs and artists. A song is looked up by its exact
 * ISRC, and every tag comes with how many people gave it, so a tag one person added weighs what it should: little.
 * The artist's tags are cached, because a playlist has hundreds of songs but only dozens of artists.
 *
 * MusicBrainz asks for one request per second and a real User-Agent; [PoliteHttp] and the analyzer's pauses respect that.
 */
class MusicBrainzClient(
    private val http: PoliteHttp,
    private val baseUrl: HttpUrl = "https://musicbrainz.org/".toHttpUrl(),
) : GenreVoteSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val artistCache = mutableMapOf<String, Artist?>()

    override suspend fun votesByIsrc(isrc: String): IsrcVotes? {
        // The isrc resource is an exact lookup; a search for "isrc:X" would happily return some other song.
        val url = baseUrl.newBuilder().addPathSegments("ws/2/isrc/$isrc")
            .addQueryParameter("inc", "artist-credits+tags")
            .addQueryParameter("fmt", "json")
            .build()
        val response = http.get(url.toString())
        if (response.code == 404) return null
        if (response.code != 200 || response.body == null) throw SourceException("MusicBrainz → HTTP ${response.code}")
        val recordings = json.decodeFromString<IsrcLookup>(response.body).recordings
        if (recordings.isEmpty()) return null
        // The same song can be listed more than once (single, album): their tags add up.
        val songCounts = recordings.flatMap { it.tags }.groupBy { it.name }.mapValues { (_, tags) -> tags.sumOf { it.count } }
        val credit = recordings.firstNotNullOfOrNull { it.artistCredit.firstOrNull()?.artist }
        val artist = credit?.id?.let { artist(it) }
        val votes = GenreVotes.fromCounts(songCounts, GenreVotes.SONG_TAGS) +
            GenreVotes.fromCounts(artist?.counts().orEmpty(), GenreVotes.ARTIST_TAGS)
        return IsrcVotes(votes, credit?.id, credit?.name)
    }

    /** Genres of an artist found by name, for "algo estilo Avicii". */
    suspend fun genresByArtistName(name: String): ArtistTags? {
        val url = baseUrl.newBuilder().addPathSegments("ws/2/artist")
            .addQueryParameter("query", "artist:\"$name\"")
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "1")
            .build()
        val response = http.get(url.toString())
        if (response.code != 200 || response.body == null) return null
        val artist = json.decodeFromString<ArtistSearch>(response.body).artists.firstOrNull() ?: return null
        return ArtistTags(artist.id, artist.name, GenreVotes.decide(GenreVotes.fromCounts(artist.counts(), GenreVotes.ARTIST_TAGS)))
    }

    private suspend fun artist(mbid: String): Artist? = artistCache.getOrPut(mbid) {
        val url = baseUrl.newBuilder().addPathSegments("ws/2/artist/$mbid")
            .addQueryParameter("inc", "tags+genres")
            .addQueryParameter("fmt", "json")
            .build()
        val response = http.get(url.toString())
        if (response.code != 200 || response.body == null) return@getOrPut null
        json.decodeFromString<Artist>(response.body)
    }

    private fun Artist.counts(): Map<String, Int> =
        (tags + genres).groupBy { it.name }.mapValues { (_, same) -> same.maxOf { it.count } }

    @Serializable
    private data class IsrcLookup(val recordings: List<Recording> = emptyList())

    @Serializable
    private data class Recording(
        val tags: List<Tag> = emptyList(),
        @SerialName("artist-credit") val artistCredit: List<ArtistCredit> = emptyList(),
    )

    @Serializable
    private data class ArtistCredit(val artist: Artist? = null)

    @Serializable
    private data class ArtistSearch(val artists: List<Artist> = emptyList())

    @Serializable
    private data class Artist(
        val id: String? = null,
        val name: String? = null,
        val tags: List<Tag> = emptyList(),
        val genres: List<Tag> = emptyList(),
    )

    @Serializable
    private data class Tag(val name: String, val count: Int = 1)
}
