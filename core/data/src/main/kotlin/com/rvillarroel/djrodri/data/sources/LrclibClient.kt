package com.rvillarroel.djrodri.data.sources

import com.rvillarroel.djrodri.data.http.PoliteHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.abs

sealed interface Lyrics {
    data class Synced(val lrc: String) : Lyrics
    /** Words exist but without timestamps: useless for finding the chorus in time. */
    data object PlainOnly : Lyrics
    data object Instrumental : Lyrics
    data object NotFound : Lyrics
}

data class LyricsQuery(val title: String, val artist: String, val album: String, val durationMs: Long)

fun interface LyricsSource {
    suspend fun lyrics(query: LyricsQuery): Lyrics
}

/**
 * LRCLIB: free, open synced lyrics. Tries the exact match first (title, artist, album, duration) and falls back to
 * a search, because albums differ between Spotify and LRCLIB far more often than titles do.
 */
class LrclibClient(
    private val http: PoliteHttp,
    private val baseUrl: HttpUrl = "https://lrclib.net/".toHttpUrl(),
) : LyricsSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lyrics(query: LyricsQuery): Lyrics {
        val exactUrl = baseUrl.newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", query.title)
            .addQueryParameter("artist_name", query.artist)
            .addQueryParameter("album_name", query.album)
            .addQueryParameter("duration", (query.durationMs / 1000).toString())
            .build()
        val exact = http.get(exactUrl.toString())
        when {
            exact.code == 200 && exact.body != null -> {
                val found = classify(json.decodeFromString<LrclibTrack>(exact.body))
                if (found is Lyrics.Synced || found is Lyrics.Instrumental) return found
            }
            exact.code != 404 -> throw SourceException("LRCLIB get → HTTP ${exact.code}")
        }
        return search(query)
    }

    private suspend fun search(query: LyricsQuery): Lyrics {
        val url = baseUrl.newBuilder().addPathSegments("api/search")
            .addQueryParameter("track_name", query.title)
            .addQueryParameter("artist_name", query.artist)
            .build()
        val result = http.get(url.toString())
        if (result.code != 200 || result.body == null) {
            if (result.code == 404) return Lyrics.NotFound
            throw SourceException("LRCLIB search → HTTP ${result.code}")
        }
        val durationSec = query.durationMs / 1000.0
        // A different cut of the song (radio edit, extended) has different timestamps: only trust a close duration.
        val candidates = json.decodeFromString<List<LrclibTrack>>(result.body)
            .filter { it.duration == null || abs(it.duration - durationSec) <= DURATION_TOLERANCE_SEC }
            .map(::classify)
        return candidates.firstOrNull { it is Lyrics.Synced }
            ?: candidates.firstOrNull { it is Lyrics.Instrumental }
            ?: if (candidates.isNotEmpty()) Lyrics.PlainOnly else Lyrics.NotFound
    }

    private fun classify(track: LrclibTrack): Lyrics = when {
        !track.syncedLyrics.isNullOrBlank() -> Lyrics.Synced(track.syncedLyrics)
        track.instrumental -> Lyrics.Instrumental
        !track.plainLyrics.isNullOrBlank() -> Lyrics.PlainOnly
        else -> Lyrics.NotFound
    }

    @Serializable
    private data class LrclibTrack(
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    private companion object {
        const val DURATION_TOLERANCE_SEC = 3.0
    }
}

class SourceException(message: String) : Exception(message)
