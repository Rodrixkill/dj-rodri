package com.rvillarroel.djrodri.data.sources

import com.rvillarroel.djrodri.data.http.PoliteHttp
import com.rvillarroel.djrodri.domain.music.Genres
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

/**
 * MusicBrainz: open, CC0 music metadata. Songs are found by ISRC, and the genres of a song's artist are cached,
 * because a playlist has hundreds of songs but only dozens of artists.
 *
 * MusicBrainz asks for one request per second and a real User-Agent; [PoliteHttp] and the analyzer's pauses respect that.
 */
class MusicBrainzClient(
    private val http: PoliteHttp,
    private val baseUrl: HttpUrl = "https://musicbrainz.org/".toHttpUrl(),
) : GenreSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val artistCache = mutableMapOf<String, ArtistTags?>()

    override suspend fun genresByIsrc(isrc: String): ArtistTags? {
        val url = baseUrl.newBuilder().addPathSegments("ws/2/recording")
            .addQueryParameter("query", "isrc:$isrc")
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "1")
            .build()
        val response = http.get(url.toString())
        if (response.code == 404) return null
        if (response.code != 200 || response.body == null) throw SourceException("MusicBrainz → HTTP ${response.code}")
        val recording = json.decodeFromString<RecordingSearch>(response.body).recordings.firstOrNull() ?: return null
        val credit = recording.artistCredit.firstOrNull()?.artist
        val songGenres = Genres.expand(recording.tags.map { it.name } + recording.genres.map { it.name })
        val artistGenres = credit?.id?.let { artistTags(it) }?.genres.orEmpty()
        return ArtistTags(credit?.id, credit?.name, songGenres + artistGenres)
    }

    override suspend fun genresByArtistName(name: String): ArtistTags? {
        val url = baseUrl.newBuilder().addPathSegments("ws/2/artist")
            .addQueryParameter("query", "artist:\"$name\"")
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "1")
            .build()
        val response = http.get(url.toString())
        if (response.code != 200 || response.body == null) return null
        val artist = json.decodeFromString<ArtistSearch>(response.body).artists.firstOrNull() ?: return null
        return ArtistTags(artist.id, artist.name, Genres.expand(artist.tags.map { it.name } + artist.genres.map { it.name }))
    }

    private suspend fun artistTags(mbid: String): ArtistTags? = artistCache.getOrPut(mbid) {
        val url = baseUrl.newBuilder().addPathSegments("ws/2/artist/$mbid")
            .addQueryParameter("inc", "tags+genres")
            .addQueryParameter("fmt", "json")
            .build()
        val response = http.get(url.toString())
        if (response.code != 200 || response.body == null) return@getOrPut null
        val artist = json.decodeFromString<Artist>(response.body)
        ArtistTags(artist.id, artist.name, Genres.expand(artist.tags.map { it.name } + artist.genres.map { it.name }))
    }

    @Serializable
    private data class RecordingSearch(val recordings: List<Recording> = emptyList())

    @Serializable
    private data class Recording(
        val tags: List<Tag> = emptyList(),
        val genres: List<Tag> = emptyList(),
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
    private data class Tag(val name: String)
}
