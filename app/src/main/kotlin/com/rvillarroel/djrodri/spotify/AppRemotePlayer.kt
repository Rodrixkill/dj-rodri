package com.rvillarroel.djrodri.spotify

import com.rvillarroel.djrodri.data.party.PlayerPort
import com.rvillarroel.djrodri.data.party.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** App Remote seen through the party's [PlayerPort]. */
class AppRemotePlayer(private val remote: RemoteController, scope: CoroutineScope) : PlayerPort {
    override val state: StateFlow<PlayerState?> = remote.nowPlaying
        .map { np ->
            np?.let {
                PlayerState(
                    trackUri = it.track.uri,
                    durationMs = it.track.duration,
                    positionMs = it.snapshot.positionMs,
                    isPaused = it.snapshot.isPaused,
                    capturedAtMs = it.snapshot.capturedAtMs,
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override fun play(uri: String) {
        remote.play(uri)
    }

    override fun seekTo(positionMs: Long) {
        remote.seekTo(positionMs)
    }

    override fun pause() {
        remote.pause()
    }

    override fun resume() {
        remote.resume()
    }

    override fun queue(uri: String) {
        remote.queue(uri)
    }

    override fun skipNext() {
        remote.skipNext()
    }

    override suspend fun crossfadeMs(): Long? =
        remote.crossfade()?.takeIf { it.isEnabled }?.duration?.toLong()?.takeIf { it > 0 }
}
