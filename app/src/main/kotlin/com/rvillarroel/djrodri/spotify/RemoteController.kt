package com.rvillarroel.djrodri.spotify

import android.content.Context
import android.os.SystemClock
import com.rvillarroel.djrodri.BuildConfig
import com.rvillarroel.djrodri.domain.playback.PlaybackSnapshot
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.SpotifyConnectionTerminatedException
import com.spotify.android.appremote.api.error.SpotifyDisconnectedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.CrossfadeState
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class NowPlaying(val track: Track, val snapshot: PlaybackSnapshot)

/**
 * Thin wrapper over App Remote: turns its callbacks into a StateFlow and stamps every PlayerState
 * with the moment it arrived, which is what the cut measurements are built on.
 */
class RemoteController(var logger: (String) -> Unit = { android.util.Log.d("DJRodri", it) }) {
    private var remote: SpotifyAppRemote? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Every state change as (arrival time, state): the probes read latencies from here. */
    val events = MutableStateFlow<List<Pair<Long, PlayerState>>>(emptyList())

    fun connect(context: Context) {
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            logger("App Remote: Spotify no está instalado")
            return
        }
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(true)
            .build()
        val startedAt = SystemClock.elapsedRealtime()
        logger("App Remote: conectando…")
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                _connected.value = true
                logger("App Remote: CONECTADO en ${SystemClock.elapsedRealtime() - startedAt} ms")
                appRemote.playerApi.subscribeToPlayerState()
                    .setEventCallback(::onPlayerState)
                    .setErrorCallback { logger("App Remote: error en la suscripción: $it") }
            }

            // Also called long after connecting, when Spotify closes: the old connection is dead from then on.
            override fun onFailure(error: Throwable) {
                if (remote != null) freezePosition()
                remote = null
                _connected.value = false
                logger("App Remote: FALLÓ la conexión: ${error.javaClass.simpleName}: ${error.message}")
            }
        })
    }

    fun disconnect() {
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
        _connected.value = false
    }

    private fun onPlayerState(state: PlayerState) {
        val now = SystemClock.elapsedRealtime()
        events.value = events.value + (now to state)
        val track = state.track
        if (track == null) {
            // Issue #386 (Aug 2026): newer Spotify builds may stop sending track info. This is the line to look for.
            logger("PlayerState SIN canción (pos=${state.playbackPosition}, pausa=${state.isPaused})")
            return
        }
        val previous = _nowPlaying.value
        _nowPlaying.value = NowPlaying(
            track,
            PlaybackSnapshot(track.uri, state.playbackPosition, now, state.isPaused, state.playbackSpeed.takeIf { it > 0f } ?: 1f),
        )
        if (previous?.track?.uri != track.uri) {
            logger("♪ ${track.name} — ${track.artist?.name} (${track.duration / 1000}s) pos=${state.playbackPosition}")
        } else {
            logger("estado: pos=${state.playbackPosition} pausa=${state.isPaused}")
        }
    }

    fun play(uri: String) = call("play $uri") { it.playerApi.play(uri) }
    fun queue(uri: String) = call("queue $uri") { it.playerApi.queue(uri) }
    fun skipNext() = call("skipNext") { it.playerApi.skipNext() }
    fun skipPrevious() = call("skipPrevious") { it.playerApi.skipPrevious() }
    fun pause() = call("pause") { it.playerApi.pause() }
    fun resume() = call("resume") { it.playerApi.resume() }
    fun seekTo(positionMs: Long) = call("seekTo $positionMs") { it.playerApi.seekTo(positionMs) }
    fun setShuffle(on: Boolean) = call("shuffle $on") { it.playerApi.setShuffle(on) }
    fun skipToIndex(contextUri: String, index: Int) = call("skipToIndex $contextUri $index") { it.playerApi.skipToIndex(contextUri, index) }

    /** Whether Spotify is still on the other end; a closed Spotify is noticed (and forgotten) here. */
    fun isLive(): Boolean = liveRemote() != null

    suspend fun crossfade(): CrossfadeState? = await { it.playerApi.crossfadeState }

    suspend fun playerState(): PlayerState? = await { it.playerApi.playerState }

    private fun call(label: String, block: (SpotifyAppRemote) -> CallResult<*>): Boolean {
        val r = liveRemote() ?: run { logger("$label: App Remote no está conectado"); return false }
        block(r).setErrorCallback {
            logger("$label → ERROR ${it.javaClass.simpleName}: ${it.message}")
            if (it.isDisconnect()) lost()
        }
        return true
    }

    /** The connection, if Spotify is still on the other end of it. */
    private fun liveRemote(): SpotifyAppRemote? = remote?.takeIf { it.isConnected } ?: run {
        if (remote != null) lost()
        null
    }

    /**
     * Spotify was closed (by HyperOS, or a swipe): every call on the old connection fails without a word, so it is
     * dropped and the next play reconnects.
     */
    private fun lost() {
        if (remote == null && !_connected.value) return
        logger("App Remote: Spotify se cerró, conexión perdida")
        freezePosition()
        remote?.let { runCatching { SpotifyAppRemote.disconnect(it) } }
        remote = null
        _connected.value = false
    }

    /**
     * The music stopped when Spotify closed: the song is held paused at that second, so the screen stops counting and
     * playing it again starts right there, not at the last position Spotify happened to report.
     */
    private fun freezePosition() {
        val now = SystemClock.elapsedRealtime()
        _nowPlaying.value = _nowPlaying.value?.let { np ->
            val at = np.snapshot.positionAt(now).coerceAtMost(np.track.duration)
            np.copy(snapshot = np.snapshot.copy(positionMs = at, capturedAtMs = now, isPaused = true))
        }
    }

    private fun Throwable.isDisconnect() =
        this is SpotifyDisconnectedException || this is SpotifyConnectionTerminatedException || remote?.isConnected == false

    private suspend fun <T> await(block: (SpotifyAppRemote) -> CallResult<T>): T? {
        val r = liveRemote() ?: return null
        return suspendCancellableCoroutine { continuation ->
            block(r)
                .setResultCallback { if (continuation.isActive) continuation.resume(it) }
                .setErrorCallback {
                    logger("App Remote → ERROR ${it.javaClass.simpleName}: ${it.message}")
                    if (it.isDisconnect()) lost()
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }
}
