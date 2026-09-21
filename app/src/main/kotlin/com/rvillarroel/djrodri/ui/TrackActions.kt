package com.rvillarroel.djrodri.ui

import android.app.Activity
import android.os.SystemClock
import com.rvillarroel.djrodri.AppContainer
import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.library.db.DropMarkEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.rvillarroel.djrodri.container

data class TrackRow(val track: SourceTrack, val profile: TrackProfile)

/**
 * What you can do with one song from anywhere in the app: hear it from a point, put it next in the party, and fix
 * its drop. One place, so Home, Search, a playlist and the drop editor all behave the same.
 */
class TrackActions(private val container: AppContainer) {
    private val party get() = container.party

    /** Your drop for this song, from now on in every party. */
    suspend fun saveDrop(trackId: String, positionMs: Long) =
        container.database.analysis().upsertDropMark(DropMarkEntity(trackId, positionMs, System.currentTimeMillis()))

    /** Back to the drop the analysis estimates. */
    suspend fun clearDrop(trackId: String) = container.database.analysis().deleteDropMark(trackId)

    /** Plays the song from [fromMs]. Returns why not, when it cannot (a party is on, Spotify is not reachable). */
    suspend fun preview(activity: Activity, track: SourceTrack, fromMs: Long): String? {
        if (party.state.value.active) return "Hay una fiesta sonando: termínala para escuchar desde aquí"
        if (!container.ensureRemoteConnected(activity)) return "No se pudo conectar con la app de Spotify"
        container.remote.play(track.uri)
        withTimeoutOrNull(PREVIEW_WAIT_MS) { container.remote.nowPlaying.first { it?.track?.uri == track.uri } }
        if (fromMs > 0) container.remote.seekTo(fromMs)
        return null
    }

    /** Where the song is right now, if it is the one playing: that is where you heard the drop. */
    fun positionOf(track: SourceTrack): Long? = container.remote.nowPlaying.value
        ?.takeIf { it.track.uri == track.uri }
        ?.snapshot?.positionAt(SystemClock.elapsedRealtime())

    fun stopPreview() {
        if (!party.state.value.active && container.remote.connected.value) container.remote.pause()
    }

    fun canPlayNext(track: SourceTrack): Boolean = party.state.value.active && party.isUpcoming(track.id)

    fun playNext(track: SourceTrack) = party.playNext(track.id)

    /** The analysis of any songs, for rows that show tempo, key and style. */
    suspend fun rows(tracks: List<SourceTrack>): List<TrackRow> {
        val profiles = container.analyzer.profiles(tracks).associateBy { it.id }
        return tracks.distinctBy { it.id }.map { TrackRow(it, profiles.getValue(it.id)) }
    }

    private companion object {
        const val PREVIEW_WAIT_MS = 3_000L
    }
}

/** Turns a bare song into a [TrackRow] (with its analysis) off the main thread, for opening its sheet. */
class TrackRowLoader(private val actions: TrackActions, private val scope: CoroutineScope) {
    fun rowFor(track: SourceTrack, onReady: (TrackRow) -> Unit) {
        scope.launch { onReady(actions.rows(listOf(track)).first()) }
    }
}

@Composable
fun rememberTrackRows(): TrackRowLoader {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { TrackRowLoader(context.container.trackActions, scope) }
}
