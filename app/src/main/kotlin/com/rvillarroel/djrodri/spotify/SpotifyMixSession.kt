package com.rvillarroel.djrodri.spotify

import android.os.SystemClock
import com.rvillarroel.djrodri.library.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the Spotify Mix is playing, for the screen. */
data class SpotifyMixState(
    val active: Boolean = false,
    val playlistId: String? = null,
    val playlistName: String = "",
    val title: String? = null,
    val artist: String? = null,
    val imageUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPaused: Boolean = false,
)

/**
 * "Mix de Spotify": Spotify plays the whole playlist, in order, with the transitions you made in its own Mix editor.
 * DJ Rodri doesn't cut, skip or queue anything here, so Spotify's transitions have the playlist to work on; it only
 * shows what plays and passes on your taps. Spotify offers no API for its Mix, so this is the one honest way to use it.
 */
class SpotifyMixSession(
    private val remote: RemoteController,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SpotifyMixState())
    val state: StateFlow<SpotifyMixState> = _state
    private var watcher: Job? = null

    /** The song last seen playing, to come back to it if Spotify gets closed. */
    private var lastUri: String? = null

    fun start(playlistId: String, playlistName: String) {
        // Shuffle would pull songs out of the order the transitions were made for.
        remote.setShuffle(false)
        remote.play("spotify:playlist:$playlistId")
        _state.value = SpotifyMixState(active = true, playlistId = playlistId, playlistName = playlistName)
        watch()
    }

    fun togglePause() {
        if (_state.value.isPaused) remote.resume() else remote.pause()
    }

    /**
     * Spotify was closed and has just been opened again, without the playlist: it plays again from the same song and
     * second, in the playlist so the Mix transitions still apply.
     */
    suspend fun reconnected() {
        val playing = _state.value
        val id = playing.playlistId ?: return
        val contextUri = "spotify:playlist:$id"
        val uri = lastUri
        val index = uri?.let { runCatching { library.tracks(id) }.getOrNull()?.indexOfFirst { it.uri == uri } }?.takeIf { it >= 0 }
        remote.setShuffle(false)
        if (index == null) {
            remote.play(contextUri)
            return
        }
        val sentAt = SystemClock.elapsedRealtime()
        remote.skipToIndex(contextUri, index)
        if (playing.positionMs > 0) {
            // The last state already names this song: wait until the new one is actually playing, or the seek is refused.
            withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
                remote.nowPlaying.first { it?.track?.uri == uri && !it.snapshot.isPaused && it.snapshot.capturedAtMs >= sentAt }
            } ?: return
            remote.seekTo(playing.positionMs)
        }
    }

    fun next() = remote.skipNext()

    fun previous() = remote.skipPrevious()

    fun seek(positionMs: Long) = remote.seekTo(positionMs)

    /** Leaves the music playing: Spotify just carries on without the app watching. */
    fun stop() {
        watcher?.cancel()
        _state.value = SpotifyMixState()
    }

    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            var coverFor: String? = null
            while (isActive) {
                val now = remote.nowPlaying.value
                if (now != null) {
                    val uri = now.track.uri
                    lastUri = uri
                    if (uri != coverFor) {
                        coverFor = uri
                        // Covers come from your library when the song is in it; App Remote only gives an image id.
                        val cover = library.trackByUri(uri)?.imageUrl
                        _state.update { it.copy(imageUrl = cover) }
                    }
                    _state.update {
                        it.copy(
                            title = now.track.name,
                            artist = now.track.artist?.name,
                            positionMs = now.snapshot.positionAt(SystemClock.elapsedRealtime()).coerceIn(0, now.track.duration),
                            durationMs = now.track.duration,
                            isPaused = now.snapshot.isPaused,
                        )
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private companion object {
        const val TICK_MS = 250L
        const val SWITCH_TIMEOUT_MS = 5_000L
    }
}
