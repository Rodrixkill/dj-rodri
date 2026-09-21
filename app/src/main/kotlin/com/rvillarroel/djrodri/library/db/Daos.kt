package com.rvillarroel.djrodri.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class PlaylistWithProgress(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val imageUrl: String?,
    val cachedTracks: Int,
    val analyzedTracks: Int,
)

@Dao
interface LibraryDao {
    @Query(
        """
        SELECT p.id, p.name, p.trackCount, p.imageUrl,
               COUNT(pt.trackId) AS cachedTracks,
               COUNT(CASE WHEN a.lyricsStatus IS NOT NULL AND a.lyricsStatus != 'ERROR' THEN 1 END) AS analyzedTracks
        FROM playlist p
        LEFT JOIN playlist_track pt ON pt.playlistId = p.id
        LEFT JOIN analysis a ON a.trackId = pt.trackId
        GROUP BY p.id
        ORDER BY p.position
        """,
    )
    fun playlists(): Flow<List<PlaylistWithProgress>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun playlist(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun observePlaylist(id: String): Flow<PlaylistEntity?>

    @Upsert
    suspend fun upsertPlaylists(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlist WHERE id NOT IN (:keep)")
    suspend fun deletePlaylistsNotIn(keep: List<String>)

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM playlist_track WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: String)

    @Insert
    suspend fun insertPlaylistTracks(entries: List<PlaylistTrackEntity>)

    @Query("UPDATE playlist SET tracksSyncedAtMs = :atMs WHERE id = :playlistId")
    suspend fun markTracksSynced(playlistId: String, atMs: Long)

    @Transaction
    suspend fun replaceTracks(playlistId: String, tracks: List<TrackEntity>, atMs: Long) {
        upsertTracks(tracks)
        clearPlaylistTracks(playlistId)
        insertPlaylistTracks(tracks.mapIndexed { index, t -> PlaylistTrackEntity(playlistId, index, t.id) })
        markTracksSynced(playlistId, atMs)
    }

    @Query("SELECT t.* FROM playlist_track pt JOIN track t ON t.id = pt.trackId WHERE pt.playlistId = :playlistId ORDER BY pt.position")
    suspend fun tracksOf(playlistId: String): List<TrackEntity>

    @Query("SELECT * FROM track WHERE uri = :uri LIMIT 1")
    suspend fun trackByUri(uri: String): TrackEntity?

    /** Every song of every playlist read so far: the library that Search and the genre mixes work on. */
    @Query("SELECT * FROM track ORDER BY name")
    suspend fun allTracks(): List<TrackEntity>

    /** By title, artist or album, as you type. [pattern] comes with its % wildcards. */
    @Query("SELECT * FROM track WHERE name LIKE :pattern OR artist LIKE :pattern OR album LIKE :pattern ORDER BY name LIMIT :limit")
    suspend fun search(pattern: String, limit: Int = 60): List<TrackEntity>

    /** Songs heard at your parties, most recent first, each once. */
    @Query(
        """
        SELECT t.* FROM track t
        JOIN (SELECT trackId, MAX(atMs) AS lastAt FROM play_event GROUP BY trackId) e ON e.trackId = t.id
        ORDER BY e.lastAt DESC LIMIT :limit
        """,
    )
    fun recentlyPlayed(limit: Int = 20): Flow<List<TrackEntity>>

    @Query("SELECT id FROM playlist WHERE tracksSyncedAtMs IS NOT NULL")
    suspend fun syncedPlaylists(): List<String>

    /** The playlist holding most of [trackIds]: where a genre mix is played from. */
    @Query(
        """
        SELECT playlistId FROM playlist_track WHERE trackId IN (:trackIds)
        GROUP BY playlistId ORDER BY COUNT(*) DESC LIMIT 1
        """,
    )
    suspend fun playlistHoldingMost(trackIds: List<String>): String?

    /** The first covers of a playlist, for a mosaic when the playlist has no picture of its own. */
    @Query("SELECT t.imageUrl FROM playlist_track pt JOIN track t ON t.id = pt.trackId WHERE pt.playlistId = :playlistId AND t.imageUrl IS NOT NULL ORDER BY pt.position LIMIT 4")
    suspend fun coversOf(playlistId: String): List<String>
}

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM analysis WHERE trackId IN (:trackIds)")
    suspend fun analyses(trackIds: List<String>): List<AnalysisEntity>

    @Upsert
    suspend fun upsert(analysis: AnalysisEntity)

    @Query("SELECT * FROM drop_mark WHERE trackId IN (:trackIds)")
    suspend fun dropMarks(trackIds: List<String>): List<DropMarkEntity>

    @Upsert
    suspend fun upsertDropMark(mark: DropMarkEntity)

    /** Forget your mark: the drop goes back to what the analysis estimates. */
    @Query("DELETE FROM drop_mark WHERE trackId = :trackId")
    suspend fun deleteDropMark(trackId: String)

    @Query("SELECT COUNT(*) FROM drop_mark")
    fun dropMarkCount(): Flow<Int>
}

@Dao
interface PartyDao {
    @Insert
    suspend fun insertSession(session: PartySessionEntity): Long

    @Query(
        """
        UPDATE party_session SET fullPlays = :fullPlays, cuts = :cuts, dropEntries = :dropEntries,
            manualSkips = :manualSkips, dislikes = :dislikes, endedAtMs = COALESCE(:endedAtMs, endedAtMs)
        WHERE id = :id
        """,
    )
    suspend fun updateStats(id: Long, fullPlays: Int, cuts: Int, dropEntries: Int, manualSkips: Int, dislikes: Int, endedAtMs: Long?)

    @Insert
    suspend fun insertEvent(event: PlayEventEntity)

    @Query("SELECT * FROM party_session ORDER BY startedAtMs DESC LIMIT :limit")
    fun recentSessions(limit: Int = 20): Flow<List<PartySessionEntity>>
}
