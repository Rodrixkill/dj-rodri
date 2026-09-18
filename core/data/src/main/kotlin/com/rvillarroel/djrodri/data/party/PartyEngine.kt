package com.rvillarroel.djrodri.data.party

import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.domain.dj.DjIntent
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.DjSession
import com.rvillarroel.djrodri.domain.dj.DjSessionState
import com.rvillarroel.djrodri.domain.dj.Entry
import com.rvillarroel.djrodri.domain.dj.Exit
import com.rvillarroel.djrodri.domain.dj.PlayCommand
import com.rvillarroel.djrodri.domain.dj.SessionStats
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.domain.dj.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

data class PartyTrack(val source: SourceTrack, val profile: TrackProfile)

data class PartySettings(
    val mode: DjMode = DjMode.FIESTA,
    /** Pause for an instant before jumping into a drop, so the first notes of the song are never heard. */
    val dropWithPause: Boolean = false,
    /** Fire a cut this early to compensate for the time Spotify takes to switch. Tuned from the spike. */
    val cutLeadMs: Long = 150,
    /** A song playing whole is handed over this close to its end. */
    val endLeadMs: Long = 400,
)

enum class PartyEventKind { FULL, CUT, DROP_ENTRY, SKIP, PREVIOUS, DISLIKE, KEEP_FULL, EXTERNAL_CHANGE }

/**
 * A party frozen on disk. HyperOS kills background apps without asking, so the party is saved on every song and
 * every few seconds: reopening the app offers to carry on from the exact song and second.
 */
data class PartySnapshot(
    val sessionId: Long,
    val playlistId: String,
    val settings: PartySettings,
    val currentTrackId: String,
    val positionMs: Long,
    val upcomingIds: List<String>,
    val playedIds: List<String>,
    val transitions: List<Transition>,
    val stats: SessionStats,
    val intent: DjIntent,
    val savedAtMs: Long,
)

interface PartySnapshotStore {
    suspend fun save(snapshot: PartySnapshot)
    suspend fun load(): PartySnapshot?
    suspend fun clear()
}

/** Persistence for what happens at a party. The app implements it with Room. */
interface PartyRecorder {
    suspend fun sessionStarted(playlistId: String, mode: DjMode, atMs: Long): Long
    suspend fun event(sessionId: Long, trackId: String, kind: PartyEventKind, positionMs: Long, atMs: Long)
    suspend fun statsChanged(sessionId: Long, stats: SessionStats, endedAtMs: Long?)
    suspend fun dropMarked(trackId: String, positionMs: Long, atMs: Long)
}

data class PartyState(
    val active: Boolean = false,
    val playlistId: String? = null,
    val current: SourceTrack? = null,
    val next: SourceTrack? = null,
    val plan: Transition? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPaused: Boolean = false,
    val settings: PartySettings = PartySettings(),
    val stats: SessionStats = SessionStats(),
    val intent: DjIntent = DjIntent.Default,
    /** Set when Spotify is playing something the DJ did not choose; the DJ stops deciding until you press ⏭. */
    val externalTrackUri: String? = null,
)

/**
 * Runs a [DjSession] against a real player: watches the position, fires cuts and drop entries on time, hands over
 * at the end of whole songs, and never fights you — if Spotify changes song on its own or from the Spotify app,
 * the DJ steps back until you give it control again.
 */
class PartyEngine(
    private val player: PlayerPort,
    private val recorder: PartyRecorder,
    private val snapshots: PartySnapshotStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
    private val tickMs: Long = 100,
) {
    private val _state = MutableStateFlow(PartyState())
    val state: StateFlow<PartyState> = _state

    /** The analyzed songs of the party, so a request like "estilo Avicii" can be read off your own library. */
    val profiles: List<TrackProfile> get() = tracks.values.map { it.profile }

    private val lock = Mutex()
    private var session: DjSession? = null
    private var sessionId = 0L
    private var tracks: Map<String, PartyTrack> = emptyMap()
    private var settings = PartySettings()
    private var lastCommandAtMs = 0L
    private var external: String? = null
    private var lastSnapshotAtMs = 0L
    private var ticker: Job? = null

    suspend fun start(playlistId: String, playlist: List<PartyTrack>, settings: PartySettings) = lock.withLock {
        stopTicker()
        this.settings = settings
        tracks = playlist.associateBy { it.source.id }
        val newSession = DjSession(playlist.map { it.profile }, settings.mode, random)
        session = newSession
        sessionId = recorder.sessionStarted(playlistId, settings.mode, clock())
        _state.value = PartyState(active = true, playlistId = playlistId, settings = settings)
        execute(newSession.start())
        startTicker()
    }

    /**
     * Carries on a party the system killed: same order, same song, same second, same cut budget.
     * Songs missing from the playlist now (removed since) are simply dropped from what is left.
     */
    suspend fun resume(snapshot: PartySnapshot, playlist: List<PartyTrack>): Boolean = lock.withLock {
        stopTicker()
        tracks = playlist.associateBy { it.source.id }
        val profiles = tracks.mapValues { it.value.profile }
        val current = profiles[snapshot.currentTrackId] ?: return@withLock false
        settings = snapshot.settings
        sessionId = snapshot.sessionId
        session = DjSession(
            tracks = profiles.values.toList(),
            mode = snapshot.settings.mode,
            random = random,
            restored = DjSessionState(
                current = current,
                upcoming = snapshot.upcomingIds.mapNotNull { profiles[it] },
                played = snapshot.playedIds.mapNotNull { profiles[it] },
                transitions = snapshot.transitions,
                stats = snapshot.stats,
                intent = snapshot.intent,
            ),
        )
        _state.value = PartyState(active = true, playlistId = snapshot.playlistId, settings = settings)

        val uri = uriOf(current.id)
        val playing = player.state.value
        if (playing?.trackUri != uri) {
            // Spotify moved on (or stopped) while the app was dead: pick the song up where it was left.
            lastCommandAtMs = clock()
            player.play(uri)
            withTimeoutOrNull(SWITCH_TIMEOUT_MS) { player.state.first { it?.trackUri == uri } }
            if (snapshot.positionMs > 0) player.seekTo(snapshot.positionMs)
        }
        publish()
        startTicker()
        true
    }

    private fun startTicker() {
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                lock.withLock { tick() }
            }
        }
    }

    fun next() = act { s -> record(PartyEventKind.SKIP); execute(s.skip()) }
    fun previous() = act { s -> record(PartyEventKind.PREVIOUS); s.previous()?.let { execute(it) } }
    fun dislike() = act { s -> record(PartyEventKind.DISLIKE); execute(s.dislike()) }
    fun keepFull() = act { s -> record(PartyEventKind.KEEP_FULL); s.keepFull(); publish() }
    /** What you asked for, already turned into numbers by the rules or the local model. */
    fun applyIntent(intent: DjIntent) = act { s ->
        s.applyIntent(intent)
        // A request like "a fondo" also changes how boldly the DJ cuts.
        if (s.mode != settings.mode) settings = settings.copy(mode = s.mode)
        publish()
    }

    fun moreEnergy() = act { s -> s.moreEnergy(); publish() }
    fun lessEnergy() = act { s -> s.lessEnergy(); publish() }

    fun changeMode(mode: DjMode) = act { s ->
        settings = settings.copy(mode = mode)
        s.changeMode(mode)
        publish()
    }

    fun updateSettings(newSettings: PartySettings) = act { s ->
        if (newSettings.mode != settings.mode) s.changeMode(newSettings.mode)
        settings = newSettings
        publish()
    }

    fun togglePause() = act {
        val paused = player.state.value?.isPaused ?: return@act
        if (paused) player.resume() else player.pause()
    }

    /** "¡Drop!": remember where the drop of the current song really is, for every future party. */
    fun markDrop() = act { s ->
        val song = s.current ?: return@act
        val playing = player.state.value ?: return@act
        if (playing.trackUri != uriOf(song.id)) return@act
        recorder.dropMarked(song.id, playing.positionAt(clock()), clock())
    }

    fun switchPlaylist(playlistId: String, playlist: List<PartyTrack>) = act { s ->
        tracks = tracks + playlist.associateBy { it.source.id }
        s.switchPlaylist(playlist.map { it.profile })
        _state.value = _state.value.copy(playlistId = playlistId)
        publish()
    }

    fun stop() = act { finish() }

    private fun act(block: suspend (DjSession) -> Unit) {
        scope.launch {
            lock.withLock {
                val s = session ?: return@withLock
                block(s)
                // Any command from you hands control back to the DJ.
                external = null
            }
        }
    }

    private suspend fun tick() {
        val s = session ?: return
        val song = s.current ?: return
        val playing = player.state.value ?: return
        val now = clock()

        if (playing.trackUri != uriOf(song.id)) {
            // Spotify takes a moment to report the song we just asked for.
            if (now - lastCommandAtMs < SWITCH_GRACE_MS) return
            if (external == null && playing.trackUri != null) {
                external = playing.trackUri
                record(PartyEventKind.EXTERNAL_CHANGE)
            }
            publish()
            return
        }

        val position = playing.positionAt(now)
        val plan = s.plan
        when {
            playing.isPaused && position >= playing.durationMs - STOPPED_AT_END_MS -> {
                // The app slept through the end and Spotify stopped: carry on as if it ended normally.
                record(PartyEventKind.FULL)
                execute(s.onTrackEnded())
            }
            playing.isPaused -> Unit
            plan?.exit is Exit.CutAt && position >= (plan.exit as Exit.CutAt).positionMs - settings.cutLeadMs -> {
                record(if (plan.entry is Entry.AtDrop) PartyEventKind.DROP_ENTRY else PartyEventKind.CUT)
                execute(s.onCutPointReached())
            }
            position >= playing.durationMs - settings.endLeadMs -> {
                record(PartyEventKind.FULL)
                execute(s.onTrackEnded())
            }
        }
        if (clock() - lastSnapshotAtMs >= SNAPSHOT_EVERY_MS) saveSnapshot()
        publish()
    }

    private suspend fun execute(command: PlayCommand?) {
        if (command == null) {
            finish()
            return
        }
        val uri = uriOf(command.trackId)
        lastCommandAtMs = clock()
        external = null
        if (command.fromMs > 0) {
            if (settings.dropWithPause) player.pause()
            player.play(uri)
            withTimeoutOrNull(SWITCH_TIMEOUT_MS) { player.state.first { it?.trackUri == uri } }
            player.seekTo(command.fromMs)
            if (settings.dropWithPause) player.resume()
        } else {
            player.play(uri)
        }
        publish()
        session?.let { recorder.statsChanged(sessionId, it.stats, null) }
        saveSnapshot()
    }

    /** Saved on every song and every few seconds, so a killed app loses seconds, not the party. */
    private suspend fun saveSnapshot() {
        val s = session ?: return
        val state = s.snapshot()
        val current = state.current ?: return
        lastSnapshotAtMs = clock()
        snapshots.save(
            PartySnapshot(
                sessionId = sessionId,
                playlistId = _state.value.playlistId ?: return,
                settings = settings,
                currentTrackId = current.id,
                positionMs = player.state.value?.takeIf { it.trackUri == uriOf(current.id) }?.positionAt(clock()) ?: 0,
                upcomingIds = state.upcoming.map { it.id },
                playedIds = state.played.map { it.id },
                transitions = state.transitions,
                stats = state.stats,
                intent = state.intent,
                savedAtMs = clock(),
            ),
        )
    }

    private suspend fun finish() {
        val s = session ?: return
        stopTicker()
        recorder.statsChanged(sessionId, s.stats, clock())
        // A party that ended on purpose must not be offered again when the app opens.
        snapshots.clear()
        session = null
        _state.value = _state.value.copy(active = false, current = null, next = null, plan = null, stats = s.stats)
    }

    private fun stopTicker() {
        // May run inside the ticker itself (the last song ended): cancelling is safe, it stops at its next delay.
        val job = ticker
        ticker = null
        job?.cancel()
    }

    private suspend fun record(kind: PartyEventKind) {
        val s = session ?: return
        val song = s.current ?: return
        val position = player.state.value?.takeIf { it.trackUri == uriOf(song.id) }?.positionAt(clock()) ?: 0
        recorder.event(sessionId, song.id, kind, position, clock())
    }

    private fun publish() {
        val s = session ?: return
        val playing = player.state.value
        val song = s.current
        val isCurrent = song != null && playing?.trackUri == uriOf(song.id)
        _state.value = _state.value.copy(
            active = true,
            current = song?.let { tracks[it.id]?.source },
            next = s.upNext?.let { tracks[it.id]?.source },
            plan = s.plan,
            positionMs = if (isCurrent) playing!!.positionAt(clock()) else 0,
            durationMs = song?.durationMs ?: 0,
            isPaused = playing?.isPaused ?: false,
            settings = settings,
            stats = s.stats,
            intent = s.intent,
            externalTrackUri = external,
        )
    }

    private fun uriOf(trackId: String) = tracks[trackId]?.source?.uri ?: "spotify:track:$trackId"

    private companion object {
        const val SWITCH_GRACE_MS = 3_000L
        const val SWITCH_TIMEOUT_MS = 3_000L
        const val STOPPED_AT_END_MS = 1_500L
        const val SNAPSHOT_EVERY_MS = 10_000L
    }
}
