package com.rvillarroel.djrodri.library

import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.library.db.LibraryDao
import com.rvillarroel.djrodri.library.db.PlaylistEntity
import com.rvillarroel.djrodri.library.db.PlaylistWithProgress
import com.rvillarroel.djrodri.library.db.toEntity
import com.rvillarroel.djrodri.library.db.toSource
import com.rvillarroel.djrodri.spotify.LIKED_SONGS_ID
import com.rvillarroel.djrodri.spotify.SpotifyWebApi
import kotlinx.coroutines.flow.Flow

/**
 * Your playlists, read from Spotify only when you ask (pull to refresh, or opening one never loaded).
 * Everything else reads the local copy, so a party works even with a flaky connection.
 */
class LibraryRepository(private val api: SpotifyWebApi, private val dao: LibraryDao, private val clock: () -> Long = System::currentTimeMillis) {

    val playlists: Flow<List<PlaylistWithProgress>> = dao.playlists()

    fun playlist(id: String) = dao.observePlaylist(id)

    suspend fun refreshPlaylists() {
        val remote = api.myPlaylists()
        val all = listOf(PlaylistEntity(LIKED_SONGS_ID, "Tus me gusta", "", null, null, 0, null)) +
            remote.mapIndexed { index, p -> PlaylistEntity(p.id, p.name, p.ownerId, p.total, p.imageUrl, index + 1, null) }
        val existing = all.associate { it.id to dao.playlist(it.id)?.tracksSyncedAtMs }
        dao.upsertPlaylists(all.map { it.copy(tracksSyncedAtMs = existing[it.id]) })
        dao.deletePlaylistsNotIn(all.map { it.id })
    }

    /** Local songs of a playlist, fetched from Spotify the first time or when [refresh] is asked. */
    suspend fun tracks(playlistId: String, refresh: Boolean = false): List<SourceTrack> {
        val synced = dao.playlist(playlistId)?.tracksSyncedAtMs != null
        if (refresh || !synced) {
            val fresh = api.tracks(playlistId)
            dao.replaceTracks(playlistId, fresh.map { it.toEntity() }, clock())
            return fresh
        }
        return dao.tracksOf(playlistId).map { it.toSource() }
    }

    suspend fun trackByUri(uri: String): SourceTrack? = dao.trackByUri(uri)?.toSource()
}
