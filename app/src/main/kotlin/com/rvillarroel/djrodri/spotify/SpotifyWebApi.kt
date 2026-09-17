package com.rvillarroel.djrodri.spotify

import com.rvillarroel.djrodri.spike.SpikeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class PlaylistSummary(val id: String, val name: String, val ownerId: String, val total: Int?)

data class TrackRef(
    val id: String,
    val uri: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isrc: String?,
)

/** The only thing we need to know about "Tus me gusta" is that it is not a real playlist id. */
const val LIKED_SONGS_ID = "liked"

/**
 * Just the Web API endpoints still open to dev-mode apps after February 2026.
 * Parsing is deliberately tolerant: that change renamed `tracks`→`items` and `track`→`item`, and the spike should
 * report what really comes back rather than crash on it.
 */
class SpotifyWebApi(private val http: OkHttpClient, private val log: SpikeLog) {
    var accessToken: String? = null

    suspend fun displayName(): String? = get("/me")?.optString("display_name")

    suspend fun myPlaylists(): List<PlaylistSummary> {
        val json = get("/me/playlists?limit=50") ?: return emptyList()
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val p = items.optJSONObject(i) ?: return@mapNotNull null
            val counts = p.optJSONObject("items") ?: p.optJSONObject("tracks")
            PlaylistSummary(
                id = p.optString("id"),
                name = p.optString("name"),
                ownerId = p.optJSONObject("owner")?.optString("id").orEmpty(),
                total = counts?.optInt("total"),
            )
        }
    }

    /** First [limit] songs of a playlist (or of "Tus me gusta"), skipping episodes and local files. */
    suspend fun tracks(playlistId: String, limit: Int = 100): List<TrackRef> {
        val result = mutableListOf<TrackRef>()
        var offset = 0
        while (result.size < limit) {
            val path = if (playlistId == LIKED_SONGS_ID) "/me/tracks?limit=50&offset=$offset"
            else "/playlists/$playlistId/items?limit=50&offset=$offset"
            val json = get(path) ?: break
            val items = json.optJSONArray("items") ?: break
            if (offset == 0 && playlistId != LIKED_SONGS_ID && items.length() == 0) {
                log.add("La playlist devolvió 0 canciones: ¿no es tuya ni colaborativa?")
            }
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val track = entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue
                if (track.optString("type") != "track" || track.optString("id").isEmpty()) continue
                result += TrackRef(
                    id = track.optString("id"),
                    uri = track.optString("uri"),
                    name = track.optString("name"),
                    artist = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty(),
                    album = track.optJSONObject("album")?.optString("name").orEmpty(),
                    durationMs = track.optLong("duration_ms"),
                    isrc = track.optJSONObject("external_ids")?.optString("isrc")?.takeIf { it.isNotBlank() },
                )
            }
            if (json.isNull("next") || items.length() == 0) break
            offset += 50
        }
        return result.take(limit)
    }

    /** Fallback path if App Remote fails: skip through the Web API, returning the HTTP code. */
    suspend fun skipNext(): Int = send(Request.Builder().url("$BASE/me/player/next").post(ByteArray(0).toRequestBody()))

    private suspend fun get(path: String): JSONObject? = withContext(Dispatchers.IO) {
        val token = accessToken ?: run { log.add("Web API: no hay token, primero inicia sesión"); return@withContext null }
        val request = Request.Builder().url("$BASE$path").header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { response ->
            val body = response.body!!.string()
            if (!response.isSuccessful) {
                // 429 now says QUOTA_EXCEEDED when the shared developer quota runs out.
                log.add("Web API GET $path → ${response.code} retry-after=${response.header("Retry-After")} ${body.take(200)}")
                return@withContext null
            }
            JSONObject(body)
        }
    }

    private suspend fun send(builder: Request.Builder): Int = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext -1
        http.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { response ->
            if (!response.isSuccessful) log.add("Web API → ${response.code} ${response.body!!.string().take(200)}")
            response.code
        }
    }

    private companion object {
        const val BASE = "https://api.spotify.com/v1"
    }
}
