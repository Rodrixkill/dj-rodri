package com.rvillarroel.djrodri.localdj

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A song file on the phone. [id] is MediaStore's, stable while the file stays where it is. */
data class LocalSong(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: Uri?,
    val modifiedAt: Long,
)

/**
 * Your own music, as Android's media library knows it. Only files at least a minute long: ringtones, voice notes
 * and notification sounds are not DJ material.
 */
class LocalLibrary(private val context: Context) {

    suspend fun songs(): List<LocalSong> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val songs = mutableListOf<LocalSong>()
        context.contentResolver.query(collection, projection, selection, arrayOf(MIN_DURATION_MS.toString()), "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val modified = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val songId = c.getLong(id)
                songs += LocalSong(
                    id = songId,
                    uri = ContentUris.withAppendedId(collection, songId),
                    title = c.getString(title) ?: "Sin título",
                    artist = c.getString(artist)?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "Artista desconocido",
                    album = c.getString(album).orEmpty(),
                    durationMs = c.getLong(duration),
                    artUri = ContentUris.withAppendedId(ALBUM_ART, c.getLong(albumId)),
                    modifiedAt = c.getLong(modified),
                )
            }
        }
        songs
    }

    private companion object {
        const val MIN_DURATION_MS = 60_000L
        val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}
