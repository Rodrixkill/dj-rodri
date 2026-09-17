package com.rvillarroel.djrodri.spike

import com.rvillarroel.djrodri.domain.lyrics.ChorusFinder
import com.rvillarroel.djrodri.domain.lyrics.LrcParser
import com.rvillarroel.djrodri.domain.music.CamelotKey
import com.rvillarroel.djrodri.domain.music.Tempo
import com.rvillarroel.djrodri.data.analysis.SourceTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class CoverageResult(
    val total: Int,
    val withIsrc: Int,
    val withSyncedLyrics: Int,
    val withChorus: Int,
    val withTempo: Int,
    val withKey: Int,
) {
    private fun pct(n: Int) = if (total == 0) "–" else "${n * 100 / total}%"

    override fun toString() =
        "canciones=$total · ISRC ${pct(withIsrc)} · letra sincronizada ${pct(withSyncedLyrics)} · " +
            "coro detectado ${pct(withChorus)} · BPM ${pct(withTempo)} · tonalidad ${pct(withKey)}"
}

/**
 * Answers the question the whole app rests on: for *your* songs, how many can we analyze without Spotify's audio?
 * Lyrics come from LRCLIB (open, synced), tempo and key from ReccoBeats looked up by Spotify track id.
 */
class CoverageProbe(private val http: OkHttpClient, private val log: SpikeLog) {

    suspend fun run(tracks: List<SourceTrack>): CoverageResult = withContext(Dispatchers.IO) {
        val features = audioFeatures(tracks.map { it.id })
        var lyrics = 0
        var chorus = 0
        for (track in tracks) {
            val lrc = syncedLyrics(track)
            val analysis = lrc?.let { ChorusFinder.find(LrcParser.parse(it), track.durationMs) }
            if (lrc != null) lyrics++
            if (analysis != null) chorus++
            val f = features[track.id]
            log.add(
                buildString {
                    append("· ${track.name} — ${track.artist}: ")
                    append(if (track.isrc != null) "ISRC ok" else "sin ISRC")
                    append(if (lrc != null) ", letra ok" else ", sin letra")
                    analysis?.let { a ->
                        append(", coro x${a.occurrences.size} en ")
                        append(a.occurrences.joinToString { "${it.startMs / 1000}-${it.endMs / 1000}s" })
                        append(" (conf ${"%.2f".format(a.confidence)})")
                    }
                    f?.tempo?.let { append(", BPM ${"%.0f".format(it)}→${"%.0f".format(Tempo.fold(it))}") }
                    f?.key?.let { append(", tono $it") }
                },
            )
            delay(250) // be polite with a free community service
        }
        CoverageResult(
            total = tracks.size,
            withIsrc = tracks.count { it.isrc != null },
            withSyncedLyrics = lyrics,
            withChorus = chorus,
            withTempo = features.values.count { it.tempo != null },
            withKey = features.values.count { it.key != null },
        )
    }

    private fun syncedLyrics(track: SourceTrack): String? {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", track.name)
            .addQueryParameter("artist_name", track.artist)
            .addQueryParameter("album_name", track.album)
            .addQueryParameter("duration", (track.durationMs / 1000).toString())
            .build()
        return runCatching {
            http.newCall(request(url.toString())).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body!!.string()).optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            }
        }.getOrElse { log.add("LRCLIB error: ${it.message}"); null }
    }

    private data class Features(val tempo: Double?, val key: CamelotKey?)

    private fun audioFeatures(spotifyIds: List<String>): Map<String, Features> {
        val result = mutableMapOf<String, Features>()
        for (chunk in spotifyIds.chunked(40)) {
            runCatching {
                val url = "https://api.reccobeats.com/v1/audio-features?ids=${chunk.joinToString(",")}"
                http.newCall(request(url)).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.add("ReccoBeats → ${response.code}")
                        return@use
                    }
                    val content = JSONObject(response.body!!.string()).optJSONArray("content") ?: return@use
                    for (i in 0 until content.length()) {
                        val item = content.getJSONObject(i)
                        val spotifyId = item.optString("href").substringAfterLast("/").substringBefore("?")
                        result[spotifyId] = Features(
                            tempo = item.optDouble("tempo").takeIf { !it.isNaN() && it > 0 },
                            key = CamelotKey.from(item.optInt("key", -1), item.optInt("mode", -1)),
                        )
                    }
                }
            }.onFailure { log.add("ReccoBeats error: ${it.message}") }
        }
        return result
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    private companion object {
        const val USER_AGENT = "DJRodri-spike/0.0.1 (uso personal)"
    }
}
