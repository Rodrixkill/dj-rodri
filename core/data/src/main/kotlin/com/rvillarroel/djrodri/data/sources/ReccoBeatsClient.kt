package com.rvillarroel.djrodri.data.sources

import com.rvillarroel.djrodri.data.http.PoliteHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class AudioFeatures(
    val tempo: Double?,
    /** Pitch class 0..11, or -1 when unknown. */
    val key: Int,
    /** 1 major, 0 minor, -1 unknown. */
    val mode: Int,
    val energy: Double?,
    val danceability: Double?,
    val valence: Double?,
)

fun interface AudioFeaturesSource {
    /** Keyed by Spotify track id; ids the source does not know are simply absent. */
    suspend fun features(spotifyTrackIds: List<String>): Map<String, AudioFeatures>
}

/** ReccoBeats: a free replacement for the audio features Spotify closed to new apps. Looked up by Spotify id. */
class ReccoBeatsClient(
    private val http: PoliteHttp,
    private val baseUrl: HttpUrl = "https://api.reccobeats.com/".toHttpUrl(),
) : AudioFeaturesSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun features(spotifyTrackIds: List<String>): Map<String, AudioFeatures> {
        val result = mutableMapOf<String, AudioFeatures>()
        for (chunk in spotifyTrackIds.distinct().chunked(MAX_IDS_PER_CALL)) {
            val url = baseUrl.newBuilder().addPathSegments("v1/audio-features")
                .addQueryParameter("ids", chunk.joinToString(","))
                .build()
            val response = http.get(url.toString())
            if (response.code == 404) continue
            if (response.code != 200 || response.body == null) throw SourceException("ReccoBeats → HTTP ${response.code}")
            for (item in json.decodeFromString<Page>(response.body).content) {
                // The response does not echo our id, only the Spotify link it resolved to.
                val spotifyId = item.href?.substringAfterLast('/')?.substringBefore('?') ?: continue
                result[spotifyId] = AudioFeatures(
                    tempo = item.tempo?.takeIf { it > 0 },
                    key = item.key ?: -1,
                    mode = item.mode ?: -1,
                    energy = item.energy,
                    danceability = item.danceability,
                    valence = item.valence,
                )
            }
        }
        return result
    }

    @Serializable
    private data class Page(val content: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val href: String? = null,
        val tempo: Double? = null,
        val key: Int? = null,
        val mode: Int? = null,
        val energy: Double? = null,
        val danceability: Double? = null,
        val valence: Double? = null,
    )

    private companion object {
        const val MAX_IDS_PER_CALL = 40
    }
}
