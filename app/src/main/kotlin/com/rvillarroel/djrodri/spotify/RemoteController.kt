package com.rvillarroel.djrodri.spotify

import android.content.Context
import android.os.SystemClock
import com.rvillarroel.djrodri.BuildConfig
import com.rvillarroel.djrodri.domain.playback.PlaybackSnapshot
import com.rvillarroel.djrodri.spike.SpikeLog
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
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
class RemoteController(private val log: SpikeLog) {
    private var remote: SpotifyAppRemote? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Every state change as (arrival time, state): the probes read latencies from here. */
    val events = MutableStateFlow<List<Pair<Long, PlayerState>>>(emptyList())

    fun connect(context: Context) {
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            log.add("App Remote: Spotify no está instalado")
            return
        }
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(true)
            .build()
        val startedAt = SystemClock.elapsedRealtime()
        log.add("App Remote: conectando…")
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                _connected.value = true
                log.add("App Remote: CONECTADO en ${SystemClock.elapsedRealtime() - startedAt} ms")
                appRemote.playerApi.subscribeToPlayerState()
                    .setEventCallback(::onPlayerState)
                    .setErrorCallback { log.add("App Remote: error en la suscripción: $it") }
            }

            override fun onFailure(error: Throwable) {
                _connected.value = false
                log.add("App Remote: FALLÓ la conexión: ${error.javaClass.simpleName}: ${error.message}")
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
            log.add("PlayerState SIN canción (pos=${state.playbackPosition}, pausa=${state.isPaused})")
            return
        }
        val previous = _nowPlaying.value
        _nowPlaying.value = NowPlaying(
            track,
            PlaybackSnapshot(track.uri, state.playbackPosition, now, state.isPaused, state.playbackSpeed.takeIf { it > 0f } ?: 1f),
        )
        if (previous?.track?.uri != track.uri) {
            log.add("♪ ${track.name} — ${track.artist?.name} (${track.duration / 1000}s) pos=${state.playbackPosition}")
        } else {
            log.add("estado: pos=${state.playbackPosition} pausa=${state.isPaused}")
        }
    }

    fun play(uri: String) = call("play $uri") { it.playerApi.play(uri) }
    fun queue(uri: String) = call("queue $uri") { it.playerApi.queue(uri) }
    fun skipNext() = call("skipNext") { it.playerApi.skipNext() }
    fun skipPrevious() = call("skipPrevious") { it.playerApi.skipPrevious() }
    fun pause() = call("pause") { it.playerApi.pause() }
    fun resume() = call("resume") { it.playerApi.resume() }
    fun seekTo(positionMs: Long) = call("seekTo $positionMs") { it.playerApi.seekTo(positionMs) }

    suspend fun crossfade(): CrossfadeState? = await { it.playerApi.crossfadeState }

    suspend fun playerState(): PlayerState? = await { it.playerApi.playerState }

    private fun call(label: String, block: (SpotifyAppRemote) -> CallResult<*>): Boolean {
        val r = remote ?: run { log.add("$label: App Remote no está conectado"); return false }
        block(r).setErrorCallback { log.add("$label → ERROR ${it.message}") }
        return true
    }

    private suspend fun <T> await(block: (SpotifyAppRemote) -> CallResult<T>): T? {
        val r = remote ?: return null
        return suspendCancellableCoroutine { continuation ->
            block(r)
                .setResultCallback { if (continuation.isActive) continuation.resume(it) }
                .setErrorCallback {
                    log.add("App Remote → ERROR ${it.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }
}
