package com.rvillarroel.djrodri.data.analysis

import com.rvillarroel.djrodri.data.sources.AudioFeatures
import com.rvillarroel.djrodri.data.sources.AudioFeaturesSource
import com.rvillarroel.djrodri.data.sources.GenreSource
import com.rvillarroel.djrodri.data.sources.Lyrics
import com.rvillarroel.djrodri.data.sources.LyricsQuery
import com.rvillarroel.djrodri.data.sources.LyricsSource
import com.rvillarroel.djrodri.domain.dj.CueEstimator
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.domain.dj.TransitionPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface AnalysisProgress {
    data class Working(val done: Int, val total: Int, val currentTitle: String?) : AnalysisProgress
    data class Finished(val coverage: Coverage) : AnalysisProgress
}

/** What the result screen shows: how much of the playlist the DJ can really work with. */
data class Coverage(
    val total: Int,
    val withSyncedLyrics: Int,
    val withChorus: Int,
    val withTempo: Int,
    val withKey: Int,
    val withDrop: Int,
    val withGenres: Int,
    /** Songs with no confident cut point in Fiesta mode, or too short to cut: they will always play whole. */
    val alwaysWhole: Int,
    val failed: Int,
) {
    companion object {
        fun of(profiles: List<TrackProfile>, analyses: Collection<TrackAnalysis>): Coverage {
            val fiesta = DjMode.FIESTA
            return Coverage(
                total = profiles.size,
                withSyncedLyrics = analyses.count { it.lyricsStatus == LyricsStatus.SYNCED },
                withChorus = profiles.count { it.chorus != null },
                withTempo = profiles.count { it.bpm != null },
                withKey = profiles.count { it.key != null },
                withGenres = profiles.count { it.genres.isNotEmpty() },
                withDrop = profiles.count { p ->
                    CueEstimator.estimate(p).drop?.let { it.confidence >= TransitionPlanner.MIN_DROP_CONFIDENCE } == true
                },
                alwaysWhole = profiles.count { p ->
                    p.durationMs < TransitionPlanner.SHORT_SONG_MS ||
                        CueEstimator.estimate(p).cuts.none { it.confidence >= fiesta.minCutConfidence }
                },
                failed = analyses.count { !it.isFinal },
            )
        }
    }
}

/**
 * Analyzes a playlist once and remembers it forever: each song is fetched only if it has no final analysis at the
 * current version. Every song is saved as soon as it is done, so a killed app resumes where it stopped.
 */
class PlaylistAnalyzer(
    private val lyricsSource: LyricsSource,
    private val featuresSource: AudioFeaturesSource,
    private val genreSource: GenreSource,
    private val store: AnalysisStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Pause between songs: MusicBrainz asks for at most one request per second, and LRCLIB is free too. */
    private val politenessDelayMs: Long = 1_100,
) {
    fun analyze(tracks: List<SourceTrack>, force: Boolean = false): Flow<AnalysisProgress> = flow {
        val unique = tracks.distinctBy { it.id }
        val cached = store.analyses(unique.map { it.id })
        val pending = unique.filter { track ->
            force || cached[track.id]?.let { !it.isFinal || it.analyzerVersion < TrackAnalysis.CURRENT_VERSION } ?: true
        }

        var done = unique.size - pending.size
        emit(AnalysisProgress.Working(done, unique.size, null))

        val features = fetchFeatures(pending)
        for ((index, track) in pending.withIndex()) {
            emit(AnalysisProgress.Working(done, unique.size, track.name))
            store.save(analyzeOne(track, features))
            done++
            if (index < pending.lastIndex) delay(politenessDelayMs)
        }

        emit(AnalysisProgress.Finished(coverage(unique)))
    }

    suspend fun coverage(tracks: List<SourceTrack>): Coverage {
        val analyses = store.analyses(tracks.map { it.id })
        return Coverage.of(profiles(tracks, analyses), analyses.values)
    }

    /** Profiles for the DJ engine. Songs never analyzed still get a bare profile: they play, just always whole. */
    suspend fun profiles(tracks: List<SourceTrack>): List<TrackProfile> =
        profiles(tracks, store.analyses(tracks.map { it.id }))

    private suspend fun profiles(tracks: List<SourceTrack>, analyses: Map<String, TrackAnalysis>): List<TrackProfile> {
        val marks = store.dropMarks(tracks.map { it.id })
        return tracks.distinctBy { it.id }.map { track ->
            analyses[track.id]?.toProfile(track, marks[track.id])
                ?: TrackProfile(id = track.id, artist = track.artist, durationMs = track.durationMs, userDropMs = marks[track.id])
        }
    }

    private suspend fun fetchFeatures(pending: List<SourceTrack>): Map<String, AudioFeatures>? =
        if (pending.isEmpty()) emptyMap()
        else try {
            featuresSource.features(pending.map { it.id })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // features unavailable this run: lyrics still get analyzed, the songs are marked for retry
        }

    private suspend fun analyzeOne(track: SourceTrack, features: Map<String, AudioFeatures>?): TrackAnalysis {
        val lyrics = try {
            lyricsSource.lyrics(LyricsQuery(track.name, track.artist, track.album, track.durationMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // Genres need an ISRC, the industry code, so the lookup never travels through a Spotify id.
        val tags = track.isrc?.let {
            try {
                genreSource.genresByIsrc(it)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val f = features?.get(track.id)
        return TrackAnalysis(
            trackId = track.id,
            lyricsStatus = when {
                lyrics == null || features == null -> LyricsStatus.ERROR
                lyrics is Lyrics.Synced -> LyricsStatus.SYNCED
                lyrics is Lyrics.PlainOnly -> LyricsStatus.PLAIN_ONLY
                lyrics is Lyrics.Instrumental -> LyricsStatus.INSTRUMENTAL
                else -> LyricsStatus.NOT_FOUND
            },
            syncedLrc = (lyrics as? Lyrics.Synced)?.lrc,
            featuresFound = f != null,
            genres = tags?.genres.orEmpty(),
            artistMbid = tags?.artistMbid,
            tempo = f?.tempo,
            key = f?.key ?: -1,
            mode = f?.mode ?: -1,
            energy = f?.energy,
            danceability = f?.danceability,
            valence = f?.valence,
            analyzerVersion = TrackAnalysis.CURRENT_VERSION,
            analyzedAtMs = clock(),
        )
    }
}
