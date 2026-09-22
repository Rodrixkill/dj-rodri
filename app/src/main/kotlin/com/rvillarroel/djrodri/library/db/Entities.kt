package com.rvillarroel.djrodri.library.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Only what is strictly needed to run the app: identity and names to show and look lyrics up.
 * No raw Spotify responses are ever stored.
 */
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerId: String,
    val trackCount: Int?,
    val imageUrl: String?,
    val position: Int,
    val tracksSyncedAtMs: Long?,
)

@Entity(tableName = "track")
data class TrackEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isrc: String?,
    /** The album cover Spotify serves, shown as it comes (never cropped or altered). */
    val imageUrl: String? = null,
)

/** Ids, not copies: a playlist points at tracks, so a song analyzed once is analyzed for every playlist. */
@Entity(tableName = "playlist_track", primaryKeys = ["playlistId", "position"], indices = [Index("trackId")])
data class PlaylistTrackEntity(
    val playlistId: String,
    val position: Int,
    val trackId: String,
)

@Entity(tableName = "analysis")
data class AnalysisEntity(
    @PrimaryKey val trackId: String,
    val lyricsStatus: String,
    val syncedLrc: String?,
    val featuresFound: Boolean,
    val genres: String,
    val artistMbid: String?,
    val tempo: Double?,
    val musicalKey: Int,
    val mode: Int,
    val energy: Double?,
    val danceability: Double?,
    val valence: Double?,
    val analyzerVersion: Int,
    val analyzedAtMs: Long,
)

/** Your "¡Drop!" taps: the most exact data the app has, never overwritten by analysis. */
@Entity(tableName = "drop_mark")
data class DropMarkEntity(
    @PrimaryKey val trackId: String,
    val positionMs: Long,
    val markedAtMs: Long,
)

@Entity(tableName = "party_session")
data class PartySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    val mode: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val fullPlays: Int = 0,
    val cuts: Int = 0,
    val dropEntries: Int = 0,
    val manualSkips: Int = 0,
    val dislikes: Int = 0,
)

@Entity(tableName = "play_event", indices = [Index("sessionId"), Index("trackId")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val trackId: String,
    val kind: String,
    val positionMs: Long,
    val atMs: Long,
)

/** A playlist of your own files for the DJ decks (nothing to do with Spotify's). */
@Entity(tableName = "local_playlist")
data class LocalPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)

/** A song of a local playlist, by its MediaStore id: the file itself is never copied. */
@Entity(tableName = "local_playlist_item", primaryKeys = ["playlistId", "position"], indices = [Index("playlistId")])
data class LocalPlaylistItemEntity(
    val playlistId: Long,
    val position: Int,
    val songId: Long,
)
