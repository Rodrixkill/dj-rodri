package com.rvillarroel.djrodri.data.analysis

import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.domain.lyrics.ChorusFinder
import com.rvillarroel.djrodri.domain.lyrics.LrcParser
import com.rvillarroel.djrodri.domain.music.CamelotKey

/** A song as it comes from a playlist: identity only, no analysis. [id] is the Spotify track id. */
data class SourceTrack(
    val id: String,
    val uri: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isrc: String?,
)

enum class LyricsStatus { SYNCED, PLAIN_ONLY, INSTRUMENTAL, NOT_FOUND, ERROR }

/**
 * What the open sources said about one song. Stored raw (the LRC text, not the detected chorus) so improving
 * [ChorusFinder] re-analyzes every song for free, without calling LRCLIB again.
 */
data class TrackAnalysis(
    val trackId: String,
    val lyricsStatus: LyricsStatus,
    val syncedLrc: String?,
    val featuresFound: Boolean,
    val tempo: Double?,
    val key: Int,
    val mode: Int,
    val energy: Double?,
    val danceability: Double?,
    val valence: Double?,
    val analyzerVersion: Int,
    val analyzedAtMs: Long,
) {
    fun toProfile(track: SourceTrack, userDropMs: Long?): TrackProfile = TrackProfile(
        id = track.id,
        artist = track.artist,
        durationMs = track.durationMs,
        bpm = tempo,
        key = CamelotKey.from(key, mode),
        energy = energy,
        chorus = syncedLrc?.let { ChorusFinder.find(LrcParser.parse(it), track.durationMs) },
        userDropMs = userDropMs,
    )

    /** An ERROR (network down, source failing) is worth retrying; a clean "not found" is not. */
    val isFinal: Boolean get() = lyricsStatus != LyricsStatus.ERROR

    companion object {
        /** Bump when what we fetch changes; older analyses are then refreshed. */
        const val CURRENT_VERSION = 1
    }
}

/** Where analyses and your drop marks live. The app implements it with Room; tests with a map. */
interface AnalysisStore {
    suspend fun analyses(trackIds: Collection<String>): Map<String, TrackAnalysis>
    suspend fun save(analysis: TrackAnalysis)
    suspend fun dropMarks(trackIds: Collection<String>): Map<String, Long>
}
