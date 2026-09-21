package com.rvillarroel.djrodri.spotify

import com.rvillarroel.djrodri.data.analysis.SourceTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class PlaylistSummary(val id: String, val name: String, val ownerId: String, val total: Int?, val imageUrl: String? = null)

/** "Tus me gusta" is not a real playlist id; the library endpoint stands in for it. */
const val LIKED_SONGS_ID = "liked"

class SpotifyApiException(val code: Int, message: String) : Exception(message)

/**
 * Just the Web API endpoints still open to dev-mode apps after February 2026.
 * Parsing is deliberately tolerant: that change renamed `tracks`→`items` and `track`→`item`.
 */
class SpotifyWebApi(
    private val http: OkHttpClient,
    private val token: suspend (forceRefresh: Boolean) -> String?,
    private val log: (String) -> Unit = { android.util.Log.d("DJRodri", it) },
) {
    suspend fun displayName(): String? = get("/me").optString("display_name")

    suspend fun myPlaylists(): List<PlaylistSummary> {
        val result = mutableListOf<PlaylistSummary>()
        var path: String? = "/me/playlists?limit=50"
        while (path != null) {
            val json = get(path)
            val items = json.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                val counts = p.optJSONObject("items") ?: p.optJSONObject("tracks")
                result += PlaylistSummary(
                    id = p.optString("id"),
                    name = p.optString("name"),
                    ownerId = p.optJSONObject("owner")?.optString("id").orEmpty(),
                    total = counts?.optInt("total"),
                    imageUrl = p.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
                )
            }
            path = nextPath(json)
        }
        return result
    }

    /** Songs of a playlist (or of "Tus me gusta"), skipping episodes and local files. */
    suspend fun tracks(playlistId: String, limit: Int = Int.MAX_VALUE): List<SourceTrack> {
        val result = mutableListOf<SourceTrack>()
        var path: String? = if (playlistId == LIKED_SONGS_ID) "/me/tracks?limit=50" else "/playlists/$playlistId/items?limit=50"
        while (path != null && result.size < limit) {
            val json = get(path)
            val items = json.optJSONArray("items") ?: break
            if (result.isEmpty() && playlistId != LIKED_SONGS_ID && items.length() == 0) {
                log("La playlist devolvió 0 canciones: ¿no es tuya ni colaborativa?")
            }
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val track = entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue
                if (track.optString("type") != "track" || track.optString("id").isEmpty()) continue
                result += SourceTrack(
                    id = track.optString("id"),
                    uri = track.optString("uri"),
                    name = track.optString("name"),
                    artist = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty(),
                    album = track.optJSONObject("album")?.optString("name").orEmpty(),
                    durationMs = track.optLong("duration_ms"),
                    isrc = track.optJSONObject("external_ids")?.optString("isrc")?.takeIf { it.isNotBlank() },
                )
            }
            path = nextPath(json)
        }
        return result.take(limit)
    }

    /** Fallback if App Remote fails: skip through the Web API. Returns the HTTP code. */
    suspend fun skipNext(): Int = withContext(Dispatchers.IO) {
        val bearer = token(false) ?: return@withContext 401
        val request = Request.Builder().url("$BASE/me/player/next").post(ByteArray(0).toRequestBody())
            .header("Authorization", "Bearer $bearer").build()
        http.newCall(request).execute().use { it.code }
    }

    private fun nextPath(json: JSONObject): String? =
        json.optString("next").takeIf { it.isNotEmpty() && it != "null" }?.removePrefix(BASE)

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        var forceRefresh = false
        repeat(MAX_ATTEMPTS) { attempt ->
            val bearer = token(forceRefresh) ?: throw SpotifyApiException(401, "Inicia sesión en Spotify")
            val request = Request.Builder().url("$BASE$path").header("Authorization", "Bearer $bearer").build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> return@withContext JSONObject(body)
                    response.code == 401 && !forceRefresh -> forceRefresh = true
                    response.code == 429 && attempt < MAX_ATTEMPTS - 1 -> {
                        // 429 says QUOTA_EXCEEDED when the shared developer quota runs out; waiting is all we can do.
                        val waitSec = response.header("Retry-After")?.toLongOrNull() ?: 5
                        log("Spotify pidió esperar $waitSec s (429): ${body.take(120)}")
                        delay(waitSec.coerceAtMost(60) * 1_000)
                    }
                    // Since February 2026 a dev-mode app only reads the songs of playlists you own or collaborate on.
                    response.code == 403 && path.startsWith("/playlists/") -> throw SpotifyApiException(
                        403,
                        "Spotify no deja leer las canciones de playlists de otros. Cópiala a una playlist tuya y analiza esa.",
                    )
                    else -> throw SpotifyApiException(response.code, "Spotify respondió ${response.code} en $path")
                }
            }
        }
        throw SpotifyApiException(429, "Spotify sigue limitando las consultas, prueba en un rato")
    }

    private companion object {
        const val BASE = "https://api.spotify.com/v1"
        const val MAX_ATTEMPTS = 3
    }
}
