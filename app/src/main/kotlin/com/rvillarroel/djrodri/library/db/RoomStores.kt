package com.rvillarroel.djrodri.library.db

import com.rvillarroel.djrodri.data.analysis.AnalysisStore
import com.rvillarroel.djrodri.data.analysis.LyricsStatus
import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.data.analysis.TrackAnalysis
import com.rvillarroel.djrodri.data.party.PartyEventKind
import com.rvillarroel.djrodri.data.party.PartyRecorder
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.SessionStats

/** SQLite caps bound variables; big playlists are queried in slices. */
private const val MAX_IDS_PER_QUERY = 900

class RoomAnalysisStore(private val dao: AnalysisDao) : AnalysisStore {
    override suspend fun analyses(trackIds: Collection<String>): Map<String, TrackAnalysis> =
        trackIds.distinct().chunked(MAX_IDS_PER_QUERY)
            .flatMap { dao.analyses(it) }
            .associate { it.trackId to it.toDomain() }

    override suspend fun save(analysis: TrackAnalysis) = dao.upsert(analysis.toEntity())

    override suspend fun dropMarks(trackIds: Collection<String>): Map<String, Long> =
        trackIds.distinct().chunked(MAX_IDS_PER_QUERY)
            .flatMap { dao.dropMarks(it) }
            .associate { it.trackId to it.positionMs }
}

class RoomPartyRecorder(private val party: PartyDao, private val analysis: AnalysisDao) : PartyRecorder {
    override suspend fun sessionStarted(playlistId: String, mode: DjMode, atMs: Long): Long =
        party.insertSession(PartySessionEntity(playlistId = playlistId, mode = mode.name, startedAtMs = atMs))

    override suspend fun event(sessionId: Long, trackId: String, kind: PartyEventKind, positionMs: Long, atMs: Long) =
        party.insertEvent(PlayEventEntity(sessionId = sessionId, trackId = trackId, kind = kind.name, positionMs = positionMs, atMs = atMs))

    override suspend fun statsChanged(sessionId: Long, stats: SessionStats, endedAtMs: Long?) =
        party.updateStats(sessionId, stats.fullPlays, stats.cuts, stats.dropEntries, stats.manualSkips, stats.dislikes, endedAtMs)

    override suspend fun dropMarked(trackId: String, positionMs: Long, atMs: Long) =
        analysis.upsertDropMark(DropMarkEntity(trackId, positionMs, atMs))
}

fun TrackEntity.toSource() = SourceTrack(id, uri, name, artist, album, durationMs, isrc)

fun SourceTrack.toEntity() = TrackEntity(id, uri, name, artist, album, durationMs, isrc)

private fun AnalysisEntity.toDomain() = TrackAnalysis(
    trackId = trackId,
    lyricsStatus = runCatching { LyricsStatus.valueOf(lyricsStatus) }.getOrDefault(LyricsStatus.ERROR),
    syncedLrc = syncedLrc,
    featuresFound = featuresFound,
    genres = genres.split(",").filter(String::isNotBlank).toSet(),
    artistMbid = artistMbid,
    tempo = tempo,
    key = musicalKey,
    mode = mode,
    energy = energy,
    danceability = danceability,
    valence = valence,
    analyzerVersion = analyzerVersion,
    analyzedAtMs = analyzedAtMs,
)

private fun TrackAnalysis.toEntity() = AnalysisEntity(
    trackId = trackId,
    lyricsStatus = lyricsStatus.name,
    syncedLrc = syncedLrc,
    featuresFound = featuresFound,
    genres = genres.joinToString(","),
    artistMbid = artistMbid,
    tempo = tempo,
    musicalKey = key,
    mode = mode,
    energy = energy,
    danceability = danceability,
    valence = valence,
    analyzerVersion = analyzerVersion,
    analyzedAtMs = analyzedAtMs,
)
